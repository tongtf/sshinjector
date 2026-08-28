package cn.srv0.sshinjector.domain.vpn

import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private val IS_DEBUG = android.util.Log.isLoggable("Socks5Proxy", android.util.Log.DEBUG)
private const val TIMEOUT_CHECK_INTERVAL_MS = 5000L
private const val SSH_SEND_QUEUE_CAPACITY = 256
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
                                    when {
                                        key.isAcceptable -> handleAccept(key)
                                        key.isReadable -> handleRead(key)
                                        key.isWritable -> handleWrite(key)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (selector?.isOpen == true) {
                        android.util.Log.w("Socks5Proxy", "eventLoop IO error", e)
                    } else {
                        stopped = true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Socks5Proxy", "eventLoop unexpected error", e)
                }
            }
        }

        // TUN 写回回调: connectionId → callback(data)
        private val pendingTunCallbacks = ConcurrentHashMap<Int, (ByteArray, Int, Int) -> Unit>()

        /**
         * 注册 TUN 写回回调，由 PacketProcessor.forwardSynToSocks 调用
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
                        activeConnections.value = connections.size
                    },
                    onDataFromTarget = null,
                    ipToDomainLookup = { dnsInterceptor.ipToDomain[it] },
                )

            // 延迟查找回调: relayFromTarget 首次使用前从 pendingTunCallbacks 获取
            connection.tunCallbackKey = clientPort
            connection.pendingTunCallbacksRef = pendingTunCallbacks

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
) {
    private val buffer = ByteBuffer.allocateDirect(32768)

    @Volatile private var state = SocksState.Handshake
    private var targetTunnel: TunnelChannel? = null
    private var remoteHost: String? = null
    private var remotePort: Int = 0
    private val pendingWrites = java.util.concurrent.ConcurrentLinkedDeque<ByteBuffer>()
    internal var selectionKey: SelectionKey? = null
    internal var tunCallbackKey: Int = 0
    internal var pendingTunCallbacksRef: ConcurrentHashMap<Int, (ByteArray, Int, Int) -> Unit>? = null

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

            buffer.clear()
            val read = channel.read(buffer)

            if (read == -1) {
                if (IS_DEBUG) android.util.Log.d("Socks5Proxy", "[conn=$id] handleRead: EOF (state=$state)")
                close()
                return
            }

            buffer.flip()
            lastActivity = System.currentTimeMillis()
            onDataReceived(read.toLong())

            // 循环处理 buffer 中所有可用数据
            var keepProcessing = true
            while (keepProcessing && buffer.hasRemaining() && state != SocksState.Closed) {
                when (state) {
                    SocksState.Handshake -> processHandshake()
                    SocksState.AuthMethods -> processAuthMethods()
                    SocksState.Request -> processRequest()
                    SocksState.Connecting -> keepProcessing = false
                    SocksState.Relaying -> {
                        enqueueToSsh()
                        keepProcessing = false
                    }
                    SocksState.Closed -> keepProcessing = false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Socks5Proxy", "[conn=$id] handleRead exception: ${e.message}", e)
            close()
        }
    }

    fun handleWrite(key: SelectionKey) {
        var buf = pendingWrites.peek()
        while (buf != null) {
            if (buf.hasRemaining()) {
                try {
                    val written = channel.write(buf)
                    onDataSent(written.toLong())
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

    private fun processHandshake() {
        // SOCKS5 握手: VER(1) NMETHODS(1) METHODS(*)
        if (buffer.remaining() < 2) return

        val ver = buffer.get().toInt() and 0xFF
        val nMethods = buffer.get().toInt() and 0xFF

        if (ver != 0x05) {
            close()
            return
        }

        // 跳过方法列表
        val methodsToSkip = minOf(nMethods, buffer.remaining())
        buffer.position(buffer.position() + methodsToSkip)

        // 回复: 版本 5, 无认证 (0x00)
        sendReply(byteArrayOf(0x05, 0x00))
        state = SocksState.Request
    }

    private fun processAuthMethods() {
        // 已在握手时处理，无认证直接进入 Request
        state = SocksState.Request
    }

    private fun processRequest() {
        // SOCKS5 请求: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR(*) DST.PORT(2)
        if (buffer.remaining() < 4) {
            android.util.Log.w("Socks5Proxy", "[conn=$id] processRequest: not enough data (${buffer.remaining()} < 4)")
            return
        }

        val ver = buffer.get().toInt() and 0xFF
        val cmd = buffer.get().toInt() and 0xFF
        buffer.get() // RSV，必须为 0
        val atyp = buffer.get().toInt() and 0xFF

        when (cmd) {
            0x01 -> { // CONNECT
                // 解析目标地址
                val (host, port) =
                    when (atyp) {
                        0x01 -> parseIpv4() // IPv4
                        0x03 -> parseDomain() // 域名
                        0x04 -> parseIpv6() // IPv6
                        else -> {
                            sendErrorReply(0x08) // Address type not supported
                            close()
                            return
                        }
                    }

                remoteHost = host
                remotePort = port

                // 异步连接目标 (通过 SSH 隧道)
                state = SocksState.Connecting
                connectToTarget()
            }
            0x03 -> { // UDP ASSOCIATE
                // UDP ASSOCIATE: 客户端告知 UDP relay 地址
                // 解析客户端的 UDP 地址 (用于后续 UDP 转发)
                val (clientHost, clientPort) =
                    when (atyp) {
                        0x01 -> parseIpv4()
                        0x03 -> parseDomain()
                        0x04 -> parseIpv6()
                        else -> {
                            // 全零表示客户端尚不知道自己的地址
                            skipAddressAndPort(atyp)
                            "0.0.0.0" to 0
                        }
                    }

                // 回复成功，告知 UDP relay 地址
                sendTunResponse(buildUdpTunResponse(clientHost, clientPort))
                // 注意: UDP ASSOCIATE 连接保持打开直到 TCP 连接关闭
                // 实际的 UDP 数据转发由 PacketProcessor 处理
            }
            else -> {
                sendErrorReply(0x07) // Command not supported
                close()
            }
        }
    }

    private fun skipAddressAndPort(atyp: Int) {
        when (atyp) {
            0x01 -> buffer.position(buffer.position() + 4 + 2) // IPv4 + Port
            0x04 -> buffer.position(buffer.position() + 16 + 2) // IPv6 + Port
            0x03 -> {
                val len = buffer.get().toInt() and 0xFF
                buffer.position(buffer.position() + len + 2) // Domain + Port
            }
        }
    }

    fun sendTunResponse(response: ByteArray) {
        try {
            if (selectionKey != null && selectionKey!!.isValid) {
                // 统一使用 pendingWrites 队列
                pendingWrites.add(ByteBuffer.wrap(response))
                selectionKey!!.interestOps(selectionKey!!.interestOps() or SelectionKey.OP_WRITE)
            } else {
                // 备用 - 使用 channel.write (direct write)
                channel.write(ByteBuffer.wrap(response))
            }
        } catch (e: Exception) {
            close()
        }
    }

    private fun buildTunResponse(
        ignoredBindHost: String,
        bindPort: Int,
    ): ByteArray =
        ByteArray(10).apply {
            this[0] = 0x05 // VER: 5
            this[1] = 0x00 // REP: Success
            this[2] = 0x00 // RSV: 0
            this[3] = 0x01 // ATYP: IPv4
            this[4] = 0x00
            this[5] = 0x00
            this[6] = 0x00
            this[7] = 0x00 // BND.ADDR: 0.0.0.0
            this[8] = (bindPort shr 8).toByte()
            this[9] = bindPort.toByte() // BND.PORT: 0
        }

    private fun buildUdpTunResponse(
        ignoredBindHost: String,
        bindPort: Int,
    ): ByteArray = buildTunResponse(ignoredBindHost, bindPort)

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
            sendErrorReply(0x05)
            close()
            return
        }

        // 线程池满载时直接拒绝: 避免 CallerRunsPolicy 将阻塞的 SSH 连接操作
        // 回执到 eventLoop 线程, 冻结整个 SOCKS5 代理 (128+ 并发时可能触发)
        if (sshIoDispatcher.isSaturated()) {
            android.util.Log.w("Socks5Proxy", "connectToTarget rejected: ssh-io pool saturated ($host:$port)")
            sendErrorReply(0x05)
            close()
            return
        }

        // 解析假 IP 为真实域名 (198.18.x.x 或 fd00::/64 范围)
        val resolvedHost =
            if (host.startsWith("198.18.")) {
                ipToDomainLookup?.invoke(host) ?: host
            } else if (host.startsWith("fd00:")) {
                ipToDomainLookup?.invoke(host) ?: host
            } else {
                host
            }

        if (resolvedHost != host && IS_DEBUG) {
            android.util.Log.d("Socks5Proxy", "Resolved fake IP $host to domain $resolvedHost")
        }

        // 提取 CONNECT 请求后预读的剩余数据 (当前在 eventLoop 线程, buffer 独占)。
        // onTargetConnected 在 sshIoDispatcher 线程只读此字段, 避免共享 buffer 跨线程并发。
        if (buffer.hasRemaining()) {
            val remaining = buffer.remaining()
            val data = ByteArray(remaining)
            buffer.get(data)
            buffer.clear()
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
                    sendErrorReply(0x05)
                    close()
                    return@launch
                }

                val connected = tunnel.connect(5000)
                if (!connected) {
                    android.util.Log.e(
                        "Socks5Proxy",
                        "connectToTarget failed: tunnel.connect returned false for $host:$port",
                    )
                    tunnel.disconnect()
                    sendErrorReply(0x05)
                    close()
                    return@launch
                }

                if (IS_DEBUG) android.util.Log.d("Socks5Proxy", "connectToTarget success: $host:$port")
                targetTunnel = tunnel
                onTargetConnected()
            } catch (e: Exception) {
                android.util.Log.e("Socks5Proxy", "connectToTarget exception: $host:$port", e)
                sendErrorReply(0x05)
                close()
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
                        this@Socks5Connection.close()
                        break
                    }
                    if (read > 0) {
                        if (IS_DEBUG) {
                            android.util.Log.d(
                                "Socks5Proxy",
                                "[conn=$id] relayFromTarget: received ${read}B wait=${readWaitMs}ms " +
                                    "callback=${callback != null}",
                            )
                        }
                        callback?.invoke(readBuffer.array(), 0, read)
                        onDataReceived(read.toLong())
                        if (callback == null) {
                            sendReply(readBuffer.array(), 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Socks5Connection", "[conn=$id] relayFromTarget error: ${e.message}", e)
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

    private fun sendErrorReply(rep: Int) {
        val reply =
            byteArrayOf(
                0x05,
                rep.toByte(),
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )
        sendReply(reply)
    }

    private fun sendReply(reply: ByteArray) {
        sendReply(reply, 0, reply.size)
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
                channel.write(ByteBuffer.wrap(data, offset, length))
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
            buffer.clear()
            return
        }
        if (!buffer.hasRemaining()) {
            buffer.clear()
            return
        }
        val remaining = buffer.remaining()
        val data = ByteArray(remaining)
        buffer.get(data)
        buffer.clear()
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
                    suspendLocalRead()
                }
            }
        }
        lastActivity = System.currentTimeMillis()
        onDataSent(data.size.toLong())
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
                    resumeLocalReadIfSpace()
                } catch (e: Exception) {
                    if (state != SocksState.Closed) {
                        android.util.Log.e("Socks5Proxy", "[conn=$id] ssh write error: ${e.message}", e)
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

        onClosed()
    }
}
