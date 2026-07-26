package com.sshinjector.domain.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import com.sshinjector.domain.vpn.tunnel.TunnelManager
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据包处理器
 * 负责解析 IP/TCP/UDP 包头，提取五元组，并转发到隧道插件
 */
@Singleton
class PacketProcessor @Inject constructor(
    private val tunnelManager: TunnelManager
) {
    companion object {
        private const val TAG = "PacketProcessor"
        private const val SOCKS5_HANDSHAKE_TIMEOUT = 5000
        private const val RELAY_BUFFER_SIZE = 65535
        const val DEFAULT_CONNECTION_CLEANUP_TIMEOUT_MS = 300000L // 5 分钟默认值
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tcpConnections = ConcurrentHashMap<Long, TcpConnection>()
    private val udpAssociations = ConcurrentHashMap<Long, UdpAssociation>()
    private val connectionIdCounter = AtomicLong(0)

    val packetsProcessed = MutableStateFlow(0L)
    val bytesProcessed = MutableStateFlow(0L)
    val errors = MutableStateFlow(0L)

    private var tunWriter: ((ByteArray) -> Unit)? = null
    private var nextTcpSeq = ConcurrentHashMap<Long, Long>()
    private var nextTcpAck = ConcurrentHashMap<Long, Long>()

    fun setTunWriter(writer: (ByteArray) -> Unit) {
        tunWriter = writer
    }

    data class TcpConnection(
        val id: Long,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        var socksChannel: SocketChannel? = null,
        var tunnelChannel: TunnelChannel? = null,
        var state: TcpState = TcpState.SynSent,
        var lastActivity: Long = System.currentTimeMillis(),
        var browserSeq: Long = 0,
        var serverSeq: Long = 0,
        var forwardedBytes: Long = 0,
        var pendingConnect: Boolean = false,
        var socksLocalPort: Int = 0
    ) {
        enum class TcpState { SynSent, SynReceived, Established, FinWait1, FinWait2, CloseWait, Closing, LastAck, TimeWait, Closed }
    }

    data class UdpAssociation(
        val id: Long,
        val srcIp: InetAddress,
        val srcPort: Int,
        val dstIp: InetAddress,
        val dstPort: Int,
        var datagramChannel: DatagramChannel,
        var lastActivity: Long = System.currentTimeMillis()
    )

    /**
     * 处理来自 TUN 的 IPv4 数据包
     */
    fun processIpv4Packet(buffer: ByteBuffer, tunFd: java.io.FileDescriptor): Boolean {
        buffer.order(ByteOrder.BIG_ENDIAN)
        Log.d(TAG, "processIpv4Packet: remaining=${buffer.remaining()}")
        
        // 最小 IPv4 头部 20 字节
        if (buffer.remaining() < 20) return false
        
        val versionIhl = buffer.get() .toInt() and 0xFF
        val version = versionIhl shr 4
        val ihl = versionIhl and 0x0F
        
        if (version != 4) return false
        if (ihl < 5) return false
        
        val headerLength = ihl * 4
        if (buffer.remaining() < headerLength - 1) return false
        
        // 跳过 DSCP/ECN, Total Length
        buffer.position(buffer.position() + 1 + 2)
        
        val identification = buffer.getShort()
        val flagsFragment = buffer.getShort()
        val ttl = buffer.get() .toInt() and 0xFF
        val protocol = buffer.get() .toInt() and 0xFF
        val headerChecksum = buffer.getShort()
        
        val srcIp = readIpAddress(buffer)
        val dstIp = readIpAddress(buffer)
        
        Log.d(TAG, "IPv4: proto=$protocol src=$srcIp dst=$dstIp")
        
        // 处理选项
        if (ihl > 5) {
            buffer.position(buffer.position() + (ihl - 5) * 4)
        }
        
        val payloadStart = buffer.position()
        val payloadLength = buffer.remaining()
        
        return when (protocol) {
            0x06 -> processTcpPacket(buffer, srcIp, dstIp, payloadStart, payloadLength, tunFd)
            0x11 -> processUdpPacket(buffer, srcIp, dstIp, payloadStart, payloadLength, tunFd)
            else -> false
        }
    }

    /**
     * 处理来自 TUN 的 IPv6 数据包
     */
    fun processIpv6Packet(buffer: ByteBuffer, tunFd: java.io.FileDescriptor): Boolean {
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        // IPv6 固定头部 40 字节
        if (buffer.remaining() < 40) return false
        
        val versionTrafficClassFlow = buffer.getInt()
        val version = versionTrafficClassFlow shr 28
        
        if (version != 6) return false
        
        val payloadLength = buffer.getShort() .toInt() and 0xFFFF
        var nextHeader = buffer.get() .toInt() and 0xFF
        val hopLimit = buffer.get() .toInt() and 0xFF
        
        val srcIp = readIpv6Address(buffer)
        val dstIp = readIpv6Address(buffer)
        
        // 解析 IPv6 扩展头部，找到最终的上层协议
        val (payloadStart, finalNextHeader) = parseIpv6ExtensionHeaders(buffer, payloadLength, nextHeader)
        if (payloadStart < 0) return false
        
        // 重新计算实际 payload 长度 (扣除扩展头部)
        val extensionHeaderLen = payloadStart - 40
        val actualPayloadLength = payloadLength - extensionHeaderLen
        if (actualPayloadLength < 0) return false
        
        return when (finalNextHeader) {
            0x06 -> processTcpPacket(buffer, srcIp, dstIp, payloadStart, actualPayloadLength, tunFd)
            0x11 -> processUdpPacket(buffer, srcIp, dstIp, payloadStart, actualPayloadLength, tunFd)
            0x3A -> processIcmpv6Packet(buffer, srcIp, dstIp, payloadStart, actualPayloadLength, tunFd) // ICMPv6
            else -> false
        }
    }

    /**
     * 解析 IPv6 扩展头部
     * @return payload 起始位置，负数表示错误
     */
    private fun parseIpv6ExtensionHeaders(buffer: ByteBuffer, payloadLength: Int, nextHeader: Int): Pair<Int, Int> {
        var pos = buffer.position()
        val maxPos = pos + payloadLength
        var currentNextHeader = nextHeader
        
        // IPv6 扩展头部类型
        val extensionHeaders = setOf(
            0,   // Hop-by-Hop Options
            43,  // Routing
            44,  // Fragment
            60,  // Destination Options
            255  // Reserved (实验性)
        )
        
        // 限制扩展头部数量防止循环
        var extHeaderCount = 0
        while (currentNextHeader in extensionHeaders && extHeaderCount < 10) {
            if (pos + 2 > maxPos) return Pair(-1, currentNextHeader)
            
            val extType = buffer.get(pos).toInt() and 0xFF
            val extLen = (buffer.get(pos + 1).toInt() and 0xFF) + 1 // 单位是 8 字节
            
            val extHeaderLen = when (currentNextHeader) {
                44 -> 8 // Fragment header 固定 8 字节
                else -> extLen * 8
            }
            
            if (pos + extHeaderLen > maxPos) return Pair(-1, currentNextHeader)
            
            // 更新 nextHeader 为扩展头部中的 Next Header 字段
            currentNextHeader = buffer.get(pos).toInt() and 0xFF
            pos += extHeaderLen
            extHeaderCount++
        }
        
        buffer.position(pos)
        return Pair(pos, currentNextHeader)
    }

    private fun processTcpPacket(
        buffer: ByteBuffer, 
        srcIp: InetAddress, 
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int,
        tunFd: java.io.FileDescriptor
    ): Boolean {
        if (payloadLength < 20) return false // 最小 TCP 头部
        
        val srcPort = buffer.getShort() .toInt() and 0xFFFF
        val dstPort = buffer.getShort() .toInt() and 0xFFFF
        val seqNum = buffer.getInt()
        val ackNum = buffer.getInt()
        val dataOffsetFlags = buffer.getShort() .toInt() and 0xFFFF
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
        val psh = (flags and 0x08) != 0
        val ack = (flags and 0x10) != 0
        
        // 连接标识符 (五元组哈希)
        val connKey = connectionKey(srcIp, dstIp, srcPort, dstPort)
        var conn = tcpConnections[connKey]
        
        if (syn && !ack) {
            if (conn == null) {
                conn = createTcpConnection(connKey, srcIp, dstIp, srcPort, dstPort)
                conn.browserSeq = (seqNum.toLong()) and 0xFFFFFFFFL
                conn.state = TcpConnection.TcpState.SynSent

                // Route through tunnel plugin or fallback to local SOCKS5
                val hasActiveTunnel = try {
                    tunnelManager.getActiveOrFallback()
                    true
                } catch (_: Exception) { false }

                if (hasActiveTunnel) {
                    forwardSynToTunnel(conn)
                } else {
                    forwardSynToSocks(conn)
                }
            } else {
                conn.browserSeq = (seqNum.toLong()) and 0xFFFFFFFFL
                conn.lastActivity = System.currentTimeMillis()
            }
        } else if (conn != null) {
            conn.lastActivity = System.currentTimeMillis()
            
            if (rst) {
                closeTcpConnection(connKey, conn)
            } else if (syn && ack) {
                conn.state = TcpConnection.TcpState.SynReceived
} else if (ack) {
                 if (conn.state == TcpConnection.TcpState.SynReceived) {
                     conn.state = TcpConnection.TcpState.Established
                 }
                 if (hasPayload) {
                     val expectedBrowserSeq = (conn.browserSeq + 1 + conn.forwardedBytes) and 0xFFFFFFFFL
                     if (seqNum.toLong() and 0xFFFFFFFFL == expectedBrowserSeq) {
                         conn.forwardedBytes += payloadLen.toLong()
                         if (conn.pendingConnect) {
                             // 延迟 CONNECT: 从首个数据包提取域名，完成 CONNECT，再转发数据
                             val firstData = ByteArray(payloadLen)
                             buffer.position(payloadStart + dataOffset)
                             buffer.get(firstData)
                             completeDeferredConnect(conn, firstData, connKey)
                         } else {
                             buffer.position(payloadStart + dataOffset)
                             forwardToSocks(conn, buffer, payloadStart + dataOffset, payloadLen)
                         }
                     } else {
                         Log.d(TAG, "Retransmit/seq mismatch for conn ${conn.id}: seq=${seqNum.toLong() and 0xFFFFFFFFL} expected=${expectedBrowserSeq} state=${conn.state} fwd=${conn.forwardedBytes}")
                     }
                 } else {
                     // pure ACK (three-way handshake completion) — no payload
                 }
                if (fin) {
                    closeTcpConnection(connKey, conn)
                }
            }
        }
        
        packetsProcessed.value++
        bytesProcessed.value = bytesProcessed.value + payloadLength.toLong()
        return true
    }

    private fun processUdpPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int,
        tunFd: java.io.FileDescriptor
    ): Boolean {
        if (payloadLength < 8) return false
        
        val srcPort = buffer.getShort() .toInt() and 0xFFFF
        val dstPort = buffer.getShort() .toInt() and 0xFFFF
        val length = buffer.getShort() .toInt() and 0xFFFF
        val checksum = buffer.getShort()
        
// DNS 查询拦截 (UDP 53)
        if (dstPort == 53 || srcPort == 53) {
            return handleDnsPacket(buffer, srcIp, dstIp, srcPort, dstPort, payloadLength, tunFd)
        }
        
        // UDP 关联查找
        val assocKey = connectionKey(srcIp, dstIp, srcPort, dstPort)
        var assoc = udpAssociations[assocKey]
        
        if (assoc == null) {
            // 新的 UDP 关联 - 通过 SOCKS5 UDP ASSOCIATE 建立
            assoc = createUdpAssociation(assocKey, srcIp, dstIp, srcPort, dstPort)
        }
        
        assoc.lastActivity = System.currentTimeMillis()
        
        // 转发 UDP 数据到 SOCKS5
        forwardUdpToSocks(assoc, buffer, payloadStart, length)
        
        packetsProcessed.value++
        bytesProcessed.value = bytesProcessed.value + payloadLength.toLong()
        return true
    }

    private var dnsInterceptor: DnsInterceptor? = null

    fun setDnsInterceptor(interceptor: DnsInterceptor) {
        dnsInterceptor = interceptor
    }

    /**
     * DNS 包处理: 委托 DnsInterceptor 进行远端解析
     * 通过 SOCKS5 TCP 发送 DNS 查询到远程 DNS 服务器 (8.8.8.8/1.1.1.1)
     * 防止 DNS 泄露
     */
    private fun handleDnsPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        length: Int,
        tunFd: java.io.FileDescriptor
    ): Boolean {
        val interceptor = dnsInterceptor
        if (interceptor == null) {
            Log.w(TAG, "DnsInterceptor not set, DNS packet ignored")
            return false
        }

        try {
            // 诊断日志：记录所有 53 端口 UDP 包
            android.util.Log.d(TAG, "handleDnsPacket: src=$srcIp:$srcPort dst=$dstIp:$dstPort len=$length mode=${interceptor.getTransportMode()}")
            
            // 提取 DNS 查询 payload (buffer 已跳过 UDP 头部 8 字节)
            val dnsPayloadLen = length - 8
            val dnsBuffer = buffer.slice()
            dnsBuffer.limit(dnsPayloadLen)

            // 委托 DnsInterceptor 处理
            val result = interceptor.processDnsQuery(dnsBuffer, srcIp, dstIp, srcPort, dstPort)
            if (result) {
                packetsProcessed.value++
                bytesProcessed.value = bytesProcessed.value + length.toLong()
                android.util.Log.d(TAG, "DNS query processed via DnsInterceptor (returned true)")
            } else {
                android.util.Log.w(TAG, "DnsInterceptor returned false, passing through")
            }
            return result
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleDnsPacket failed", e)
            errors.value++
            return false
        }
    }

    private fun processIcmpv6Packet(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int,
        tunFd: java.io.FileDescriptor
    ): Boolean {
        if (payloadLength < 4) return false
        
        buffer.position(payloadStart)
        val type = buffer.get().toInt() and 0xFF
        val code = buffer.get().toInt() and 0xFF
        val checksum = buffer.getShort()
        
        // ICMPv6 类型: 133=Router Solicitation, 134=Router Advertisement, 
        // 135=Neighbor Solicitation, 136=Neighbor Advertisement
        when (type) {
            135 -> { // Neighbor Solicitation (NS) - 响应 Neighbor Advertisement
                return handleNeighborSolicitation(buffer, srcIp, dstIp, payloadStart, payloadLength)
            }
            133 -> { // Router Solicitation (RS) - 响应 Router Advertisement
                return handleRouterSolicitation(buffer, srcIp, dstIp, payloadStart, payloadLength)
            }
            else -> {
                Log.d(TAG, "ICMPv6 type=$type code=$code not handled, dropping")
                return false
            }
        }
    }

    private fun handleNeighborSolicitation(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int
    ): Boolean {
        // NS: Type(1) Code(1) Checksum(2) Reserved(4) TargetAddr(16) Options...
        if (payloadLength < 24) return false
        
        buffer.position(payloadStart + 4) // Skip type, code, checksum, reserved
        val targetAddr = ByteArray(16)
        buffer.get(targetAddr)
        
        // 检查是否在查询我们的 VPN 网关地址
        val vpnGatewayIpv6 = InetAddress.getByName("fd00::1")
        if (!targetAddr.contentEquals(vpnGatewayIpv6.address)) {
            return false
        }
        
        // 构造 Neighbor Advertisement 响应
        val writer = tunWriter ?: return false
        val naPacket = buildIcmpv6NeighborAdvertisement(
            srcIp = vpnGatewayIpv6.address,
            dstIp = srcIp.address,
            targetAddr = targetAddr,
            isSolicited = true,
            isOverride = true
        )
        writer(naPacket)
        Log.d(TAG, "Sent Neighbor Advertisement for $targetAddr")
        return true
    }

    private fun handleRouterSolicitation(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int
    ): Boolean {
        // RS: Type(1) Code(1) Checksum(2) Reserved(4) Options...
        // 响应 Router Advertisement
        val writer = tunWriter ?: return false
        val raPacket = buildIcmpv6RouterAdvertisement(
            srcIp = InetAddress.getByName("fd00::1").address,
            dstIp = srcIp.address
        )
        writer(raPacket)
        Log.d(TAG, "Sent Router Advertisement")
        return true
    }

    private fun buildIcmpv6NeighborAdvertisement(
        srcIp: ByteArray, dstIp: ByteArray,
        targetAddr: ByteArray,
        isSolicited: Boolean, isOverride: Boolean
    ): ByteArray {
        // IPv6 固定头部 40 字节 + ICMPv6 NA 24 字节 + 选项
        val icmpLen = 24 + 8 // NA + Target Link-layer Address Option
        val ipHeaderLen = 40
        val totalLen = ipHeaderLen + icmpLen

        val packet = ByteBuffer.allocate(totalLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        // IPv6 Header
        packet.putInt(0x60000000) // Version=6, TC=0, Flow=0
        packet.putShort(icmpLen.toShort()) // Payload length
        packet.put(58.toByte()) // Next Header: ICMPv6
        packet.put(64.toByte()) // Hop Limit
        packet.put(srcIp)
        packet.put(dstIp)

        // ICMPv6 Neighbor Advertisement
        packet.put(136.toByte()) // Type: Neighbor Advertisement
        packet.put(0.toByte())   // Code: 0
        packet.putShort(0)       // Checksum (计算后填入)
        val flags = (if (isSolicited) 0x80 else 0) or (if (isOverride) 0x40 else 0) // S=1, O=1
        packet.put(flags.toByte()) // Flags: R=0, S=solicited, O=override
        packet.put(0.toByte())   // Reserved
        packet.putInt(0)         // Reserved
        packet.put(targetAddr)   // Target Address
        
        // Target Link-layer Address Option (简化: 全零 MAC)
        packet.put(2.toByte())   // Type: Target Link-layer Address
        packet.put(1.toByte())   // Length: 1 (in units of 8 bytes = 8 bytes)
        packet.put(ByteArray(6)) // MAC address (6 bytes) + 2 bytes padding

        // 计算 ICMPv6 校验和 (包含伪头部)
        val icmpStart = ipHeaderLen
        val icmpChecksum = calculateIcmpv6Checksum(packet.array(), srcIp, dstIp, icmpStart, icmpLen)
        packet.position(icmpStart + 2)
        packet.putShort(icmpChecksum)

        return packet.array()
    }

    private fun buildIcmpv6RouterAdvertisement(
        srcIp: ByteArray, dstIp: ByteArray
    ): ByteArray {
        val icmpLen = 16 + 8 // RA + Source Link-layer Address Option
        val ipHeaderLen = 40
        val totalLen = ipHeaderLen + icmpLen

        val packet = ByteBuffer.allocate(totalLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        // IPv6 Header
        packet.putInt(0x60000000)
        packet.putShort(icmpLen.toShort())
        packet.put(58.toByte()) // Next Header: ICMPv6
        packet.put(255.toByte()) // Hop Limit: 255 (链路本地)
        packet.put(srcIp)
        packet.put(dstIp)

        // ICMPv6 Router Advertisement
        packet.put(134.toByte()) // Type: Router Advertisement
        packet.put(0.toByte())   // Code: 0
        packet.putShort(0)       // Checksum
        packet.put(64.toByte())  // Cur Hop Limit: 64
        packet.put(0.toByte())   // Flags: M=0, O=0
        packet.putShort(1800.toShort()) // Router Lifetime: 1800s
        packet.putInt(0)         // Reachable Time: 0 (unspecified)
        packet.putInt(0)         // Retrans Timer: 0 (unspecified)
        
        // Source Link-layer Address Option
        packet.put(1.toByte())   // Type: Source Link-layer Address
        packet.put(1.toByte())   // Length: 1
        packet.put(ByteArray(6)) // MAC (6 bytes) + 2 padding

        // 计算 ICMPv6 校验和
        val icmpStart = ipHeaderLen
        val icmpChecksum = calculateIcmpv6Checksum(packet.array(), srcIp, dstIp, icmpStart, icmpLen)
        packet.position(icmpStart + 2)
        packet.putShort(icmpChecksum)

        return packet.array()
    }

    private fun calculateIcmpv6Checksum(packet: ByteArray, srcIp: ByteArray, dstIp: ByteArray, icmpOffset: Int, icmpLen: Int): Short {
        // ICMPv6 伪头部: srcIp(16) + dstIp(16) + length(4) + zeros(3) + nextHeader(1)
        val pseudoHeader = ByteBuffer.allocate(40 + icmpLen)
        pseudoHeader.order(ByteOrder.BIG_ENDIAN)
        pseudoHeader.put(srcIp)
        pseudoHeader.put(dstIp)
        pseudoHeader.putInt(icmpLen)
        pseudoHeader.put(ByteArray(3)) // zeros
        pseudoHeader.put(58.toByte()) // Next Header: ICMPv6
        pseudoHeader.put(packet, icmpOffset, icmpLen)

        var sum = 0L
        pseudoHeader.position(0)
        for (i in 0 until pseudoHeader.limit() step 2) {
            sum += (pseudoHeader.getShort().toInt() and 0xFFFF)
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toShort()
    }

    /**
     * 将 TCP SYN 转发到隧道插件建立连接
     * 通过 TunnelManager 获取活跃插件，调用 openTcpChannel
     */
    private fun forwardSynToTunnel(conn: TcpConnection) {
        scope.launch {
            try {
                val plugin = tunnelManager.getActiveOrFallback()
                val channel = plugin.openTcpChannel(conn.dstIp.hostAddress, conn.dstPort)
                if (channel == null) {
                    Log.e(TAG, "openTcpChannel failed for plugin ${plugin.id}: ${conn.dstIp.hostAddress}:${conn.dstPort}")
                    conn.state = TcpConnection.TcpState.Closed
                    return@launch
                }

                val connected = channel.connect(5000)
                if (!connected) {
                    Log.e(TAG, "channel.connect failed for plugin ${plugin.id}")
                    channel.disconnect()
                    conn.state = TcpConnection.TcpState.Closed
                    return@launch
                }

                conn.tunnelChannel = channel
                conn.state = TcpConnection.TcpState.Established

                val connKey = connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)
                val synAckPacket = buildSynAckPacket(conn, connKey)
                if (synAckPacket != null) {
                    tunWriter?.invoke(synAckPacket)
                }

                startRelayFromTunnel(conn, connKey)
                Log.d(TAG, "TCP connection established via tunnel plugin ${plugin.id} to ${conn.dstIp}:${conn.dstPort}")
            } catch (e: Exception) {
                Log.e(TAG, "forwardSynToTunnel failed", e)
                errors.value++
                conn.state = TcpConnection.TcpState.Closed
            }
        }
    }

    /**
     * 从隧道插件读取数据并写回 TUN
     */
    private fun startRelayFromTunnel(conn: TcpConnection, connKey: Long) {
        val channel = conn.tunnelChannel ?: return
        val input = channel.inputStream ?: return
        val writer = tunWriter ?: return

        Thread({
            val buffer = ByteArray(RELAY_BUFFER_SIZE)
            try {
                while (channel.isConnected && conn.state == TcpConnection.TcpState.Established) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        val data = buffer.copyOf(read)
                        val responsePacket = buildTcpResponsePacket(conn, data, connKey)
                        if (responsePacket != null) {
                            writer(responsePacket)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Tunnel relay read ended: ${e.message}")
            } finally {
                closeTcpConnection(connKey, conn)
            }
        }, "Tunnel-Relay-${conn.id}").start()
    }

    /**
     * 将 TCP SYN 转发到隧道插件建立连接 (fallback 路径)
     */
    private fun forwardSynToSocks(conn: TcpConnection) {
        forwardSynToTunnel(conn)
    }

    /**
     * 完成延迟的连接: 从首个数据包提取域名，通过隧道插件转发
     */
    private fun completeDeferredConnect(conn: TcpConnection, firstData: ByteArray, connKey: Long) {
        try {
            val domain = extractSniFromTls(firstData) ?: extractHostFromHttp(firstData)
            if (domain != null) {
                dnsInterceptor?.ipToDomain?.put(conn.dstIp.hostAddress, domain)
                Log.d(TAG, "Extracted domain: $domain for IP ${conn.dstIp.hostAddress} (conn ${conn.id})")
            }

            val plugin = tunnelManager.getActiveOrFallback()
            val channel = plugin.openTcpChannel(conn.dstIp.hostAddress, conn.dstPort)
            if (channel != null && channel.connect(5000)) {
                conn.tunnelChannel = channel
                conn.pendingConnect = false
                conn.state = TcpConnection.TcpState.Established

                val synAckPacket = buildSynAckPacket(conn, connKey)
                if (synAckPacket != null) tunWriter?.invoke(synAckPacket)

                startRelayFromTunnel(conn, connKey)

                val output = channel.outputStream
                if (output != null) {
                    output.write(firstData)
                    output.flush()
                }
            } else {
                Log.e(TAG, "Deferred tunnel connect failed for conn ${conn.id}")
                closeTcpConnection(connKey, conn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "completeDeferredConnect failed for conn ${conn.id}", e)
            closeTcpConnection(connKey, conn)
        }
    }

    /**
     * 从 TLS ClientHello 中提取 SNI 域名
     */
    private fun extractSniFromTls(data: ByteArray): String? {
        if (data.size < 6) return null
        // TLS Record: ContentType(1) Version(2) Length(2) = 5 bytes header
        if (data[0] != 0x16.toByte()) return null // Not TLS Handshake
        // HandshakeType
        val hsType = data[5].toInt() and 0xFF
        if (hsType != 0x01) return null // Not ClientHello

        var offset = 6
        // Handshake length (3 bytes)
        if (data.size < offset + 3) return null
        offset += 3
        // ClientVersion (2 bytes)
        offset += 2
        // Random (32 bytes)
        offset += 32
        if (data.size < offset) return null
        // SessionID
        if (data.size < offset + 1) return null
        val sessionIdLen = data[offset].toInt() and 0xFF
        offset += 1 + sessionIdLen
        // Cipher Suites
        if (data.size < offset + 2) return null
        val csLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2 + csLen
        // Compression Methods
        if (data.size < offset + 1) return null
        val compLen = data[offset].toInt() and 0xFF
        offset += 1 + compLen
        // Extensions
        if (data.size < offset + 2) return null
        val extLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val extEnd = offset + extLen

        while (offset + 4 <= extEnd && offset + 4 <= data.size) {
            val extType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val extDataLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (extType == 0x0000) { // SNI
                if (data.size < offset + 5) return null
                offset += 2 // server name list length
                val nameType = data[offset].toInt() and 0xFF
                if (nameType != 0) { offset += extDataLen - 2; continue }
                offset += 1
                val nameLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
                if (data.size < offset + nameLen) return null
                return String(data, offset, nameLen, Charsets.US_ASCII)
            }
            offset += extDataLen
        }
        return null
    }

    /**
     * 从 HTTP 请求中提取 Host 头
     */
    private fun extractHostFromHttp(data: ByteArray): String? {
        if (data.size < 10) return null
        // 检查是否以 HTTP 方法开头
        val methods = arrayOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ", "CONNECT ")
        var isHttp = false
        for (method in methods) {
            if (data.size >= method.length) {
                val prefix = String(data, 0, method.length, Charsets.US_ASCII)
                if (prefix == method) { isHttp = true; break }
            }
        }
        if (!isHttp) return null

        val text = String(data, 0, minOf(data.size, 2048), Charsets.US_ASCII)
        val hostIdx = text.indexOf("\r\nHost:", 0, true)
        if (hostIdx == -1) return null
        val start = hostIdx + 7
        val end = text.indexOf("\r\n", start)
        if (end == -1) return null
        val host = text.substring(start, end).trim()
        if (host.isEmpty()) return null
        // 去除端口号
        if (host.startsWith("[")) {
            val bracketEnd = host.indexOf(']')
            if (bracketEnd != -1) return host.substring(1, bracketEnd)
        }
        val lastColon = host.lastIndexOf(':')
        if (lastColon > 0) {
            val port = host.substring(lastColon + 1).toIntOrNull()
            if (port != null) return host.substring(0, lastColon)
        }
        return host
    }

    /**
     * 启动双向中继线程: SOCKS5 通道 ↔ TUN
     */
    private fun startRelayThreads(conn: TcpConnection) {
        val socksChannel = conn.socksChannel ?: return
        val writer = tunWriter
        if (writer == null) {
            Log.e(TAG, "TUN writer not set, cannot relay data")
            return
        }

        // 初始化 seq/ack 计数器 (用 conn id 作为 key)
        val connKey = connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)
        nextTcpSeq.putIfAbsent(connKey, 1000L)
        nextTcpAck.putIfAbsent(connKey, 1000L)

        Thread({
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

                        // 构造反向 IP/TCP 响应包并写回 TUN
                        val responsePacket = buildTcpResponsePacket(conn, payload, connKey)
                        if (responsePacket != null) {
                            writer(responsePacket)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "SOCKS5 relay read ended: ${e.message}")
            } finally {
                closeTcpConnection(connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort), conn)
            }
        }, "SOCKS5-Relay-${conn.id}").start()
    }

    /**
     * 构建反向 IP/TCP 响应包: src ↔ dst 互换 (支持 IPv4/IPv6)
     */
    private fun buildTcpResponsePacket(conn: TcpConnection, payload: ByteArray, connKey: Long): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = (conn.browserSeq + 1 + conn.forwardedBytes) and 0xFFFFFFFFL

            conn.serverSeq = (seqNum + payload.size) and 0xFFFFFFFFL
            nextTcpAck[connKey] = conn.serverSeq
            nextTcpSeq[connKey] = ackNum

            val tcpHeaderLen = 20
            val ipHeaderLen = if (isIPv6) 40 else 20
            val totalLen = ipHeaderLen + tcpHeaderLen + payload.size

            val packet = ByteBuffer.allocate(totalLen)
            packet.order(ByteOrder.BIG_ENDIAN)

            if (isIPv6) {
                // IPv6 Header (40 bytes)
                packet.putInt(0x60000000.toInt()) // Version=6, Traffic Class=0, Flow Label=0
                packet.putShort((tcpHeaderLen + payload.size).toShort()) // Payload length = TCP header + payload
                packet.put(6.toByte())  // Next Header: TCP
                packet.put(64.toByte()) // Hop Limit
                packet.put(srcIp)
                packet.put(dstIp)
            } else {
                // IPv4 Header (20 bytes)
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

            // TCP Header
            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putInt(seqNum.toInt())
            packet.putInt(ackNum.toInt())
            packet.putShort(0x5018.toShort()) // ACK+PSH
            packet.putShort(65535.toShort())
            packet.putShort(0)
            packet.putShort(0)

            // Payload
            packet.put(payload)

            if (!isIPv6) {
                // IPv4 校验和
                packet.position(0)
                val ipChecksum = calculateIpChecksum(packet, ipHeaderLen)
                packet.position(10)
                packet.putShort(ipChecksum)
            }

            // TCP 校验和
            val tcpChecksum = calculateTcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, payload.size + tcpHeaderLen)
            packet.position(ipHeaderLen + 16)
            packet.putShort(tcpChecksum)

            if (!isIPv6) {
                Log.d(TAG, "TCP resp (${packet.array().size}B) conn=${conn.id}")
            }

            return packet.array()
        } catch (e: Exception) {
            Log.e(TAG, "buildTcpResponsePacket failed: ${e::class.simpleName}: conn.srcIp.size=${conn.srcIp.address.size} payload.size=${payload.size}", e)
            return null
        }
    }

    /**
     * 构建 SYN-ACK 包 (支持 IPv4/IPv6)
     */
    private fun buildSynAckPacket(conn: TcpConnection, connKey: Long): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = conn.browserSeq + 1

            conn.serverSeq = (seqNum + 1) and 0xFFFFFFFFL
            nextTcpAck[connKey] = conn.serverSeq
            nextTcpSeq[connKey] = ackNum

            val tcpHeaderLen = 20
            val ipHeaderLen = if (isIPv6) 40 else 20
            val totalLen = ipHeaderLen + tcpHeaderLen

            val packet = ByteBuffer.allocate(totalLen)
            packet.order(ByteOrder.BIG_ENDIAN)

            if (isIPv6) {
                // IPv6 Header
                packet.putInt(0x60000000.toInt())
                packet.putShort(tcpHeaderLen.toShort()) // Payload length = TCP header only
                packet.put(6.toByte())
                packet.put(64.toByte())
                packet.put(srcIp)
                packet.put(dstIp)
            } else {
                // IPv4 Header
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
                val ipChecksum = calculateIpChecksum(packet, ipHeaderLen)
                packet.position(10)
                packet.putShort(ipChecksum)
            }

            // TCP 校验和 (对 IPv6 伪头部用 0 填充)
            val tcpChecksum = calculateTcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, tcpHeaderLen)
            packet.position(ipHeaderLen + 16)
            packet.putShort(tcpChecksum)

            if (!isIPv6) {
                val hex = packet.array().joinToString("") { "%02x".format(it) }
                Log.d(TAG, "SYN-ACK packet (${packet.array().size}B): $hex")
            }

            return packet.array()
        } catch (e: Exception) {
            Log.e(TAG, "buildSynAckPacket failed: ${e::class.simpleName}: ${e.message}", e)
            return null
        }
    }

    private fun buildRstPacket(conn: TcpConnection, connKey: Long): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = (conn.browserSeq + 1 + conn.forwardedBytes) and 0xFFFFFFFFL
            val tcpHeaderLen = 20
            val ipHeaderLen = if (isIPv6) 40 else 20
            val totalLen = ipHeaderLen + tcpHeaderLen

            val packet = ByteBuffer.allocate(totalLen)
            packet.order(ByteOrder.BIG_ENDIAN)

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
                val ipChecksum = calculateIpChecksum(packet, ipHeaderLen)
                packet.position(10)
                packet.putShort(ipChecksum)
            }

            val tcpChecksum = calculateTcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, tcpHeaderLen)
            packet.position(ipHeaderLen + 16)
            packet.putShort(tcpChecksum)

            Log.d(TAG, "RST packet ${packet.array().size}B for conn ${conn.id} ${conn.srcIp.hostAddress}:${conn.srcPort}")
            return packet.array()
        } catch (e: Exception) {
            Log.e(TAG, "buildRstPacket failed: ${e::class.simpleName}: ${e.message}", e)
            return null
        }
    }

    /**
     * 构建 UDP 响应包 (用于 DNS 回程)
     */
    fun buildUdpResponsePacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
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
        val ipChecksum = calculateIpChecksum(packet, ipHeaderLen)
        packet.position(10)
        packet.putShort(ipChecksum)

        return packet.array()
    }

    private fun calculateIpChecksum(header: ByteBuffer, headerLen: Int): Short {
        val savedPos = header.position()
        header.position(0)
        var sum = 0L
        val end = headerLen - (headerLen and 1)
        for (i in 0 until end step 2) {
            sum += (header.getShort().toInt() and 0xFFFF)
        }
        if (headerLen and 1 != 0) {
            header.position(end)
            sum += (header.get().toInt() and 0xFF) shl 8
        }
        header.position(savedPos)
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv().toShort())
    }

    private fun calculateTcpChecksum(srcIp: ByteArray, dstIp: ByteArray, packet: ByteArray, ipOffset: Int, tcpLen: Int): Short {
        val isIPv6 = srcIp.size == 16
        val paddedLen = tcpLen + (tcpLen and 1)
        val pseudoHeader = if (isIPv6) {
            ByteBuffer.allocate(40 + paddedLen).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(srcIp)
                put(dstIp)
                putInt(tcpLen)
                put(ByteArray(3))
                put(6)
                put(packet, ipOffset, tcpLen)
                if (paddedLen != tcpLen) put(0)
            }
        } else {
            ByteBuffer.allocate(12 + paddedLen).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(srcIp)
                put(dstIp)
                put(0)
                put(6)
                putShort(tcpLen.toShort())
                put(packet, ipOffset, tcpLen)
                if (paddedLen != tcpLen) put(0)
            }
        }

        var sum = 0L
        pseudoHeader.position(0)
        while (pseudoHeader.remaining() >= 2) {
            sum += (pseudoHeader.getShort().toInt() and 0xFFFF)
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toShort()
    }

    /**
     * 将 TCP 数据转发到 SOCKS5 通道
     */
