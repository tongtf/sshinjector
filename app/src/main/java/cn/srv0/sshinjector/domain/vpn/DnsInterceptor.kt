package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Opcode
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DNS 拦截与远端解析
 *
 * 拦截 VPN 接口中的 UDP:53 流量
 * REMOTE 模式：解析 DNS 查询，通过 SSH 隧道 (SOCKS5 TCP) 发送到 SSH 服务器本地 DNS 解析
 * SYSTEM 模式：绕过 VPN 使用保护 socket 发送到系统 DNS
 * 接收响应后封装回 TUN 接口
 */
@Singleton
class DnsInterceptor
    @Inject
    constructor() {
        companion object {
            private const val TAG = "DnsInterceptor"
            private const val CONNECT_TIMEOUT = 5000

            // IPv4 假 IP 池: 198.18.0.0/15 (RFC 2544 benchmarking range, 不会与真实 IP 冲突)
            private const val FAKE_IP_BASE = (198 shl 24) or (18 shl 16) // 198.18.0.0
            private const val FAKE_IP_MAX = (198 shl 24) or (19 shl 16) or 0xFFFF // 198.19.255.255
            private const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL

            // IPv6 假 IP 池: fd00::2 ~ fd00::ffff:ffff (VPN 网关 fd00::1/64 范围内)
            // 用递增计数器生成 fd00::N 形式的假 IPv6 地址

            // 映射表大小限制，防止长时间运行 OOM (LRU 淘汰, 见 LruStringMap)
            private const val MAX_IP_DOMAIN_MAP_SIZE = 16384

            // 真实 IP → 域名 (SYSTEM / DOMAIN_SPLIT 未命中路径的 DNS 回包) 只用于
            // 连接日志反查域名, 单独一张小表: 与假 IP 表共用 16384 会被真实 IP 撑满并
            // 逐出假 IP 映射 → 后续假 IP CONNECT 拿不到域名 → SOCKS 0x03 → 下载卡"连接中"
            private const val MAX_REAL_IP_MAP_SIZE = 8192
            private const val MAX_DOMAIN_IP_MAP_SIZE = 16384
            private const val MAX_DNS_CACHE_SIZE = 512

            /**
             * 假 IP 应答 TTL (秒)。必须短: 进程/会话重启后映射表清空, 而客户端 (Play 等)
             * 仍缓存着旧假 IP 并直接 CONNECT — 收到 SOCKS 0x03 后不会重新解析, 不会重试下载。
             * TTL=300 时该故障形态 ("点下载无反应") 会持续最长 5 分钟; 5s 把窗口压到秒级。
             */
            private const val FAKE_IP_TTL_SECONDS = 5L

            /** DNS 缓存条目策略标签: 本次查询走隧道 (回假 IP)。 */
            private const val CACHE_TAG_TUNNEL = "t"

            /** DNS 缓存条目策略标签: 本次查询走直连 (回真实 IP)。 */
            private const val CACHE_TAG_DIRECT = "d"

            /**
             * 连接全部关闭后的假 IP 摘除宽限期 (毫秒)。
             *
             * "活动连接数归零" ≠ "客户端不会再用这个 IP": 客户端自己 (App 内的 DNS 缓存) 常常
             * 不遵守我们应答里的 5s TTL, 现场实测会在归零后 ~6s 拿同一张假 IP 再发起新连接。
             * 归零瞬间就删映射 → 这些重试全部拿到 SOCKS 0x03 秒拒 (日志: 假 IP 映射失效),
             * 而客户端不会重新查 DNS 就直接重试 → 反复失败直到它自己的缓存过期,
             * 现场表现为"下载卡连接中 / 点安装无反应"。宽限期内映射完全可用,
             * 期满由 30s cleanupScheduler 摘除 (另有 16384 LRU 兜底, 池不会被撑满)。
             * 语义 = "最后一次连接关闭后再保留 60s", 取 60s 覆盖 6s 实测值留足余量。
             */
            private const val FAKE_IP_RELEASE_GRACE_MS = 60_000L

            // 定期清理过期待处理查询
            private const val PENDING_QUERY_TIMEOUT_MS = 30_000L
        }

        enum class DnsTransport {
            REMOTE, // 全部流量走 TCP over SOCKS5 (SSH 隧道)
            SYSTEM, // 系统默认: DNS 用 DHCP 获取的 DNS，所有流量走物理网卡
            WHITELIST, // 白名单: 白名单应用走 REMOTE，其余走 SYSTEM
            DOMAIN_SPLIT, // 域名分流: 命中域名列表 → 假 IP(走隧道)，未命中 → 系统 DNS(直连)
        }

        // 域名分流模式使用的列表管理器
        @Volatile private var domainListManager: DomainListManager? = null

        fun setDomainListManager(manager: DomainListManager) {
            domainListManager = manager
        }

        // S5: IPv6 总开关 (connect 时注入); 关闭时 AAAA 回空应答, TUN 侧丢弃 v6 包
        @Volatile private var enableIPv6 = true

        fun setEnableIPv6(enable: Boolean) {
            enableIPv6 = enable
        }

        // R5: daemon + 空闲回收 — 等价 newFixedThreadPool(2), 进程退出不被探测线程挂住
        private val executor =
            java.util.concurrent
                .ThreadPoolExecutor(
                    2,
                    2,
                    60L,
                    java.util.concurrent.TimeUnit.SECONDS,
                    java.util.concurrent.LinkedBlockingQueue(),
                    { r -> Thread(r, "dns-probe").apply { isDaemon = true } },
                ).apply { allowCoreThreadTimeOut(true) }
        private val pendingQueries = ConcurrentHashMap<Int, DnsPendingQuery>()
        private val dnsCache = ConcurrentHashMap<String, CacheEntry>()

        // F12-d: DNS 缓存/假 IP 映射键的唯一构造 — Name.toString(true) 无尾点 + "." + 类型;
        // 读 (查询命中) 与写 (真实响应/假 IP 两处) 必须一致, 否则缓存永远 miss
        private fun dnsCacheKey(
            q: org.xbill.DNS.Name,
            type: Int,
        ) = "${q.toString(true)}.$type"

        /**
         * 缓存条目键 = 策略标签 + [dnsCacheKey]。缓存里装的是"假 IP"还是"真实 IP"完全由
         * **本次查询走隧道还是直连**决定 (DNS 模式 + 域名列表成员), 与域名本身无关。
         * 键带上标签后, 模式切换/列表变更自动 miss —— 此前缓存无任何失效入口,
         * 用户改完设置要在整个 TTL 内继续拿到旧策略的应答。
         * 写入侧固定标签: 真实应答永远 [CACHE_TAG_DIRECT] (onDnsResponse), 假 IP 应答永远
         * [CACHE_TAG_TUNNEL] (handleRemoteDnsFakery); 读取侧按当前策略算 → 判定一变即 miss。
         */
        private fun cacheEntryKey(
            dnsKey: String,
            tag: String,
        ) = "$tag|$dnsKey"

        /** 当前策略下该查询应走隧道还是直连 (仅用于读取侧缓存键, 与 useSystemDns 同判定)。 */
        private fun cachePolicyTag(question: Record): String =
            when (transportMode) {
                DnsTransport.SYSTEM -> CACHE_TAG_DIRECT
                DnsTransport.DOMAIN_SPLIT ->
                    if (domainListManager?.matches(question.name.toString(true)) == true) {
                        CACHE_TAG_TUNNEL
                    } else {
                        CACHE_TAG_DIRECT
                    }
                else -> CACHE_TAG_TUNNEL
            }

        private fun cacheDns(
            key: String,
            entry: CacheEntry,
        ) {
            dnsCache[key] = entry
            if (dnsCache.size > MAX_DNS_CACHE_SIZE) {
                dnsCache.entries.removeIf { it.value.expireAt < System.currentTimeMillis() }
                if (dnsCache.size > MAX_DNS_CACHE_SIZE) {
                    val iter = dnsCache.entries.iterator()
                    var removed = 0
                    val toRemove = dnsCache.size - MAX_DNS_CACHE_SIZE
                    while (iter.hasNext() && removed < toRemove) {
                        iter.next()
                        iter.remove()
                        removed++
                    }
                }
            }
        }

        private val pendingResponses =
            kotlinx.coroutines.channels.Channel<DnsResponse>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        private val queryIdCounter = AtomicInteger(0)

        // 当前传输模式
        @Volatile private var transportMode = DnsTransport.REMOTE

        // SYSTEM 模式使用的系统真实 DNS 服务器列表
        @Volatile private var systemDnsServers: List<String> = emptyList()

        val queriesIntercepted =
            java.util.concurrent.atomic
                .AtomicLong(0)
        val queriesResolved =
            java.util.concurrent.atomic
                .AtomicLong(0)
        val cacheHits =
            java.util.concurrent.atomic
                .AtomicLong(0)
        val cacheMisses =
            java.util.concurrent.atomic
                .AtomicLong(0)

        // 广告拦截命中计数 (命中即回 0.0.0.0, 不进缓存 / 不解析)
        val adBlockCount =
            java.util.concurrent.atomic
                .AtomicLong(0)

        // 定期清理过期待查 (R5: daemon, 不挂进程退出)
        private val cleanupScheduler =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor { r -> Thread(r, "dns-cleanup").apply { isDaemon = true } }

        init {
            cleanupScheduler.scheduleAtFixedRate({
                val now = System.currentTimeMillis()
                // 清理超时 pendingQueries
                pendingQueries.entries.removeIf { (_, query) ->
                    now - query.timestamp > PENDING_QUERY_TIMEOUT_MS
                }
                // 清理过期缓存
                dnsCache.entries.removeIf { it.value.expireAt < now }
                // 宽限期满的假 IP 才真正摘除 (归零即删会让客户端的重试拿 0x03, 见 FAKE_IP_RELEASE_GRACE_MS)
                sweepPendingFakeIpRelease(now)
                // 映射表超限时按 LRU 淘汰最久未访问条目 (不随机清半, 防止客户端
                // 本地缓存的假 IP 失去映射 → 远端黑洞; 部分表失效互相独立, 下次查询即重建)
                ipToDomain.trim()
                realIpToDomain.trim()
                domainToIp.trim()
                // 驱逐后把假 IP 计数器对齐到剩余映射的最大值 (updateAndGet 只升不降, 防竞态回拨)
                resetFakeIpCounters()
            }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
        }

        /**
         * 把假 IP 计数器重置到剩余映射中的最大值 (按序分配, 未分配区间可复用)。
         */
        private fun resetFakeIpCounters() {
            var max4 = 0L
            var max6 = 0L
            for (ip in domainToIp.values()) {
                val v4 = ip.split(".").mapNotNull { it.toIntOrNull() }
                if (v4.size == 4) {
                    val n =
                        (v4[0].toLong() shl 24) or
                            (v4[1].toLong() shl 16) or
                            (v4[2].toLong() shl 8) or
                            v4[3].toLong()
                    if (n > max4) max4 = n
                } else {
                    val hex = ip.substringAfterLast(':').toIntOrNull(16)
                    if (hex != null && hex > max6) max6 = hex.toLong()
                }
            }
            // 重置到剩余映射的最大值, 但不下探到分配基线以下:
            // IPv4 不低于 198.18.0.0 (段外会破坏 isFakeIp/路由判定),
            // IPv6 不低于 fd00::2 (fd00::1 是 VPN 网关)
            // 用 updateAndGet 取当前值与目标值较大者, 避免与数据包线程的
            // incrementAndGet 竞态导致计数器回拨、假 IP 重用。
            fakeIpCounter.updateAndGet { cur -> clampFakeIpCounter(cur, max4) }
            fakeIpv6Counter.updateAndGet { cur -> maxOf(cur.toLong(), max6, 2L).toInt() }
        }

        /**
         * IPv4 假 IP 计数器 clamp: 取当前值 / 剩余映射最大值 / 分配基址 三者较大者。
         *
         * **必须按无符号比较** —— 198.18.0.0 的高位是 1, 作为 [Int] 是负数:
         * `FAKE_IP_BASE.toLong()` / `cur.toLong()` 会符号扩展成负 long, 而 `maxSeen`
         * 是从字节拼出的正 long。`domainToIp` 里没有 IPv4 条目 (`maxSeen == 0`) 时,
         * 带符号的 `maxOf(负, 0, 负)` 恒等于 **0** → 计数器被打回 0 → 下一次分配
         * 拿到 0.0.0.1, `rawIp > FAKE_IP_MAX` 判为"池耗尽", REMOTE 模式所有 A 记录
         * 解析失败且**不自愈** (分配失败 → domainToIp 永远没有 IPv4 → 下轮再清零)。
         * 单测 `DnsInterceptorTest.resetFakeIpCounterNeverFallsBelowBase` 锁死此语义。
         */
        internal fun clampFakeIpCounter(
            cur: Int,
            maxSeen: Long,
        ): Int = maxOf(cur.toLong() and UNSIGNED_INT_MASK, maxSeen, FAKE_IP_BASE.toLong() and UNSIGNED_INT_MASK).toInt()

        data class DnsPendingQuery(
            val queryId: Int,
            val originalQueryId: Int,
            val srcIp: InetAddress,
            val srcPort: Int,
            val dstIp: InetAddress?,
            val dstPort: Int,
            val question: Record,
            val timestamp: Long = System.currentTimeMillis(),
        )

        data class CacheEntry(
            val records: List<Record>,
            val expireAt: Long,
        )

        data class DnsResponse(
            // DNS 服务器 IP (响应来源)
            val srcIp: InetAddress,
            // VPN 客户端 IP (响应目标)
            val dstIp: InetAddress,
            // VPN 客户端源端口
            val dstPort: Int,
            val data: ByteArray,
        )

        // IP → 域名映射: DNS 解析时建立，PacketProcessor 用于 SOCKS5 CONNECT 域名模式
        internal val ipToDomain = LruStringMap(MAX_IP_DOMAIN_MAP_SIZE)

        /** 真实 IP → 域名, 只由 [lookupDomain] 作兜底读取, 不参与假 IP 生命周期。 */
        private val realIpToDomain = LruStringMap(MAX_REAL_IP_MAP_SIZE)
        private val domainToIp = LruStringMap(MAX_DOMAIN_IP_MAP_SIZE) // key: "$qname.$qtype", value: 假 IP

        /**
         * 查询 IP → 域名映射 (命中刷新 LRU 访问时间)。
         * fake IP 映射缺失时调用方必须快速失败 (拒绝 CONNECT), 不能把假 IP 当真实主机连远端。
         *
         * 优先查假 IP 表 (受假 IP 生命周期管理), 未命中再看真实 IP 表 ——
         * 分表的目的见 [MAX_REAL_IP_MAP_SIZE], 两张表的淘汰互不影响。
         */
        fun lookupDomain(ip: String): String? = ipToDomain.get(ip) ?: realIpToDomain.get(ip)

        /**
         * 仅供单测: 直接种一条假 IP → 域名映射 (生产路径必须走 DNS 查询)。
         * 用于锁死"映射按活动连接数释放" —— `TcpWindowGateTest` 不发真 DNS 报文。
         */
        internal fun seedFakeIpMappingForTest(
            ip: String,
            domain: String,
        ) {
            ipToDomain.put(ip, domain)
        }

        /**
         * 仅供单测: 往**真实 IP** 表种一条映射, 并触发一次 LRU 淘汰。
         * 用于锁死"真实 IP 表的淘汰不得波及假 IP 表" (分表原因见 MAX_REAL_IP_MAP_SIZE)。
         */
        internal fun seedRealIpMappingForTest(
            ip: String,
            domain: String,
        ) {
            realIpToDomain.put(ip, domain)
        }

        /** 仅供单测: 立刻执行一次本该由 30s 定时任务做的 LRU 淘汰。 */
        internal fun trimMappingsForTest() {
            ipToDomain.trim()
            realIpToDomain.trim()
            domainToIp.trim()
        }

        /**
         * 归零但仍在摘除宽限期内的假 IP → 摘除截止时刻; 到期由 cleanupScheduler 真正删除。
         * 期间映射保持可解析 (客户端可能仍持有这张假 IP), 见 [FAKE_IP_RELEASE_GRACE_MS]。
         */
        private val pendingFakeIpRelease = ConcurrentHashMap<String, Long>()

        /**
         * 释放假 IP 映射 (连接全部关闭后调用)。
         *
         * 调用方必须**按活动连接数**判定, 不能在单条 TCP 连接关闭时就动: 同一域名
         * 常有多条并发 TCP (HTTP/2 多路复用尤其如此), 先关的那条会把仍在用的映射摘掉,
         * 后续 CONNECT 拿到 0x03 直接被拒 —— 这正是"下载卡在连接中"的成因之一。
         *
         * 归零后也**不立即删**: 进入 [FAKE_IP_RELEASE_GRACE_MS] 宽限期, 由 30s 定时任务摘除。
         * 宽限期内 [lookupDomain] 仍能解析, 因此本函数只做登记, [domainToIp] 的反向条目
         * 一并保留 —— 否则同一域名下次查询会换一个新假 IP, 与客户端手里那张对不上。
         *
         * 反向表按**值**删除 (在摘除时): domainToIp 的 key 含 qtype, 仅有域名无法还原全部 key。
         */
        fun releaseFakeIp(ip: String): Boolean {
            val domain = ipToDomain.get(ip) ?: return false
            pendingFakeIpRelease[ip] = System.currentTimeMillis() + FAKE_IP_RELEASE_GRACE_MS
            VpnController.appLogThrottled(
                "假 IP 归零 · $ip ← $domain — 摘除宽限 ${FAKE_IP_RELEASE_GRACE_MS / 1000}s " +
                    "(期内 CONNECT 照常解析) · 剩余映射 ${ipToDomain.size}",
                level = LogLevel.INFO,
                throttleKey = "假 IP 归零",
            )
            return true
        }

        /**
         * 宽限期满才真正摘除映射。由 30s cleanupScheduler 调用; 单测直接调用本函数。
         *
         * 必须先取消登记再删, 且**登记与删除同属本函数**: 若在别处直接 `ipToDomain.remove`,
         * `pendingFakeIpRelease` 里会留下指向已删条目的悬空项, 下次扫描时 `domain` 取到 null
         * 仍会 `domainToIp.removeByValue` 清掉反向条目 —— 好在结果一致, 但仍以单点删除为准。
         */
        private fun sweepPendingFakeIpRelease(now: Long) {
            pendingFakeIpRelease.entries.removeIf { (ip, deadline) ->
                if (now < deadline) return@removeIf false
                val domain = ipToDomain.remove(ip)
                if (domain != null) domainToIp.removeByValue(ip)
                VpnController.appLogThrottled(
                    "假 IP 已摘除 · $ip ← $domain — 宽限期满 · 剩余映射 ${ipToDomain.size}",
                    level = LogLevel.INFO,
                    throttleKey = "假 IP 已摘除",
                )
                true
            }
        }

        /** 仅供单测: 以给定时刻执行一次本该由 30s 定时任务做的宽限期摘除。 */
        internal fun sweepPendingFakeIpReleaseForTest(now: Long = System.currentTimeMillis()) {
            sweepPendingFakeIpRelease(now)
        }

        private val fakeIpCounter = AtomicInteger(FAKE_IP_BASE)
        private val fakeIpv6Counter = AtomicInteger(2) // fd00::2 开始 (fd00::1 是 VPN 网关)

        // 用于绕过 VPN 的 socket 保护函数 (由 VpnService 提供)
        private var protectSocket: ((java.net.DatagramSocket) -> Boolean)? = null

        fun setProtectFunction(protectSocket: (java.net.DatagramSocket) -> Boolean) {
            this.protectSocket = protectSocket
        }

        // 广告过滤匹配器 (由 VpnController 连接时注入, 无参构造默认 null → 单测安全放行)
        @Volatile private var adBlocker: AdBlocker? = null

        fun setAdBlocker(blocker: AdBlocker?) {
            adBlocker = blocker
        }

        // 设置广告过滤总开关, 由 DnsInterceptor 在连接时注入; 作用于全部 DNS 传输模式。
        fun setEnabledAdBlock(enabled: Boolean) {
            adBlocker?.setEnabled(enabled)
        }

        /**
         * 设置 DNS 传输模式
         */
        fun setTransportMode(mode: DnsTransport) {
            transportMode = mode
            Log.d(TAG, "DNS transport mode set to: $mode")
        }

        /**
         * 设置 SYSTEM 模式使用的系统真实 DNS 服务器列表
         */
        fun setSystemDnsServers(servers: List<String>) {
            systemDnsServers = servers
            Log.d(TAG, "System DNS servers set to: $servers")
        }

        /**
         * 获取当前 DNS 传输模式
         */
        fun getTransportMode(): DnsTransport = transportMode

        /**
         * 处理 DNS 查询包
         * @return true 表示已拦截，false 表示丢弃（未拦截/解析失败；回注是黑洞，无透传语义）
         */
        fun processDnsQuery(
            buffer: java.nio.ByteBuffer,
            srcIp: InetAddress,
            dstIp: InetAddress,
            srcPort: Int,
            dstPort: Int,
        ): Boolean {
            try {
                val queryData = ByteArray(buffer.remaining())
                buffer.get(queryData)

                val message = Message(queryData)

                // 只处理标准查询
                if (message.header.opcode != Opcode.QUERY) {
                    android.util.Log.d(
                        TAG,
                        ">>> [DnsInterceptor] processDnsQuery: not QUERY opcode != QUERY, returning false",
                    )
                    return false
                }
                if (message.header.rcode != Rcode.NOERROR) {
                    android.util.Log.d(TAG, ">>> [DnsInterceptor] processDnsQuery: rcode != NOERROR, returning false")
                    return false
                }

                val questions = message.getSection(Section.QUESTION)
                if (questions.isEmpty()) {
                    android.util.Log.d(TAG, ">>> [DnsInterceptor] processDnsQuery: no questions, returning false")
                    return false
                }

                val question = questions[0]
                val originalQueryId = message.header.id
                queriesIntercepted.incrementAndGet()

                // ★ 广告过滤: 对所有 DNS 传输模式统一生效。
                //   在判定走隧道(假 IP)/系统 DNS、分配假 IP、真实解析之前拦截,
                //   命中即回 0.0.0.0(A) / 空答案(AAAA), 不进缓存、不消耗任何通道资源。
                val adQname = question.name.toString(true)
                // 捕获为局部 val, 避免 @Volatile var 上无法 smart cast
                val blocker = adBlocker
                val blockedAd =
                    blocker != null &&
                        blocker.isBlocked(adQname) == true &&
                        (question.type == org.xbill.DNS.Type.A || question.type == org.xbill.DNS.Type.AAAA)
                if (blockedAd) {
                    val blockedResponse =
                        Message(originalQueryId).apply {
                            header.setFlag(Flags.QR.toInt())
                            header.setFlag(Flags.RD.toInt())
                            header.setFlag(Flags.RA.toInt())
                            header.setRcode(Rcode.NOERROR)
                        }
                    blockedResponse.addRecord(question, Section.QUESTION)
                    if (question.type == org.xbill.DNS.Type.A) {
                        // A 记录统一回 0.0.0.0 (黑洞); 客户端连该地址即失败, 达到拦截目的
                        blockedResponse.addRecord(
                            org.xbill.DNS.ARecord(
                                question.name,
                                org.xbill.DNS.Type.A,
                                0,
                                InetAddress.getByName("0.0.0.0"),
                            ),
                            Section.ANSWER,
                        )
                    }
                    // AAAA: 不回 IPv4 的 0.0.0.0, 回空答案集逼客户端回落 A 记录
                    pendingResponses.trySend(
                        DnsResponse(
                            srcIp = dstIp,
                            dstIp = srcIp,
                            dstPort = srcPort,
                            data = blockedResponse.toWire(),
                        ),
                    )
                    adBlockCount.incrementAndGet()
                    Log.d(TAG, "广告域名命中拦截: $adQname (type=${question.type})")
                    return true
                }

                // S5: IPv6 关闭 → AAAA 一律回空应答 (不分配/不读缓存/不真实解析 fd00 假 IP)。
                // 应用回落 A 记录走 IPv4; 若超时式丢弃, getaddrinfo 会卡到解析超时
                if (question.type == org.xbill.DNS.Type.AAAA && !enableIPv6) {
                    val response = Message(originalQueryId)
                    response.header.setFlag(Flags.QR.toInt())
                    response.header.setFlag(Flags.RD.toInt())
                    response.header.setFlag(Flags.RA.toInt())
                    response.header.setRcode(Rcode.NOERROR)
                    response.addRecord(question, Section.QUESTION)
                    pendingResponses.trySend(
                        DnsResponse(
                            srcIp = dstIp,
                            dstIp = srcIp,
                            dstPort = srcPort,
                            data = response.toWire(),
                        ),
                    )
                    queriesResolved.incrementAndGet()
                    return true
                }

                // 检查缓存 (键带策略标签, 见 cacheEntryKey — 策略一变即 miss)
                val dnsKey = dnsCacheKey(question.name, question.type)
                val cacheKey = cacheEntryKey(dnsKey, cachePolicyTag(question))
                val cached = dnsCache[cacheKey]
                if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                    cacheHits.incrementAndGet()
                    rebuildFakeIpMappings(question, cached.records, dnsKey)
                    // F10: 源 IP = 本次查询的目标 DNS 服务器, 目的 = 客户端
                    pendingResponses.trySend(
                        DnsResponse(
                            srcIp = dstIp,
                            dstIp = srcIp,
                            dstPort = srcPort,
                            data = sendCachedResponse(question, cached.records, originalQueryId).toWire(),
                        ),
                    )
                    return true
                }

                cacheMisses.incrementAndGet()

                // 判断本次查询走隧道(假 IP)还是系统 DNS(直连)
                val useSystemDns =
                    when (transportMode) {
                        DnsTransport.SYSTEM -> true
                        DnsTransport.DOMAIN_SPLIT -> {
                            val qname = question.name.toString(true)
                            val inList = domainListManager?.matches(qname) == true
                            Log.d(TAG, "DOMAIN_SPLIT: $qname -> ${if (inList) "tunnel" else "direct"}")
                            !inList
                        }
                        else -> false
                    }

                // 走隧道: 不做真实 DNS 解析, 分配假 IP, CONNECT 时用域名让 SSH 服务器解析
                if (!useSystemDns) {
                    return handleRemoteDnsFakery(question, originalQueryId, srcIp, dstIp, srcPort)
                }

                // 系统 DNS 模式: 正常 DNS 解析
                val queryId = queryIdCounter.incrementAndGet()

                val pending =
                    DnsPendingQuery(
                        queryId = queryId,
                        originalQueryId = originalQueryId,
                        srcIp = srcIp,
                        srcPort = srcPort,
                        dstIp = dstIp,
                        dstPort = dstPort,
                        question = question,
                    )

                pendingQueries[queryId] = pending

                // 修改查询 ID 为我们的内部 ID，通过隧道发送
                message.header.setID(queryId)

                // 异步发送到远程 DNS (根据传输模式)
                sendDnsQuery(message.toWire(), queryId)
                android.util.Log.d(TAG, ">>> [DnsInterceptor] sendDnsQuery 调用完成，返回 true")

                return true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "processDnsQuery failed: ${e.message}", e)
                VpnController.appLogThrottled(
                    "DNS 查询处理失败 — ${e.message}",
                    level = LogLevel.ERROR,
                    throttleKey = "DNS 查询处理失败",
                )
                return false
            }
        }

        /**
         * 根据传输模式发送 DNS 查询 (仅 SYSTEM 模式使用)
         */
        private fun sendDnsQuery(
            queryData: ByteArray,
            queryId: Int,
        ) {
            // 仅 SYSTEM / DOMAIN_SPLIT(域名未命中列表) 到达此处, 均通过受保护 socket 直查; else(REMOTE) 不可达
            sendDnsOverProtectedSocket(queryData, queryId)
        }

        /**
         * SYSTEM 模式：使用受保护的 DatagramSocket 绕过 VPN 发送 DNS 查询
         * 使用 VpnService.protect() 让 socket 走物理网卡
         */
        private fun sendDnsOverProtectedSocket(
            queryData: ByteArray,
            queryId: Int,
        ) {
            executor.submit {
                val pending = pendingQueries[queryId]
                if (pending == null) {
                    Log.w(TAG, "[$queryId] No pending query found")
                    return@submit
                }

                // 获取系统真实 DNS 服务器 (pending.dstIp 是 VPN 网关 10.0.0.2，不是真实 DNS)
                val dnsServer =
                    systemDnsServers.firstOrNull()
                        ?: pending.dstIp?.hostAddress?.takeIf { it != VpnNetwork.TUN_IP }
                        ?: "8.8.8.8"

                try {
                    Log.d(TAG, "[$queryId] >>> SYSTEM 模式: 准备查询 $dnsServer:53")

                    // 先检查 protectSocket 是否已设置
                    if (protectSocket == null) {
                        Log.e(TAG, "[$queryId] protectSocket 为 null！VPN 保护函数未设置")
                        VpnController.appLog(
                            "DNS 保护函数未设置 · 系统 DNS 查询可能绕回 VPN 形成自环",
                            level = LogLevel.WARNING,
                        )
                    }

                    val socket = java.net.DatagramSocket()
                    try {
                        // 关键：使用 VpnService.protect() 让此 socket 绕过 VPN
                        val protected = protectSocket?.invoke(socket) ?: false
                        if (!protected) {
                            Log.w(TAG, "[$queryId] VpnService.protect() 返回 false，socket 可能仍走 VPN")
                            VpnController.appLogThrottled(
                                "DNS protect 返回 false · 查询可能绕回 VPN 形成自环 ($dnsServer)",
                                level = LogLevel.WARNING,
                            )
                        }

                        socket.soTimeout = CONNECT_TIMEOUT
                        val dstAddr = java.net.InetAddress.getByName(dnsServer)
                        val packet = java.net.DatagramPacket(queryData, queryData.size, dstAddr, 53)
                        socket.send(packet)

                        val responseBuf = ByteArray(4096)
                        val responsePacket = java.net.DatagramPacket(responseBuf, responseBuf.size)
                        socket.receive(responsePacket)
                        val responseData = responseBuf.copyOfRange(0, responsePacket.length)
                        onDnsResponse(queryId, responseData)
                    } finally {
                        socket.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$queryId] SYSTEM 模式 DNS 查询失败: ${e.message}", e)
                    VpnController.appLogThrottled(
                        "系统 DNS 查询失败 · $dnsServer — ${e.message}",
                        level = LogLevel.WARNING,
                        throttleKey = "系统 DNS 查询失败",
                    )
                    onDnsResponse(queryId, ByteArray(0))
                }
            }
        }

        /**
         * 处理来自远程 DNS 的响应
         */
        fun onDnsResponse(
            queryId: Int,
            responseData: ByteArray,
        ) {
            val pending = pendingQueries.remove(queryId)
            if (pending == null) {
                Log.w(TAG, ">>> [DnsInterceptor] [$queryId] onDnsResponse: no pending query found")
                return
            }

            try {
                val response = Message(responseData)
                val records = response.getSection(Section.ANSWER)

                // 缓存结果 (真实 IP 应答固定直连标签)
                val cacheKey =
                    cacheEntryKey(
                        dnsCacheKey(pending.question.name, pending.question.type),
                        CACHE_TAG_DIRECT,
                    )
                val minTtl = records.map { it.ttl }.minOrNull() ?: 300
                cacheDns(
                    cacheKey,
                    CacheEntry(
                        records = records.toList(),
                        expireAt = System.currentTimeMillis() + minTtl.toLong() * 1000,
                    ),
                )

                // 建立 IP → 域名映射 (A/AAAA 记录)。
                // 本函数只由 sendDnsOverProtectedSocket 调用 (SYSTEM / DOMAIN_SPLIT 未命中),
                // 回包里的**都是真实 IP** —— 必须进 realIpToDomain 而不是假 IP 表,
                // 否则真实 IP 的巨大基数会把 16384 条假 IP 映射逐出 (策略同 rebuildFakeIpMappings)。
                val qname = pending.question.name.toString(true)
                for (record in records) {
                    val addr =
                        when (record) {
                            is org.xbill.DNS.ARecord -> record.address.hostAddress
                            is org.xbill.DNS.AAAARecord -> record.address.hostAddress
                            else -> null
                        }
                    if (addr != null) realIpToDomain.put(addr, qname)
                }

                // 恢复原始查询 ID
                response.header.setID(pending.originalQueryId)

                // 放入响应队列
                // pending.dstIp 是 DNS 服务器 IP (对于 SYSTEM 模式是物理网卡的 DNS，对于 REMOTE 是 8.8.8.8 等)
                pendingResponses.trySend(
                    DnsResponse(
                        srcIp = pending.dstIp ?: InetAddress.getByName("8.8.8.8"),
                        dstIp = pending.srcIp,
                        dstPort = pending.srcPort,
                        data = response.toWire(),
                    ),
                )

                queriesResolved.incrementAndGet()
            } catch (e: Exception) {
                Log.e(TAG, ">>> [DnsInterceptor] [$queryId] onDnsResponse 解析失败: ${e.message}", e)
                VpnController.appLogThrottled(
                    "DNS 响应解析失败 — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "DNS 响应解析失败",
                )
                // 发送 SERVFAIL
                sendErrorResponse(pending, Rcode.SERVFAIL)
            }
        }

        /**
         * REMOTE 模式: 不做真实 DNS 解析, 分配假 IP
         * SOCKS5 CONNECT 时用域名, SSH 服务器自己解析
         *
         * A 查询:   返回 IPv4 假 IP (198.18.x.x)
         * AAAA 查询: 返回 IPv6 假 IP (fd00::N, 在 VPN fd00::1/64 范围内)
         */
        private fun handleRemoteDnsFakery(
            question: Record,
            originalQueryId: Int,
            srcIp: InetAddress,
            dstIp: InetAddress,
            srcPort: Int,
        ): Boolean {
            val qname = question.name.toString(true)
            val qtype = question.type

            // 非 A/AAAA 查询 (MX/TXT/PTR/ANY...): 走受保护 socket 直查系统 DNS 并回填,
            // 否则该查询会被吞掉导致解析必然失败
            if (qtype != org.xbill.DNS.Type.A && qtype != org.xbill.DNS.Type.AAAA) {
                if (protectSocket == null || systemDnsServers.isEmpty()) {
                    Log.w(TAG, "REMOTE 假IP: 不支持类型 $qtype 且无系统 DNS 可用, 跳过")
                    VpnController.appLogThrottled(
                        "DNS 查询类型不支持 · qtype=$qtype 无系统 DNS 可用, 已跳过 (应用解析将失败)",
                        level = LogLevel.WARNING,
                        throttleKey = "DNS 查询类型不支持",
                    )
                    return false
                }
                Log.d(TAG, "REMOTE 假IP: 非 A/AAAA 类型 $qtype, 改走系统 DNS 直查")
                val queryId = queryIdCounter.incrementAndGet()
                pendingQueries[queryId] =
                    DnsPendingQuery(
                        queryId = queryId,
                        originalQueryId = originalQueryId,
                        srcIp = srcIp,
                        srcPort = srcPort,
                        dstIp = dstIp,
                        dstPort = 53,
                        question = question,
                    )
                val msg = Message(originalQueryId)
                msg.addRecord(question, Section.QUESTION)
                msg.header.setID(queryId)
                sendDnsOverProtectedSocket(msg.toWire(), queryId)
                return true
            }

            // 同一域名+类型返回相同假IP (key 包含类型, A 和 AAAA 独立分配)
            val dnsKey = dnsCacheKey(question.name, qtype)
            val existingIp = domainToIp.get(dnsKey)
            val fakeIp: String
            val fakeInetAddress: InetAddress

            if (existingIp != null) {
                fakeIp = existingIp
                fakeInetAddress = InetAddress.getByName(fakeIp)
            } else if (qtype == org.xbill.DNS.Type.A) {
                // A 记录: 分配 IPv4 假 IP (198.18.x.x)
                val rawIp = fakeIpCounter.incrementAndGet()
                if (rawIp > FAKE_IP_MAX) {
                    Log.e(TAG, "REMOTE 假IP: IPv4 池已耗尽")
                    VpnController.appLogThrottled(
                        "假 IP 池耗尽 (IPv4 198.18.0.0/15) · 新域名将无法解析",
                        level = LogLevel.ERROR,
                    )
                    return false
                }
                fakeIp =
                    "${(rawIp shr 24) and 0xFF}.${(rawIp shr 16) and 0xFF}." +
                    "${(rawIp shr 8) and 0xFF}.${rawIp and 0xFF}"
                fakeInetAddress = InetAddress.getByName(fakeIp)
                domainToIp.put(dnsKey, fakeIp)
            } else {
                // AAAA 记录: 分配 IPv6 假 IP (fd00::N, 在 VPN fd00::1/64 范围内)
                val counter = fakeIpv6Counter.incrementAndGet()
                if (counter > 0xFFFF) {
                    Log.e(TAG, "REMOTE 假IP: IPv6 池已耗尽")
                    VpnController.appLogThrottled(
                        "假 IP 池耗尽 (IPv6 fd00::/8) · 新域名将无法解析",
                        level = LogLevel.ERROR,
                    )
                    return false
                }
                fakeIp = String.format(java.util.Locale.ROOT, "fd00::%04x", counter)
                fakeInetAddress = InetAddress.getByName(fakeIp)
                // 反向表必须存 InetAddress 的规范形 (fd00:0:0:0:0:0:0:42), 与 ipToDomain 的键
                // (下方 fakeInetAddress.hostAddress) 一致: releaseFakeIp 拿到的是 CONNECT 侧的
                // hostAddress, 存 "fd00::0042" 会让 removeByValue 永远 0 命中 → 反向表只进不出,
                // 撑满 16384 后假 IP 池告急。resetFakeIpCounters 解析用 substringAfterLast(':'),
                // 两种写法的十六进制尾数相同, 迁移安全。
                domainToIp.put(dnsKey, fakeInetAddress.hostAddress ?: fakeIp)
            }

            // 建立双向映射 (假 IP → 域名, 用于 SOCKS5 CONNECT 域名模式)
            val mappedIp = fakeInetAddress.hostAddress ?: fakeIp
            ipToDomain.put(mappedIp, qname)
            // 能被重新解析/复用 = 这张假 IP 还活着, 撤销上一轮的待摘除登记
            pendingFakeIpRelease.remove(mappedIp)

            Log.d(TAG, "REMOTE 假IP: $qname → $fakeIp (type=$qtype)")

            // 构建假 DNS 响应
            val response = Message(originalQueryId)
            response.header.setFlag(Flags.QR.toInt())
            response.header.setFlag(Flags.RD.toInt())
            response.header.setFlag(Flags.RA.toInt())
            response.header.setRcode(Rcode.NOERROR)
            response.addRecord(question, Section.QUESTION)

            val answer: Record =
                if (qtype == org.xbill.DNS.Type.A) {
                    org.xbill.DNS.ARecord(question.name, org.xbill.DNS.Type.A, FAKE_IP_TTL_SECONDS, fakeInetAddress)
                } else {
                    org.xbill.DNS.AAAARecord(
                        question.name,
                        org.xbill.DNS.Type.AAAA,
                        FAKE_IP_TTL_SECONDS,
                        fakeInetAddress,
                    )
                }
            response.addRecord(answer, Section.ANSWER)

            // 缓存 (TTL 与应答一致: 客户端过期重新查询时重新分配/复用假 IP 并重建映射)
            cacheDns(
                cacheEntryKey(dnsKey, CACHE_TAG_TUNNEL),
                CacheEntry(
                    records = listOf(answer),
                    expireAt = System.currentTimeMillis() + FAKE_IP_TTL_SECONDS * 1000L,
                ),
            )

            // F10: 源 IP = 查询的目标地址 (客户端实际查询的 VPN DNS), 非假 IP
            pendingResponses.trySend(
                DnsResponse(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    data = response.toWire(),
                ),
            )

            queriesResolved.incrementAndGet()
            return true
        }

        /**
         * dnsCache 与 ipToDomain/domainToIp 是独立 LRU (各自淘汰): 命中 DNS 缓存也必须
         * 重建假 IP ↔ 域名映射, 否则映射被逐出后, 客户端拿缓存里的假 IP CONNECT 会一直
         * 收到 SOCKS 0x03 (最长整个 cacheTtl 内反复失败)。只重建假 IP 条目 —
         * SYSTEM 模式缓存里的真实 IP 不进表, 避免污染有限的 LRU 容量。
         */
        private fun rebuildFakeIpMappings(
            question: Record,
            records: List<Record>,
            cacheKey: String,
        ) {
            if (question.type != org.xbill.DNS.Type.A && question.type != org.xbill.DNS.Type.AAAA) return
            val qname = question.name.toString(true)
            for (r in records) {
                val addr =
                    when (r) {
                        is org.xbill.DNS.ARecord -> r.address
                        is org.xbill.DNS.AAAARecord -> r.address
                        else -> null
                    }
                val ipStr = addr?.hostAddress
                if (addr == null || ipStr == null || !VpnController.isFakeIp(addr)) continue
                ipToDomain.put(ipStr, qname)
                domainToIp.put(cacheKey, ipStr)
                // 缓存命中重建 = 该假 IP 仍被使用, 撤销待摘除登记
                pendingFakeIpRelease.remove(ipStr)
            }
        }

        private fun sendCachedResponse(
            question: Record,
            records: List<Record>,
            originalId: Int,
        ): Message {
            val response = Message(originalId)
            response.header.setFlag(Flags.QR.toInt())
            response.header.setFlag(Flags.RD.toInt())
            response.header.setFlag(Flags.RA.toInt())
            response.header.setRcode(Rcode.NOERROR)
            response.addRecord(question, Section.QUESTION)
            records.forEach { r ->
                // 假 IP 记录回包时重写短 TTL: 缓存命中路径不能把原始 300s TTL 泄漏给客户端
                val answer =
                    when (r) {
                        is org.xbill.DNS.ARecord ->
                            if (VpnController.isFakeIp(r.address)) {
                                org.xbill.DNS.ARecord(r.name, r.type, FAKE_IP_TTL_SECONDS, r.address)
                            } else {
                                r
                            }
                        is org.xbill.DNS.AAAARecord ->
                            if (VpnController.isFakeIp(r.address)) {
                                org.xbill.DNS.AAAARecord(r.name, r.type, FAKE_IP_TTL_SECONDS, r.address)
                            } else {
                                r
                            }
                        else -> r
                    }
                response.addRecord(answer, Section.ANSWER)
            }
            return response
        }

        private fun sendErrorResponse(
            pending: DnsPendingQuery,
            rcode: Int,
        ) {
            // 分阶段健康: 错误应答 (超时/SERVFAIL) 计数, 供 StageHealthEvaluator 判 DNS 段
            StageCounters.onDnsError()
            VpnController.appLogThrottled(
                "DNS 应答错误 · rcode=$rcode — 应用解析将失败 (超时/SERVFAIL)",
                level = LogLevel.WARNING,
                throttleKey = "DNS 应答错误",
            )
            val response = Message(pending.originalQueryId)
            response.header.setFlag(Flags.QR.toInt())
            response.header.setFlag(Flags.RD.toInt())
            response.header.setFlag(Flags.RA.toInt())
            response.header.setRcode(rcode)
            response.addRecord(pending.question, Section.QUESTION)

            pendingResponses.trySend(
                DnsResponse(
                    srcIp = pending.dstIp ?: InetAddress.getByName("8.8.8.8"),
                    dstIp = pending.srcIp,
                    dstPort = pending.srcPort,
                    data = response.toWire(),
                ),
            )
        }

        /**
         * 获取待发送的 DNS 响应
         * 由 VpnService 调用写回 TUN 接口
         */
        suspend fun pollResponse(): DnsResponse? = pendingResponses.receiveCatching().getOrNull()

        /**
         * 排空待发送 DNS 响应队列。
         * VPN 断开时调用: 避免旧会话残留响应在新会话的 dnsResponseDeliveryLoop 中被拾取,
         * 携带过期客户端地址写入新 TUN 接口。
         */
        fun clearPendingResponses() {
            while (pendingResponses.tryReceive().isSuccess) {
                // 丢弃残留响应
            }
        }

        /**
         * 清理过期缓存
         */
        fun cleanupCache() {
            val now = System.currentTimeMillis()
            dnsCache.entries.removeIf { it.value.expireAt < now }
        }

        fun getStats() =
            DnsStats(
                queriesIntercepted = queriesIntercepted.get(),
                queriesResolved = queriesResolved.get(),
                cacheHits = cacheHits.get(),
                cacheMisses = cacheMisses.get(),
                adBlockCount = adBlockCount.get(),
                pendingQueries = pendingQueries.size,
                cacheSize = dnsCache.size,
            )
    }

data class DnsStats(
    val queriesIntercepted: Long,
    val queriesResolved: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val adBlockCount: Long = 0L,
    val pendingQueries: Int,
    val cacheSize: Int,
)
