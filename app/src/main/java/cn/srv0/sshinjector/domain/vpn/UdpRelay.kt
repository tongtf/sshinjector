package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

private val IS_DEBUG = android.util.Log.isLoggable("PacketProcessor", android.util.Log.DEBUG)

private const val BUFFER_SIZE = 65536
private const val EVENT_LOOP_SELECT_TIMEOUT_MS = 1000L

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

    @Volatile private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udpAssociations = ConcurrentHashMap<Long, UdpAssociation>()
    private val connectionIdCounter = AtomicLong(0)

    @Volatile private var selector: Selector? = null

    @Volatile private var eventLoopStarted = false
    private val pendingRegistrations = ConcurrentLinkedQueue<Pair<DatagramChannel, UdpAssociation>>()
    private val readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)

    private var dnsInterceptor: DnsInterceptor? = null

    init {
        startEventLoop()
    }

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
        val dgramChannel = assoc.datagramChannel
        if (!dgramChannel.isOpen) return

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
                dgramChannel.send(socksUdpFrame, InetSocketAddress("127.0.0.1", SOCKS_PORT))
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
        startEventLoop()
        val id = connectionIdCounter.incrementAndGet()
        val channel = DatagramChannel.open().apply { configureBlocking(false) }
        val assoc = UdpAssociation(id, srcIp, srcPort, dstIp, dstPort, channel)
        udpAssociations[key] = assoc

        // 注册到共享 selector，由 eventLoop 线程完成注册，避免与 select 并发
        pendingRegistrations.offer(channel to assoc)
        selector?.wakeup()

        return assoc
    }

    /**
     * 启动共享 Selector 事件循环（单例，构造时启动一次，停止后首个关联创建时重启）
     */
    private fun startEventLoop() {
        if (eventLoopStarted) return
        eventLoopStarted = true
        selector =
            try {
                Selector.open()
            } catch (e: IOException) {
                eventLoopStarted = false
                Log.e(TAG, "UDP relay selector open failed", e)
                return
            }
        scope.launch { eventLoop() }
    }

    /**
     * 共享 Selector 事件循环：所有 UDP 关联的响应收包集中在此。
     * 无数据时阻塞在 select()，避免每关联一个忙等线程。
     */
    private fun eventLoop() {
        val sel = selector ?: return
        val buffer = readBuffer
        while (eventLoopStarted && sel.isOpen) {
            drainPendingRegistrations(sel)
            try {
                if (sel.select(EVENT_LOOP_SELECT_TIMEOUT_MS) != 0) {
                    val it = sel.selectedKeys().iterator()
                    while (it.hasNext()) {
                        val key = it.next()
                        it.remove()
                        if (!key.isValid) continue
                        handleUdpRead(key, buffer)
                    }
                }
            } catch (e: ClosedSelectorException) {
                break
            } catch (e: IOException) {
                Log.w(TAG, "UDP relay eventLoop IO error", e)
            }
        }
    }

    private fun drainPendingRegistrations(sel: Selector) {
        while (true) {
            val (channel, assoc) = pendingRegistrations.poll() ?: break
            try {
                channel.register(sel, SelectionKey.OP_READ, assoc)
            } catch (e: ClosedChannelException) {
                try {
                    channel.close()
                } catch (_: Exception) {
                }
                udpAssociations.entries.removeIf { it.value === assoc }
            }
        }
    }

    /**
     * 处理单个就绪 UDP 通道：循环接收数据报（level-triggered 需清空），
     * 解析 SOCKS5 UDP 帧并封装为 IP 包写回 TUN。
     */
    private fun handleUdpRead(
        key: SelectionKey,
        buffer: ByteBuffer,
    ) {
        val assoc = key.attachment() as? UdpAssociation ?: return
        val channel = key.channel() as? DatagramChannel ?: return
        if (!channel.isOpen) return
        val writer = tunWriterProvider() ?: return

        while (channel.isOpen) {
            buffer.clear()
            val sender =
                try {
                    channel.receive(buffer) as? InetSocketAddress
                } catch (_: IOException) {
                    null
                } ?: break
            if (sender.address.isLoopbackAddress) {
                buffer.flip()
                val frame = ByteArray(buffer.remaining())
                buffer.get(frame)

                val payload = parseSocks5UdpFrame(frame)
                if (payload != null) {
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

    /**
     * 解析 SOCKS5 UDP 帧: RSV(2) FRAG(1) ATYP(1) DST.ADDR(*) DST.PORT(2) DATA(*)
     * @return 负载字节数组，格式非法返回 null
     */
    private fun parseSocks5UdpFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 10) return null
        val atyp = frame[3].toInt() and 0xFF

        var addrOffset = 4
        when (atyp) {
            0x01 -> addrOffset += 4 // IPv4
            0x04 -> addrOffset += 16 // IPv6
            else -> return null
        }
        if (frame.size < addrOffset + 2) return null
        addrOffset += 2
        return frame.sliceArray(addrOffset until frame.size)
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

    /**
     * 停止事件循环并释放所有 UDP 关联资源（VPN 断开时调用）。
     */
    fun stop() {
        if (!eventLoopStarted) return
        eventLoopStarted = false
        try {
            selector?.wakeup()
            selector?.close()
        } catch (_: Exception) {
        }
        selector = null
        pendingRegistrations.clear()
        udpAssociations.values.forEach { assoc ->
            try {
                assoc.datagramChannel.close()
            } catch (_: Exception) {
            }
        }
        udpAssociations.clear()
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
