package com.sshinjector.domain.vpn

import com.sshinjector.domain.vpn.TunnelChannel
import com.sshinjector.domain.vpn.SshChannelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 SOCKS5 代理服务器 (RFC 1928)
 * 
 * 接受来自 VPNService 的连接，通过 SSH 隧道转发到远程服务器
 * 支持: TCP CONNECT, UDP ASSOCIATE, IPv4/IPv6/域名
 */
@Singleton
class Socks5ProxyServer @Inject constructor(
    private val sshChannelFactory: SshChannelFactory
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
        val error: String? = null
    ) {
        enum class Status { Stopped, Starting, Running, Stopping, Error }
    }

    /**
     * 启动 SOCKS5 服务器
     */
    suspend fun start(port: Int = 1080, bindAddress: String = "127.0.0.1"): Result<Int> {
        if (serverState.value.status == ServerState.Status.Running) {
            return Result.success(boundPort.value ?: port)
        }

        serverState.value = ServerState(ServerState.Status.Starting)

        return try {
            serverChannel = ServerSocketChannel.open().apply {
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
        
        try { selector?.close() } catch (_: Exception) {}
        try { serverChannel?.close() } catch (_: Exception) {}
        
        selector = null
        serverChannel = null
        boundPort.value = null
        activeConnections.value = 0
        
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        serverState.value = ServerState(ServerState.Status.Stopped)
    }

    private fun eventLoop() {
        while (!Thread.interrupted() && selector?.isOpen == true) {
            try {
                val ready = selector?.select(10) ?: break
                if (ready == 0) continue
                
                val selectedKeys = selector?.selectedKeys() ?: continue
                val keysCopy = selectedKeys.toSet()
                selectedKeys.clear()
                
                for (key in keysCopy) {
                    if (!key.isValid) continue
                    
                    when {
                        key.isAcceptable -> handleAccept(key)
                        key.isReadable -> handleRead(key)
                        key.isWritable -> handleWrite(key)
                    }
                }
            } catch (e: IOException) {
                if (selector?.isOpen == true) {
                    android.util.Log.w("Socks5Proxy", "eventLoop IO error", e)
                } else {
                    break
                }
            } catch (e: Exception) {
                android.util.Log.e("Socks5Proxy", "eventLoop unexpected error", e)
            }
        }
    }

    // TUN 写回回调: connectionId → callback(data)
    private val pendingTunCallbacks = ConcurrentHashMap<Int, (ByteArray) -> Unit>()

    /**
     * 注册 TUN 写回回调，由 PacketProcessor.forwardSynToSocks 调用
     */
    fun registerTunCallback(connectionId: Int, callback: (ByteArray) -> Unit) {
        pendingTunCallbacks[connectionId] = callback
    }

    fun removeTunCallback(connectionId: Int) {
        pendingTunCallbacks.remove(connectionId)
    }

    fun getTunCallback(connectionId: Int): ((ByteArray) -> Unit)? {
        return pendingTunCallbacks.remove(connectionId)
    }

    private fun handleAccept(key: SelectionKey) {
        val serverChannel = key.channel() as ServerSocketChannel
        val clientChannel = serverChannel.accept() ?: return
        
        clientChannel.configureBlocking(false)
        val connectionId = connectionIdCounter.incrementAndGet()
        
        val clientPort = clientChannel.socket().remoteSocketAddress?.let {
            (it as? java.net.InetSocketAddress)?.port
        } ?: 0
        
        val connection = Socks5Connection(
            id = connectionId,
            channel = clientChannel,
            sshChannelFactory = sshChannelFactory,
            onDataSent = { bytes -> totalBytesUp.value = totalBytesUp.value + bytes },
            onDataReceived = { bytes -> totalBytesDown.value = totalBytesDown.value + bytes },
            onClosed = { 
                connections.remove(connectionId.toInt())
                removeTunCallback(clientPort)
                activeConnections.value = connections.size
            },
            onDataFromTarget = null
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

    private     fun handleRead(key: SelectionKey) {
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

    fun getStats(): ProxyStats {
        return ProxyStats(
            status = serverState.value.status,
            port = boundPort.value,
            activeConnections = activeConnections.value,
            totalBytesUp = totalBytesUp.value,
            totalBytesDown = totalBytesDown.value
        )
    }

    data class ProxyStats(
        val status: ServerState.Status,
        val port: Int?,
        val activeConnections: Int,
        val totalBytesUp: Long,
        val totalBytesDown: Long
    )
}

/**
 * 单个 SOCKS5 连接处理 (状态机)
 * 通过 SSH 隧道 (TunnelChannel) 转发流量到目标服务器
 */
class Socks5Connection(
    val id: Long,
    val channel: SocketChannel,
    private val sshChannelFactory: SshChannelFactory?,
    private val onDataSent: (Long) -> Unit,
    private val onDataReceived: (Long) -> Unit,
    private val onClosed: () -> Unit,
    var onDataFromTarget: ((ByteArray) -> Unit)? = null
) {
    private val buffer = ByteBuffer.allocateDirect(32768)
    private var state = SocksState.Handshake
    private var targetTunnel: TunnelChannel? = null
    private var remoteHost: String? = null
    private var remotePort: Int = 0
    private val pendingWrites = java.util.concurrent.ConcurrentLinkedDeque<ByteBuffer>()
    private var relayThread: Thread? = null
    internal var selectionKey: SelectionKey? = null
    internal var tunCallbackKey: Int = 0
    internal var pendingTunCallbacksRef: java.util.concurrent.ConcurrentHashMap<Int, (ByteArray) -> Unit>? = null
    
    // 超时配置
    private var lastActivity = System.currentTimeMillis()
    private val connectionTimeoutMs = 10000L  // 连接建立超时 10s
    private val idleTimeoutMs = 300000L       // 空闲超时 5 分钟
    private var timeoutCheckJob: kotlinx.coroutines.Job? = null
    
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    enum class SocksState {
        Handshake,      // 等待客户端握手
        AuthMethods,    // 认证方法协商
        Request,        // 等待连接请求
        Connecting,     // 正在连接目标
        Relaying,       // 数据中转
        Closed
    }

    fun handleRead(key: SelectionKey) {
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
                android.util.Log.d("Socks5Proxy", "[conn=$id] handleRead: EOF (state=$state)")
                close()
                return
            }

            buffer.flip()
            lastActivity = System.currentTimeMillis()
            onDataReceived(read.toLong())

            // 循环处理 buffer 中所有可用数据
            while (buffer.hasRemaining() && state != SocksState.Closed) {
                when (state) {
                    SocksState.Handshake -> processHandshake()
                    SocksState.AuthMethods -> processAuthMethods()
                    SocksState.Request -> processRequest()
                    SocksState.Connecting -> break
                    SocksState.Relaying -> { relayToTarget(); break }
                    SocksState.Closed -> break
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("Socks5Proxy", "[conn=$id] handleRead exception: ${e.message}", e)
            close()
        }
    }

    fun handleWrite(key: SelectionKey) {
        pendingWrites.poll()?.let { buf ->
            try {
                val written = channel.write(buf)
                onDataSent(written.toLong())
                lastActivity = System.currentTimeMillis()
                if (!buf.hasRemaining()) {
                    // Buffer fully written, remove OP_WRITE if queue is empty
                    if (pendingWrites.isEmpty()) {
                        key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
                    } else {
                        // Re-add this buffer to front of queue
                        pendingWrites.addFirst(buf)
                    }
                } else {
                    // Buffer partially written, re-add to front
                    pendingWrites.addFirst(buf)
                }
            } catch (e: Exception) {
                close()
            }
        }
        // If queue is empty, remove OP_WRITE
        if (pendingWrites.isEmpty()) {
            key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
        }
    }

    private fun processHandshake() {
        // SOCKS5 握手: VER(1) NMETHODS(1) METHODS(*)
        if (buffer.remaining() < 2) return

        val ver = buffer.get() .toInt() and 0xFF
        val nMethods = buffer.get() .toInt() and 0xFF

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

        val ver = buffer.get() .toInt() and 0xFF
        val cmd = buffer.get() .toInt() and 0xFF
        val rsv = buffer.get() .toInt() and 0xFF // 必须为 0
        val atyp = buffer.get() .toInt() and 0xFF

        when (cmd) {
            0x01 -> { // CONNECT
                // 解析目标地址
                val (host, port) = when (atyp) {
                    0x01 -> parseIpv4()   // IPv4
                    0x03 -> parseDomain() // 域名
                    0x04 -> parseIpv6()   // IPv6
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
                val (clientHost, clientPort) = when (atyp) {
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
                val len = buffer.get() .toInt() and 0xFF
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

    private fun buildTunResponse(bindHost: String, bindPort: Int): ByteArray {
        return ByteArray(10).apply {
            this[0] = 0x05         // VER: 5
            this[1] = 0x00         // REP: Success
            this[2] = 0x00         // RSV: 0
            this[3] = 0x01         // ATYP: IPv4
            this[4] = 0x00
            this[5] = 0x00
            this[6] = 0x00
            this[7] = 0x00         // BND.ADDR: 0.0.0.0
            this[8] = (bindPort shr 8).toByte()
            this[9] = bindPort.toByte()   // BND.PORT: 0
        }
    }

    private fun buildUdpTunResponse(bindHost: String, bindPort: Int): ByteArray {
        return buildTunResponse(bindHost, bindPort)
    }

    private fun parseIpv4(): Pair<String, Int> {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        val port = readPort()
        return java.net.InetAddress.getByAddress(bytes).hostAddress!! to port
    }

    private fun parseIpv6(): Pair<String, Int> {
        val bytes = ByteArray(16)
        buffer.get(bytes)
        val port = readPort()
        return java.net.InetAddress.getByAddress(bytes).hostAddress!! to port
    }

    private fun parseDomain(): Pair<String, Int> {
        val len = buffer.get() .toInt() and 0xFF
        val bytes = ByteArray(len)
        buffer.get(bytes)
        val port = readPort()
        return String(bytes) to port
    }

    private fun readPort(): Int {
        val b1 = buffer.get() .toInt() and 0xFF
        val b2 = buffer.get() .toInt() and 0xFF
        return (b1 shl 8) or b2
    }

    /**
     * 通过 SSH 隧道连接目标服务器
     * 使用 SshChannelFactory 创建 TunnelChannel (ChannelDirectTCPIP)
     */
    private fun connectToTarget() {
        val factory = sshChannelFactory
        val host = remoteHost
        val port = remotePort

        android.util.Log.d("Socks5Proxy", "connectToTarget: $host:$port, factory=${factory != null}")

        if (factory == null || host == null) {
            android.util.Log.e("Socks5Proxy", "connectToTarget failed: factory=$factory host=$host")
            sendErrorReply(0x05)
            close()
            return
        }

        scope.launch {
            try {
                val tunnel = factory.createDirectChannel(host, port)
                if (tunnel == null) {
                    android.util.Log.e("Socks5Proxy", "connectToTarget failed: createDirectChannel returned null for $host:$port")
                    sendErrorReply(0x05)
                    close()
                    return@launch
                }

                val connected = tunnel.connect(5000)
                if (!connected) {
                    android.util.Log.e("Socks5Proxy", "connectToTarget failed: tunnel.connect returned false for $host:$port")
                    tunnel.disconnect()
                    sendErrorReply(0x05)
                    close()
                    return@launch
                }

                android.util.Log.d("Socks5Proxy", "connectToTarget success: $host:$port")
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

        // 启动超时检查
        startTimeoutChecker()

        // 继续处理缓冲区剩余数据
        if (buffer.hasRemaining()) {
            relayToTarget()
        }
    }

    /**
     * 启动反向中继线程: 从 SSH 隧道读取数据写回 SOCKS5 客户端
     */
private fun startRelayFromTarget() {
        val tunnel = targetTunnel ?: return
        val input = tunnel.inputStream ?: return

        relayThread = Thread({
            val readBuffer = ByteBuffer.allocate(32768)
            var resolvedCallback = onDataFromTarget
            if (resolvedCallback == null && pendingTunCallbacksRef != null) {
                resolvedCallback = pendingTunCallbacksRef!![tunCallbackKey]
            }
            val callback = resolvedCallback
            android.util.Log.d("Socks5Proxy", "[conn=$id] relayFromTarget started, callbackKey=$tunCallbackKey callback=${if (callback != null) "set" else "MISSING"}")
            try {
                while (tunnel.isConnected && state == SocksState.Relaying) {
                    readBuffer.clear()
                    val read = input.read(readBuffer.array())
                    if (read == -1) {
                        android.util.Log.w("Socks5Proxy", "[conn=$id] relayFromTarget: EOF from tunnel")
                        this@Socks5Connection.close()
                        break
                    }
                    if (read > 0) {
                        val data = readBuffer.array().copyOf(read)
                        val hex = data.joinToString("") { "%02x".format(it) }
                        android.util.Log.d("Socks5Proxy", "[conn=$id] relayFromTarget: received ${read}B hex=$hex callback=${callback != null}")
                        callback?.invoke(data)
                        onDataReceived(read.toLong())
                        if (callback == null) {
                            sendReply(data)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Socks5Proxy", "[conn=$id] relayFromTarget error: ${e.message}", e)
            } finally {
                android.util.Log.d("Socks5Proxy", "[conn=$id] relayFromTarget thread exiting")
            }
        }, "SOCKS5-Relay-FromTarget-$id")
        relayThread?.isDaemon = true
        relayThread?.start()
    }

    private fun buildSuccessReply(): ByteArray {
        // 简化: 返回 0.0.0.0:0 作为绑定地址
        return byteArrayOf(
            0x05, 0x00, 0x00, 0x01,  // VER REP RSV ATYP(IPv4)
            0x00, 0x00, 0x00, 0x00,  // BND.ADDR (0.0.0.0)
            0x00, 0x00               // BND.PORT (0)
        )
    }

    private fun sendErrorReply(rep: Int) {
        val reply = byteArrayOf(
            0x05, rep.toByte(), 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00
        )
        sendReply(reply)
    }

    private fun sendReply(reply: ByteArray) {
        try {
            if (selectionKey != null && selectionKey!!.isValid) {
                // Queue the reply for writing
                pendingWrites.add(ByteBuffer.wrap(reply))
                selectionKey!!.interestOps(selectionKey!!.interestOps() or SelectionKey.OP_WRITE)
            } else {
                channel.write(ByteBuffer.wrap(reply))
            }
        } catch (e: Exception) {
            close()
        }
    }

    /**
     * 转发数据到 SSH 隧道 (SOCKS5 Client → SSH Tunnel)
     */
    private fun relayToTarget() {
        val tunnel = targetTunnel
        if (tunnel == null || state != SocksState.Relaying) return

        try {
            val output = tunnel.outputStream
            if (output != null) {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
                output.flush()
                onDataSent(bytes.size.toLong())
            }
            buffer.clear()
            lastActivity = System.currentTimeMillis()
        } catch (e: Exception) {
            android.util.Log.e("Socks5Proxy", "[conn=$id] relayToTarget error: ${e.message}", e)
            close()
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
        timeoutCheckJob = scope.launch {
            while (state != SocksState.Closed) {
                kotlinx.coroutines.delay(5000) // 每 5 秒检查一次
                if (state != SocksState.Closed && checkTimeout()) {
                    android.util.Log.w("Socks5Proxy", "Connection ${id} timed out (state=$state)")
                    close()
                    break
                }
            }
        }
    }

    fun close() {
        if (state == SocksState.Closed) return
        state = SocksState.Closed

        timeoutCheckJob?.cancel()
        timeoutCheckJob = null

        relayThread?.interrupt()
        relayThread = null

        try { channel.close() } catch (_: Exception) {}
        try {
            val sk = selectionKey
            if (sk != null) {
                if (sk.isValid) {
                    sk.interestOps(0)
                    sk.cancel()
                }
                sk.attach(null)
            }
        } catch (_: Exception) {}
        selectionKey = null

        try { targetTunnel?.disconnect() } catch (_: Exception) {}
        targetTunnel = null

        onClosed()
    }
}