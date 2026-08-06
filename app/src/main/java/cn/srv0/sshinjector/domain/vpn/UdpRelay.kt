package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

private val IS_DEBUG = android.util.Log.isLoggable("PacketProcessor", android.util.Log.DEBUG)

private const val BUFFER_SIZE = 65536
private val BUFFER_POOL = ConcurrentLinkedQueue<ByteBuffer>()

fun acquireBuffer(): ByteBuffer {
    return BUFFER_POOL.poll() ?: ByteBuffer.allocateDirect(BUFFER_SIZE)
}

fun releaseBuffer(buffer: ByteBuffer) {
    if (buffer.capacity() == BUFFER_SIZE && buffer.isDirect) {
        buffer.clear()
        BUFFER_POOL.offer(buffer)
    }
}

/**
 * UDP 关联管理与 SOCKS5 UDP ASSOCIATE 转发。
 */
class UdpRelay(
    private val tunWriterProvider: () -> ((ByteArray) -> Unit)?,
    private val stats: PacketStats,
) {
    companion object {
        private const val TAG = "PacketProcessor"
        private const val SOCKS_PORT = 1080
    }

    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val udpAssociations = ConcurrentHashMap<Long, UdpAssociation>()
    private val connectionIdCounter = AtomicLong(0)

    private var dnsInterceptor: DnsInterceptor? = null

    fun setDnsInterceptor(interceptor: DnsInterceptor) {
        dnsInterceptor = interceptor
    }

    /**
     * 处理 UDP 数据包（含 DNS 拦截与 SOCKS5 UDP ASSOCIATE 转发）
     */
    fun processUdpPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int,
    ): Boolean {
        if (payloadLength < 8) return false

        buffer.position(payloadStart)
        val srcPort = buffer.getShort().toInt() and 0xFFFF
        val dstPort = buffer.getShort().toInt() and 0xFFFF
        val length = buffer.getShort().toInt() and 0xFFFF
        buffer.getShort() // checksum

        // DNS 查询拦截 (UDP 53)
        if (dstPort == 53 || srcPort == 53) {
            buffer.limit(payloadStart + payloadLength)
            return handleDnsPacket(buffer, srcIp, dstIp, srcPort, dstPort)
        }

        // UDP 关联查找
        val assocKey = IpPacketParser.connectionKey(srcIp, dstIp, srcPort, dstPort)
        var assoc = udpAssociations[assocKey]

        if (assoc == null) {
            // 新的 UDP 关联 - 通过 SOCKS5 UDP ASSOCIATE 建立
            assoc = createUdpAssociation(assocKey, srcIp, dstIp, srcPort, dstPort)
        }

        assoc.lastActivity = System.currentTimeMillis()

        // 转发 UDP 数据到 SOCKS5
        forwardUdpToSocks(assoc, buffer, payloadStart, length)

        stats.packetsProcessed.value++
        stats.bytesProcessed.value = stats.bytesProcessed.value + payloadLength.toLong()
        return true
    }

    /**
     * DNS 包处理: 委托 DnsInterceptor 进行远端解析
     */
    private fun handleDnsPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): Boolean {
        val interceptor = dnsInterceptor
        if (interceptor == null) {
            Log.w(TAG, "DnsInterceptor not set, DNS packet ignored")
            return false
        }

        try {
            // 诊断日志：记录所有 53 端口 UDP 包
            if (IS_DEBUG) {
                android.util.Log.d(
                    TAG,
                    "handleDnsPacket: src=$srcIp:$srcPort dst=$dstIp:$dstPort " +
                        "mode=${interceptor.getTransportMode()}",
                )
            }

            // 提取 DNS 查询 payload (buffer 已跳过 UDP 头部 8 字节, limit 为 DNS 负载长度)
            val dnsBuffer = buffer.slice()
            val dnsPayloadLen = dnsBuffer.remaining()
            dnsBuffer.limit(dnsPayloadLen)

            // 委托 DnsInterceptor 处理
            interceptor.processDnsQuery(dnsBuffer, srcIp, dstIp, srcPort, dstPort)
            stats.packetsProcessed.value++
            stats.bytesProcessed.value = stats.bytesProcessed.value + dnsPayloadLen.toLong()
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleDnsPacket failed", e)
            stats.errors.value++
            return false
        }
    }

    /**
     * 通过 SOCKS5 UDP ASSOCIATE 转发 UDP 数据
     * SOCKS5 UDP 帧格式: RSV(2) FRAG(1) ATYP(1) DST.ADDR(*) DST.PORT(2) DATA(*)
     */
    private fun forwardUdpToSocks(
        assoc: UdpAssociation,
        buffer: ByteBuffer,
        payloadStart: Int,
        length: Int,
    ) {
        // 在 launch 前复制数据，避免异步执行时底层 readBuffer 被覆盖
        val payloadCopy = ByteArray(length)
        val origPos = buffer.position()
        buffer.position(payloadStart)
        buffer.get(payloadCopy)
        buffer.position(origPos)

        val dstIpCopy = assoc.dstIp
        val dstPortCopy = assoc.dstPort

        scope.launch {
            try {
                val socksPort = SOCKS_PORT

                // 如果没有关联通道，先建立 UDP ASSOCIATE
                if (!assoc.datagramChannel.isOpen) {
                    val sock = SocketChannel.open()
                    sock.configureBlocking(true)
                    sock.connect(InetSocketAddress("127.0.0.1", socksPort))

                    // SOCKS5 握手
                    val handshake = byteArrayOf(0x05, 0x01, 0x00)
                    sock.write(ByteBuffer.wrap(handshake))
                    val handshakeResp = ByteBuffer.allocate(2)
                    val hsRead = sock.read(handshakeResp)
                    if (hsRead <= 0) {
                        Log.e(TAG, "UDP ASSOCIATE handshake read failed: $hsRead")
                        sock.close()
                        return@launch
                    }

                    // UDP ASSOCIATE 请求 (CMD=0x03)
                    val assocReq =
                        byteArrayOf(
                            // VER CMD RSV ATYP(IPv4)
                            0x05, 0x03, 0x00, 0x01,
                            0x00, 0x00, 0x00, 0x00, // BND.ADDR (0.0.0.0)
                            // BND.PORT (0)
                            0x00, 0x00,
                        )
                    sock.write(ByteBuffer.wrap(assocReq))
                    val assocResp = ByteBuffer.allocate(32)
                    val arRead = sock.read(assocResp)
                    if (arRead <= 0) {
                        Log.e(TAG, "UDP ASSOCIATE resp read failed: $arRead")
                        sock.close()
                        return@launch
                    }
                    sock.close()

                    // 解析 UDP relay 地址 (简化: 使用同一端口)
                    assocResp.flip()
                    assocResp.position(assocResp.position() + 4) // Skip VER REP RSV ATYP
                    assocResp.position(assocResp.position() + 4) // Skip BND.ADDR (4字节 IPv4)
                    assocResp.short // Skip BND.PORT

                    // 绑定本地端口用于 UDP relay
                    assoc.datagramChannel = DatagramChannel.open()
                    assoc.datagramChannel.configureBlocking(false)
                    assoc.datagramChannel.bind(InetSocketAddress(0))
                }

                // 封装 SOCKS5 UDP 帧 (使用复制的数据)
                val addrBytes = dstIpCopy.address
                val atyp = if (addrBytes.size == 4) 0x01.toByte() else 0x04.toByte() // IPv4 or IPv6
                val headerLen = 2 + 1 + 1 + addrBytes.size + 2 // RSV + FRAG + ATYP + ADDR + PORT
                val socksUdpFrame = ByteBuffer.allocate(headerLen + payloadCopy.size)
                socksUdpFrame.putShort(0) // RSV
                socksUdpFrame.put(0) // FRAG
                socksUdpFrame.put(atyp)
                socksUdpFrame.put(addrBytes)
                socksUdpFrame.putShort(dstPortCopy.toShort())
                socksUdpFrame.put(payloadCopy)

                socksUdpFrame.flip()
                assoc.datagramChannel.send(socksUdpFrame, InetSocketAddress("127.0.0.1", socksPort))
            } catch (e: IOException) {
                Log.e(TAG, "forwardUdpToSocks failed", e)
                stats.errors.value++
            }
        }
    }

    private fun createUdpAssociation(
        key: Long,
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): UdpAssociation {
        val id = connectionIdCounter.incrementAndGet()
        val channel = DatagramChannel.open().apply { configureBlocking(false) }
        val assoc = UdpAssociation(id, srcIp, srcPort, dstIp, dstPort, channel)
        udpAssociations[key] = assoc

        // 启动 UDP 接收线程：从 SOCKS5 读取响应并写回 TUN
        startUdpRelayReceiveThread(assoc)

        return assoc
    }

    /**
     * 启动 UDP 接收线程: 从 SOCKS5 UDP relay 读取响应并封装为 IP/UDP 包写回 TUN
     */
    private fun startUdpRelayReceiveThread(assoc: UdpAssociation) {
        val writer = tunWriterProvider()
        if (writer == null) {
            Log.e(TAG, "TUN writer not set, cannot start UDP relay receive thread")
            return
        }

        Thread({
            val receiveBuffer = acquireBuffer()
            try {
                while (assoc.datagramChannel.isOpen) {
                    receiveBuffer.clear()
                    val sender = assoc.datagramChannel.receive(receiveBuffer) as? InetSocketAddress
                    if (sender != null && sender.address.isLoopbackAddress) {
                        receiveBuffer.flip()
                        val frame = ByteArray(receiveBuffer.remaining())
                        receiveBuffer.get(frame)

                        // 解析 SOCKS5 UDP 帧: RSV(2) FRAG(1) ATYP(1) DST.ADDR(*) DST.PORT(2) DATA(*)
                        if (frame.size >= 10) {
                            val atyp = frame[3].toInt() and 0xFF

                            var addrOffset = 4
                            when (atyp) {
                                0x01 -> { // IPv4
                                    addrOffset += 4
                                }
                                0x04 -> { // IPv6
                                    addrOffset += 16
                                }
                            }

                            if (frame.size >= addrOffset + 2 && (atyp == 0x01 || atyp == 0x04)) {
                                val dstPort =
                                    ((frame[addrOffset].toInt() and 0xFF) shl 8) or
                                        (frame[addrOffset + 1].toInt() and 0xFF)
                                addrOffset += 2

                                val payload = frame.sliceArray(addrOffset until frame.size)

                                // 构造 IPv4/UDP 响应包写回 TUN
                                val responsePacket =
                                    buildUdpResponsePacket(
                                        srcIp = assoc.dstIp.address,
                                        dstIp = assoc.srcIp.address,
                                        srcPort = assoc.dstPort,
                                        dstPort = assoc.srcPort,
                                        payload = payload,
                                    )
                                writer(responsePacket)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "UDP relay receive ended: ${e.message}")
            } finally {
                releaseBuffer(receiveBuffer)
            }
        }, "UDP-Relay-Receive-${assoc.id}").start()
    }

    /**
     * 构建 UDP 响应包 (用于 DNS 回程)
     */
    fun buildUdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipHeaderLen = 20
        val totalLen = ipHeaderLen + udpLen

        val packet = ByteBuffer.allocate(totalLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        // IPv4 Header
        packet.put(0x45.toByte())
        packet.put(0x00)
        packet.putShort(totalLen.toShort())
        packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
        packet.putShort(0x4000.toShort())
        packet.put(64.toByte())
        packet.put(17.toByte()) // Protocol: UDP
        packet.putShort(0)
        packet.put(srcIp)
        packet.put(dstIp)

        // UDP Header
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putShort(udpLen.toShort())
        packet.putShort(0) // Checksum (optional for IPv4)

        // Payload
        packet.put(payload)

        // IP 校验和
        packet.position(0)
        val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
        packet.position(10)
        packet.putShort(ipChecksum)

        return packet.array()
    }

    fun cleanupStaleConnections(timeoutMs: Long) {
        val now = System.currentTimeMillis()
        udpAssociations.values.removeIf { assoc ->
            if (now - assoc.lastActivity > timeoutMs) {
                try {
                    assoc.datagramChannel.close()
                } catch (_: Exception) {
                }
                true
            } else {
                false
            }
        }
    }

    data class UdpAssociation(
        val id: Long,
        val srcIp: InetAddress,
        val srcPort: Int,
        val dstIp: InetAddress,
        val dstPort: Int,
        var datagramChannel: DatagramChannel,
        var lastActivity: Long = System.currentTimeMillis(),
    )
}