private fun forwardToSocks(
         conn: TcpConnection,
         buffer: ByteBuffer,
         payloadStart: Int,
         payloadLength: Int
     ) {
         if (conn.state != TcpConnection.TcpState.Established) {
             Log.w(TAG, "forwardToSocks: conn ${conn.id} not established (state=${conn.state}), dropping ${payloadLength}B")
             return
         }

         // Try tunnel channel first, then SOCKS5 channel
         val tunnelChannel = conn.tunnelChannel
         if (tunnelChannel != null) {
             try {
                 buffer.position(payloadStart)
                 buffer.limit(payloadStart + payloadLength)
                 val output = tunnelChannel.outputStream
                 if (output != null) {
                     val payload = ByteArray(payloadLength)
                     buffer.get(payload)
                     output.write(payload)
                     output.flush()
                     Log.d(TAG, "forwardToTunnel: wrote ${payloadLength}B for conn ${conn.id} → ${conn.dstIp}:${conn.dstPort}")
                 }
             } catch (e: IOException) {
                 Log.e(TAG, "forwardToTunnel failed", e)
                 errors.value++
                 closeTcpConnection(connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort), conn)
             }
             return
         }

         val socksChannel = conn.socksChannel
         if (socksChannel == null) {
             Log.w(TAG, "forwardToSocks: both tunnelChannel and socksChannel are null for conn ${conn.id}")
             return
         }

         try {
             buffer.position(payloadStart)
             buffer.limit(payloadStart + payloadLength)
             while (buffer.hasRemaining()) {
                 socksChannel.write(buffer)
             }
             Log.d(TAG, "forwardToSocks: wrote ${payloadLength}B for conn ${conn.id} → ${conn.dstIp}:${conn.dstPort}")
         } catch (e: IOException) {
             Log.e(TAG, "forwardToSocks failed", e)
            errors.value++
            closeTcpConnection(connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort), conn)
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
        length: Int
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
                val socksPort = 1080

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
                    val assocReq = byteArrayOf(
                        0x05, 0x03, 0x00, 0x01, // VER CMD RSV ATYP(IPv4)
                        0x00, 0x00, 0x00, 0x00, // BND.ADDR (0.0.0.0)
                        0x00, 0x00               // BND.PORT (0)
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
                    val relayIp = ByteArray(4)
                    assocResp.get(relayIp)
                    val relayPort = assocResp.short.toInt() and 0xFFFF

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
                errors.value++
            }
        }
    }

    private fun createTcpConnection(
        key: Long, srcIp: InetAddress, dstIp: InetAddress, srcPort: Int, dstPort: Int
    ): TcpConnection {
        val id = connectionIdCounter.incrementAndGet()
        val conn = TcpConnection(id, srcIp, dstIp, srcPort, dstPort)
        tcpConnections[key] = conn
        return conn
    }

    private fun createUdpAssociation(
        key: Long, srcIp: InetAddress, dstIp: InetAddress, srcPort: Int, dstPort: Int
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
        val writer = tunWriter
        if (writer == null) {
            Log.e(TAG, "TUN writer not set, cannot start UDP relay receive thread")
            return
        }

        Thread({
            val receiveBuffer = ByteBuffer.allocateDirect(65535)
            try {
                while (assoc.datagramChannel.isOpen) {
                    receiveBuffer.clear()
                    val sender = assoc.datagramChannel.receive(receiveBuffer)
                    if (sender != null) {
                        receiveBuffer.flip()
                        val frame = ByteArray(receiveBuffer.remaining())
                        receiveBuffer.get(frame)
                        
                        // 解析 SOCKS5 UDP 帧: RSV(2) FRAG(1) ATYP(1) DST.ADDR(*) DST.PORT(2) DATA(*)
                        if (frame.size >= 10) {
                            val frag = frame[2].toInt() and 0xFF
                            val atyp = frame[3].toInt() and 0xFF
                            
                            var addrOffset = 4
                            var dstIpBytes: ByteArray
                            when (atyp) {
                                0x01 -> { // IPv4
                                    dstIpBytes = frame.sliceArray(addrOffset until addrOffset + 4)
                                    addrOffset += 4
                                }
                                0x04 -> { // IPv6
                                    dstIpBytes = frame.sliceArray(addrOffset until addrOffset + 16)
                                    addrOffset += 16
                                }
                                else -> continue // 不支持的地址类型
                            }
                            
                            if (frame.size < addrOffset + 2) continue
                            val dstPort = ((frame[addrOffset].toInt() and 0xFF) shl 8) or (frame[addrOffset + 1].toInt() and 0xFF)
                            addrOffset += 2
                            
                            val payload = frame.sliceArray(addrOffset until frame.size)
                            
                            // 构造 IPv4/UDP 响应包写回 TUN
                            val responsePacket = buildUdpResponsePacket(
                                srcIp = assoc.dstIp.address,
                                dstIp = assoc.srcIp.address,
                                srcPort = assoc.dstPort,
                                dstPort = assoc.srcPort,
                                payload = payload
                            )
                            writer(responsePacket)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "UDP relay receive ended: ${e.message}")
            }
        }, "UDP-Relay-Receive-${assoc.id}").start()
    }

    private fun closeTcpConnection(key: Long, conn: TcpConnection) {
        tcpConnections.remove(key)
        try { conn.socksChannel?.close() } catch (_: Exception) {}
    }

    private fun connectionKey(srcIp: InetAddress, dstIp: InetAddress, srcPort: Int, dstPort: Int): Long {
        val srcBytes = srcIp.address
        val dstBytes = dstIp.address
        val isSrc6 = srcBytes.size == 16
        val isDst6 = dstBytes.size == 16

        val srcLow = if (isSrc6) {
            (srcBytes[12].toLong() and 0xFF shl 24) or
            (srcBytes[13].toLong() and 0xFF shl 16) or
            (srcBytes[14].toLong() and 0xFF shl 8) or
            (srcBytes[15].toLong() and 0xFF)
        } else {
            (srcBytes[0].toLong() and 0xFF shl 24) or
            (srcBytes[1].toLong() and 0xFF shl 16) or
            (srcBytes[2].toLong() and 0xFF shl 8) or
            (srcBytes[3].toLong() and 0xFF)
        }

        val dstLow = if (isDst6) {
            (dstBytes[12].toLong() and 0xFF shl 24) or
            (dstBytes[13].toLong() and 0xFF shl 16) or
            (dstBytes[14].toLong() and 0xFF shl 8) or
            (dstBytes[15].toLong() and 0xFF)
        } else {
            (dstBytes[0].toLong() and 0xFF shl 24) or
            (dstBytes[1].toLong() and 0xFF shl 16) or
            (dstBytes[2].toLong() and 0xFF shl 8) or
            (dstBytes[3].toLong() and 0xFF)
        }

        return (srcLow shl 48) xor
               (dstLow shl 32) xor
               (srcPort.toLong() shl 16) xor
               dstPort.toLong()
    }

    private fun readIpAddress(buffer: ByteBuffer): InetAddress {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return InetAddress.getByAddress(bytes)
    }

    private fun readIpv6Address(buffer: ByteBuffer): InetAddress {
        val bytes = ByteArray(16)
        buffer.get(bytes)
        return InetAddress.getByAddress(bytes)
    }

    fun cleanupStaleConnections(timeoutMs: Long = DEFAULT_CONNECTION_CLEANUP_TIMEOUT_MS) {
        val now = System.currentTimeMillis()
        tcpConnections.values.removeIf { conn ->
            if (now - conn.lastActivity > timeoutMs) {
                closeTcpConnection(connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort), conn)
                true
            } else false
        }
        udpAssociations.values.removeIf { assoc ->
            if (now - assoc.lastActivity > timeoutMs) {
                try { assoc.datagramChannel.close() } catch (_: Exception) {}
                true
            } else false
        }
    }
}