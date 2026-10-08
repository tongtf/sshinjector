package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
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

/** SOCKS5 握手/认证诊断日志开关 (独立 tag, 便于一次性开两端排查). */
private val IS_DEBUG_SOCKS = android.util.Log.isLoggable("Socks5Proxy", android.util.Log.DEBUG)

/**
 * TCP 状态机：解析 TCP 头、维护连接状态、通过隧道/SOCKS5 转发。
 */
@Suppress("LargeClass", "TooManyFunctions") // 组包+转发+背压+流控全在一处; 待拆 TcpPacketBuilder / TcpRelay
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
        private const val MAX_TCP_SEGMENT_V6 = 1440 // MTU1500 - IPv6 头40 - TCP 头20
        private const val UINT32_MASK = 0xFFFFFFFFL
        private const val TUN_CONNECT_TIMEOUT_MS = 5000

        /**
         * loopback 连接 + SOCKS 协商的预算。纯本地操作, 正常是µs 级 —— 2s 只用来兜死。
         *
         * 三个阶段必须**各自独立**预算: 曾用同一个 5s deadline 覆盖全程, 结果日志里
         * 成功建连耗时 4186ms/2839ms (大头是 SSH direct-tcpip 建通道) 会把预算耗尽,
         * 后面读 CONNECT 应答时立刻判超时 → 把"慢"变成"硬失败" (现场:
         * `本地代理无响应 · SOCKS CONNECT 读取失败` + `存活 5s`)。
         */
        private const val SOCKS_LOCAL_TIMEOUT_MS = 2000

        /** 零回程归因阈值: 存活短于此值算"客户端等不到即放弃", 长于此值算本栈中途丢。 */
        private const val BLACK_HOLE_IMMEDIATE_MS = 1000L
        private const val BLACK_HOLE_LOG_THROTTLE_MS = 10_000L

        /**
         * CONNECT 请求→应答的预算。这一步的耗时**就是** SSH 通道建立时间
         * (`Socks5ProxyServer.connectToTarget` 同步等 SSH), 天然比协商慢一个量级,
         * 远端 SSH 握手慢时能到数秒。给足余量, 否则误杀正常慢连接。
         */
        private const val SOCKS_CONNECT_REPLY_TIMEOUT_MS = 15000
        private const val MAX_INFLIGHT_SANITY = 1L shl 31 // 在途超过该值视为 ack/seq 状态异常 (放开闸门)
        private const val FLOW_WAIT_TIMEOUT_MS = 1000L // 窗口等待重查间隔 (漏通知兜底, 不可移除)
        private const val MAX_WINDOW_SCALE = 14 // RFC 7323 上限, 防畸形 SYN 溢出
        private const val TCP_FLAGS_RST_ACK = 0x5014
        private const val TCP_FLAGS_FIN_ACK = 0x5011
        private const val SOCKS_FAIL_LOG_THROTTLE_MS = 10_000L // CONNECT 拒绝的应用内日志节流窗口
        private const val SLOW_HANDSHAKE_WARN_MS = 1500L // 建连耗时告警阈值 (SYN-ACK 延迟发送的代价)
        private const val SLOW_HANDSHAKE_SAMPLE_EVERY = 20 // 每 N 次建连评估一次慢握手占比
        private const val SLOW_HANDSHAKE_RATE_PCT = 30 // 慢握手占比阈值 (%)
        private const val MAX_PENDING_RETURN_BYTES = 512 * 1024 // 回程排队上限 (SYN-ACK 前/flush 期间)
        private const val MAX_PENDING_UPSTREAM_BYTES = 256 * 1024L // 通道就绪前可缓存的上行数据上限
        private const val UPSTREAM_WAIT_LOG_THROTTLE_MS = 3_000L // "上行等待通道" 告警节流 (需比 10s 更密才可见)
        private const val NANOS_PER_MILLI = 1_000_000L // System.nanoTime → ms
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tcpConnections = ConcurrentHashMap<Long, TcpConnection>()
    private val connectionIdCounter = AtomicLong(0)

    // 背压丢段累计字节 (SSH 上行队列满 → 丢段靠客户端重传)。非 0 且持续增长 =
    // 下行卡住导致服务器不读上行, 隧道正在走向死锁; 计入周期快照以便发现。
    private val droppedByBackpressure = AtomicLong(0)

    // 回程窗口闸门累计阻塞时长 (客户端 ACK 停止回补的证据)
    private val flowBlockedMillis = AtomicLong(0)

    // 上行缓存累计字节 (进入缓存而非丢弃的数据量; 与 TUN 计数对比可知隧道是否在空转)
    private val upstreamBufferedBytes = AtomicLong(0)

    // 无连接(孤儿)上行包: 连接已关闭/被清理后客户端仍在发包的数量与字节。
    // 持续增长 = 我们发的 RST 没让客户端停下, 或连接被过早清理。
    private val droppedOrphanPackets = AtomicLong(0)

    /** 回程丢弃累计字节 (窗口耗尽/连接已关/组包失败 → 客户端收不到)。 */
    private val droppedDownstreamBytes = AtomicLong(0)

    /** 假 IP → 活动连接数; 只在归零时才释放 DNS 映射 (见 releaseUnusedFakeIps)。 */
    private val fakeIpActiveConns = ConcurrentHashMap<String, Int>()

    // 建连耗时统计 (慢握手占比 = SYN-ACK 延迟发送的总代价, 见 reportSlowHandshakeRate)
    private val handshakeCount = AtomicLong(0)
    private val slowHandshakeCount = AtomicLong(0)

    /**
     * 隧道零回程连接数 (上行有数据但下行 0 字节)。拆成"秒断"与"稍后断"两段归因,
     * 见 [reportTunnelBlackHole]。持续增长 = 客户端在系统性地放弃这些连接。
     */
    private val tunnelBlackHoleConns = AtomicLong(0)
    private val tunnelBlackHoleImmediate = AtomicLong(0)
    private val tunnelBlackHoleDelayed = AtomicLong(0)

    /**
     * 回程排队溢出累计字节 (pendingReturn 满 → 中继读到的数据无处安放)。
     * 独立计数: 这条路径曾完全没有计数器, 表现为"各丢弃计数全 0 但数据真的丢了"。
     */
    private val droppedReturnQueueBytes = AtomicLong(0)
    private val droppedOrphanBytes = AtomicLong(0)

    /**
     * 孤儿包按 TCP 标志位分列。不分列时这个数没法用: FIN 收尾的尾包 (客户端对我方 FIN 的
     * ACK、或它自己那半个 FIN) 是**必然发生**的, 会把"真·过期/错投包"淹没掉 —— 现场只看到
     * "孤儿包 41~59(0B)" 却分不清是收尾噪声还是真有包被丢。0B = 无载荷控制段。
     */
    private val orphanFinPackets = AtomicLong(0)
    private val orphanRstPackets = AtomicLong(0)
    private val orphanAckPackets = AtomicLong(0)
    private val orphanDataPackets = AtomicLong(0)

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

    /** 连接日志的目标标识: 优先域名 (假 IP 反查), 回退 IP:port。 */
    private fun target(conn: TcpConnection): String {
        val ip = conn.dstIp.hostAddress ?: "-"
        val domain = dnsInterceptor?.lookupDomain(ip)
        return if (domain != null) "$domain(${conn.dstPort})" else "$ip:${conn.dstPort}"
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
        /**
         * 已成功转发给隧道的**最高连续序号偏移** (相对 browserSeq+1), 是 ACK 号的基准。
         * 必须只按连续前缀推进: 若按"累计写入字节"推进, 乱序段会让中间缺口被跳过,
         * 缺口段重传时 seq < 该值 → 被 dup 分支误判为已转发 → 数据永久丢失、
         * 客户端无限重传 (实测 5.6MB 上行只送达 166KB)。
         */
        @Volatile var forwardedBytes: Long = 0,
        var socksLocalPort: Int = 0,
    ) {
        /**
         * 本连接是否已对假 IP 计数表 acquire 过 (只在 Established 且映射存在时置位)。
         *
         * 必须有这个标志: `closeTcpConnection` 会在**大量**从未走到 Established 的路径上被调
         * (SOCKS 各失败分支、SYN 期关闭、陈旧清理), 若无条件递减, 一条握手失败的同域名连接
         * 就会把活跃连接的计数扣掉 → 归零 → 误删仍在使用的 DNS 映射 → 后续 CONNECT 拿
         * SOCKS 0x03 (HTTP/2 多路复用下载卡"连接中")。
         */
        @Volatile var fakeIpAcquired: Boolean = false

        /**
         * 已发出的 SYN-ACK 序列号 (ISN)。-1 = 尚未发出。
         * SYN-ACK 重传必须复用同一个 ISN (RFC 793): 客户端因丢包重传 SYN 时, 收到不同 seq 的
         * SYN-ACK 会当作冲突包丢弃 → 双方僵持到超时, 表现为"连接挂着但永远无数据"。
         */
        @Volatile var synAckSeq: Long = -1L

        /**
         * SYN-ACK 是否**已真正写入 TUN**。与 [synAckSeq] 分开: 后者在组包时就发布
         * (SYN 重传分支靠 `synAckSeq >= 0` 判断"已建过, 必须复用原 ISN", 提前发布才不会
         * 出现两次组包用两个 ISN); 而回程闸门 (writeTcpPayloadToTun) 要的是"客户端已经能
         * 收到数据" —— 组包到写 TUN 之间有窗口, 期间直写的数据会落在 SYN-ACK 之前,
         * 客户端在 SYN_SENT 丢弃, 而 serverSeq 已推进 → 本栈不重传 → 永久洞。
         * 见 flushPendingReturn KDoc "必须在 buildSynAckPacket 写 TUN 之后调用"。
         */
        @Volatile var synAckWritten: Boolean = false

        /** 建连时间戳 (生命周期日志用)。 */
        @Volatile var createdAt: Long = System.currentTimeMillis()

        /** 已写入 TUN 的下行字节 (生命周期日志用: "上行有 N 字节但下行 0" = 请求没等到响应)。 */
        @Volatile var downstreamBytes: Long = 0

        /** 已写入 TUN 但被丢弃(零窗口/已关闭/组包失败)的下行字节。 */
        @Volatile var droppedDownstreamBytes: Long = 0

        /**
         * 通道未就绪(SOCKS 握手/SSH 通道建立中)时到达的上行数据。
         * 与回程 [pendingReturn] 对称: 丢弃会让客户端无限重传 (实测 TUN 收到 5MB 而隧道只送出 147KB),
         * 缓存后通道就绪时统一 flush 并补 ACK, 数据不丢、ACK 记账一致。
         */
        val pendingUpstreamLock = Any()
        val pendingUpstream = ArrayDeque<ByteArray>()
        var pendingUpstreamBytes: Long = 0

        /**
         * SYN-ACK 尚未发出时到达的回程数据 (服务端 banner 最典型): 排队等通道就绪后按序下发。
         *
         * [pendingReturn] 非线程安全, 一律只在 [pendingReturnLock] 内访问。
         * [pendingReturnFlushing] 标记 flush 正在排空队列: 中继线程据此把新到的数据**入队**
         * 而不是直接写 TUN —— 否则 flusher 手里那块更旧的数据会被后到的新数据抢在前面发出
         * (乱序)。本假 TCP 栈没有乱序缓存也没有重传, 一个洞 = 该连接永久卡死。
         * 只能在锁内置 false (队列空的那一次), 保证"发完最后一块"先于"允许直接写"发生。
         */
        val pendingReturnLock = Any()
        val pendingReturn = ArrayDeque<ByteArray>()
        var pendingReturnBytes: Long = 0
        var pendingReturnFlushing: Boolean = false

        /** 回程流控: 客户端确认位 (u32), 由 packetLoop 在每个 ACK 段更新。 */
        @Volatile var clientAck: Long = 0

        /**
         * 客户端已发 FIN (该序号已被 FIN 占用)。此后本端**每一个** ACK / 关闭包的 ack 号
         * 都必须再 +1 才能覆盖它 —— 否则客户端收不到对 FIN 的确认, 停在 FIN_WAIT_1 按
         * RTO 指数退避反复重传 FIN, 而重传段到达时条目早已被删 → 每帧都进孤儿桶且恒 0B。
         */
        @Volatile var clientFinSeen: Boolean = false

        /** 回程流控: 客户端通告窗口原值 (u16, 未缩放), 每包更新。默认放开 (解析失败时不劣于旧行为)。 */
        @Volatile var clientWindowRaw: Int = 65535

        /** 客户端 SYN 声明的 Window Scale shift (已 clamp 0..14); null = 未提供 (未协商缩放)。 */
        @Volatile var clientWscaleOffered: Int? = null

        /** 窗口闸门的等待/唤醒监视器; 谓词与 wait 必须同持此锁, TUN 写入严禁在锁内 (见 awaitFlowCapacity)。 */
        val flowLock = java.lang.Object()

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

        // TCP 标志位 (提前到选项解析前, SYN 选项解析依赖 syn/ack)
        val fin = (flags and 0x01) != 0
        val syn = (flags and 0x02) != 0
        val rst = (flags and 0x04) != 0
        val ack = (flags and 0x10) != 0

        // 通告窗口原值 (回程流控), 之后跳过 checksum + urgentPtr → payloadStart+20
        val windowSize = buffer.getShort().toInt() and 0xFFFF
        buffer.position(buffer.position() + 4)

        // 跳过选项; SYN 段解析客户端 Window Scale (RFC 7323, 仅记提供与否, 由本端回显后才生效)
        var wscaleOffered: Int? = null
        if (dataOffset > 20) {
            if (syn && !ack) {
                wscaleOffered = parseSynWindowScale(buffer, payloadStart + 20, payloadStart + dataOffset)
            }
            buffer.position(payloadStart + dataOffset)
        }

        val payloadLen = payloadLength - dataOffset
        val hasPayload = payloadLen > 0

        // 连接标识符 (五元组哈希)
        val connKey = IpPacketParser.connectionKey(srcIp, dstIp, srcPort, dstPort)
        var conn = tcpConnections[connKey]

        if (syn && !ack) {
            if (conn == null) {
                conn = createTcpConnection(connKey, srcIp, dstIp, srcPort, dstPort)
                // 必须先于 forwardSynToTunnel 赋值: 协程可能立刻读取这些字段构建 SYN-ACK
                conn.clientWindowRaw = windowSize
                conn.clientWscaleOffered = wscaleOffered
                conn.browserSeq = (seqNum.toLong()) and UINT32_MASK
                conn.state = TcpConnection.TcpState.SynSent

                // 通过隧道插件建立连接 (内部取 active/fallback 插件; 失败由 forwardSynToTunnel 自身 try/catch 处理)
                forwardSynToTunnel(conn)
            } else if (conn.browserSeq != ((seqNum.toLong()) and UINT32_MASK)) {
                // 同五元组但**不是 SYN 重传**(客户端用相同源端口新建连接, 旧连接尚未从 map 清理):
                // 旧状态必须整体作废 —— 沿用旧 forwardedBytes/serverSeq 会让 ACK 号变成
                // "新 browserSeq + 旧 forwardedBytes", 客户端不认我们的 ACK, 只能无限重传
                // (实测上行 3.5MB 重传而服务器侧只收到 0.2MB)。
                closeTcpConnection(connKey, conn, notifyBrowser = false)
                val fresh = createTcpConnection(connKey, srcIp, dstIp, srcPort, dstPort)
                fresh.clientWindowRaw = windowSize
                fresh.clientWscaleOffered = wscaleOffered
                fresh.browserSeq = (seqNum.toLong()) and UINT32_MASK
                forwardSynToTunnel(fresh)
            } else {
                conn.lastActivity = System.currentTimeMillis()
                conn.clientWindowRaw = windowSize
                conn.clientWscaleOffered = wscaleOffered
                // "发布窗口字段即唤醒"约定的另一半: 重复 SYN 打中已 Established 的连接时,
                // 回程线程正卡在闸门上, 不唤醒就得干等满 FLOW_WAIT_TIMEOUT_MS 才重查谓词。
                synchronized(conn.flowLock) { conn.flowLock.notifyAll() }
                // 客户端重传 SYN = 它没收到我们的 SYN-ACK, 必须补发 (复用原 ISN)。
                // 不补发则双方僵持到客户端超时放弃 —— 此后它不再重传, 连接永久挂起。
                if (conn.state != TcpConnection.TcpState.Closed && conn.synAckSeq >= 0) {
                    val again = buildSynAckPacket(conn)
                    if (again != null) {
                        tunWriterProvider()?.invoke(again)
                        conn.synAckWritten = true
                    }
                    VpnController.appLogThrottled(
                        "SYN 重传 · 补发 SYN-ACK ${target(conn)} (握手较慢或包丢失)",
                        level = LogLevel.INFO,
                    )
                }
            }
        } else if (conn != null) {
            conn.lastActivity = System.currentTimeMillis()

            if (rst) {
                // 对端已复位, 无需回包。先关后更新流控: RST 的 window 字段无意义 (RFC 793),
                // 在关闭前发布它会 notify 闸门, 与 close 的 notify 形成竞态窗口,
                // 让回程线程在连接已死时写出刚被拒绝的数据
                closeTcpConnection(connKey, conn, notifyBrowser = false)
            } else if (ack) {
                conn.clientWindowRaw = windowSize
                conn.clientAck = ackNum.toLong() and UINT32_MASK
                // 必须在本包任何回包之前置位: FIN 占用一个序号, 本包起所有 ACK 号都要覆盖它
                if (fin) conn.clientFinSeen = true
                // 唤醒窗口闸门 (谓词在 flowLock 内重查; 无等待者时开销为一次无竞争锁)
                synchronized(conn.flowLock) { conn.flowLock.notifyAll() }

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
                    } else if (seqIsOlder(receivedSeq, expectedBrowserSeq)) {
                        // 重传段 (seq 更旧): 数据已转发过, 丢弃重复, 回 ACK 推进浏览器窗口
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
                    // 浏览器主动 FIN: 先回 ACK 覆盖 FIN 占用的序号, 否则客户端停在 FIN_WAIT_1
                    // 按 RTO 退避(1/2/4/8s…)无限重传 FIN —— 每次重传都是无载荷段, 到达时
                    // 条目已删 → 全部落进孤儿桶且恒 0B, 这正是快照"孤儿包 N(0B)"的主源。
                    // 确认后再静默收尾, 不回 RST (F6: 对端是正常关闭的应用, 会看到 ECONNRESET)。
                    sendAckToBrowser(conn, connKey)
                    closeTcpConnection(connKey, conn, notifyBrowser = false)
                }
            }
        } else {
            // conn == null: 客户端仍在为已关闭 / 已被 stale 清理 / 已被 RST 的连接发包
            // (RST 尚未送达, 或重传队列里残留的包)。此前完全静默 —— 一次会话里 950+ 包 / 395KB
            // 消失得无声无息, 表现为"TUN 上行远大于隧道上行"的长期谜团。
            droppedOrphanPackets.incrementAndGet()
            when {
                fin -> orphanFinPackets.incrementAndGet()
                rst -> orphanRstPackets.incrementAndGet()
                hasPayload -> orphanDataPackets.incrementAndGet()
                else -> orphanAckPackets.incrementAndGet()
            }
            if (hasPayload) {
                droppedOrphanBytes.addAndGet(payloadLen.toLong())
            }
            if (IS_DEBUG) {
                Log.d(
                    TAG,
                    "orphan packet (no connection) ${srcIp.hostAddress}:$srcPort → " +
                        "${dstIp.hostAddress}:$dstPort payload=${payloadLen}B",
                )
            }
        }

        stats.addPacket(payloadLength.toLong())
        return true
    }

    /**
     * 解析 SYN 选项区中的 Window Scale (kind=3, RFC 7323), 返回 shift count (clamp 0..14)。
     * 未提供或畸形返回 null。
     *
     * 严格限界: option len < 2 或越界立即终止 —— 解析跑在 packetLoop 单线程上,
     * 死循环 = 整个 VPN 断流, 任何畸形输入都必须有界退出。
     */
    private fun parseSynWindowScale(
        buffer: ByteBuffer,
        start: Int,
        end: Int,
    ): Int? {
        var p = start
        var scale: Int? = null
        while (p < end) {
            val kind = buffer.get(p).toInt() and 0xFF
            if (kind == 0) return null // EOL
            if (kind == 1) {
                p += 1 // NOP 单字节
            } else {
                if (p + 1 >= end) return null
                val len = buffer.get(p + 1).toInt() and 0xFF
                if (len < 2 || p + len > end) return null // 畸形: 越界/零长
                if (kind == 3 && len == 3) {
                    scale = (buffer.get(p + 2).toInt() and 0xFF).coerceIn(0, MAX_WINDOW_SCALE)
                    return scale
                }
                p += len
            }
        }
        return scale
    }

    /**
     * 将 TCP SYN 转发到隧道插件建立连接
     * SOCKS5 优先：先回 SYN-ACK 再异步建 SSH 隧道，避免客户端超时
     */
    private fun forwardSynToTunnel(conn: TcpConnection) {
        scope.launch {
            try {
                // F1: 排除路由/域名分流未命中 → 用户态直连 (包已进 TUN, 回注是黑洞, 只能本地直连)
                // 分流决策每连接一条 (非每包): 0% 下载失败排查时用于确认下载主机是否绕过隧道
                val bypass = bypassPredicate?.invoke(conn.dstIp, conn.dstPort) == true
                Log.d(TAG, "SYN conn=${conn.id} dst=${conn.dstIp.hostAddress}:${conn.dstPort} bypass=$bypass")
                if (bypass) {
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
                        VpnController.appLog(
                            "隧道插件异常 · ${plugin.id} 既无本地 SOCKS 端口也无直连通道",
                            level = LogLevel.WARNING,
                        )
                        val connKey = IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)
                        // F6: 收敛到 closeTcpConnection (内部发 RST + 移除条目, 允许同五元组重建)
                        closeTcpConnection(connKey, conn)
                    }
                }
            } catch (e: Exception) {
                // 常开: 该异常 = 连接被 RST 秒断, 是"0% 下载失败"排查的关键现场
                Log.e(TAG, "forwardSynToTunnel failed conn=${conn.id} dst=${conn.dstIp.hostAddress}:${conn.dstPort}", e)
                VpnController.appLog(
                    "隧道转发失败 · ${conn.dstIp.hostAddress}:${conn.dstPort} — ${e.message}",
                    level = LogLevel.ERROR,
                )
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
                VpnController.appLog(
                    "分流直连失败 · protect 未设置或 VpnService.protect() 失败 " +
                        "(${conn.dstIp.hostAddress}:${conn.dstPort})",
                    level = LogLevel.WARNING,
                )
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
            // 上行 forwardToSocks 依赖非阻塞 write 的 write()==0 背压语义, 保持非阻塞。
            // 置 Established 必须与 closeTcpConnection 的 (state=Closed) 同持 conn 锁:
            // 握手期间客户端可能已 RST, 不加锁会让 close 先跑完、这里再置 Established →
            // 这条连接已从 map 移除, relay 却继续跑 (forwardThroughLocalSocks 有同款守卫)。
            synchronized(conn) {
                if (conn.state == TcpConnection.TcpState.Closed) {
                    try {
                        sc.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                conn.socksChannel = sc
                conn.state = TcpConnection.TcpState.Established
            }
            val synAckPacket = buildSynAckPacket(conn)
            if (synAckPacket != null) {
                tunWriterProvider()?.invoke(synAckPacket)
                conn.synAckWritten = true
            }
            startRelayFromSocks(conn, connKey)
            if (IS_DEBUG) {
                Log.d(TAG, "TCP bypass direct to ${conn.dstIp}:${conn.dstPort} for conn ${conn.id}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bypass connect failed for conn ${conn.id}: ${e.message}")
            VpnController.appLogThrottled(
                "分流直连失败 · ${conn.dstIp.hostAddress}:${conn.dstPort} — ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "分流直连失败",
            )
            try {
                sc.close()
            } catch (_: IOException) {
            }
            closeTcpConnection(connKey, conn)
        }
    }

    /**
     * 读满 `dst`;返回 false = EOF/错误/超时。
     *
     * ed713d3 回归修复: 原实现单次 `sock.read()` 后连续 `.get()`, 服务端把 `05 02`/
     * `01 00`/CONNECT 响应这类小报文按 TCP 分段送达时首读只拿到部分字节, 后续 `.get()`
     * 触发 BufferUnderflow → 连接被静默关闭 → VPN 建了但无法联网。
     *
     * 握手期 socket 为**非阻塞**: read()==0 时按 [deadline] 轮询。原先这里是纯阻塞读且
     * 完全没有超时 —— SSH 通道迟迟不回就永久占住一个 SshIoDispatcher 线程 (池上限 128,
     * 耗尽后新连接全被拒), 同时客户端在 SYN_SENT 干等到自己超时。日志里见过 6577ms 的
     * 建连耗时 (已超过 TUN_CONNECT_TIMEOUT_MS=5000), 说明走的正是这条无保护路径。
     */
    private fun readSocksFully(
        sock: SocketChannel,
        dst: ByteBuffer,
        deadline: Long,
    ): Boolean {
        while (dst.hasRemaining()) {
            val n = sock.read(dst)
            if (n < 0) return false
            if (n == 0) {
                if (System.currentTimeMillis() > deadline) return false
                Thread.sleep(2)
            }
        }
        return true
    }

    /**
     * 非阻塞 socket 的完整写: write()==0 时短暂让出, 超过 [deadline] 判失败。
     * 握手请求虽只有几字节, 但非阻塞模式下单次 write 不保证写完 (loopback 一般够,
     * 仍不能假设) —— 部分写会让 SOCKS5 解析器读到半截报文。
     */
    private fun writeSocksFully(
        sock: SocketChannel,
        src: ByteArray,
        deadline: Long,
    ): Boolean {
        val buf = ByteBuffer.wrap(src)
        while (buf.hasRemaining()) {
            val n = sock.write(buf)
            if (n < 0) return false
            if (n == 0) {
                if (System.currentTimeMillis() > deadline) return false
                Thread.sleep(2)
            }
        }
        return true
    }

    /**
     * SOCKS5 方法协商 (RFC 1928 0x02) + 用户名/密码认证 (RFC 1929)。
     * 任一步失败/超时都只记日志并返回 false, 由调用方统一关连接。
     */
    @Suppress("ReturnCount") // 每步 fail-fast 早退并各自记日志, 合并出口会丢掉归因
    private fun socksNegotiate(
        sock: SocketChannel,
        creds: Pair<String, String>,
        conn: TcpConnection,
        deadline: Long,
    ): Boolean {
        val readFully: (ByteBuffer) -> Boolean = { dst -> readSocksFully(sock, dst, deadline) }
        val writeFully: (ByteArray) -> Boolean = { src -> writeSocksFully(sock, src, deadline) }

        if (IS_DEBUG_SOCKS) {
            Log.d("Socks5Proxy", "[socks] conn=${conn.id} writing handshake 05 01 02 to ${conn.dstIp}:${conn.dstPort}")
        }
        if (!writeFully(byteArrayOf(0x05, 0x01, 0x02))) {
            Log.e(TAG, "SOCKS5 handshake write failed for conn=${conn.id}")
            return false
        }
        val handshakeResp = ByteBuffer.allocate(2)
        if (!readFully(handshakeResp)) {
            Log.e(TAG, "SOCKS5 handshake read failed (partial/EOF)")
            VpnController.appLogThrottled(
                "本地代理无响应 · SOCKS 握手读取失败 (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.WARNING,
                throttleKey = "本地代理无响应",
            )
            return false
        }
        handshakeResp.flip()
        val respVer = handshakeResp.get().toInt() and 0xFF
        val respMethod = handshakeResp.get().toInt() and 0xFF
        if (IS_DEBUG_SOCKS) {
            Log.d("Socks5Proxy", "[socks] conn=${conn.id} handshake reply ver=$respVer method=$respMethod")
        }
        if (respVer != 0x05 || respMethod != 0x02) {
            Log.e(TAG, "SOCKS5 handshake failed: ver=$respVer method=$respMethod")
            VpnController.appLogThrottled(
                "本地代理握手被拒 · ver=$respVer method=$respMethod (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.WARNING,
                throttleKey = "本地代理握手被拒",
            )
            return false
        }

        // RFC 1929: VER(1)=0x01 ULEN(1) USER PLEN(1) PASS — 报文长 = 3 + userLen + passLen
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
        if (!writeFully(authReq)) {
            Log.e(TAG, "SOCKS5 auth write failed for conn=${conn.id}")
            return false
        }
        val authResp = ByteBuffer.allocate(2)
        if (!readFully(authResp)) {
            Log.e(TAG, "SOCKS5 auth read failed (partial/EOF)")
            VpnController.appLogThrottled(
                "本地代理无响应 · SOCKS 认证读取失败 (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.WARNING,
                throttleKey = "本地代理无响应",
            )
            return false
        }
        authResp.flip()
        val authVer = authResp.get().toInt() and 0xFF
        val authStatus = authResp.get().toInt() and 0xFF
        if (IS_DEBUG_SOCKS) Log.d("Socks5Proxy", "[socks] conn=${conn.id} auth reply ver=$authVer status=$authStatus")
        if (authVer != 0x01 || authStatus != 0x00) {
            Log.e(TAG, "SOCKS5 auth rejected: ver=$authVer status=$authStatus")
            VpnController.appLogThrottled(
                "本地代理认证被拒 · status=$authStatus (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.WARNING,
                throttleKey = "本地代理认证被拒",
            )
            return false
        }
        return true
    }

    /** 非阻塞 connect 到本地 SOCKS 端口并轮询 finishConnect; 超时返回 false (调用方负责清理)。 */
    private suspend fun awaitLoopbackConnect(
        sock: SocketChannel,
        socksPort: Int,
        deadline: Long,
    ): Boolean {
        if (sock.connect(InetSocketAddress("127.0.0.1", socksPort))) return true
        while (!sock.finishConnect()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(2)
        }
        return true
    }

    /**
     * 通过本地 SOCKS5 代理转发 (保持原有 SOCKS5 握手流程)
     */
    @Suppress("ReturnCount", "LongMethod") // 每条错误路径 fail-fast 早退; 握手→认证→CONNECT 单链路, 拆分需传递全部局部上下文
    private suspend fun forwardThroughLocalSocks(
        conn: TcpConnection,
        plugin: TunnelPlugin,
        socksPort: Int,
    ) {
        // 分阶段健康: 进入隧道转发路径计数 (成功建立在 CONNECT 应答通过后计)
        StageCounters.onForwardAttempt()
        val connKey = IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, conn.srcPort, conn.dstPort)
        val sock = SocketChannel.open()
        // 握手期非阻塞: readFully/writeFully 靠各阶段自己的 deadline 兜底 (见其 KDoc)。
        // 握手完成后 forwardToSocks/回程中继同样依赖非阻塞 write 的 write()==0 背压语义。
        sock.configureBlocking(false)
        if (!awaitLoopbackConnect(sock, socksPort, System.currentTimeMillis() + SOCKS_LOCAL_TIMEOUT_MS)) {
            VpnController.appLogThrottled(
                "本地代理无响应 · 连接本地 SOCKS 端口超时 (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.WARNING,
                throttleKey = "本地代理无响应",
            )
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }

        // SYN-ACK **必须等到通道就绪后再发** (1.0.6 的行为, 后被 early-SYN-ACK 改错):
        // 提前发会让客户端立刻发首批数据, 而此时 socksChannel/tunnelChannel 还没建立,
        // forwardToSocks 的"通道未就绪"检查会把它们全部丢弃 + 不 ACK, 客户端重传到超时放弃 ——
        // 表现为"Play 点安装无反应"且隧道日志一切正常。通道就绪前客户端只会重传 SYN, 不发应用数据。
        // 服务端 banner 不受影响: tunCallback 已提前注册, 且 Socks5ProxyServer 会把 CONNECT 前预读到的
        // banner 存入 pendingConnectData, 握手成功后随首批数据一起下发。
        val handshakeStartedAt = System.currentTimeMillis()

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
                // 远端结束 → 发 FIN+ACK; 本栈主动关闭/回程异常 → 发 RST。
                // 两条都必须接到: 主路径 directRelay=true 时不跑 startRelayFromSocks,
                // 少了这根线远端 EOF 无处上报, 对端正常收尾却收到 RST、或挂到 300s 陈旧清理。
                plugin.registerTargetEofCallback(localPort) { graceful ->
                    closeTcpConnection(connKey, conn, graceful = graceful)
                }
                tunCallbackPlugin = plugin
                directRelay = true
            } catch (e: Exception) {
                Log.w(TAG, "registerTunCallback failed for conn ${conn.id}, falling back to socket relay")
                VpnController.appLogThrottled(
                    "回程回调注册失败 · 降级为 socket 中继 (${conn.dstIp.hostAddress}:${conn.dstPort})",
                    level = LogLevel.WARNING,
                    throttleKey = "回程回调注册失败",
                )
            }
        }

        // SOCKS5 握手: 仅提供用户名/密码认证 (RFC 1929, 0x02); 服务端 fail-closed, 无凭据必失败
        val creds = plugin.socksAuth
        if (creds == null) {
            Log.e(TAG, "SOCKS5 auth credentials missing, refusing connection to ${conn.dstIp}:${conn.dstPort}")
            VpnController.appLog(
                "本地代理配置错误 · SOCKS 认证凭据缺失 (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.WARNING,
            )
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }
        // 握手 + 认证合成一步: 方法协商 (RFC 1928) 与用户名/密码 (RFC 1929) 失败都是
        // "本地代理不可用", 拆出去让 forwardThroughLocalSocks 回到可读的长度。
        if (!socksNegotiate(sock, creds, conn, deadline = System.currentTimeMillis() + SOCKS_LOCAL_TIMEOUT_MS)) {
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }

        // SOCKS5 CONNECT 请求
        val dstHost = conn.dstIp.hostAddress
        val domain = dstHost?.let { dnsInterceptor?.lookupDomain(it) }
        if (domain == null && dstHost != null && VpnController.isFakeIp(conn.dstIp)) {
            // fake IP 无映射: 本地 SOCKS 会秒拒 (Socks5ProxyServer 诊断日志同步可见), 客户端重查 DNS 后恢复
            Log.w(
                TAG,
                "fake IP has no domain mapping, CONNECT falls back to raw IP " +
                    "conn=${conn.id} dst=$dstHost:${conn.dstPort}",
            )
        }
        val connectReq = buildSocks5ConnectRequest(conn.dstIp, conn.dstPort, domain)
        if (!writeSocksFully(sock, connectReq, System.currentTimeMillis() + SOCKS_LOCAL_TIMEOUT_MS)) {
            Log.e(TAG, "SOCKS5 CONNECT write failed for conn=${conn.id}")
            VpnController.appLogThrottled(
                "本地代理无响应 · SOCKS CONNECT 写入失败 (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.WARNING,
                throttleKey = "本地代理无响应",
            )
            sock.close()
            closeTcpConnection(connKey, conn)
            return
        }

        // 服务端 CONNECT 响应为定长 (VER REP RSV ATYP BND.ADDR BND.PORT)，IPv4/错误均为 10 字节；
        // 只处理前 10 字节，跳过按 atyp 计算的剩余段(仅移动 position，不再 .get())。用 allocate(10)
        // 配合 readFully 既避免分片 BufferUnderflow，又不会像原 allocate(32) 那样死等不满。
        val connectResp = ByteBuffer.allocate(10)
        // 这一步等的是 **SSH direct-tcpip 通道建立** (Socks5ProxyServer.connectToTarget 同步等),
        // 耗时天然以秒计, 用独立的 15s 预算而不是复用协商阶段的 2s/5s。
        val connectSentAt = System.currentTimeMillis()
        if (!readSocksFully(sock, connectResp, connectSentAt + SOCKS_CONNECT_REPLY_TIMEOUT_MS)) {
            val waitedMs = System.currentTimeMillis() - connectSentAt
            Log.e(TAG, "SOCKS5 CONNECT read failed (partial/EOF) after ${waitedMs}ms")
            VpnController.appLogThrottled(
                "SSH 通道建立超时 · ${target(conn)} — 等 CONNECT 应答 ${waitedMs}ms 无响应 " +
                    "(预算 ${SOCKS_CONNECT_REPLY_TIMEOUT_MS}ms; SSH 池饱和/远端握手慢)",
                level = LogLevel.WARNING,
                throttleKey = "SSH 通道建立超时",
            )
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
            // 应用内可见: rep=3 是"假 IP 映射失效"的经典现场 (下载卡 0%), 节流防 Play 重试刷屏
            val detail = if (rep == 0x03) "目标映射失效(0x03), DNS 映射可能已被 LRU 回收" else "rep=$rep"
            VpnController.appLogThrottled(
                "SOCKS 连接被拒 · ${conn.dstIp.hostAddress}:${conn.dstPort} — $detail",
                SOCKS_FAIL_LOG_THROTTLE_MS,
                level = LogLevel.WARNING,
                throttleKey = "SOCKS 连接被拒",
            )
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

        // 置 Established + acquire 假 IP 必须与 closeTcpConnection 的 (state=Closed → release)
        // 串在**同一把 conn 锁**内: 握手期间客户端可能已 RST, 若不加锁, close 先跑完
        // (flag=false → 不递减) 然后这里再 acquire, 这条连接永远不会再走到 release 分支
        // → 计数只增不减 → 假 IP 池 (16384) 泄漏打满, 后续 CONNECT 全部 0x03 卡"连接中"。
        synchronized(conn) {
            if (conn.state == TcpConnection.TcpState.Closed) {
                // 握手期间已被关闭: 不能置 Established, 也不能 acquire
                try {
                    sock.close()
                } catch (_: Exception) {
                }
                return
            }
            conn.socksChannel = sock
            conn.state = TcpConnection.TcpState.Established
            // 记一次活动连接: closeTcpConnection 归零时才释放假 IP 映射 (见 releaseUnusedFakeIps)。
            // fakeIpAcquired 必须在 acquire 的同一处置位, 否则未 acquire 的连接也会递减。
            VpnController.fakeIpOrNull(conn.dstIp)?.let {
                acquireFakeIp(it)
                conn.fakeIpAcquired = true
            }
        }
        // 握手耗时告警: SYN-ACK 是等到这里才发的, 客户端全程在 SYN_SENT 里干等。
        // 分页一次开 6-10 条连接时, 建连耗时直接叠加成"点安装无反应"。
        val handshakeMs = System.currentTimeMillis() - handshakeStartedAt
        handshakeCount.incrementAndGet()
        if (handshakeMs >= SLOW_HANDSHAKE_WARN_MS) {
            slowHandshakeCount.incrementAndGet()
            reportSlowHandshakeRate(conn)
            VpnController.appLogThrottled(
                "SOCKS 建连偏慢 · ${target(conn)} — ${handshakeMs}ms " +
                    "(SYN-ACK 延迟发送, 客户端在 SYN_SENT 等待; SSH 池饱和/远端握手慢)",
                level = LogLevel.WARNING,
            )
        }
        // 通道就绪 = 现在才能安全发 SYN-ACK (客户端此前只重传 SYN, 不会发应用数据)
        val synAckPacket = buildSynAckPacket(conn)
        if (synAckPacket != null) {
            tunWriterProvider()?.invoke(synAckPacket)
            conn.synAckWritten = true
        }
        // 握手期间缓存的回程数据 (banner/早到响应) 现在按序下发
        flushPendingReturn(conn)
        // 握手期间缓存的上行数据 (客户端提前发来的请求) 现在送出并补 ACK
        flushPendingUpstream(conn, connKey)
        StageCounters.onForwardEstablished()
        VpnController.appLogThrottled(
            "隧道通道就绪 · ${target(conn)} — " +
                "SSH+SOCKS 建连耗时 ${System.currentTimeMillis() - handshakeStartedAt}ms",
            level = LogLevel.INFO,
        )
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
     *
     * 每段发送前经 [awaitFlowCapacity] 按客户端通告窗口限流 (RFC 793 流控):
     * 窗口耗尽则阻塞本回程线程 (SSH 背压随之传导到远端发送方), 防止超窗注入被
     * 本机内核丢弃后无法重造 —— 本假 TCP 栈没有重传, 丢一包 = 连接永久卡死。
     * IPv6 按 MTU1500 收缩到 1440 (1500-40-20), 避免 1520B 超 MTU 注入。
     */
    private fun writeTcpPayloadToTun(
        conn: TcpConnection,
        payload: ByteArray,
        offset: Int,
        length: Int,
        fromFlush: Boolean = false,
    ) {
        val writer = tunWriterProvider() ?: return
        val end = offset + length
        // 入队判定必须在 pendingReturnLock 内一次性完成 (SYN-ACK 未真正写进 TUN / 队列非空 /
        // flushing 中, 任一成立都要入队)。旧实现在锁外读 pendingReturn.isNotEmpty():
        // flusher 弹出最后一块后中继会看到"空"而直接写 TUN, 抢在 flusher 手里那块更旧的数据
        // 前面 → 回程乱序。fromFlush = flushPendingReturn 自己取出的块: 必须直写,
        // 否则会被自己重新入队 → 死循环。
        // 判据是 synAckWritten 而非 synAckSeq: 后者在组包时就置位 (重传要复用 ISN),
        // 组包→写 TUN 之间仍有一段窗口, 期间直写会抢到 SYN-ACK 前面。
        val queued =
            if (fromFlush) {
                false
            } else {
                synchronized(conn.pendingReturnLock) {
                    if (!conn.synAckWritten || conn.pendingReturnBytes > 0 || conn.pendingReturnFlushing) {
                        if (conn.pendingReturnBytes < MAX_PENDING_RETURN_BYTES) {
                            conn.pendingReturn.addLast(payload.copyOfRange(offset, end))
                            conn.pendingReturnBytes += length.toLong()
                        } else {
                            // 队列满: 静默丢会让客户端无限重传且各计数器全为 0 —— 必须显式上报
                            droppedReturnQueueBytes.addAndGet(length.toLong())
                            reportReturnQueueOverflow(conn, length)
                        }
                        true
                    } else {
                        false
                    }
                }
            }
        // SYN-ACK 还没真正写进 TUN 时客户端仍在 SYN_SENT, 任何回程数据都会被它静默丢弃,
        // 且 serverSeq 若已推进则该段永久丢失 (TcpCloseBannerTest 的 F8 场景: 服务端 banner
        // 早于 CONNECT 应答)。SOCKS 路径下 relay 在 CONNECT 前就启动了, 所以这里必须缓存。
        if (queued) return
        var pos = offset
        val maxSegment = if (conn.dstIp.address.size == 16) MAX_TCP_SEGMENT_V6 else MAX_TCP_SEGMENT
        while (pos < end) {
            val capacity = awaitFlowCapacity(conn)
            if (capacity <= 0) {
                // 静默丢弃是重大盲点: 曾导致"代理从 SSH 读到 3.4MB、TUN 只收到 0.4MB"
                // 而日志无任何异常 (客户端零窗口/连接关闭时回程数据整段消失)。
                droppedDownstreamBytes.addAndGet((end - pos).toLong())
                reportDownstreamDrop(conn, end - pos)
                return
            }
            val chunkLen = minOf(maxSegment, end - pos, capacity)
            val responsePacket = buildTcpResponsePacket(conn, payload, pos, chunkLen)
            pos += chunkLen
            if (responsePacket != null) {
                writer(responsePacket)
                conn.downstreamBytes += chunkLen.toLong()
            } else {
                droppedDownstreamBytes.addAndGet(chunkLen.toLong())
                reportDownstreamDrop(conn, chunkLen)
            }
        }
    }

    /** 回程排队溢出上报: pendingReturn 满 → 该段既没进队列也没写 TUN, 客户端会一直重传。 */
    private fun reportReturnQueueOverflow(
        conn: TcpConnection,
        dropped: Int,
    ) {
        conn.droppedDownstreamBytes += dropped.toLong()
        Log.w(TAG, "pendingReturn overflow conn=${conn.id} dropped=${dropped}B")
        VpnController.appLogThrottled(
            "回程缓存溢出丢弃 · ${target(conn)} — ${dropped}B " +
                "(pendingReturn 已满 ${MAX_PENDING_RETURN_BYTES}B, 累计丢弃 " +
                "${droppedReturnQueueBytes.get()}B; 客户端会重传该段, 隧道吞吐受损)",
            level = LogLevel.WARNING,
            throttleKey = "回程缓存溢出丢弃",
        )
    }

    /** 回程丢弃上报: 零窗口/连接关闭/组包失败导致的数据未能送达客户端。 */
    private fun reportDownstreamDrop(
        conn: TcpConnection,
        dropped: Int,
    ) {
        conn.droppedDownstreamBytes += dropped.toLong()
        VpnController.appLogThrottled(
            "回程数据丢弃 · ${target(conn)} — ${dropped}B " +
                "(state=${conn.state}, 累计连接级 ${conn.droppedDownstreamBytes}B, " +
                "全局 ${droppedDownstreamBytes.get()}B)",
            level = LogLevel.WARNING,
            throttleKey = "回程数据丢弃",
        )
    }

    /** 上行缓存入队; 返回 false = 队列满, 调用方需按丢弃处理 (靠客户端重传兜底)。 */
    private fun bufferPendingUpstream(
        conn: TcpConnection,
        buffer: ByteBuffer,
        payloadStart: Int,
        payloadLength: Int,
    ): Boolean =
        synchronized(conn.pendingUpstreamLock) {
            if (conn.pendingUpstreamBytes >= MAX_PENDING_UPSTREAM_BYTES) return false
            conn.pendingUpstream.addLast(buffer.array().copyOfRange(payloadStart, payloadStart + payloadLength))
            conn.pendingUpstreamBytes += payloadLength.toLong()
            true
        }

    /**
     * 通道就绪后按序下发缓存的上行数据并补 ACK。
     * ACK 记账与正常路径一致 (forwardedBytes += 实发字节), 因此客户端重传的旧段会走 dup 分支,
     * 不会重复转发。写不进去 (write==0) 时把该段放回队首等下次, 不丢数据。
     */
    private fun flushPendingUpstream(
        conn: TcpConnection,
        connKey: Long,
    ) {
        val channel = conn.socksChannel ?: return
        while (true) {
            val data: ByteArray
            synchronized(conn.pendingUpstreamLock) {
                data = conn.pendingUpstream.removeFirstOrNull() ?: return
                conn.pendingUpstreamBytes -= data.size.toLong()
            }
            val buf = ByteBuffer.wrap(data)
            var written = 0
            try {
                while (buf.hasRemaining()) {
                    val n = channel.write(buf)
                    if (n == 0) break
                    written += n
                }
            } catch (e: IOException) {
                Log.w(TAG, "flushPendingUpstream write failed for conn ${conn.id}: ${e.message}")
            }
            if (written < data.size) {
                // 只发了部分: 未发部分放回队首, 且不推进 forwardedBytes (客户端会重传这部分)
                val rest = data.copyOfRange(written, data.size)
                synchronized(conn.pendingUpstreamLock) {
                    conn.pendingUpstream.addFirst(rest)
                    conn.pendingUpstreamBytes += rest.size.toLong()
                }
            }
            if (written > 0) {
                synchronized(conn) { conn.forwardedBytes += written.toLong() }
                sendAckToBrowser(conn, connKey)
                VpnController.appLogThrottled(
                    "上行缓存已补发 · ${target(conn)} — ${written}B${if (written < data.size) " (部分)" else ""}",
                    level = LogLevel.INFO,
                )
            }
        }
    }

    /**
     * SYN-ACK 发出后按序下发缓存的回程数据。
     * 必须在 buildSynAckPacket 写 TUN **之后**调用, 保证 "SYN-ACK 先于所有回程数据"。
     */
    private fun flushPendingReturn(conn: TcpConnection) {
        while (true) {
            val chunk: ByteArray
            synchronized(conn.pendingReturnLock) {
                val head = conn.pendingReturn.removeFirstOrNull()
                if (head == null) {
                    // 队列空 = 已无更旧的数据待发。此刻才允许中继线程直接写 TUN;
                    // 在锁内清标志, 保证"发完最后一块"严格先于"允许直写"。
                    conn.pendingReturnFlushing = false
                    return
                }
                chunk = head
                conn.pendingReturnBytes -= chunk.size.toLong()
                conn.pendingReturnFlushing = true
            }
            writeTcpPayloadToTun(conn, chunk, 0, chunk.size, fromFlush = true)
            if (IS_DEBUG) {
                Log.d(TAG, "flushed buffered return data ${chunk.size}B for conn ${conn.id}")
            }
        }
    }

    /**
     * 等待客户端通告窗口内的可用容量, 返回可发送字节数 (>0), 连接关闭返回 0。
     *
     * 锁纪律 (改动前先读, 违反会死锁):
     * - 谓词计算与 wait 必须同持 [TcpConnection.flowLock], 否则 notify 与等待竞态漏唤醒;
     * - 1s 超时重查是漏通知兜底, 不可移除;
     * - TUN 写入 ([writeTcpPayloadToTun] 的 writer 调用) 严禁在锁内: writeToTun 是
     *   @Synchronized 且可能阻塞, 而 packetLoop 发窗口更新 ACK 前要抢 flowLock ——
     *   持 flowLock 写 TUN 会与 packetLoop 形成锁序死锁 (TUN 满 → packetLoop 卡 → 更满)。
     */
    @Suppress("ReturnCount") // closed/interrupted 各自早退, 合并单出口反而绕
    private fun awaitFlowCapacity(conn: TcpConnection): Int {
        synchronized(conn.flowLock) {
            if (conn.state == TcpConnection.TcpState.Closed) return 0
            var available = flowAvailable(conn)
            var logged = false
            while (available <= 0 && conn.state != TcpConnection.TcpState.Closed) {
                if (!logged) {
                    logged = true
                    // 常开: 窗口耗尽是大流量故障的第一现场, 0% 下载问题靠这条定位
                    Log.w(
                        TAG,
                        "flow window exhausted conn=${conn.id} " +
                            "win=${conn.clientWindowRaw}<<${conn.clientWscaleOffered ?: 0} " +
                            "inflight=${conn.serverSeq - conn.clientAck} " +
                            "ack=${conn.clientAck} seq=${conn.serverSeq}",
                    )
                    VpnController.appLog(
                        "回程窗口耗尽 · ${conn.dstIp.hostAddress}:${conn.dstPort} — " +
                            "对端窗口 ${conn.clientWindowRaw}<<${conn.clientWscaleOffered ?: 0}," +
                            " 在途 ${conn.serverSeq - conn.clientAck}B (大流量下载停滞现场)",
                        level = LogLevel.WARNING,
                    )
                }
                val waitStartNs = System.nanoTime()
                try {
                    conn.flowLock.wait(FLOW_WAIT_TIMEOUT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
                // 闸门阻塞时长累计: 持续增长 = 客户端 ACK 停止回补, 回程整体停摆
                // (与"上行背压丢段"互为因果, 两者一起出现在快照即隧道死锁)。
                // 记**真实**等待时长: 每个到达的 ACK 都会 notifyAll 提前唤醒, 无条件加
                // FLOW_WAIT_TIMEOUT_MS 会把几十毫秒记成 1s, 快照里的这个指标会虚高 ~20×。
                flowBlockedMillis.addAndGet((System.nanoTime() - waitStartNs) / NANOS_PER_MILLI)
                available = flowAvailable(conn)
            }
            // 窗口更新与 closeTcpConnection 几乎同时发生时, 上面的循环会以
            // "state==Closed 但 available>0" 退出 —— 对已关连接返回正容量会让调用方
            // 继续组包写 TUN 并推进 serverSeq (KDoc 承诺连接关闭返回 0)。
            return if (conn.state == TcpConnection.TcpState.Closed) 0 else available
        }
    }

    /**
     * 数据面诊断快照 (周期日志用): 活跃连接数、背压丢段累计、回程闸门累计阻塞时长。
     * 三者共同回答"隧道此刻是否在空转/死锁"。
     */
    fun diagnostics(): String =
        "TCP 连接 ${tcpConnections.size} · 背压丢段 ${droppedByBackpressure.get()}B · " +
            "上行缓存 ${upstreamBufferedBytes.get()}B · 回程闸门阻塞 ${flowBlockedMillis.get() / 1000}s · " +
            "孤儿包 ${droppedOrphanPackets.get()}(${droppedOrphanBytes.get()}B · " +
            "fin ${orphanFinPackets.get()}/rst ${orphanRstPackets.get()}" +
            "/ack ${orphanAckPackets.get()}/data ${orphanDataPackets.get()}) · " +
            "回程丢弃 ${droppedDownstreamBytes.get()}B · 回程缓存溢出 ${droppedReturnQueueBytes.get()}B · " +
            "零回程 ${tunnelBlackHoleConns.get()}" +
            "(秒断 ${tunnelBlackHoleImmediate.get()}/迟断 ${tunnelBlackHoleDelayed.get()})"

    /**
     * 闸门可用容量 = 客户端通告窗口 (含 scale) - 在途字节 (serverSeq - clientAck, u32 回绕安全)。
     * 尚无 ACK 段 (clientAck=0, 握手未完成/banner 注入窗口) → 不限流, 与旧行为一致 (宁开勿卡)。
     * 在途 > 2^31 视为 ack/seq 状态异常 → 按已全确认处理 (闸门只防丢不防错)。
     * 结果 ≤ 65535<<14 ≈ 1.07GB, Int 安全。
     */
    private fun flowAvailable(conn: TcpConnection): Int {
        if (conn.clientAck == 0L) return Int.MAX_VALUE
        val inFlight = (conn.serverSeq - conn.clientAck) and UINT32_MASK
        val sane = if (inFlight >= MAX_INFLIGHT_SANITY) 0L else inFlight
        val window = conn.clientWindowRaw.toLong() shl (conn.clientWscaleOffered ?: 0)
        return (window - sane).coerceAtLeast(0).toInt()
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
            var cleanEof = false
            try {
                while (socksChannel.isOpen && conn.state == TcpConnection.TcpState.Established) {
                    buffer.clear()
                    val read = socksChannel.read(buffer)
                    if (read == -1) {
                        cleanEof = true
                        break
                    }
                    if (read > 0) {
                        buffer.flip()
                        val payload = ByteArray(read)
                        buffer.get(payload)
                        writeTcpPayloadToTun(conn, payload, 0, payload.size)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "SOCKS5 relay read ended: ${e.message}")
                VpnController.appLogThrottled(
                    "回程中继异常终止 · 本地代理 → 浏览器 (${conn.dstIp.hostAddress}:${conn.dstPort}) — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "回程中继异常终止",
                )
            } finally {
                closeTcpConnection(connKey, conn, graceful = cleanEof, reason = "远端 EOF")
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
            VpnController.appLog(
                "SSH 通道连接失败 · ${plugin.id} (${conn.dstIp.hostAddress}:${conn.dstPort})",
                level = LogLevel.ERROR,
            )
            channel.disconnect()
            // F6: 发 RST + 移除条目, 允许同五元组新 SYN 重建
            closeTcpConnection(connKey, conn)
            return
        }

        // 置 Established 必须与 closeTcpConnection 的 (state=Closed) 同持 conn 锁
        // (握手期间客户端可能已 RST), 见 forwardThroughLocalSocks establish 处注释。
        synchronized(conn) {
            if (conn.state == TcpConnection.TcpState.Closed) {
                channel.disconnect()
                return
            }
            conn.tunnelChannel = channel
            conn.state = TcpConnection.TcpState.Established
        }

        val synAckPacket = buildSynAckPacket(conn)
        if (synAckPacket != null) {
            tunWriterProvider()?.invoke(synAckPacket)
            conn.synAckWritten = true
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
            var cleanEof = false
            try {
                while (channel.isConnected && conn.state == TcpConnection.TcpState.Established) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        cleanEof = true
                        break
                    }
                    if (read > 0) {
                        writeTcpPayloadToTun(conn, buffer, 0, read)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Tunnel relay read ended: ${e.message}")
                VpnController.appLogThrottled(
                    "回程中继异常终止 · 隧道 → 浏览器 (${conn.dstIp.hostAddress}:${conn.dstPort}) — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "回程中继异常终止",
                )
            } finally {
                closeTcpConnection(connKey, conn, graceful = cleanEof, reason = "远端 EOF")
            }
        }
    }

    /**
     * 本端回包的 ack 号 = 客户端下一个**未被确认**的序号。
     * = ISN +1(占用 SYN) + forwardedBytes(已转发载荷) + clientFinSeen(客户端 FIN 占用的那一个)。
     *
     * 三个组包函数 (数据 / 纯 ACK / 关闭) 必须共用它, 否则会出现"数据包 ack 覆盖了 FIN、
     * 关闭包又退回去"的矛盾确认, 客户端据此重传。见 [TcpConnection.clientFinSeen]。
     */
    private fun browserAckNumber(conn: TcpConnection): Long =
        (conn.browserSeq + 1 + conn.forwardedBytes + if (conn.clientFinSeen) 1L else 0L) and UINT32_MASK

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
            val ackNum = browserAckNumber(conn)

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
            VpnController.appLogThrottled(
                "组包失败 · TCP 回程响应 — ${e::class.simpleName}: ${e.message} (数据被丢弃)",
                level = LogLevel.ERROR,
                throttleKey = "组包失败",
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

            if (conn.synAckSeq >= 0) {
                // 重传: 复用原 ISN, 绝不再次推进 serverSeq (客户端会丢弃 seq 变化的 SYN-ACK)
                return buildSynAckWithSeq(conn, conn.synAckSeq, ackNum)
            }
            conn.synAckSeq = seqNum
            conn.serverSeq = (seqNum + 1) and UINT32_MASK

            return buildSynAckWithSeq(conn, seqNum, ackNum)
        } catch (e: Exception) {
            Log.e(TAG, "buildSynAckPacket failed: ${e::class.simpleName}: ${e.message}", e)
            VpnController.appLogThrottled(
                "组包失败 · SYN-ACK — ${e::class.simpleName}: ${e.message}",
                level = LogLevel.ERROR,
                throttleKey = "组包失败",
            )
            return null
        }
    }

    /**
     * 组装 SYN-ACK 包 (seq/ack 由调用方给定, 重传时复用原 ISN, 见 [buildSynAckPacket])。
     * TCP 选项: MSS 恒有; Window Scale 仅当客户端 SYN 提供时回显 (RFC 7323 协商,
     * shift=0 —— 本端从不缩放自己的窗口字段, 但回显使客户端能启用它自己声明的缩放,
     * 客户端通告窗口从 64KB 封顶解放到 MB 级)。SACK 不回显 (从不发送 SACK 块)。
     */
    private fun buildSynAckWithSeq(
        conn: TcpConnection,
        seqNum: Long,
        ackNum: Long,
    ): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16
            val wscale = conn.clientWscaleOffered
            val tcpHeaderLen = if (wscale != null) 28 else 24
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

            // TCP Header: SYN+ACK (dataOffset = tcpHeaderLen/4)
            packet.putShort(srcPort.toShort())
            packet.putShort(dstPort.toShort())
            packet.putInt(seqNum.toInt())
            packet.putInt(ackNum.toInt())
            packet.putShort(((tcpHeaderLen / 4) shl 12 or 0x12).toShort()) // SYN+ACK
            packet.putShort(65535.toShort())
            packet.putShort(0)
            packet.putShort(0)
            // 选项: [MSS] (+ [NOP WScale 0] 当客户端提供 WS)
            val mss = if (isIPv6) MAX_TCP_SEGMENT_V6 else MAX_TCP_SEGMENT
            packet.put(0x02)
            packet.put(0x04)
            packet.putShort(mss.toShort())
            if (wscale != null) {
                packet.put(0x01) // NOP (对齐惯例)
                packet.put(0x03)
                packet.put(0x03)
                packet.put(0x00) // WScale shift = 0
            }

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
            Log.e(TAG, "buildSynAckWithSeq failed: ${e::class.simpleName}: ${e.message}", e)
            VpnController.appLogThrottled(
                "组包失败 · SYN-ACK — ${e::class.simpleName}: ${e.message}",
                level = LogLevel.ERROR,
                throttleKey = "组包失败",
            )
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
            val ackNum = browserAckNumber(conn)
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
            VpnController.appLogThrottled(
                "组包失败 · ACK — ${e::class.simpleName}: ${e.message}",
                level = LogLevel.ERROR,
                throttleKey = "组包失败",
            )
            return null
        }
    }

    /**
     * 关闭控制包 (RST+ACK / FIN+ACK): seq = 本端未用序号, ack = 浏览器已发全部数据+1,
     * 两者语义与 TCP 一致; 区别仅在标志位 — RST 让对端立刻 abort, FIN 是正常收尾。
     */
    private fun buildClosePacket(
        conn: TcpConnection,
        flagsWord: Int,
    ): ByteArray? {
        try {
            val srcPort = conn.dstPort
            val dstPort = conn.srcPort
            val srcIp = conn.dstIp.address
            val dstIp = conn.srcIp.address
            val isIPv6 = srcIp.size == 16

            val seqNum = conn.serverSeq
            val ackNum = browserAckNumber(conn)
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
            packet.putShort(flagsWord.toShort())
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
                Log.d(
                    TAG,
                    "close packet flags=0x${flagsWord.toString(16)} ${totalLen}B " +
                        "for conn ${conn.id} ${conn.srcIp.hostAddress}:${conn.srcPort}",
                )
            }
            return packet.array()
        } catch (e: Exception) {
            Log.e(TAG, "buildClosePacket failed: ${e::class.simpleName}: ${e.message}", e)
            VpnController.appLogThrottled(
                "组包失败 · 关闭包 — ${e::class.simpleName}: ${e.message}",
                level = LogLevel.ERROR,
                throttleKey = "组包失败",
            )
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
            // 缓存而不是丢弃: 丢弃 + 不 ACK 会让客户端按 RTO 无限重传 (实测 1 分钟 5MB 上行 /
            // 147KB 实际送出, 表现为 Play 点下载完全无反应)。队列满才真丢。
            val buffered = bufferPendingUpstream(conn, buffer, payloadStart, payloadLength)
            upstreamBufferedBytes.addAndGet(if (buffered) payloadLength.toLong() else 0L)
            VpnController.appLogThrottled(
                "上行等待通道 · ${target(conn)} — " +
                    "(state=${conn.state}, 队列 ${conn.pendingUpstreamBytes}B) ${payloadLength}B" +
                    if (buffered) " 已缓存" else " 队列满·丢弃等重传",
                windowMs = UPSTREAM_WAIT_LOG_THROTTLE_MS,
                level = LogLevel.INFO,
                throttleKey = "上行等待通道",
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
                VpnController.appLogThrottled(
                    "上行转发失败 · ${conn.dstIp.hostAddress}:${conn.dstPort} — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "上行转发失败",
                )
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
                    // 关键监控点: 丢段只靠客户端重传, 是"上行暴涨/下载卡死"的唯一早期信号。
                    // 曾经只有 IS_DEBUG 日志, 导致"上行 16MB / 下行 0.4MB"的重传风暴完全不可见。
                    val dropped = buffer.remaining()
                    val total = droppedByBackpressure.addAndGet(dropped.toLong())
                    VpnController.appLogThrottled(
                        "上行背压丢段 · ${conn.dstIp.hostAddress}:${conn.dstPort} — 本次 ${dropped}B " +
                            "等待客户端重传 (累计 ${total}B)",
                        level = LogLevel.WARNING,
                        throttleKey = "上行背压丢段",
                    )
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
            VpnController.appLogThrottled(
                "上行转发失败 · ${conn.dstIp.hostAddress}:${conn.dstPort} — ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "上行转发失败",
            )
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
            VpnController.appLogThrottled(
                "回程 ACK 写入 TUN 失败 — ${e::class.simpleName}: ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "回程 ACK 写入 TUN 失败",
            )
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
        graceful: Boolean = false,
        reason: String = "",
    ) {
        // 幂等占位: 关闭可能由多条路径并发触发 (远端 clean EOF 回调 / 回程 relay finally /
        // 上行写失败 / 陈旧清理)。state==Closed 只在本函数内置, 且写入与 releaseUnusedFakeIps
        // 同持 conn 锁 —— 这里也必须持锁读, 否则会漏判正在关闭的那一次。
        // 不早退会: 重复计黑洞 + 重复发关闭包; 更关键的是下面的 map 删除若已被同五元组
        // 重建换过新连接, 会把新建的那条摘掉 → 后续包全走孤儿分支, 连接永久卡死。
        synchronized(conn) {
            if (conn.state == TcpConnection.TcpState.Closed) return
        }
        // 生命周期日志: 连接为什么消失是排障必需信息 (曾只看到"同五元组反复重建"却不知原因)。
        // 上行字节取 forwardedBytes (已被隧道接收并转发的), 下行无逐连接计数故只报存活时长。
        val why =
            when {
                reason.isNotEmpty() -> reason
                graceful -> "远端正常关闭(clean EOF)"
                !notifyBrowser -> "客户端关闭(RST/FIN)"
                else -> "内部失败/超时"
            }
        VpnController.appLogThrottled(
            "TCP 连接结束 · ${target(conn)} — $why · " +
                "上行 ${conn.forwardedBytes}B / 下行 ${conn.downstreamBytes}B · " +
                "存活 ${(System.currentTimeMillis() - conn.createdAt) / 1000}s",
            level = LogLevel.INFO,
        )
        reportTunnelBlackHole(conn, why)
        // F6: 通知浏览器使其立即 abort 而非挂死; 移除条目后同五元组新 SYN 自动重建。
        // graceful=true (远端正常 EOF) 发 FIN+ACK 而非 RST: 对端是正常收尾的应用,
        // RST 会让它看到 connection reset; 出错/拒绝/陈旧/断线清理仍走 RST 立即 abort。
        if (notifyBrowser && conn.state != TcpConnection.TcpState.Closed) {
            try {
                val flags = if (graceful) TCP_FLAGS_FIN_ACK else TCP_FLAGS_RST_ACK
                val closePacket = buildClosePacket(conn, flags)
                if (closePacket != null) tunWriterProvider()?.invoke(closePacket)
            } catch (e: Exception) {
                Log.e(TAG, "send close packet on close failed: ${e::class.simpleName}: ${e.message}")
                VpnController.appLogThrottled(
                    "关闭包发送失败 · conn=${conn.id} — ${e.message} (对端可能悬挂)",
                    level = LogLevel.WARNING,
                    throttleKey = "关闭包发送失败",
                )
            }
        }
        // 与 forwardThroughLocalSocks 的 (check → establish → acquire) 串在同一把 conn 锁内,
        // 否则"close 先跑 release、establish 再 acquire"会让计数永久泄漏 (见 acquire 处注释)。
        synchronized(conn) {
            conn.state = TcpConnection.TcpState.Closed
            releaseUnusedFakeIps(conn)
        }
        // 唤醒窗口闸门上的回程线程 (先置 volatile state 再 notify; 锁纪律见 awaitFlowCapacity)
        synchronized(conn.flowLock) { conn.flowLock.notifyAll() }
        // identity 删除: 同五元组可能已被 (close 旧 → create fresh) 换成新对象,
        // 1 参 remove(key) 会误删新建的那条 (见 forwardThroughLocalSocks 的重建分支)。
        tcpConnections.remove(key, conn)
        if (conn.socksLocalPort > 0) {
            tunCallbackPlugin?.removeTunCallback(conn.socksLocalPort)
            tunCallbackPlugin?.removeTargetEofCallback(conn.socksLocalPort)
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

    /**
     * 假 IP 生命周期: 连接建立时 [acquireFakeIp] 记一次活动连接, 关闭时归还,
     * **归零才真正释放 DNS 映射**。
     *
     * 不能在单条连接关闭时就删映射: 同一域名常有并发 TCP (HTTP/2 多路复用),
     * 先关的那条会删掉仍在用的映射 → 后续 CONNECT 拿到 SOCKS 0x03 被拒,
     * 表现为"下载卡在连接中"。也不能不删: 映射只增不减会耗尽假 IP 池
     * (16384 条上限, 长会话必然打满)。
     */
    private fun acquireFakeIp(ip: String) {
        fakeIpActiveConns.merge(ip, 1) { a, b -> a + b }
    }

    /**
     * **隧道黑洞检测**: 通道建立成功, 客户端也把数据交进了隧道 (上行 > 0), 但一个字节的
     * 响应都没回来 (下行 == 0) 就关闭。
     *
     * 这是最难排的一类: `隧道通道就绪` 正常打印、所有丢弃计数为 0、SSH 侧看不出异常,
     * 现场就是 `TCP 连接结束 · <域名> · 客户端关闭(RST/FIN) · 上行 517B / 下行 0B · 存活 0s`
     * —— 517B 恰是 TLS ClientHello。客户端等不到 ServerHello 就放弃 (Play 的连接预热/
     * 探测请求大量如此), 表现为"推荐页只能加载一页"。
     *
     * 归因需区分两种: 客户端秒断 (存活 <1s, 主动放弃) 与我们中途丢 (存活更久)。
     * 前者通常是远端/客户端侧行为, 后者才指向本栈丢包 —— 计数与快照分开上报。
     */
    private fun reportTunnelBlackHole(
        conn: TcpConnection,
        why: String,
    ) {
        if (conn.forwardedBytes <= 0 || conn.downstreamBytes > 0) return
        val lifetimeMs = System.currentTimeMillis() - conn.createdAt
        tunnelBlackHoleConns.incrementAndGet()
        val immediate = lifetimeMs < BLACK_HOLE_IMMEDIATE_MS
        if (immediate) tunnelBlackHoleImmediate.incrementAndGet() else tunnelBlackHoleDelayed.incrementAndGet()
        // 存活越短越像"客户端主动放弃", 需要连同是否已就绪一起看才有意义
        VpnController.appLogThrottled(
            "隧道零回程 · ${target(conn)} — 收了 ${conn.forwardedBytes}B 上行, 下行 0B, " +
                "存活 ${lifetimeMs}ms, 关闭原因 $why " +
                "(${if (immediate) "客户端等不到响应即放弃" else "本栈中途丢, 非客户端主动断开"})",
            BLACK_HOLE_LOG_THROTTLE_MS,
            level = LogLevel.WARNING,
            throttleKey = "隧道零回程",
        )
    }

    /**
     * 超过该阈值的慢握手占比过高时上报一次 (分页一次开 6-10 条连接, 全部变慢即整体卡顿)。
     * 只统计不阻断: 慢本身不一定错 (远端 SSH 握手开销), 但它是"点安装无反应"的上游成因。
     */
    private fun reportSlowHandshakeRate(conn: TcpConnection) {
        val total = handshakeCount.get()
        if (total % SLOW_HANDSHAKE_SAMPLE_EVERY != 0L) return
        val rate = slowHandshakeCount.get() * 100 / total.coerceAtLeast(1)
        if (rate < SLOW_HANDSHAKE_RATE_PCT) return
        VpnController.appLogThrottled(
            "建连偏慢占比偏高 · ${slowHandshakeCount.get()}/$total ($rate%) " +
                "· 最近 ${target(conn)} — SYN-ACK 延迟到通道就绪, 客户端在 SYN_SENT 等待",
            level = LogLevel.WARNING,
            throttleKey = "建连偏慢占比偏高",
        )
    }

    private fun releaseUnusedFakeIps(conn: TcpConnection) {
        // 只有真正 acquire 过才递减 (见 TcpConnection.fakeIpAcquired): SYN 期/SOCKS 各失败分支
        // 也会走 closeTcpConnection, 无条件递减会把同域名活跃连接的计数扣成 0 → 误删映射。
        if (!conn.fakeIpAcquired) return
        conn.fakeIpAcquired = false
        val ip = VpnController.fakeIpOrNull(conn.dstIp) ?: return
        // compute 保留 0 值条目 (返回 null 会让 CHM 直接删条目, 后面的 remove(ip,0) 恒 false
        // → releaseFakeIp 成死代码, 映射永不释放, 只剩 30s LRU trim 兜底)。
        fakeIpActiveConns.compute(ip) { _, cur -> if (cur == null || cur <= 1) 0 else cur - 1 }
        // 原子判定"是否由本次归零": 条目已是 0 才移除成功; 并发 acquire 会把它改回 ≥1 → 失败,
        // 于是不删仍在使用的映射。
        if (fakeIpActiveConns.remove(ip, 0)) {
            dnsInterceptor?.releaseFakeIp(ip)
        }
    }

    /**
     * 会话断开时释放全部连接 (F6-4): 只清 map 是不够的 — 回程线程可能正停在窗口闸门的
     * `flowLock.wait(1000)` 上, state 永不置 Closed 就永不 notifyAll, 线程被永久搁浅
     * (SshIoDispatcher 上限 128, 反复断连会耗尽导致新连接全被拒)。逐个走 closeTcpConnection
     * (关通道 + Closed + notifyAll); TUN 正在拆卸, 不再向浏览器发包。
     */
    fun reset() {
        tcpConnections.entries.toList().forEach { (key, conn) ->
            closeTcpConnection(key, conn, notifyBrowser = false)
        }
        tcpConnections.clear()
        fakeIpActiveConns.clear()
    }

    /**
     * 判断 `seq` 是否比 `expected` 更旧 (u32 序列号空间, RFC 1982 序列号算术)。
     *
     * 不能用 `<` 直接比较: 两个值都已掩码到 [0, 2^32), 一旦回绕
     * (expected=100, seq=4294967200) 用 `<` 会判成"更新的乱序段"而丢掉真正的重传段,
     * 客户端窗口不再被 ACK 推进 → 卡到超时。
     */
    private fun seqIsOlder(
        seq: Long,
        expected: Long,
    ): Boolean {
        val diff = (seq - expected) and UINT32_MASK
        // 落在窗口后半段 = 更旧 (含 diff == 0, 精确匹配已在调用方先处理)
        return diff >= (1L shl 31)
    }

    /** 供单测使用: 当前仍在活动的某假 IP 连接数 (归零即映射已释放)。 */
    internal fun fakeIpActiveConnCount(ip: String): Int = fakeIpActiveConns[ip] ?: 0

    /** 供单测使用: 按客户端源端口关闭连接 (避免测试构造 RST 报文)。 */
    internal fun closeForTest(connSrcPort: Int) {
        tcpConnections.entries.firstOrNull { it.value.srcPort == connSrcPort }?.let { (k, c) ->
            closeTcpConnection(k, c, notifyBrowser = false)
        }
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
