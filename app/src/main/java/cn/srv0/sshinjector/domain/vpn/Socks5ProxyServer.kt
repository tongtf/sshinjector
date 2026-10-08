package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private val IS_DEBUG = android.util.Log.isLoggable("Socks5Proxy", android.util.Log.DEBUG)
private const val TIMEOUT_CHECK_INTERVAL_MS = 5000L

/** 回程写入 TUN 超过此时长就告警: 它会把 JSch 读线程拖住 (管道 32KB→1MB 后仍是会话级风险)。 */
private const val SLOW_TUN_WRITE_WARN_MS = 300L

/** 已写 SSH 但读回 0B 持续这么久 → 打「隧道无回程」, 每条连接只打一次。 */
private const val ZERO_RETURN_WARN_MS = 5000L

private val FAKE_TUNNEL_HOST_PREFIXES = listOf("198.18.", "198.19.", "fd00:")

/** 是否 DnsInterceptor 分配的假 IP 主机 (198.18.0.0/15 或 fd00::/8) — 必须先映射回域名才能连接。 */
private fun isFakeTunnelHost(host: String): Boolean = FAKE_TUNNEL_HOST_PREFIXES.any { host.startsWith(it) }

// L4: 出向队列 64×32KB=2MB/连接。**实测过小**: 下行卡住时 SSH 写协程随之阻塞, 队列一满就
// 只能"丢段靠客户端重传", 表现为上行重传风暴 (实测 1 分钟 16MB 上行 / 0.4MB 下行)。128 段留足突发吸收。
private const val SSH_SEND_QUEUE_CAPACITY = 128
private typealias TunCallback = (ByteArray, Int, Int) -> Unit

/**
 * 本地 SOCKS5 代理服务器 (RFC 1928)
 *
 * 接受来自 VPNService 的连接，通过 SSH 隧道转发到远程服务器
 * 支持: TCP CONNECT, UDP ASSOCIATE, IPv4/IPv6/域名
 */
