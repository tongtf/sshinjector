package cn.srv0.sshinjector.domain.vpn

/**
 * 远端 (VPS 出口) 域名解析的结果三态 (与往返探活 `ROUND_TRIP_OK/UNAVAILABLE/FAILED` 同构)。
 *
 * **三态必须分开**: 前两种都会让调用方静默回退本地视角 (设计 §7.2「不要报错」), 但成因完全不同 ——
 * Unavailable = 远端明确给不出答案 (通道没建起来 / 服务器不允许出网 53 / 无记录), Failed = 黑洞
 * (撑满预算)。日志里区分它们才能判断该不该动 app, 所以每个非 Ok 分支都带一句 [detail]。
 */
sealed interface RemoteResolveOutcome {
    /**
     * @param ips 远端 DNS 返回的地址 (去重、**保持应答顺序**、上限 8) —— 顺序即这一侧解析器的
     *   偏好顺序, 首个就是 SSH 通道实际会拨的那个 (别重排 v4/v6)。
     * @param elapsedMs 整次查询往返耗时 (自证点用)。
     * @param sessionId 承载本次查询的会话标识 (`"S"+identityHashCode`), 排障用。
     * @param dnsServer 实际问到答案的那台 DNS (自证点: 换候选要看得见)。
     */
    data class Ok(
        val ips: List<String>,
        val elapsedMs: Long,
        val sessionId: String,
        val dnsServer: String = "",
    ) : RemoteResolveOutcome

    /**
     * 远端明确给不出答案 (通道打不开 / 53 出网被禁 / 应答里没记录) —— 调用方回退本地视角, **不是故障**。
     *
     * @param detail 人话成因, 直接进应用内日志 —— 无 adb 时这是唯一能看清"远端腿为什么不干活"的通道。
     */
    data class Unavailable(
        val detail: String,
    ) : RemoteResolveOutcome

    /** 远端无响应 (黑洞: 通道开出来但一个字节都不回, 撑满预算) —— 调用方回退本地视角, 记一条 INFO。 */
    data class Failed(
        val detail: String,
    ) : RemoteResolveOutcome
}

/**
 * 远端域名解析 (拿"VPS 出口实际会连的 IP", 用于域名统计的远端视角)。
 *
 * **机制 (2026-10-19 真机定案: 从 exec 改成 DNS over SSH direct-tcpip)**:
 * 在**池内已有会话**上开一条 `direct-tcpip` 通道到一台公共 DNS (53/TCP), 自己组 DNS 报文去问。
 * 这拿到的就是"从这台 VPS 出去的解析视角" (权威 DNS 按查询源 IP 给就近节点), 与 v2fly 类工具的
 * 做法一致。
 *
 * **为什么不用 exec `getent ahosts`** (上一版实现, 已废): 加固过的服务器对 exec 永远返回空输出。
 * 自建向导生成的 Match 块是 `ChrootDirectory` + 无 shell 的 `nologin` 账号; 运维手写的硬化配置
 * 更直接 —— `ForceCommand /bin/false`, 任何 exec 都被替换成 `/bin/false`。真机日志形态: 池健康
 * `3/3`、耗时 <1s 就回退, 只有 `远端解析不可用 · … (exec 不可用或会话池无活口)`, 而同一份配置里的
 * `AllowTcpForwarding yes` 恰好说明 direct-tcpip 才是**被允许**的那条路 (整个数据面本来就靠它)。
 *
 * 其余纪律不变:
 * - **绝不**新建 SSH 会话 (`RemoteCommandExecutor.execSingleShot` 每次 `getSession()+connect()`,
 *   0.4~4s 且会在服务器侧堆积会话 → 历史上直接导致 `socket is not established`);
 * - 不在 `SshIoDispatcher` 上跑 (那个专用池只服务数据面阻塞读写), 走 `Dispatchers.IO`;
 * - 任何异常都不外抛, 调用方一律回退本地视角且不报错。
 */
interface RemoteDomainResolver {
    suspend fun resolveIps(
        domain: String,
        timeoutMs: Long = DEFAULT_REMOTE_RESOLVE_TIMEOUT_MS,
    ): RemoteResolveOutcome

    companion object {
        /** 整次查询总预算 (设计 §7.2 冻结 5s)。 */
        const val DEFAULT_REMOTE_RESOLVE_TIMEOUT_MS: Long = 5000L
    }
}
