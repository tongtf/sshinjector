package cn.srv0.sshinjector.domain.vpn

/**
 * 「连接 → 发起方 uid」反查 (P2 按应用归属 + 多出口按应用分流)。
 *
 * 平台能力: `ConnectivityManager.getConnectionOwnerUid(int, InetSocketAddress, InetSocketAddress)`
 * —— **公开 API (自 API 29)**, stub 文档明确写给**活动 VpnService** (取证见 `AGENTS.md`
 * Key Gotchas「域名统计 ③ 远端解析 + ④ uid 取证」与
 * `docs/specs/2026-10-19-domain-stats-round2-todo.md` §2.1)。
 *
 * 调用方是数据面 (`TcpStateMachine.createTcpConnection`, 每个客户端 socket **一次**), 所以实现
 * **必须**满足: [request] 本身只做"入队 + 计数" (原子操作; 无 binder、无阻塞、无分配洪泛), 真正的
 * 查询跑在有界线程池里; 拿不到就给 [AppUidResolver.NO_UID]。
 *
 * **反查时机 (2026-10-19 真机定案)**: 必须在**连接刚建立 (SYN 到达) 时**发起, 不能等关闭时再发起。
 * `getConnectionOwnerUid` 查的是**内核里这条 socket 的归属**, 而关闭那一刻应用往往已经把 socket
 * 销毁 (真机实测: 关闭时反查只有 `7/105` 命中, 大量 `存活 0s` 的短连接一个都查不到)。SYN 到达时
 * 这条 socket 必然存在 (SYN 就是它发的), 结果缓存在连接上 (`TcpConnection.appUid`), 关闭时回写统计。
 *
 * **一次查询, 两个消费者**: 路由 (等 50ms) 与域名统计 (完成回调) 共用同一个 [UidRequest],
 * 不各自入队 —— 两次入队 = 每条新连接两次 binder 调用, 且第二个必然排在第一个后面, 50ms 预算内
 * 更容易超时。
 */
interface AppUidResolver {
    /**
     * 在 SYN 到达时发起一次反查 (非阻塞, 只入队)。
     *
     * 返回的句柄**一定会完成**: 命中给 `>= 0`, 否则给 [NO_UID] (无能力 / 无归属 / 平台拒绝 /
     * 队列满 / 窗口暂停)。地址传**原始字节** (`InetAddress.address`; 长度 4/16 即地址族),
     * 避免在数据面线程上物化 `InetAddress`。
     *
     * **D-19**: 地址在这里**复制并校验**后再交给 [enqueue]。数据面传的是连接对象上复用的
     * 缓存数组, 而查询跑在有界线程池里 (异步) —— 不复制的话调用方后续改写/复用它,
     * 反查线程读到的是另一个目标; 长度校验把"传错数组"变成明确失败而不是静默查错五元组。
     */
    fun request(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
    ): UidRequest =
        enqueue(
            srcIp = copyAddressBytes(srcIp, "源"),
            srcPort = srcPort,
            dstIp = copyAddressBytes(dstIp, "目的"),
            dstPort = dstPort,
        )

    /**
     * 平台实现的入队点: **入参已是 [request] 复制的私有副本**, 实现可以安全地跨线程持有。
     * 默认 = 立即完成 [NO_UID] (测试替身只覆写 [request] 即可)。
     */
    fun enqueue(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
    ): UidRequest = UidRequest.completed(NO_UID)

    /** 兼容入口: 只有拿到 (≥ 0) 才回调。新代码用 [request]。 */
    fun lookup(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        onResolved: (Int) -> Unit,
    ) {
        request(srcIp, srcPort, dstIp, dstPort).onComplete { uid ->
            if (uid >= 0) onResolved(uid)
        }
    }

    /** 快照资源节用 (`命中 H/Q 查询`); 无平台能力时返回空串, 调用方跳过。 */
    fun diagnostics(): String

    companion object {
        /** 未拿到归属 (无能力 / 平台查不到 / 非隧道连接)。 */
        const val NO_UID: Int = -1

        /** 无平台能力 / 测试替身: 不查询、不计数、不分配。 */
        val NONE: AppUidResolver =
            object : AppUidResolver {
                override fun request(
                    srcIp: ByteArray,
                    srcPort: Int,
                    dstIp: ByteArray,
                    dstPort: Int,
                ): UidRequest = UidRequest.completed(NO_UID)

                override fun diagnostics(): String = ""
            }
    }
}

/**
 * D-19: 校验地址族并复制。
 *
 * @throws IllegalArgumentException 长度不是 4 (IPv4) 或 16 (IPv6) —— 数据面组装错误, 早失败
 *   比在反查线程上拿一个错地址去查、再把归属记到别的域名上更好。
 */
private fun copyAddressBytes(
    bytes: ByteArray,
    label: String,
): ByteArray {
    require(bytes.size == 4 || bytes.size == 16) {
        "${label}地址必须是 4 或 16 字节, 实际 ${bytes.size}"
    }
    return bytes.copyOf()
}

/**
 * 一次 uid 反查的句柄。**一定完成** —— 这是 [UidRequest.await] 能有界等待的前提。
 *
 * 两个消费者 (路由 / 统计) 用同一个句柄: [await] 挂起等待 (不占线程), [onComplete] 不等待、
 * 完成时回调 (已完成则立即回调)。
 */
interface UidRequest {
    /**
     * 有界等待: 命中给 uid, 超时/未命中给 [AppUidResolver.NO_UID] (不抛异常)。
     */
    suspend fun await(timeoutMs: Long = UID_WAIT_TIMEOUT_MS): Int

    /** 统计侧用: 不等待, 完成时回调 (在反查线程上); 已完成的立即回调。 */
    fun onComplete(onDone: (Int) -> Unit)

    companion object {
        /**
         * 路由路径的等待预算 (设计 §6.6)。
         *
         * 50ms 是"不显著拖慢首字节"与"分页一次开 6~10 条连接时仍能等到"的折中; 超时只是
         * **应用规则不参与匹配** (继续匹配域名/IP/default), 绝不因此改变隧道/直连判定。
         */
        const val UID_WAIT_TIMEOUT_MS: Long = 50L

        /** 一个已完成的句柄 (无平台能力 / 测试替身)。 */
        fun completed(uid: Int): UidRequest =
            object : UidRequest {
                override suspend fun await(timeoutMs: Long): Int = uid

                override fun onComplete(onDone: (Int) -> Unit) {
                    runCatching { onDone(uid) }
                }
            }
    }
}
