package cn.srv0.sshinjector.domain.vpn

/**
 * 「连接 → 发起方 uid」反查 (P2 按应用归属)。
 *
 * 平台能力: `ConnectivityManager.getConnectionOwnerUid(int, InetSocketAddress, InetSocketAddress)`
 * —— **公开 API (自 API 29)**, stub 文档明确写给**活动 VpnService** (取证见 `AGENTS.md`
 * Key Gotchas「域名统计 ③ 远端解析 + ④ uid 取证」与
 * `docs/specs/2026-10-19-domain-stats-round2-todo.md` §2.1)。
 *
 * 调用方是数据面 (`TcpStateMachine.createTcpConnection`, 每个客户端 socket **一次**), 所以实现
 * **必须**满足: [lookup] 本身只做"入队 + 计数" (原子操作; 无 binder、无阻塞、无分配洪泛), 真正的
 * 查询跑在单线程有界队列里; 拿不到就静默放弃 —— uid 是**展示增强**, 拿不到不影响任何数据面决策,
 * 也不允许影响隧道行为。
 *
 * **反查时机 (2026-10-19 真机定案)**: 必须在**连接刚建立 (SYN 到达) 时**发起, 不能等关闭时再发起。
 * `getConnectionOwnerUid` 查的是**内核里这条 socket 的归属**, 而关闭那一刻应用往往已经把 socket
 * 销毁 (真机实测: 关闭时反查只有 `7/105` 命中, 大量 `存活 0s` 的短连接一个都查不到)。SYN 到达时
 * 这条 socket 必然存在 (SYN 就是它发的), 结果缓存在连接上 (`TcpConnection.appUid`), 关闭时回写统计。
 */
interface AppUidResolver {
    /**
     * 异步查一条连接的归属 uid; 只有拿到 (≥ 0) 才回调 [onResolved]。
     *
     * 地址传**原始字节** (`InetAddress.address`; 长度 4/16 即地址族), 避免在数据面线程上物化
     * `InetAddress`。回调在查询线程上执行, 实现方保证其内部异常不外溢。
     */
    fun lookup(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        onResolved: (Int) -> Unit,
    )

    /** 快照资源节用 (`命中 H/Q 查询`); 无平台能力时返回空串, 调用方跳过。 */
    fun diagnostics(): String

    companion object {
        /** 未拿到归属 (无能力 / 平台查不到 / 非隧道连接): [lookup] 不会回调, 连接上的缓存值保持此值。 */
        const val NO_UID: Int = -1

        /** 无平台能力 / 测试替身: 不查询、不计数、不分配。 */
        val NONE: AppUidResolver =
            object : AppUidResolver {
                override fun lookup(
                    srcIp: ByteArray,
                    srcPort: Int,
                    dstIp: ByteArray,
                    dstPort: Int,
                    onResolved: (Int) -> Unit,
                ) {
                }

                override fun diagnostics(): String = ""
            }
    }
}
