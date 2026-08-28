package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import cn.srv0.sshinjector.data.local.DomainListManager
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Opcode
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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

            // IPv6 假 IP 池: fd00::2 ~ fd00::ffff:ffff (VPN 网关 fd00::1/64 范围内)
            // 用递增计数器生成 fd00::N 形式的假 IPv6 地址

            // 映射表大小限制，防止长时间运行 OOM
            private const val MAX_IP_DOMAIN_MAP_SIZE = 4096
            private const val MAX_DOMAIN_IP_MAP_SIZE = 4096
            private const val MAX_DNS_CACHE_SIZE = 512

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

        private val executor = Executors.newFixedThreadPool(2)
        private val pendingQueries = ConcurrentHashMap<Int, DnsPendingQuery>()
        private val dnsCache = ConcurrentHashMap<String, CacheEntry>()

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

        // 定期清理过期待查
        private val cleanupScheduler =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor()

        init {
            cleanupScheduler.scheduleAtFixedRate({
                val now = System.currentTimeMillis()
                // 清理超时 pendingQueries
                pendingQueries.entries.removeIf { (_, query) ->
                    now - query.timestamp > PENDING_QUERY_TIMEOUT_MS
                }
                // 清理过期缓存
                dnsCache.entries.removeIf { it.value.expireAt < now }
                // 防止映射表无限增长：超过限制时清空一半（简单驱逐策略）
                // 驱逐时同步清理反向映射, 避免 ipToDomain/domainToIp 双向不一致
                if (ipToDomain.size > MAX_IP_DOMAIN_MAP_SIZE) {
                    val half = ipToDomain.size / 2
                    val iter = ipToDomain.keys.iterator()
                    var removed = 0
                    while (iter.hasNext() && removed < half) {
                        val fakeIp = iter.next()
                        iter.remove()
                        removed++
                        domainToIp.entries.removeIf { it.value == fakeIp }
                    }
                }
                if (domainToIp.size > MAX_DOMAIN_IP_MAP_SIZE) {
                    val half = domainToIp.size / 2
                    val iter = domainToIp.keys.iterator()
                    var removed = 0
                    while (iter.hasNext() && removed < half) {
                        val key = iter.next()
                        val fakeIp = domainToIp[key]
                        iter.remove()
                        removed++
                        if (fakeIp != null) {
                            ipToDomain.remove(fakeIp)
                        }
                    }
                    // 驱逐后把假 IP 计数器重置到剩余映射的最大值, 防止长期运行池耗尽
                    resetFakeIpCounters()
                }
            }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
        }

        /**
         * 把假 IP 计数器重置到剩余映射中的最大值 (按序分配, 未分配区间可复用)。
         */
        private fun resetFakeIpCounters() {
            var max4 = 0L
            var max6 = 0L
            for (ip in domainToIp.values) {
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
            fakeIpCounter.updateAndGet { cur -> maxOf(cur.toLong(), max4, FAKE_IP_BASE.toLong()).toInt() }
            fakeIpv6Counter.updateAndGet { cur -> maxOf(cur.toLong(), max6, 2L).toInt() }
        }

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
        val ipToDomain = ConcurrentHashMap<String, String>()
        private val domainToIp = ConcurrentHashMap<String, String>() // key: "$qname.$qtype", value: 假 IP
        private val fakeIpCounter = AtomicInteger(FAKE_IP_BASE)
        private val fakeIpv6Counter = AtomicInteger(2) // fd00::2 开始 (fd00::1 是 VPN 网关)

        // 用于绕过 VPN 的 socket 保护函数 (由 VpnService 提供)
        private var protectSocket: ((java.net.DatagramSocket) -> Boolean)? = null

        fun setProtectFunction(protectSocket: (java.net.DatagramSocket) -> Boolean) {
            this.protectSocket = protectSocket
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
         * @return true 表示已拦截，false 表示透传
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

                // 检查缓存
                val cacheKey = "${question.name}.${question.type}"
                val cached = dnsCache[cacheKey]
                if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                    cacheHits.incrementAndGet()
                    sendCachedResponse(question, cached.records, originalQueryId, srcIp, srcPort)
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
                    return handleRemoteDnsFakery(question, originalQueryId, srcIp, srcPort)
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
            when (transportMode) {
                DnsTransport.SYSTEM, DnsTransport.DOMAIN_SPLIT -> sendDnsOverProtectedSocket(queryData, queryId)
                else -> {
                    Log.w(TAG, "sendDnsQuery called in non-SYSTEM mode ($transportMode), should not happen")
                    onDnsResponse(queryId, ByteArray(0))
                }
            }
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
                        ?: pending.dstIp?.hostAddress?.takeIf { it != "10.0.0.2" }
                        ?: "8.8.8.8"

                try {
                    Log.d(TAG, "[$queryId] >>> SYSTEM 模式: 准备查询 $dnsServer:53")

                    // 先检查 protectSocket 是否已设置
                    if (protectSocket == null) {
                        Log.e(TAG, "[$queryId] protectSocket 为 null！VPN 保护函数未设置")
                    }

                    val socket = java.net.DatagramSocket()
                    try {
                        // 关键：使用 VpnService.protect() 让此 socket 绕过 VPN
                        val protected = protectSocket?.invoke(socket) ?: false
                        if (!protected) {
                            Log.w(TAG, "[$queryId] VpnService.protect() 返回 false，socket 可能仍走 VPN")
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

                // 缓存结果
                val cacheKey = "${pending.question.name}.${pending.question.type}"
                val minTtl = records.map { it.ttl }.minOrNull() ?: 300
                cacheDns(
                    cacheKey,
                    CacheEntry(
                        records = records.toList(),
                        expireAt = System.currentTimeMillis() + minTtl.toLong() * 1000,
                    ),
                )

                // 建立 IP → 域名映射 (A/AAAA 记录)
                val qname = pending.question.name.toString(true)
                for (record in records) {
                    val addr =
                        when (record) {
                            is org.xbill.DNS.ARecord -> record.address.hostAddress
                            is org.xbill.DNS.AAAARecord -> record.address.hostAddress
                            else -> null
                        }
                    if (addr != null) ipToDomain[addr] = qname
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
            srcPort: Int,
        ): Boolean {
            val qname = question.name.toString(true)
            val qtype = question.type

            // 非 A/AAAA 查询 (MX/TXT/PTR/ANY...): 走受保护 socket 直查系统 DNS 并回填,
            // 否则该查询会被吞掉导致解析必然失败
            if (qtype != org.xbill.DNS.Type.A && qtype != org.xbill.DNS.Type.AAAA) {
                if (protectSocket == null || systemDnsServers.isEmpty()) {
                    Log.w(TAG, "REMOTE 假IP: 不支持类型 $qtype 且无系统 DNS 可用, 跳过")
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
                        dstIp = null,
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
            val cacheKey = "$qname.$qtype"
            val existingIp = domainToIp[cacheKey]
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
                    return false
                }
                fakeIp =
                    "${(rawIp shr 24) and 0xFF}.${(rawIp shr 16) and 0xFF}." +
                    "${(rawIp shr 8) and 0xFF}.${rawIp and 0xFF}"
                fakeInetAddress = InetAddress.getByName(fakeIp)
                domainToIp[cacheKey] = fakeIp
            } else {
                // AAAA 记录: 分配 IPv6 假 IP (fd00::N, 在 VPN fd00::1/64 范围内)
                val counter = fakeIpv6Counter.incrementAndGet()
                if (counter > 0xFFFF) {
                    Log.e(TAG, "REMOTE 假IP: IPv6 池已耗尽")
                    return false
                }
                fakeIp = String.format(java.util.Locale.ROOT, "fd00::%04x", counter)
                fakeInetAddress = InetAddress.getByName(fakeIp)
                domainToIp[cacheKey] = fakeIp
            }

            // 建立双向映射 (假 IP → 域名, 用于 SOCKS5 CONNECT 域名模式)
            ipToDomain[fakeInetAddress.hostAddress ?: fakeIp] = qname

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
                    org.xbill.DNS.ARecord(question.name, org.xbill.DNS.Type.A, 300, fakeInetAddress)
                } else {
                    org.xbill.DNS.AAAARecord(question.name, org.xbill.DNS.Type.AAAA, 300, fakeInetAddress)
                }
            response.addRecord(answer, Section.ANSWER)

            // 缓存
            cacheDns(
                cacheKey,
                CacheEntry(
                    records = listOf(answer),
                    expireAt = System.currentTimeMillis() + 300_000L,
                ),
            )

            // 发送响应 (DNS 服务器 IP 用假 IP 作为 srcIp, 不影响)
            pendingResponses.trySend(
                DnsResponse(
                    srcIp = fakeInetAddress,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    data = response.toWire(),
                ),
            )

            queriesResolved.incrementAndGet()
            return true
        }

        private fun sendCachedResponse(
            question: Record,
            records: List<Record>,
            originalId: Int,
            srcIp: InetAddress,
            srcPort: Int,
        ) {
            val response = Message(originalId)
            response.header.setFlag(Flags.QR.toInt())
            response.header.setFlag(Flags.RD.toInt())
            response.header.setFlag(Flags.RA.toInt())
            response.header.setRcode(Rcode.NOERROR)
            response.addRecord(question, Section.QUESTION)
            records.forEach { response.addRecord(it, Section.ANSWER) }

            // 缓存响应没有特定的源 DNS IP，使用默认
            pendingResponses.trySend(
                DnsResponse(
                    srcIp = InetAddress.getByName("8.8.8.8"),
                    dstIp = srcIp,
                    dstPort = srcPort,
                    data = response.toWire(),
                ),
            )
        }

        private fun sendErrorResponse(
            pending: DnsPendingQuery,
            rcode: Int,
        ) {
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
                pendingQueries = pendingQueries.size,
                cacheSize = dnsCache.size,
            )
    }

data class DnsStats(
    val queriesIntercepted: Long,
    val queriesResolved: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val pendingQueries: Int,
    val cacheSize: Int,
)