@Singleton
class Socks5ProxyServer
    @Inject
    constructor(
        private val sshChannelFactory: SshChannelFactory,
        private val dnsInterceptor: DnsInterceptor,
        private val sshIoDispatcher: SshIoDispatcher,
    ) {
        private var serverChannel: ServerSocketChannel? = null
        private var selector: Selector? = null
        private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val connections = ConcurrentHashMap<Int, Socks5Connection>()
        private val connectionIdCounter = AtomicLong(0)

        /**
         * 隧道数据面诊断快照 (周期日志): 上行/下行累计字节对比可立即判断卡在哪一侧,
         * 队列峰值贴近容量 = 持续背压 (丢段重传的直接原因)。
         */
        fun diagnostics(): String {
            val list = connections.values
            return "代理 活跃 ${list.size} 连接 · 上行累计 ${list.sumOf { it.toTunnelTotal.get() }}B · " +
                "下行累计 ${list.sumOf { it.fromTunnelTotal.get() }}B · " +
                "背压触发 ${list.sumOf { it.backpressureCount.get() }} 次"
        }

        /** RFC 1929 期望凭据；由插件在 start() 前设置。null = 拒绝一切认证（fail-closed，不降级无认证）。 */
        @Volatile private var expectedAuth: Pair<String, String>? = null

        fun setExpectedAuth(
            user: String,
            password: String,
        ) {
            expectedAuth = user to password
        }

        val serverState = MutableStateFlow<ServerState>(ServerState(ServerState.Status.Stopped))
        val boundPort = MutableStateFlow<Int?>(null)
        val activeConnections = MutableStateFlow(0)
        val totalBytesUp = MutableStateFlow(0L)
        val totalBytesDown = MutableStateFlow(0L)

        data class ServerState(
            val status: Status = Status.Stopped,
            val port: Int? = null,
            val error: String? = null,
        ) {
            enum class Status { Stopped, Starting, Running, Stopping, Error }
        }

        /**
         * 启动 SOCKS5 服务器
         */
        suspend fun start(
            port: Int = 1080,
            bindAddress: String = "127.0.0.1",
        ): Result<Int> {
            if (serverState.value.status == ServerState.Status.Running) {
                return Result.success(boundPort.value ?: port)
            }

            serverState.value = ServerState(ServerState.Status.Starting)

            return try {
                serverChannel =
                    ServerSocketChannel.open().apply {
                        configureBlocking(false)
                        socket().reuseAddress = true
                        bind(InetSocketAddress(bindAddress, port))
                    }

                selector = Selector.open()
                serverChannel!!.register(selector!!, SelectionKey.OP_ACCEPT)

                val actualPort = serverChannel!!.socket().localPort
                boundPort.value = actualPort

                // 启动事件循环
                scope.launch { eventLoop() }

                serverState.value = ServerState(ServerState.Status.Running, actualPort)
                Result.success(actualPort)
            } catch (e: IOException) {
                serverState.value = ServerState(ServerState.Status.Error, error = e.message)
                VpnController.appLog(
                    "本地代理启动失败 · bind $bindAddress:$port — ${e.message} (端口被占用?)",
                    level = LogLevel.ERROR,
                )
                stop()
                Result.failure(e)
            }
        }

        /**
         * 停止服务器
         */
        suspend fun stop() {
            serverState.value = ServerState(ServerState.Status.Stopping)

            scope.cancel()

            connections.values.forEach { it.close() }
            connections.clear()

            try {
                selector?.close()
            } catch (_: Exception) {
            }
            try {
                serverChannel?.close()
            } catch (_: Exception) {
            }

            selector = null
            serverChannel = null
            boundPort.value = null
            activeConnections.value = 0

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            serverState.value = ServerState(ServerState.Status.Stopped)
        }

        private fun eventLoop() {
            var stopped = false
            while (!stopped && !Thread.interrupted() && selector?.isOpen == true) {
                try {
                    if (selector?.select(1000) != 0) {
                        val selectedKeys = selector?.selectedKeys() ?: continue
                        if (selectedKeys.isNotEmpty()) {
                            val keysCopy = selectedKeys.toTypedArray()
                            selectedKeys.clear()

                            for (key in keysCopy) {
                                if (key.isValid) {
                                    try {
                                        when {
                                            key.isAcceptable -> handleAccept(key)
                                            key.isReadable -> handleRead(key)
                                            key.isWritable -> handleWrite(key)
                                        }
                                    } catch (e: java.nio.channels.CancelledKeyException) {
                                        // 连接关闭与选中键处理的正常竞态 (key 在 select 后、处理前被 cancel)
                                        android.util.Log.d("Socks5Proxy", "cancelled key (benign)", e)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (selector?.isOpen == true) {
                        android.util.Log.w("Socks5Proxy", "eventLoop IO error", e)
                        VpnController.appLogThrottled(
                            "本地代理事件循环 IO 错误 — ${e.message}",
                            throttleKey = "本地代理事件循环 IO 错误",
                        )
                    } else {
                        stopped = true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Socks5Proxy", "eventLoop unexpected error", e)
                    // 单次迭代异常 (非 IO): 循环继续; 带异常类名 — message 可能为 null
                    VpnController.appLogThrottled(
                        "本地代理事件循环单次异常 — $e (循环继续)",
                        level = LogLevel.WARNING,
                        throttleKey = "本地代理事件循环单次异常",
                    )
                }
            }
        }

        // TUN 写回回调: connectionId → callback(data)
        private val pendingTunCallbacks = ConcurrentHashMap<Int, (ByteArray, Int, Int) -> Unit>()

        /**
         * 注册 TUN 写回回调，在 SYN 建立连接 (forwardSynToTunnel → 直连通道) 时调用
         */
        fun registerTunCallback(
            connectionId: Int,
            callback: (ByteArray, Int, Int) -> Unit,
        ) {
            pendingTunCallbacks[connectionId] = callback
        }

        fun removeTunCallback(connectionId: Int) {
            pendingTunCallbacks.remove(connectionId)
        }

        fun getTunCallback(connectionId: Int): TunCallback? = pendingTunCallbacks.remove(connectionId)

        // 远端结束通知: connectionId → 回调, 形参 = 是否 clean EOF (决定 TcpStateMachine 发
        // FIN 还是 RST)。与 pendingTunCallbacks 同键 (tunCallbackKey = clientPort)。
        private val pendingTargetEofCallbacks = ConcurrentHashMap<Int, (Boolean) -> Unit>()

        fun registerTargetEofCallback(
            connectionId: Int,
            callback: (Boolean) -> Unit,
        ) {
            pendingTargetEofCallbacks[connectionId] = callback
        }

        fun removeTargetEofCallback(connectionId: Int) {
            pendingTargetEofCallbacks.remove(connectionId)
        }

        private fun handleAccept(key: SelectionKey) {
            val serverChannel = key.channel() as ServerSocketChannel
            val clientChannel = serverChannel.accept() ?: return

            // 仅监听 127.0.0.1 (loopback): Android 上其他应用无法连接本应用监听的 loopback 端口,
            // 同进程的 TcpStateMachine 是唯一合法客户端, 无需对端 UID 校验
            // (/proc/net/tcp 在 Android 应用进程内受 SELinux 限制不可读, 校验会导致拒绝所有连接)。
            clientChannel.configureBlocking(false)
            val connectionId = connectionIdCounter.incrementAndGet()

            val clientPort =
                clientChannel.socket().remoteSocketAddress?.let {
                    (it as? java.net.InetSocketAddress)?.port
                } ?: 0

            val connection =
                Socks5Connection(
                    id = connectionId,
                    channel = clientChannel,
                    sshChannelFactory = sshChannelFactory,
                    sshIoDispatcher = sshIoDispatcher,
                    onDataSent = { bytes -> totalBytesUp.update { it + bytes } },
                    onDataReceived = { bytes -> totalBytesDown.update { it + bytes } },
                    onClosed = {
                        connections.remove(connectionId.toInt())
                        removeTunCallback(clientPort)
                        removeTargetEofCallback(clientPort)
                        activeConnections.value = connections.size
                    },
                    onDataFromTarget = null,
                    ipToDomainLookup = { dnsInterceptor.lookupDomain(it) },
                    expectedAuth = expectedAuth,
                )

            // 延迟查找回调: relayFromTarget 首次使用前从 pendingTunCallbacks 获取
            connection.tunCallbackKey = clientPort
            connection.pendingTunCallbacksRef = pendingTunCallbacks
            connection.pendingTargetEofCallbacksRef = pendingTargetEofCallbacks

            connections[connectionId.toInt()] = connection
            activeConnections.value = connections.size

            connection.startTimeoutChecker()

            val selectionKey = clientChannel.register(selector!!, SelectionKey.OP_READ, connection)
            connection.selectionKey = selectionKey
        }

        private fun handleRead(key: SelectionKey) {
            if (!key.isValid) return
            val connection = key.attachment() as Socks5Connection?
            if (connection != null) {
                connection.handleRead(key)
            }
        }

        private fun handleWrite(key: SelectionKey) {
            if (!key.isValid) return
            val connection = key.attachment() as Socks5Connection?
            if (connection != null) {
                connection.handleWrite(key)
            }
        }

        fun getStats(): ProxyStats =
            ProxyStats(
                status = serverState.value.status,
                port = boundPort.value,
                activeConnections = activeConnections.value,
                totalBytesUp = totalBytesUp.value,
                totalBytesDown = totalBytesDown.value,
            )

        data class ProxyStats(
            val status: ServerState.Status,
            val port: Int?,
            val activeConnections: Int,
            val totalBytesUp: Long,
            val totalBytesDown: Long,
        )
    }

/**
 * 单个 SOCKS5 连接处理 (状态机)
 * 通过 SSH 隧道 (TunnelChannel) 转发流量到目标服务器
 */
@Suppress("LongParameterList") // 连接级回调+工厂汇聚成一个对象, 拆分需引入 config 包装类
private class Socks5Connection(
    val id: Long,
    val channel: SocketChannel,
    private val sshChannelFactory: SshChannelFactory?,
    private val sshIoDispatcher: SshIoDispatcher,
    private val onDataSent: (Long) -> Unit,
    private val onDataReceived: (Long) -> Unit,
    private val onClosed: () -> Unit,
    var onDataFromTarget: ((ByteArray, Int, Int) -> Unit)? = null,
    private val ipToDomainLookup: ((String) -> String?)? = null,
    private val expectedAuth: Pair<String, String>? = null,
) {
    private val buffer = ByteBuffer.allocateDirect(32768)

    @Volatile private var state = SocksState.Handshake
    private var targetTunnel: TunnelChannel? = null
    private var remoteHost: String? = null
    private var remotePort: Int = 0
    private val pendingWrites = java.util.concurrent.ConcurrentLinkedDeque<ByteBuffer>()

    // 直写 channel 的互斥: eventLoop handleWrite 与 sshIoDispatcher 的 sendReplyAndClose
    // (connectToTarget 失败路径) 并发直写同一 SocketChannel 时串行化, 避免字节交错
    private val writeLock = Any()
    internal var selectionKey: SelectionKey? = null
    internal var tunCallbackKey: Int = 0
    internal var pendingTunCallbacksRef: ConcurrentHashMap<Int, (ByteArray, Int, Int) -> Unit>? = null

    /** 远端 clean EOF 回调 (同 tunCallbackKey 键), 由 handleAccept 注入; 见 companion 的注册函数。 */
    internal var pendingTargetEofCallbacksRef: ConcurrentHashMap<Int, (Boolean) -> Unit>? = null

    // 连接级流量计数: 故障日志里必须能回答"这个 conn 是谁、搬了多少字节" ——
    // 早期只有 conn=NN 无法定位目标与数据量 (排障时反复猜测的唯一原因)。
    private val bytesToTunnel = AtomicLong()
    private val bytesFromTunnel = AtomicLong()
    private val openedAt = System.currentTimeMillis()

    /**
     * 真正传给 `createDirectChannel` 的主机 (假 IP 已反查成域名)。
     * 故障日志一律用它而非 [remoteHost]: 后者对本栈来说几乎总是 198.18.x.x,
     * 只打假 IP 等于没说哪个域名在失败。
     */
    @Volatile
    private var resolvedRemoteHost: String? = null

    /** 已写 SSH 的时刻 (0 = 还没写过); 「隧道无回程」看门狗的起算点。 */
    @Volatile
    private var firstSshWriteAt = 0L

    /** 看门狗每条连接最多告警一次, 避免 5s 一刷。 */
    @Volatile
    private var zeroReturnWarned = false

    /** SSH 写协程累计写入字节 (客户端 → 隧道)。与 fromTunnelTotal 对比可判断"上行堵"还是"下行堵"。 */
    internal val toTunnelTotal = AtomicLong()

    /** relay 从隧道累计读回字节 (隧道 → 客户端)。 */
    internal val fromTunnelTotal = AtomicLong()

    /**
     * 背压触发次数 (出向 Channel 满 + 单槽挂起): 持续增长 = 写 SSH 的速度跟不上客户端上行,
     * 本地读已被暂停。数据未丢 (单槽 + Channel 承接), 但它是上行被卡的唯一可见证据。
     */
    internal val backpressureCount = AtomicLong()

    // 出向(本地 SOCKS → SSH)有界 Channel: eventLoop trySend 入队, 写协程挂起接收。
    // 满时挂到连接级单槽 pendingToSshBlock(不丢), 暂停 OP_READ 背压; 写协程腾出空间后回填。
    private val toSshChannel =
        Channel<ByteArray>(capacity = SSH_SEND_QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.SUSPEND)

    @Volatile private var pendingToSshBlock: ByteArray? = null

    @Volatile private var pendingToSshFull = false

    // CONNECT 请求后剩余于 buffer 的预读数据: eventLoop 在 connectToTarget 时提取,
    // onTargetConnected (sshIoDispatcher) 只读此字段, 避免 buffer 跨线程并发访问。
    @Volatile private var pendingConnectData: ByteArray? = null

    // 背压状态锁: 保护 pendingToSshBlock/pendingToSshFull/OP_READ 切换的原子性
    private val backpressureLock = Any()

    // 超时配置
    @Volatile private var lastActivity = System.currentTimeMillis()
    private val connectionTimeoutMs = 10000L // 连接建立超时 10s
    private val idleTimeoutMs = 300000L // 空闲超时 5 分钟
    private var timeoutCheckJob: kotlinx.coroutines.Job? = null

    private val scope =
        kotlinx.coroutines.CoroutineScope(
            sshIoDispatcher.dispatcher + kotlinx.coroutines.SupervisorJob(),
        )

    private enum class SocksState {
        Handshake, // 等待客户端握手
        AuthMethods, // 认证方法协商
        Request, // 等待连接请求
        Connecting, // 正在连接目标
        Relaying, // 数据中转
        Closed,
    }

    fun handleRead(ignoredKey: SelectionKey) {
        if (state == SocksState.Closed) return
        try {
            // 检查超时
            if (checkTimeout()) {
                android.util.Log.w("Socks5Proxy", "[conn=$id] handleRead: timeout, closing")
                close()
                return
            }

            // 不 clear: 上次未消费的半包留在 buffer 开头, 本次 read 追加其后
            val read = channel.read(buffer)

            if (read == -1) {
                if (IS_DEBUG) android.util.Log.d("Socks5Proxy", "[conn=$id] handleRead: EOF (state=$state)")
                close()
                return
            }

            buffer.flip()
            lastActivity = System.currentTimeMillis()
            onDataReceived(read.toLong())

            // 循环处理 buffer 中所有可用数据; 各 process 返回 false = 半包等待续传/已关闭, 停止空转
            var keepProcessing = true
            while (keepProcessing && buffer.hasRemaining() && state != SocksState.Closed) {
                if (IS_DEBUG) {
                    android.util.Log.d(
                        "Socks5Proxy",
                        "[conn=$id] loop state=$state remaining=${buffer.remaining()}",
                    )
                }
                when (state) {
                    SocksState.Handshake -> keepProcessing = processHandshake()
                    SocksState.AuthMethods -> keepProcessing = processAuthMethods()
                    SocksState.Request -> keepProcessing = processRequest()
                    SocksState.Connecting -> keepProcessing = false
                    SocksState.Relaying -> {
                        enqueueToSsh()
                        keepProcessing = false
                    }
                    SocksState.Closed -> keepProcessing = false
                }
            }
            // 半包保留: 未消费字节移到 buffer 开头等待下次 read;全消费 (pos==limit) 则整体重置。
            // 禁止在 enqueueToSsh/connectToTarget 的 get()/clear() 之后无脑 compact:
            // clear() 后是 (pos=0, limit=capacity), compact 会把 pos 推到 capacity → 下次 read
            // 零容量返回 0, flip() 暴露整段陈旧字节 → 事件循环空转 + 把垃圾灌进远端隧道。
            if (state != SocksState.Closed) {
                if (buffer.position() == buffer.limit()) buffer.clear() else buffer.compact()
            }
        } catch (e: Exception) {
            android.util.Log.e("Socks5Proxy", "[conn=$id] handleRead exception: ${e.message}", e)
            val up = bytesToTunnel.get()
            val down = bytesFromTunnel.get()
            val ageSec = (System.currentTimeMillis() - openedAt) / 1000
            VpnController.appLogThrottled(
                "本地代理读取异常 · conn=$id → ${remoteHost ?: "-"}:$remotePort — ${e.message} " +
                    "(上行 ${up}B / 下行 ${down}B / ${ageSec}s)",
                level = LogLevel.DEBUG,
                throttleKey = "本地代理读取异常",
            )
            close()
        }
    }

    fun handleWrite(key: SelectionKey) {
        var buf = pendingWrites.peek()
        while (buf != null) {
            if (buf.hasRemaining()) {
                try {
                    val written = synchronized(writeLock) { channel.write(buf) }
                    onDataSent(written.toLong())
                    bytesFromTunnel.addAndGet(written.toLong())
                    lastActivity = System.currentTimeMillis()
                    if (buf.hasRemaining()) {
                        // Buffer partially written, re-add to front (keeps order)
                        pendingWrites.poll()
                        pendingWrites.addFirst(buf)
                    } else {
                        pendingWrites.poll()
                    }
                    // 写满一个 buffer 后继续尝试下一个, 直到 write 返回 0 或队列空
                    if (written == 0) {
                        break
                    }
                } catch (e: Exception) {
                    close()
                    return
                }
            } else {
                // 丢弃队列里残留的空 buffer, 继续处理下一个
                pendingWrites.poll()
            }
            buf = pendingWrites.peek()
        }
        // If queue is empty, remove OP_WRITE
        if (pendingWrites.isEmpty()) {
            try {
                if (key.isValid) {
                    // 与 sendReply (sshIoDispatcher) 并发修改 interestOps, 加锁避免 RMW 丢失位
                    synchronized(backpressureLock) {
                        key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
                    }
                }
            } catch (e: java.nio.channels.CancelledKeyException) {
                close()
            }
        }
    }

    /** @return true 可继续处理; false = 半包待续传或已关闭。整条记录齐备前不消费任何字节。 */
    private fun processHandshake(): Boolean {
        // SOCKS5 握手: VER(1) NMETHODS(1) METHODS(*)
        if (buffer.remaining() < 2) return false

        val pos = buffer.position()
        val ver = buffer.get(pos).toInt() and 0xFF
        if (ver != 0x05) {
            close()
            return false
        }
        val nMethods = buffer.get(pos + 1).toInt() and 0xFF
        if (buffer.remaining() < 2 + nMethods) return false

        val methods = ByteArray(nMethods)
        buffer.position(pos + 2)
        buffer.get(methods)

        // 仅接受用户名/密码认证 (RFC 1929, 0x02); 无凭据或客户端不支持一律拒绝, 不留无认证口子
        val auth = expectedAuth
        if (auth == null || methods.none { (it.toInt() and 0xFF) == 0x02 }) {
            sendReplyAndClose(byteArrayOf(0x05, 0xFF.toByte()))
            return false
        }

        sendReply(byteArrayOf(0x05, 0x02))
        state = SocksState.AuthMethods
        return true
    }

    /** @return true 可继续处理; false = 半包待续传或已关闭。 */
    private fun processAuthMethods(): Boolean {
        // RFC 1929: VER(1) ULEN(1) USER(ULEN) PLEN(1) PASS(PLEN)
        if (buffer.remaining() < 2) return false

        val pos = buffer.position()
        val ver = buffer.get(pos).toInt() and 0xFF
        val userLen = buffer.get(pos + 1).toInt() and 0xFF
        if (ver != 0x01 || userLen == 0) {
            sendReplyAndClose(byteArrayOf(0x01, 0x01))
            return false
        }
        // RFC 1929 报文 = VER + ULEN + USER + PLEN + PASS = 3 + userLen + passLen
        if (buffer.remaining() < 3 + userLen) return false
        val passLen = buffer.get(pos + 2 + userLen).toInt() and 0xFF
        if (buffer.remaining() < 3 + userLen + passLen) return false

        buffer.position(pos + 2)
        val user = ByteArray(userLen)
        buffer.get(user)
        buffer.get() // PLEN
        val pass = ByteArray(passLen)
        buffer.get(pass)

        val auth = expectedAuth
        val ok =
            auth != null &&
                MessageDigest.isEqual(auth.first.toByteArray(Charsets.UTF_8), user) &&
                MessageDigest.isEqual(auth.second.toByteArray(Charsets.UTF_8), pass)
        if (IS_DEBUG) {
            android.util.Log.d(
                "Socks5Proxy",
                "[conn=$id] auth: userLen=$userLen passLen=$passLen ok=$ok expected=${auth?.first}",
            )
        }
        if (!ok) {
            sendReplyAndClose(byteArrayOf(0x01, 0x01))
            return false
        }

        sendReply(byteArrayOf(0x01, 0x00))
        state = SocksState.Request
        return true
    }

    /** @return true 可继续处理; false = 半包待续传或已关闭。 */
    private fun processRequest(): Boolean {
        // SOCKS5 请求: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR(*) DST.PORT(2) — 齐备前不消费
        if (buffer.remaining() < 4) return false

        val pos = buffer.position()
        val cmd = buffer.get(pos + 1).toInt() and 0xFF
        val atyp = buffer.get(pos + 3).toInt() and 0xFF

        if (cmd != 0x01) {
            // 仅支持 CONNECT; UDP ASSOCIATE 与其他命令立即拒绝 (本地代理仅转发 TCP)
            sendReplyAndClose(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            return false
        }

        // 按 ATYP 计算地址+端口段最小长度
        val addrLen =
            when (atyp) {
                0x01 -> 6 // IPv4 + Port
                0x04 -> 18 // IPv6 + Port
                0x03 -> {
                    if (buffer.remaining() < 5) return false // 差 1 字节拿不到域名长度
                    3 + (buffer.get(pos + 4).toInt() and 0xFF) // len 字节 + 域名 + Port
                }
                else -> {
                    sendReplyAndClose(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    return false
                }
            }
        if (buffer.remaining() < 4 + addrLen) return false
        buffer.position(pos + 4) // 头部已齐, 交给 parse* 消费地址段

        val (host, port) =
            when (atyp) {
                0x01 -> parseIpv4()
                0x03 -> parseDomain()
                else -> parseIpv6()
            }
        remoteHost = host
        remotePort = port

        // 异步连接目标 (通过 SSH 隧道)
        state = SocksState.Connecting
        connectToTarget()
        return true
    }

    private fun parseIpv4(): Pair<String, Int> {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        val port = readPort()
        return java.net.InetAddress
            .getByAddress(bytes)
            .hostAddress!! to port
    }

    private fun parseIpv6(): Pair<String, Int> {
        val bytes = ByteArray(16)
        buffer.get(bytes)
        val port = readPort()
        return java.net.InetAddress
            .getByAddress(bytes)
            .hostAddress!! to port
    }

    private fun parseDomain(): Pair<String, Int> {
        val len = buffer.get().toInt() and 0xFF
        val bytes = ByteArray(len)
        buffer.get(bytes)
        val port = readPort()
        return String(bytes) to port
    }

    private fun readPort(): Int {
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        return (b1 shl 8) or b2
    }

    /**
     * 通过 SSH 隧道连接目标服务器
     * 使用 SshChannelFactory 创建 TunnelChannel (ChannelDirectTCPIP)
     */
    private fun connectToTarget() {
        val factory = sshChannelFactory
        var host = remoteHost
        val port = remotePort

        if (IS_DEBUG) android.util.Log.d("Socks5Proxy", "connectToTarget: $host:$port, factory=${factory != null}")

        if (factory == null || host == null) {
            android.util.Log.e("Socks5Proxy", "connectToTarget failed: factory=$factory host=$host")
            VpnController.appLog(
                "本地代理配置错误 · 无 SSH 通道工厂 (host=$host)",
                level = LogLevel.WARNING,
            )
            sendErrorReplyAndClose(0x05)
            return
        }

        // 线程池满载时直接拒绝: 避免 CallerRunsPolicy 将阻塞的 SSH 连接操作
        // 回执到 eventLoop 线程, 冻结整个 SOCKS5 代理 (128+ 并发时可能触发)
        if (sshIoDispatcher.isSaturated()) {
            android.util.Log.w("Socks5Proxy", "connectToTarget rejected: ssh-io pool saturated ($host:$port)")
            VpnController.appLogThrottled(
                "连接被拒 · SSH IO 线程池已饱和 ($host:$port)",
                level = LogLevel.WARNING,
                throttleKey = "连接被拒 · SSH IO 线程池已饱和",
            )
            sendErrorReplyAndClose(0x05)
            return
        }

        // 解析假 IP 为真实域名 (198.18.0.0/15 或 fd00::/8)
        val resolvedHost: String
        if (isFakeTunnelHost(host)) {
            val mapped = ipToDomainLookup?.invoke(host)
            if (mapped == null || mapped == host) {
                // 假 IP 无映射 (映射被逐出/VPN 进程重启): 把 198.18.x.x 当真实主机发给远端
                // 会黑洞超时再重试, 永远"连接中" — 秒拒让客户端立刻重查 DNS 拿新映射
                android.util.Log.w(
                    "Socks5Proxy",
                    "connectToTarget: fake IP has no domain mapping, reject fast: $host:$port",
                )
                VpnController.appLogThrottled(
                    "假 IP 映射失效 · $host:$port 无域名映射, 已秒拒 (请重试让 DNS 重新分配)",
                    level = LogLevel.WARNING,
                    throttleKey = "假 IP 映射失效",
                )
                sendErrorReplyAndClose(0x03)
                return
            }
            resolvedHost = mapped
        } else {
            resolvedHost = host
        }
        resolvedRemoteHost = resolvedHost

        if (resolvedHost != host && IS_DEBUG) {
            android.util.Log.d("Socks5Proxy", "Resolved fake IP $host to domain $resolvedHost")
        }

        // 提取 CONNECT 请求后预读的剩余数据 (当前在 eventLoop 线程, buffer 独占)。
        // onTargetConnected 在 sshIoDispatcher 线程只读此字段, 避免共享 buffer 跨线程并发。
        if (buffer.hasRemaining()) {
            val remaining = buffer.remaining()
            val data = ByteArray(remaining)
            buffer.get(data)
            // get() 已把 pos 推到 limit (全消费), 由 handleRead 尾部统一 clear 重置;
            // 这里再 clear() 会把状态置为 (0, capacity), 尾部 compact 将毒化 buffer。
            pendingConnectData = data
        }

        scope.launch {
            try {
                val tunnel = factory.createDirectChannel(resolvedHost, port)
                if (tunnel == null) {
                    android.util.Log.e(
                        "Socks5Proxy",
                        "connectToTarget failed: createDirectChannel returned null for $host:$port",
                    )
                    VpnController.appLogThrottled(
                        "SSH 通道建立失败 · $resolvedHost:$port — 通道工厂返回 null",
                        level = LogLevel.ERROR,
                        throttleKey = "SSH 通道建立失败",
                    )
                    sendErrorReplyAndClose(0x05)
                    return@launch
                }

                val connected = tunnel.connect(5000)
                if (!connected) {
                    android.util.Log.e(
                        "Socks5Proxy",
                        "connectToTarget failed: tunnel.connect returned false for $host:$port",
                    )
                    VpnController.appLogThrottled(
                        "SSH 通道连接失败 · $resolvedHost:$port — connect 超时/返回 false",
                        level = LogLevel.ERROR,
                        throttleKey = "SSH 通道连接失败",
                    )
                    tunnel.disconnect()
                    sendErrorReplyAndClose(0x05)
                    return@launch
                }

                if (IS_DEBUG) android.util.Log.d("Socks5Proxy", "connectToTarget success: $host:$port")
                // close() 可能在这 5s 的阻塞 connect() 里整个跑完: scope.cancel() 打不断非挂起点,
                // 而此刻 targetTunnel 还是 null, close() 的 targetTunnel?.disconnect() 会空过 ——
                // 这条通道从此无人 disconnect, 挂在 JSch 静态 Channel.pool 里泄漏, 且它会持续
                // 灌 1MB 输入管道把整条 SSH 会话的读线程冻结 (见 MAX_INPUT_BUFFER_SIZE 注释)。
                // 与 close() 同持连接锁登记: 抢在它前面写入, close() 就能看到并负责断开;
                // 它先跑完则这里必读到 Closed, 自己断开。两条路都有人收尾。
                val closedWhileConnecting =
                    synchronized(this@Socks5Connection) {
                        if (state == SocksState.Closed) {
                            true
                        } else {
                            targetTunnel = tunnel
                            false
                        }
                    }
                if (closedWhileConnecting) {
                    android.util.Log.w(
                        "Socks5Proxy",
                        "connectToTarget: connection closed during tunnel.connect, discarding channel $host:$port",
                    )
                    tunnel.disconnect()
                    return@launch
                }
                onTargetConnected()
            } catch (e: Exception) {
                android.util.Log.e("Socks5Proxy", "connectToTarget exception: $host:$port", e)
                VpnController.appLogThrottled(
                    "SSH 通道连接异常 · $host:$port — ${e.message}",
                    level = LogLevel.ERROR,
                    throttleKey = "SSH 通道连接异常",
                )
                sendErrorReplyAndClose(0x05)
            }
        }
    }

    private fun onTargetConnected() {
        // 发送成功响应
        val reply = buildSuccessReply()
        sendReply(reply)

        state = SocksState.Relaying
        lastActivity = System.currentTimeMillis()

        // 启动反向中继线程: SSH Tunnel → SOCKS5 Client
        startRelayFromTarget()

        // 启动出向写协程: Channel → SSH
        startSshWriteLoop()

        // 启动超时检查
        startTimeoutChecker()

        // 继续处理 CONNECT 请求后预读的剩余数据 (来自 eventLoop 提取的 pendingConnectData,
        // 不直接访问共享 buffer, 避免与 handleRead 并发)
        val pending = pendingConnectData
        if (pending != null) {
            pendingConnectData = null
            enqueuePreconnectedData(pending)
        }
    }

    /**
     * 启动反向中继: 从 SSH 隧道读取数据写回 SOCKS5 客户端。
     * 跑在 sshIoDispatcher (动态池), 每个活跃连接占 1 个专用线程而非共享 Dispatchers.IO;
     * 连接级 64KB buffer 全程复用, 回调以 (data, offset, len) 零拷贝传递。
     */
    private fun startRelayFromTarget() {
        val tunnel = targetTunnel ?: return
        val input = tunnel.inputStream ?: return

        scope.launch(sshIoDispatcher.dispatcher) {
            val readBuffer = ByteBuffer.allocate(65535) // 增加到 64KB
            var resolvedCallback = onDataFromTarget
            if (resolvedCallback == null && pendingTunCallbacksRef != null) {
                resolvedCallback = pendingTunCallbacksRef!![tunCallbackKey]
            }
            val callback = resolvedCallback
            if (IS_DEBUG) {
                android.util.Log.d(
                    "Socks5Proxy",
                    "[conn=$id] relayFromTarget started, callbackKey=$tunCallbackKey " +
                        "callback=${if (callback != null) "set" else "MISSING"}",
                )
            }
            try {
                while (tunnel.isConnected && state == SocksState.Relaying) {
                    readBuffer.clear()
                    val readStart = System.nanoTime()
                    val read = input.read(readBuffer.array())
                    val readWaitMs = (System.nanoTime() - readStart) / 1000000
                    if (read == -1) {
                        android.util.Log.w("Socks5Proxy", "[conn=$id] relayFromTarget: EOF from tunnel")
                        // remove-and-invoke: 与 close() 的 remove 竞争时也只触发一次
                        // (CHM.remove 原子, 谁拿到非 null 谁负责上报)。
                        // 必须先于 close(): close() 走 onClosed 会清掉这条回调。
                        // graceful 取**调用瞬间**的 state: 仍 Relaying = 真·远端 clean EOF → FIN;
                        // 否则说明 close() 已先置 Closed 并断开管道把我们唤醒 → 必须报 false 才发 RST,
                        // 报 true 会把"本栈主动断开"伪装成远端正常收尾 (截断下载被当成正常结束)。
                        val eofCallback = pendingTargetEofCallbacksRef?.remove(tunCallbackKey)
                        eofCallback?.invoke(state == SocksState.Relaying)
                        this@Socks5Connection.close()
                        break
                    }
                    if (read > 0) {
                        lastActivity = System.currentTimeMillis()
                        // 必须在 callback **之前**计数: callback 走 writeTcpPayloadToTun →
                        // @Synchronized writeToTun, 可能阻塞; 之后再计数会把"SSH 其实读到了"
                        // 的字节藏起来, 零回程诊断就把"回程堵在 TUN 写"误判成"远端没回话"。
                        fromTunnelTotal.addAndGet(read.toLong())
                        onDataReceived(read.toLong())
                        if (IS_DEBUG) {
                            android.util.Log.d(
                                "Socks5Proxy",
                                "[conn=$id] relayFromTarget: received ${read}B wait=${readWaitMs}ms " +
                                    "callback=${callback != null}",
                            )
                        }
                        val cbStartNs = System.nanoTime()
                        callback?.invoke(readBuffer.array(), 0, read)
                        val cbMs = (System.nanoTime() - cbStartNs) / 1_000_000
                        if (cbMs >= SLOW_TUN_WRITE_WARN_MS) {
                            // 这一步卡住 = 本连接停止排空 JSch 管道 = 该 SSH 会话读线程被拖住
                            // = 同会话所有通道一起收不到数据 (见 MAX_INPUT_BUFFER_SIZE 注释)
                            VpnController.appLogThrottled(
                                "回程写入 TUN 阻塞 · ${targetForLog()}:$remotePort — ${read}B 耗时 $cbMs ms " +
                                    "(会话 ${tunnel.sessionId}) 回程协程停摆会拖死 JSch 读线程, 同会话其它通道一起断流",
                                level = LogLevel.WARNING,
                                throttleKey = "回程写入 TUN 阻塞",
                            )
                        }
                        if (callback == null) {
                            // 快照后入队: wrap 是共享 readBuffer.array() 的视图, 异步写出前下轮 read 会覆写它
                            sendReply(readBuffer.array().copyOf(read))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Socks5Connection", "[conn=$id] relayFromTarget error: ${e.message}", e)
                VpnController.appLogThrottled(
                    "回程读取异常 · SSH → 浏览器 $remoteHost:$remotePort — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "回程读取异常",
                )
                // 回程异常 = 隧道已坏, 不关闭则客户端永远等不到 EOF;
                // 顺带上报 graceful=false: 不报的话 directRelay 主路径下 TcpStateMachine
                // 完全收不到通知, 纯下行连接会挂到 300s 陈旧清理才被 RST。
                pendingTargetEofCallbacksRef?.remove(tunCallbackKey)?.invoke(false)
                this@Socks5Connection.close()
            } finally {
                if (IS_DEBUG) android.util.Log.d("Socks5Connection", "[conn=$id] relayFromTarget coroutine exiting")
            }
        }
    }

    private fun buildSuccessReply(): ByteArray {
        // 简化: 返回 0.0.0.0:0 作为绑定地址
        return byteArrayOf(
            // VER REP RSV ATYP(IPv4)
            0x05,
            0x00,
            0x00,
            0x01,
            // BND.ADDR (0.0.0.0)
            0x00,
            0x00,
            0x00,
            0x00,
            // BND.PORT (0)
            0x00,
            0x00,
        )
    }

    /**
     * 最终拒绝回复 + 关闭: 必须同步写出 — 走 sendReply 排队再紧跟 close(), pendingWrites
     * 会被丢弃, 客户端只看到 EOF 而收不到明确拒绝码 (0x03 假 IP 无映射等诊断依赖该码)。
     */
    private fun sendErrorReplyAndClose(rep: Int) {
        val reply = byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        sendReplyAndClose(reply)
    }

    private fun sendReply(reply: ByteArray) {
        sendReply(reply, 0, reply.size)
    }

    /**
     * 最终拒绝回复 + 关闭: 必须同步写出 — 走 sendReply 排队的话, 紧接着的 close() 会
     * cancel selectionKey, pendingWrites 被丢弃, 客户端只看到 EOF 收不到拒绝码。
     * writeLock 与 eventLoop 的 handleWrite 直写串行化, 因此 connectToTarget 的
     * sshIoDispatcher 失败路径也可安全调用; 回复 ≤10B, loopback 上单次 write 基本写完,
     * 循环只兜底极小概率的部分写。
     */
    private fun sendReplyAndClose(reply: ByteArray) {
        val buf = ByteBuffer.wrap(reply)
        try {
            synchronized(writeLock) {
                var attempts = 0
                while (buf.hasRemaining() && attempts < 16) {
                    if (channel.write(buf) <= 0) break
                    attempts++
                }
            }
        } catch (_: Exception) {
        }
        close()
    }

    private fun sendReply(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        try {
            if (selectionKey != null && selectionKey!!.isValid) {
                // Queue the reply for writing
                pendingWrites.add(ByteBuffer.wrap(data, offset, length))
                // 与 handleWrite (eventLoop) 并发修改 interestOps, 加锁避免 RMW 丢失位
                synchronized(backpressureLock) {
                    selectionKey!!.interestOps(selectionKey!!.interestOps() or SelectionKey.OP_WRITE)
                }
                // 唤醒阻塞中的 selector, 避免跨线程 interestOps 修改后写入延迟
                try {
                    selectionKey!!.selector().wakeup()
                } catch (_: Exception) {
                }
            } else {
                synchronized(writeLock) {
                    channel.write(ByteBuffer.wrap(data, offset, length))
                }
            }
        } catch (e: Exception) {
            close()
        }
    }

    /**
     * 转发数据到 SSH 隧道 (SOCKS5 Client → SSH Tunnel)
     * 仅在 eventLoop 线程调用 (handleRead 的 Relaying 分支)。
     */
    private fun enqueueToSsh() {
        if (state != SocksState.Relaying) {
            buffer.position(buffer.limit())
            return
        }
        if (!buffer.hasRemaining()) {
            return
        }
        val remaining = buffer.remaining()
        val data = ByteArray(remaining)
        buffer.get(data)
        // get() 已全消费 (pos==limit); 不 clear(), 由 handleRead 尾部按 (pos==limit) 统一重置。
        enqueueData(data)
    }

    /**
     * 将预读数据写入出向 Channel, 不触碰共享 buffer。
     * 仅在 sshIoDispatcher 线程调用 (onTargetConnected)。
     */
    private fun enqueuePreconnectedData(data: ByteArray) {
        if (state != SocksState.Relaying) return
        enqueueData(data)
    }

    /**
     * 出向入队统一入口: 双检 trySend, 失败时在锁内挂单槽 + 暂停 OP_READ。
     * 锁保证 pendingToSshBlock 单槽不被并发覆盖, 且 full 标志与 OP_READ
     * 状态切换原子, 消除 eventLoop 与 sshIoDispatcher 之间的背压竞态。
     */
    private fun enqueueData(data: ByteArray) {
        if (!toSshChannel.trySend(data).isSuccess) {
            synchronized(backpressureLock) {
                if (!toSshChannel.trySend(data).isSuccess) {
                    pendingToSshBlock = data
                    pendingToSshFull = true
                    // 必须计数: diagnostics() 的「背压触发 N 次」是上行重传风暴的唯一可见证据,
                    // 不自增就永远打 0 —— 正是历史上"各丢弃计数全 0 但数据真丢了"的形态
                    backpressureCount.incrementAndGet()
                    suspendLocalRead()
                }
            }
        }
        lastActivity = System.currentTimeMillis()
        onDataSent(data.size.toLong())
        bytesToTunnel.addAndGet(data.size.toLong())
    }

    /**
     * 出向写协程: 挂起接收 Channel 数据并写 SSH。
     * for 循环在无数据时挂起(不占线程), 阻塞 IO 跑在 Dispatchers.IO。
     */
    private fun startSshWriteLoop() {
        val tunnel = targetTunnel ?: return
        val output = tunnel.outputStream ?: return

        scope.launch {
            for (data in toSshChannel) {
                try {
                    output.write(data)
                    // JSch SSH channel 需要 flush 才能真正发送数据包
                    output.flush()
                    toTunnelTotal.addAndGet(data.size.toLong())
                    if (firstSshWriteAt == 0L) firstSshWriteAt = System.currentTimeMillis()
                    resumeLocalReadIfSpace()
                } catch (e: Exception) {
                    if (state != SocksState.Closed) {
                        android.util.Log.e("Socks5Proxy", "[conn=$id] ssh write error: ${e.message}", e)
                        VpnController.appLogThrottled(
                            "SSH 写入失败 · 浏览器 → SSH $remoteHost:$remotePort — ${e.message}",
                            level = LogLevel.ERROR,
                            throttleKey = "SSH 写入失败",
                        )
                        close()
                    }
                    break
                }
            }
        }
    }

    /**
     * 写协程腾出空间后: 回填挂起块, 再恢复本地 OP_READ。
     */
    private fun resumeLocalReadIfSpace() {
        synchronized(backpressureLock) {
            if (!pendingToSshFull) return
            val pending = pendingToSshBlock
            if (pending != null) {
                if (!toSshChannel.trySend(pending).isSuccess) return
                pendingToSshBlock = null
            }
            pendingToSshFull = false

            val sk = selectionKey
            if (sk == null || !sk.isValid) return
            try {
                sk.interestOps(sk.interestOps() or SelectionKey.OP_READ)
                sk.selector().wakeup()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Channel 已满, 暂停本地 OP_READ 以向上游背压。
     * 必须在持有 backpressureLock 时调用。
     */
    private fun suspendLocalRead() {
        val sk = selectionKey
        if (sk == null || !sk.isValid) return
        try {
            sk.interestOps(sk.interestOps() and SelectionKey.OP_READ.inv())
        } catch (_: Exception) {
        }
    }

    /** 故障日志用域名 (假 IP 已反查); 拿不到就退回 remoteHost。 */
    private fun targetForLog(): String = resolvedRemoteHost ?: remoteHost ?: "?"

    /**
     * 「已写未读」看门狗: 写进 SSH 但一直读不回, 是最难排的一类故障 ——
     * `隧道通道就绪` 正常、所有丢弃计数为 0、SSH 侧也无异常, 只能等到 30s 客户端
     * 放弃时才从 `隧道零回程` 里看到结果。这里 5s 就报, 并带上 `sessionId`:
     * **同会话多个通道一起无回程 = JSch 读线程被某通道的管道拖死 (会话级冻结)**;
     * 只有本连接无回程 = 远端对这个主机不应答 —— 两者排障方向完全相反。
     */
    private fun reportZeroReturnIfNeeded() {
        if (zeroReturnWarned || state != SocksState.Relaying) return
        val written = toTunnelTotal.get()
        if (written == 0L || fromTunnelTotal.get() > 0L) return
        val startedAt = firstSshWriteAt
        if (startedAt == 0L) return
        val waited = System.currentTimeMillis() - startedAt
        if (waited < ZERO_RETURN_WARN_MS) return
        zeroReturnWarned = true
        VpnController.appLogThrottled(
            "隧道无回程 · ${targetForLog()}:$remotePort — SSH 已写 $written B / 读回 0B · $waited ms " +
                "(会话 ${targetTunnel?.sessionId ?: "?"}; 同会话其它通道也没回程=会话读线程被管道拖死, " +
                "只有本连接=远端不应答)",
            level = LogLevel.WARNING,
            throttleKey = "隧道无回程",
        )
    }

    /**
     * 检查连接超时
     * @return true 表示已超时，应关闭连接
     */
    private fun checkTimeout(): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastActivity

        return when (state) {
            SocksState.Handshake, SocksState.AuthMethods, SocksState.Request, SocksState.Connecting -> {
                // 连接建立阶段：使用连接超时
                elapsed > connectionTimeoutMs
            }
            SocksState.Relaying -> {
                // 数据传输阶段：使用空闲超时
                elapsed > idleTimeoutMs
            }
            else -> false
        }
    }

    /**
     * 启动超时检查定时任务
     */
    fun startTimeoutChecker() {
        // 幂等: handleAccept 与 onTargetConnected 各调用一次, 避免孤儿协程
        if (timeoutCheckJob != null) return
        timeoutCheckJob =
            scope.launch {
                while (state != SocksState.Closed) {
                    kotlinx.coroutines.delay(TIMEOUT_CHECK_INTERVAL_MS)
                    reportZeroReturnIfNeeded()
                    if (state != SocksState.Closed && checkTimeout()) {
                        android.util.Log.w("Socks5Proxy", "Connection $id timed out (state=$state)")
                        close()
                        break
                    }
                }
            }
    }

    @Synchronized
    fun close() {
        if (state == SocksState.Closed) return
        state = SocksState.Closed

        // 隧道侧零回程 (TcpStateMachine.reportTunnelBlackHole 的镜像, 但带 SSH 真实写出量):
        // TcpStateMachine 的 forwardedBytes 只说明数据**交给了本地代理**, toTunnelTotal 才是
        // 真正写进 SSH 通道的字节。两者都 >0 而读回 0 → 数据确实出了本栈, 问题在远端/回程;
        // 若 toTunnelTotal 明显小于上行 → 上行堵在本栈 (Channel 满 / 写协程卡), 排障方向相反。
        val sshTarget = targetForLog()
        if (targetTunnel != null) {
            val sshSent = toTunnelTotal.get()
            if (sshSent > 0 && fromTunnelTotal.get() == 0L) {
                VpnController.appLogThrottled(
                    "隧道侧零回程 · $sshTarget:$remotePort — SSH 写出 $sshSent B / 读回 0B · " +
                        "存活 ${System.currentTimeMillis() - openedAt}ms (会话 ${targetTunnel?.sessionId ?: "?"})",
                    level = LogLevel.WARNING,
                    throttleKey = "隧道侧零回程",
                )
            }
        }

        timeoutCheckJob?.cancel()
        timeoutCheckJob = null
        scope.cancel()

        try {
            channel.close()
        } catch (_: Exception) {
        }
        try {
            val sk = selectionKey
            if (sk != null) {
                if (sk.isValid) {
                    sk.interestOps(0)
                    sk.cancel()
                }
                sk.attach(null)
            }
        } catch (_: Exception) {
        }
        selectionKey = null

        try {
            targetTunnel?.disconnect()
        } catch (_: Exception) {
        }
        targetTunnel = null

        // 本栈主动关闭 (空闲超时 / SSH 写失败 / 会话死亡 / 握手失败) —— 必须让
        // TcpStateMachine 发 RST 而不是干等它 300s 陈旧清理; 且必须在 onClosed() 的
        // remove **之前** invoke, 否则回调被摘走却没被调用, 纯下行连接就此悬挂。
        // 拿到 null = 对面 (relay EOF / TSM 自己) 已上报过, 幂等靠 CHM.remove 保证。
        pendingTargetEofCallbacksRef?.remove(tunCallbackKey)?.invoke(false)

        onClosed()
    }
}
