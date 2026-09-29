package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val IS_DEBUG = android.util.Log.isLoggable("PacketProcessor", android.util.Log.DEBUG)

/**
 * TCP 状态机：解析 TCP 头、维护连接状态、通过隧道/SOCKS5 转发。
 */
class TcpStateMachine(
    private val tunnelManager: TunnelManager,
    private val tunWriterProvider: () -> ((ByteArray) -> Unit)?,
    private val stats: PacketStats,
    private val sshIoDispatcher: SshIoDispatcher,
) {
    companion object {
        private const val TAG = "PacketProcessor"
        private const val RELAY_BUFFER_SIZE = 65535
        private const val MAX_TCP_SEGMENT = 1460
        private const val UINT32_MASK = 0xFFFFFFFFL
        private const val TUN_CONNECT_TIMEOUT_MS = 5000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tcpConnections = ConcurrentHashMap<Long, TcpConnection>()
    private val connectionIdCounter = AtomicLong(0)

    // F1: 用户态直连策略 (VpnController 注入) —— null = 不启用, 全部走隧道
    @Volatile private var bypassPredicate: ((InetAddress, Int) -> Boolean)? = null

    @Volatile private var protectSocketFn: ((java.net.Socket) -> Boolean)? = null

    fun setBypass(
        shouldBypass: (InetAddress, Int) -> Boolean,
        protect: (java.net.Socket) -> Boolean,
    ) {
        bypassPredicate = shouldBypass
        protectSocketFn = protect
    }

    // 回向直通回调注册目标 (socks5 插件), 连接关闭时用于移除回调
    @Volatile private var tunCallbackPlugin: TunnelPlugin? = null

    private var dnsInterceptor: DnsInterceptor? = null

    fun setDnsInterceptor(interceptor: DnsInterceptor) {
        dnsInterceptor = interceptor
    }

    data class TcpConnection(
        val id: Long,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        var socksChannel: SocketChannel? = null,
        var tunnelChannel: TunnelChannel? = null,
        @Volatile var state: TcpState = TcpState.SynSent,
        @Volatile var lastActivity: Long = System.currentTimeMillis(),
        @Volatile var browserSeq: Long = 0,
        @Volatile var serverSeq: Long = 0,
        @Volatile var forwardedBytes: Long = 0,
        var socksLocalPort: Int = 0,
    ) {
        enum class TcpState {
            SynSent,
            Established,
            Closed,
        }
    }

    /**
     * 处理 TCP 数据包
     */
    fun processTcpPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int,
    ): Boolean {
        if (payloadLength < 20) return false // 最小 TCP 头部

        buffer.position(payloadStart)
        val srcPort = buffer.getShort().toInt() and 0xFFFF
        val dstPort = buffer.getShort().toInt() and 0xFFFF
        val seqNum = buffer.getInt()
        val ackNum = buffer.getInt()
        val dataOffsetFlags = buffer.getShort().toInt() and 0xFFFF
        val dataOffset = (dataOffsetFlags shr 12) * 4
        val flags = dataOffsetFlags and 0x0FFF
        buffer.position(buffer.position() + 4) // skip windowSize + checksum + urgentPtr

        // 跳过选项
        if (dataOffset > 20) {
            buffer.position(payloadStart + dataOffset)
        }

        val payloadLen = payloadLength - dataOffset
        val hasPayload = payloadLen > 0

        // TCP 标志位
        val fin = (flags and 0x01) != 0
        val syn = (flags and 0x02) != 0
        val rst = (flags and 0x04) != 0
        val ack = (flags and 0x10) != 0

        // 连接标识符 (五元组哈希)
        val connKey = IpPacketParser.connectionKey(srcIp, dstIp, srcPort, dstPort)
        var conn = tcpConnections[connKey]

        if (syn && !ack) {
            if (conn == null) {
                conn = createTcpConnection(connKey, srcIp, dstIp, srcPort, dstPort)
                conn.browserSeq = (seqNum.toLong()) and UINT32_MASK
                conn.state = TcpConnection.TcpState.SynSent

                // 通过隧道插件建立连接 (内部取 active/fallback 插件; 失败由 forwardSynToTunnel 自身 try/catch 处理)
                forwardSynToTunnel(conn)
            } else {
                conn.browserSeq = (seqNum.toLong()) and UINT32_MASK
                conn.lastActivity = System.currentTimeMillis()
            }
        } else if (conn != null) {
            conn.lastActivity = System.currentTimeMillis()

            if (rst) {
                // 对端已复位, 无需回包
                closeTcpConnection(connKey, conn, notifyBrowser = false)
            } else if (ack) {
                if (hasPayload) {
                    val expectedBrowserSeq = (conn.browserSeq + 1 + conn.forwardedBytes) and UINT32_MASK
                    val receivedSeq = seqNum.toLong() and UINT32_MASK
                    if (receivedSeq == expectedBrowserSeq) {
                        buffer.position(payloadStart + dataOffset)
                        val written = forwardToSocks(conn, buffer, payloadStart + dataOffset, payloadLen)
                        if (written > 0) {
                            // 部分写也只推进/ACK 到已写处; 未写完的字节浏览器重传,
                            // 重传段 seq 落在 [expected, expected+written) 之外 → 正常续写或 dup 分支, 不重复
                            conn.forwardedBytes += written.toLong()
                            // 立即回纯 ACK，避免浏览器因等待确认而超时重传
                            sendAckToBrowser(conn, connKey)
                        }
                        // written == 0 (SSH 背压/写失败): 不推进不 ACK,
                        // 浏览器超时重传该段, 数据不丢失; 背压停留在本连接, 不阻塞 packetLoop
                    } else if (receivedSeq < expectedBrowserSeq) {
                        // 重传段 (seq < expected): 数据已转发过, 丢弃重复, 回 ACK 推进浏览器窗口
                        if (IS_DEBUG) {
                            Log.d(
                                TAG,
                                "Retransmit (dup) for conn ${conn.id}: seq=$receivedSeq expected=$expectedBrowserSeq " +
                                    "fwd=${conn.forwardedBytes}, acking",
                            )
                        }
                        sendAckToBrowser(conn, connKey)
                    } else {
                        // 乱序段 (seq > expected): 尚未能按序转发, 丢弃并回 ACK, 触发浏览器快重传缺失段
                        if (IS_DEBUG) {
                            Log.d(
                                TAG,
                                "Out-of-order for conn ${conn.id}: seq=$receivedSeq expected=$expectedBrowserSeq " +
                                    "fwd=${conn.forwardedBytes}, acking",
                            )
                        }
                        sendAckToBrowser(conn, connKey)
                    }
                } else {
                    // pure ACK (three-way handshake completion) — no payload
                }
                if (fin) {
                    // 浏览器主动 FIN: 对端已关, 不回 RST (F6)
                    closeTcpConnection(connKey, conn, notifyBrowser = false)
                }
            }
        }

        stats.addPacket(payloadLength.toLong())
        return true
    }

    /**
     * 将 TCP SYN 转发到隧道插件建立连接
     * SOCKS5 优先：先回 SYN-ACK 再异步建 SSH 隧道，避免客户端超时
     */
    private fun forwardSynToTunnel(conn: TcpConnection) {
        scope.launch {
            try {
                // F1: 排除路由/域名分流未命中 → 用户态直连 (包已进 TUN, 回注是黑洞, 只能本地直连)
                if (bypassPredicate?.invoke(conn.dstIp, conn.dstPort) == true) {
                    forwardThroughBypass(conn)
                    return@launch
                }

                val plugin = tunnelManager.getActiveOrFallback()

                if (plugin.localSocksPort > 0) {
                    forwardThroughLocalSocks(conn, plugin, plugin.localSocksPort)
                } else {
                    val channel = plugin.openTcpChannel(conn.dstIp.hostAddress!!, conn.dstPort)
                    if (channel != null) {
                        forwardThroughDirectChannel(conn, plugin, channel)
                    } else {
                        Log.e(TAG, "Plugin ${plugin.id} provides neither SOCKS5 port nor direct channel")
                        val connKey = IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)
                        // F6: 收敛到 closeTcpConnection (内部发 RST + 移除条目, 允许同五元组重建)
                        closeTcpConnection(connKey, conn)
                    }
                }
            } catch (e: Exception) {
                if (IS_DEBUG) Log.e(TAG, "forwardSynToTunnel failed", e)
                stats.addError()
                closeTcpConnection(
                    IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort),
                    conn,
                )
            }
        }
    }

    /**
     * F1: 用户态直连 —— 受 VpnService.protect 保护的 SocketChannel 直接 connect 目标,
     * 复用现有上行 forwardToSocks (conn.socksChannel) 与下行 startRelayFromSocks 路径。
     * 与 forwardThroughDirectChannel 同构, 只是本地 socket 直连、不经 Socks5ProxyServer/SSH。
     */
    private fun forwardThroughBypass(conn: TcpConnection) {
        val connKey = IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)
        val protect = protectSocketFn
        val sc = SocketChannel.open()
        try {
            // protect 必须在 connect 之前: 未保护的套接字会路由进 VPN, 直连流量再次进 TUN 自环
            if (protect == null || !protect(sc.socket())) {
                Log.e(TAG, "bypass: protect function missing or VpnService.protect() failed for conn ${conn.id}")
                sc.close()
                // F6: 收敛到 closeTcpConnection (发 RST + 移除条目)
                closeTcpConnection(connKey, conn)
                return
            }
            // 非阻塞 connect + finishConnect 轮询 (SocketChannel 无带超时的阻塞 connect)
            sc.configureBlocking(false)
            val addr = InetSocketAddress(conn.dstIp, conn.dstPort)
            if (!sc.connect(addr)) {
                val deadline = System.currentTimeMillis() + TUN_CONNECT_TIMEOUT_MS
                while (!sc.finishConnect()) {
                    if (System.currentTimeMillis() > deadline) {
                        throw java.net.SocketTimeoutException("bypass connect timeout")
                    }
                    Thread.sleep(10)
                }
            }
            // 上行 forwardToSocks 依赖非阻塞 write 的 write()==0 背压语义, 保持非阻塞
            conn.socksChannel = sc
            conn.state = TcpConnection.TcpState.Established
            val synAckPacket = buildSynAckPacket(conn)
            if (synAckPacket != null) tunWriterProvider()?.invoke(synAckPacket)
            startRelayFromSocks(conn, connKey)
            if (IS_DEBUG) {
                Log.d(TAG, "TCP bypass direct to ${conn.dstIp}:${conn.dstPort} for conn ${conn.id}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bypass connect failed for conn ${conn.id}: ${e.message}")
            try {
                sc.close()
            } catch (_: IOException) {
            }
            closeTcpConnection(connKey, conn)
        }
    }

    /**
     * 通过本地 SOCKS5 代理转发 (保持原有 SOCKS5 握手流程)
     */
    @Suppress("ReturnCount") // 每条错误路径 fail-fast 早退, 合并单出口反而降低可读性
    private suspend fun forwardThroughLocalSocks(
        conn: TcpConnection,
        plugin: TunnelPlugin,
        socksPort: Int,
    ) {
        val sock = SocketChannel.open()
        sock.configureBlocking(true)
        sock.connect(InetSocketAddress("127.0.0.1", socksPort))

        val connKey = IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)

        // F8: SYN-ACK 必须先于任何回程数据写入 TUN —— tunCallback 在 CONNECT 之前已注册,
        // 服务端 banner (SSH-2.0-…) 可能先于 CONNECT 应答到达; 先写 SYN-ACK 保证浏览器内核栈
        // 先完成握手再收数据 (否则 SYN_SENT 直接丢弃且 seq 已推进 → banner 首字节永久丢失)。
        // serverSeq 的写者也随之只剩回程 relay 单线程, 原双写者 RMW 竞态消失。
        val earlySynAck = buildSynAckPacket(conn)
        if (earlySynAck != null) {
            tunWriterProvider()?.invoke(earlySynAck)
        }

        // 回向直通: 远端数据经插件回调直接写 TUN, 跳过本地 SOCKS socket 往返。
        // 注册必须在 CONNECT 请求前完成——Socks5ProxyServer 收到 CONNECT 后才启动
        // relay 协程, 因此此时注册可保证协程查询 callback 时必然命中。
        val localPort = (sock.socket().localSocketAddress as? InetSocketAddress)?.port ?: 0
        conn.socksLocalPort = localPort
        var directRelay = false
        if (localPort > 0) {
            try {
                plugin.registerTunCallback(localPort) { data, offset, length ->
                    writeTcpPayloadToTun(conn, data, offset, length)
                }
                tunCallbackPlugin = plugin
                directRelay = true
            } catch (e: Exception) {
                Log.w(TAG, "registerTunCallback failed for conn ${conn.id}, falling back to socket relay")
            }
        }

        // SOCKS5 握手: 仅提供用户名/密码认证 (RFC 1929, 0x02); 服务端 fail-closed, 无凭据必失败
        val creds = plugin.socksAuth
        if (creds == null) {
            Log.e(TAG, "SOCKS5 auth credentials missing, refusing connection to ${conn.dstIp}:${conn.dstPort}")
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }
        sock.write(ByteBuffer.wrap(byteArrayOf(0x05, 0x01, 0x02)))

        val handshakeResp = ByteBuffer.allocate(2)
        val hsRead = sock.read(handshakeResp)
        if (hsRead <= 0) {
            Log.e(TAG, "SOCKS5 handshake read failed: bytesRead=$hsRead")
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }
        handshakeResp.flip()
        val respVer = handshakeResp.get().toInt() and 0xFF
        val respMethod = handshakeResp.get().toInt() and 0xFF
        if (respVer != 0x05 || respMethod != 0x02) {
            Log.e(TAG, "SOCKS5 handshake failed: ver=$respVer method=$respMethod")
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }

        // RFC 1929 认证: VER(1)=0x01 ULEN(1) USER PLEN(1) PASS — 报文长 = 3 + userLen + passLen
        val userBytes = creds.first.toByteArray(Charsets.UTF_8)
        val passBytes = creds.second.toByteArray(Charsets.UTF_8)
        val authReq =
            ByteBuffer
                .allocate(3 + userBytes.size + passBytes.size)
                .apply {
                    put(0x01)
                    put(userBytes.size.toByte())
                    put(userBytes)
                    put(passBytes.size.toByte())
                    put(passBytes)
                }.array()
        sock.write(ByteBuffer.wrap(authReq))

        val authResp = ByteBuffer.allocate(2)
        val authRead = sock.read(authResp)
        if (authRead <= 0) {
            Log.e(TAG, "SOCKS5 auth read failed: bytesRead=$authRead")
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }
        authResp.flip()
        val authVer = authResp.get().toInt() and 0xFF
        val authStatus = authResp.get().toInt() and 0xFF
        if (authVer != 0x01 || authStatus != 0x00) {
            Log.e(TAG, "SOCKS5 auth rejected: ver=$authVer status=$authStatus")
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }

        // SOCKS5 CONNECT 请求
        val domain = dnsInterceptor?.ipToDomain?.get(conn.dstIp.hostAddress!!)
        val connectReq = buildSocks5ConnectRequest(conn.dstIp, conn.dstPort, domain)
        sock.write(ByteBuffer.wrap(connectReq))

        val connectResp = ByteBuffer.allocate(32)
        val bytesRead = sock.read(connectResp)
        if (bytesRead <= 0) {
            Log.e(TAG, "SOCKS5 CONNECT read failed")
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }
        connectResp.flip()
        val repVer2 = connectResp.get().toInt() and 0xFF
        val rep = connectResp.get().toInt() and 0xFF
        connectResp.get() // RSV
        val atyp = connectResp.get().toInt() and 0xFF
        if (repVer2 != 0x05 || rep != 0x00) {
            Log.e(TAG, "SOCKS5 CONNECT failed: rep=$rep")
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }

        // 跳过 BND.ADDR + BND.PORT
        when (atyp) {
            0x01 -> connectResp.position(connectResp.position() + 4 + 2)
            0x04 -> connectResp.position(connectResp.position() + 16 + 2)
            0x03 -> {
                val domainLen = connectResp.get().toInt() and 0xFF
                connectResp.position(connectResp.position() + domainLen + 2)
            }
        }

        conn.socksChannel = sock
        conn.state = TcpConnection.TcpState.Established
        try {
            // 握手完成后切非阻塞: 转发失败时丢弃段 + 不回 ACK, 由浏览器 TCP 重传兜底,
            // 避免慢连接阻塞 packetLoop 全局数据通路
            sock.configureBlocking(false)
        } catch (e: IOException) {
            Log.w(TAG, "configureBlocking(false) failed for conn ${conn.id}: ${e.message}")
        }

        if (!directRelay) {
            startRelayFromSocks(conn, connKey)
        }
        if (IS_DEBUG) Log.d(TAG, "TCP established via SOCKS5 to ${conn.dstIp}:${conn.dstPort}")
    }

    /**
     * 构建 SOCKS5 CONNECT 请求
     */
    private fun buildSocks5ConnectRequest(
        dstIp: java.net.InetAddress,
        dstPort: Int,
        domain: String?,
    ): ByteArray {
        val buf: ByteArray
        if (domain != null) {
            val domainBytes = domain.toByteArray(Charsets.US_ASCII)
            buf =
                ByteBuffer
                    .allocate(4 + 1 + domainBytes.size + 2)
                    .apply {
                        put(0x05) // VER
                        put(0x01) // CMD: CONNECT
                        put(0x00) // RSV
                        put(0x03) // ATYP: Domain
                        put(domainBytes.size.toByte())
                        put(domainBytes)
                        putShort(dstPort.toShort())
                    }.array()
        } else {
            val ipBytes = dstIp.address
            val atyp = if (ipBytes.size == 4) 0x01 else 0x04
            buf =
                ByteBuffer
                    .allocate(4 + ipBytes.size + 2)
                    .apply {
                        put(0x05) // VER
                        put(0x01) // CMD: CONNECT
                        put(0x00) // RSV
                        put(atyp.toByte())
                        put(ipBytes)
                        putShort(dstPort.toShort())
                    }.array()
        }
        return buf
    }

    /**
     * 将隧道/代理读到的数据按 MTU 安全切片后逐个构造 TCP 段写回 TUN。
     * 支持 (payload, offset, length) 零拷贝视图: 切片不复制, 由 buildTcpResponsePacket
     * 直接 put(payload, offset, length), 每段仅 1 次拷贝进包 buffer。
     */
    private fun writeTcpPayloadToTun(
        conn: TcpConnection,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val writer = tunWriterProvider() ?: return
        val end = offset + length
        var pos = offset
        while (pos < end) {
            val chunkLen = minOf(MAX_TCP_SEGMENT, end - pos)
            val responsePacket = buildTcpResponsePacket(conn, payload, pos, chunkLen)
            pos += chunkLen
            if (responsePacket != null) {
                writer(responsePacket)
            }
        }
    }

    /**
     * 从 SOCKS5 代理读取数据并写回 TUN
     */
    private fun startRelayFromSocks(
        conn: TcpConnection,
        connKey: Long,
    ) {
        val socksChannel = conn.socksChannel ?: return

        scope.launch(sshIoDispatcher.dispatcher) {
            val buffer = ByteBuffer.allocateDirect(RELAY_BUFFER_SIZE)
            try {
                while (socksChannel.isOpen && conn.state == TcpConnection.TcpState.Established) {
                    buffer.clear()
                    val read = socksChannel.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        buffer.flip()
                        val payload = ByteArray(read)
                        buffer.get(payload)
                        writeTcpPayloadToTun(conn, payload, 0, payload.size)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "SOCKS5 relay read ended: ${e.message}")
            } finally {
                closeTcpConnection(connKey, conn)
            }
        }
    }

    /**
     * 通过隧道插件直接转发 (无本地 SOCKS5 代理)
     */
    private suspend fun forwardThroughDirectChannel(
        conn: TcpConnection,
        plugin: TunnelPlugin,
        channel: TunnelChannel,
    ) {
        val connKey = IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)
        val connected = channel.connect(TUN_CONNECT_TIMEOUT_MS)
        if (!connected) {
            Log.e(TAG, "channel.connect failed for plugin ${plugin.id}")
            channel.disconnect()
            // F6: 发 RST + 移除条目, 允许同五元组新 SYN 重建
            closeTcpConnection(connKey, conn)
            return
        }

        conn.tunnelChannel = channel
        conn.state = TcpConnection.TcpState.Established

        val synAckPacket = buildSynAckPacket(conn)
        if (synAckPacket != null) {
            tunWriterProvider()?.invoke(synAckPacket)
        }

        startRelayFromTunnel(conn, connKey)
        if (IS_DEBUG) Log.d(TAG, "TCP established via direct channel ${plugin.id} to ${conn.dstIp}:${conn.dstPort}")
    }

    /**
     * 从隧道插件读取数据并写回 TUN
     */
    private fun startRelayFromTunnel(
        conn: TcpConnection,
        connKey: Long,
    ) {
        val channel = conn.tunnelChannel ?: return
        val input = channel.inputStream ?: return

        scope.launch(sshIoDispatcher.dispatcher) {
            val buffer = ByteArray(RELAY_BUFFER_SIZE)
            try {
                while (channel.isConnected && conn.state == TcpConnection.TcpState.Established) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        writeTcpPayloadToTun(conn, buffer, 0, read)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Tunnel relay read ended: ${e.message}")
            } finally {
                closeTcpConnection(connKey, conn)
            }
        }
    }

    /**
     * 构建反向 IP/TCP 响应包: src ↔ dst 互换 (支持 IPv4/IPv6)。
     * 每段 1 分配 (ByteBuffer) + 1 拷贝 (payload), 返回的数组即 buffer 本身, 无二次拷贝。
     */
    private fun buildTcpResponsePacket(
        conn: TcpConnection,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = (conn.browserSeq + 1 + conn.forwardedBytes) and UINT32_MASK

            conn.serverSeq = (seqNum + payloadLength) and UINT32_MASK

            val tcpHeaderLen = 20
            val ipHeaderLen = if (isIPv6) 40 else 20
            val totalLen = ipHeaderLen + tcpHeaderLen + payloadLength

            val packet = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

            if (isIPv6) {
                packet.putInt(0x60000000.toInt())
                packet.putShort((tcpHeaderLen + payloadLength).toShort())
                packet.put(6.toByte())
                packet.put(64.toByte())
                packet.put(srcIp)
                packet.put(dstIp)
            } else {
                packet.put(0x45.toByte())
                packet.put(0x00)
                packet.putShort(totalLen.toShort())
                packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
                packet.putShort(0x0000.toShort())
                packet.put(64.toByte())
                packet.put(6.toByte())
                packet.putShort(0)
                packet.put(srcIp)
                packet.put(dstIp)
            }

            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putInt(seqNum.toInt())
            packet.putInt(ackNum.toInt())
            packet.putShort(0x5018.toShort())
            packet.putShort(65535.toShort())
            packet.putShort(0)
            packet.putShort(0)

            packet.put(payload, payloadOffset, payloadLength)

            if (!isIPv6) {
                packet.position(0)
                val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
                packet.position(10)
                packet.putShort(ipChecksum)
            }

            val tcpChecksum =
                ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, payloadLength + tcpHeaderLen)
            packet.position(ipHeaderLen + 16)
            packet.putShort(tcpChecksum)

            if (!isIPv6) {
                if (IS_DEBUG) Log.d(TAG, "TCP resp (${totalLen}B) conn=${conn.id}")
            }

            return packet.array()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "buildTcpResponsePacket failed: ${e::class.simpleName}: conn.srcIp.size=${conn.srcIp.address.size} " +
                    "payload.size=$payloadLength",
                e,
            )
            return null
        }
    }

    /**
     * 构建 SYN-ACK 包 (支持 IPv4/IPv6)
     */
    private fun buildSynAckPacket(conn: TcpConnection): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = conn.browserSeq + 1

            conn.serverSeq = (seqNum + 1) and UINT32_MASK

            val tcpHeaderLen = 20
            val ipHeaderLen = if (isIPv6) 40 else 20
            val totalLen = ipHeaderLen + tcpHeaderLen

            val packet = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

            if (isIPv6) {
                packet.putInt(0x60000000.toInt())
                packet.putShort(tcpHeaderLen.toShort())
                packet.put(6.toByte())
                packet.put(64.toByte())
                packet.put(srcIp)
                packet.put(dstIp)
            } else {
                packet.put(0x45.toByte())
                packet.put(0x00)
                packet.putShort(totalLen.toShort())
                packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
                packet.putShort(0x0000.toShort())
                packet.put(64.toByte())
                packet.put(6.toByte())
                packet.putShort(0)
                packet.put(srcIp)
                packet.put(dstIp)
            }

            // TCP Header: SYN+ACK
            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putInt(seqNum.toInt())
            packet.putInt(ackNum.toInt())
            packet.putShort(0x5012.toShort()) // SYN+ACK
            packet.putShort(65535.toShort())
            packet.putShort(0)
            packet.putShort(0)

            if (!isIPv6) {
                packet.position(0)
                val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
                packet.position(10)
                packet.putShort(ipChecksum)
            }

            val tcpChecksum = ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, tcpHeaderLen)
            packet.position(ipHeaderLen + 16)
            packet.putShort(tcpChecksum)

            if (!isIPv6) {
                if (IS_DEBUG) {
                    val hex = packet.array().copyOfRange(0, totalLen).joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "SYN-ACK packet (${totalLen}B): $hex")
                }
            }

            return packet.array()
        } catch (e: Exception) {
            Log.e(TAG, "buildSynAckPacket failed: ${e::class.simpleName}: ${e.message}", e)
            return null
        }
    }

    /**
     * 构建纯 ACK 包 (无 payload), 确认浏览器已发送的数据
     */
    private fun buildAckPacket(
        conn: TcpConnection,
        ignoredConnKey: Long,
    ): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = (conn.browserSeq + 1 + conn.forwardedBytes) and UINT32_MASK
            val tcpHeaderLen = 20
            val ipHeaderLen = if (isIPv6) 40 else 20
            val totalLen = ipHeaderLen + tcpHeaderLen

            val packet = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

            if (isIPv6) {
                packet.putInt(0x60000000.toInt())
                packet.putShort(tcpHeaderLen.toShort())
                packet.put(6.toByte())
                packet.put(64.toByte())
                packet.put(srcIp)
                packet.put(dstIp)
            } else {
                packet.put(0x45.toByte())
                packet.put(0x00)
                packet.putShort(totalLen.toShort())
                packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
                packet.putShort(0x0000.toShort())
                packet.put(64.toByte())
                packet.put(6.toByte())
                packet.putShort(0)
                packet.put(srcIp)
                packet.put(dstIp)
            }

            // TCP Header: ACK (无 payload, 不消耗 seq)
            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putInt(seqNum.toInt())
            packet.putInt(ackNum.toInt())
            packet.putShort(0x5010.toShort()) // ACK
            packet.putShort(65535.toShort())
            packet.putShort(0)
            packet.putShort(0)

            if (!isIPv6) {
                packet.position(0)
                val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
                packet.position(10)
                packet.putShort(ipChecksum)
            }

            val tcpChecksum = ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, tcpHeaderLen)
            packet.position(ipHeaderLen + 16)
            packet.putShort(tcpChecksum)

            if (IS_DEBUG) Log.d(TAG, "ACK packet (${totalLen}B) conn=${conn.id} ack=$ackNum")
            return packet.array()
        } catch (e: Exception) {
            Log.e(TAG, "buildAckPacket failed: ${e::class.simpleName}: ${e.message}", e)
            return null
        }
    }

    private fun buildRstPacket(
        conn: TcpConnection,
        ignoredConnKey: Long,
    ): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = (conn.browserSeq + 1 + conn.forwardedBytes) and UINT32_MASK
            val tcpHeaderLen = 20
            val ipHeaderLen = if (isIPv6) 40 else 20
            val totalLen = ipHeaderLen + tcpHeaderLen

            val packet = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

            if (isIPv6) {
                packet.putInt(0x60000000.toInt())
                packet.putShort(tcpHeaderLen.toShort())
                packet.put(6.toByte())
                packet.put(64.toByte())
                packet.put(srcIp)
                packet.put(dstIp)
            } else {
                packet.put(0x45.toByte())
                packet.put(0x00)
                packet.putShort(totalLen.toShort())
                packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
                packet.putShort(0x0000.toShort())
                packet.put(64.toByte())
                packet.put(6.toByte())
                packet.putShort(0)
                packet.put(srcIp)
                packet.put(dstIp)
            }

            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putInt(seqNum.toInt())
            packet.putInt(ackNum.toInt())
            packet.putShort(0x5014.toShort()) // RST+ACK
            packet.putShort(0)
            packet.putShort(0)
            packet.putShort(0)

            if (!isIPv6) {
                packet.position(0)
                val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
                packet.position(10)
                packet.putShort(ipChecksum)
            }

            val tcpChecksum = ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, tcpHeaderLen)
            packet.position(ipHeaderLen + 16)
            packet.putShort(tcpChecksum)

            if (IS_DEBUG) {
                Log.d(TAG, "RST packet ${totalLen}B for conn ${conn.id} ${conn.srcIp.hostAddress}:${conn.srcPort}")
            }
            return packet.array()
        } catch (e: Exception) {
            Log.e(TAG, "buildRstPacket failed: ${e::class.simpleName}: ${e.message}", e)
            return null
        }
    }

    /**
     * 将 TCP 数据转发到 SOCKS5/隧道通道。
     * @return 实际写入字节数 (0 = 未写入, 调用方不推进 forwardedBytes/不 ACK, 浏览器整段重传;
     *   0 < return < payloadLength = 部分写, 只推进已写处)。
     */
    @Suppress("ReturnCount") // 背压/降级路径各自早退, 调用方按返回值分派
    private fun forwardToSocks(
        conn: TcpConnection,
        buffer: ByteBuffer,
        payloadStart: Int,
        payloadLength: Int,
    ): Int {
        // F7: socksChannel 可为 null (直连仅 tunnelChannel), 只有两条通道都没有时才拒绝
        if (conn.state != TcpConnection.TcpState.Established ||
            (conn.socksChannel == null && conn.tunnelChannel == null)
        ) {
            Log.w(
                TAG,
                "forwardToSocks: conn ${conn.id} not established or no channel (state=${conn.state}), " +
                    "dropping ${payloadLength}B",
            )
            return 0
        }

        // Try tunnel channel first, then SOCKS5 channel
        val tunnelChannel = conn.tunnelChannel
        if (tunnelChannel != null) {
            try {
                buffer.position(payloadStart)
                buffer.limit(payloadStart + payloadLength)
                val output = tunnelChannel.outputStream
                if (output == null) return 0
                val payload = ByteArray(payloadLength)
                buffer.get(payload)
                output.write(payload)
                output.flush()
                if (IS_DEBUG) {
                    Log.d(
                        TAG,
                        "forwardToTunnel: wrote ${payloadLength}B for conn ${conn.id} → " +
                            "${conn.dstIp}:${conn.dstPort}",
                    )
                }
                return payloadLength
            } catch (e: IOException) {
                Log.e(TAG, "forwardToTunnel failed", e)
                stats.addError()
                closeTcpConnection(
                    IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort),
                    conn,
                )
                return 0
            }
        }

        val socksChannel = conn.socksChannel ?: return 0
        try {
            buffer.position(payloadStart)
            buffer.limit(payloadStart + payloadLength)
            // 非阻塞写: loopback 缓冲满 (SSH 背压) 时返回已写字节数,
            // 调用方只 ACK 到已写处, 浏览器重传剩余部分 —— packetLoop 不被单连接拖死
            while (buffer.hasRemaining()) {
                if (socksChannel.write(buffer) == 0) {
                    if (IS_DEBUG) {
                        Log.d(
                            TAG,
                            "forwardToSocks: backpressure, wrote ${payloadLength - buffer.remaining()}" +
                                "/${payloadLength}B for conn ${conn.id}",
                        )
                    }
                    break
                }
            }
            return payloadLength - buffer.remaining()
        } catch (e: IOException) {
            Log.e(TAG, "forwardToSocks failed", e)
            stats.addError()
            closeTcpConnection(IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort), conn)
            return 0
        }
    }

    private fun sendAckToBrowser(
        conn: TcpConnection,
        connKey: Long,
    ) {
        val writer = tunWriterProvider() ?: return
        val ackPacket = buildAckPacket(conn, connKey) ?: return
        try {
            writer.invoke(ackPacket)
        } catch (e: Exception) {
            Log.e(TAG, "sendAckToBrowser failed: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private fun createTcpConnection(
        key: Long,
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): TcpConnection {
        val id = connectionIdCounter.incrementAndGet()
        val conn = TcpConnection(id, srcIp, dstIp, srcPort, dstPort)
        tcpConnections[key] = conn
        return conn
    }

    private fun closeTcpConnection(
        key: Long,
        conn: TcpConnection,
        notifyBrowser: Boolean = true,
    ) {
        // F6: 通知浏览器 (RST) 使其立即 abort 而非挂死; 移除条目后同五元组新 SYN 自动重建
        if (notifyBrowser && conn.state != TcpConnection.TcpState.Closed) {
            try {
                val rstPacket = buildRstPacket(conn, key)
                if (rstPacket != null) tunWriterProvider()?.invoke(rstPacket)
            } catch (e: Exception) {
                Log.e(TAG, "send RST on close failed: ${e::class.simpleName}: ${e.message}")
            }
        }
        conn.state = TcpConnection.TcpState.Closed
        tcpConnections.remove(key)
        if (conn.socksLocalPort > 0) {
            tunCallbackPlugin?.removeTunCallback(conn.socksLocalPort)
            conn.socksLocalPort = 0
        }
        try {
            conn.socksChannel?.close()
        } catch (_: Exception) {
        }
        try {
            conn.tunnelChannel?.disconnect()
        } catch (_: Exception) {
        }
    }

    /** 会话断开时清空连接表, 跨会话残留不复用 (F6-4) */
    fun reset() {
        tcpConnections.clear()
    }

    fun cleanupStaleConnections(timeoutMs: Long) {
        val now = System.currentTimeMillis()
        tcpConnections.values.removeIf { conn ->
            if (now - conn.lastActivity > timeoutMs) {
                closeTcpConnection(
                    IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort),
                    conn,
                )
                true
            } else {
                false
            }
        }
    }
}
