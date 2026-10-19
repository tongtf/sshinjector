package cn.srv0.sshinjector.domain.vpn

/**
 * 远端 (VPS 出口) 域名解析的结果三态 (与往返探活 `ROUND_TRIP_OK/UNAVAILABLE/FAILED` 同构)。
 *
 * **三态必须分开**: 前两种都会让调用方静默回退本地视角 (设计 §7.2「不要报错」), 但成因完全不同 ——
 * Unavailable = 服务器禁用 exec / 池里没活口 / 池饱和 (常态, 在 OpenWrt/dropbear 上必然发生),
 * Failed = 黑洞 (撑满 5s)。日志里区分它们才能判断该不该动 app。
 */
sealed interface RemoteResolveOutcome {
    /**
     * @param ips 服务器 `getent ahosts` 返回的地址 (去重、**保持 getent 顺序**、上限 8) —— 顺序即
     *   服务器 `getaddrinfo` 的偏好顺序, 首个就是 SSH 通道实际会拨的那个 (别重排 v4/v6)。
     * @param elapsedMs 整次 exec 往返耗时 (自证点用)。
     * @param sessionId 承载本次 exec 的会话标识 (`"S"+identityHashCode`), 排障用。
     */
    data class Ok(
        val ips: List<String>,
        val elapsedMs: Long,
        val sessionId: String,
    ) : RemoteResolveOutcome

    /** exec 不可用 / 无可用会话 / 池饱和 / 命令无输出 —— 调用方回退本地视角, **不是故障**。 */
    data object Unavailable : RemoteResolveOutcome

    /** 远端无响应 (黑洞) 或命令层异常 —— 调用方回退本地视角, 记一条 INFO。 */
    data object Failed : RemoteResolveOutcome
}

/**
 * 远端域名解析 (拿"VPS 出口实际会连的 IP", 用于域名统计的远端视角)。
 *
 * 实现必须**复用池内已有 SSH 会话**的 exec 通道, 绝不新建会话
 * (设计 §7.2 机制纠错: `RemoteCommandExecutor.execSingleShot` 每次 `getSession()+connect()`,
 * 0.4~4s 且会在服务器侧堆积会话 → 历史上直接导致 `socket is not established`)。
 *
 * 不在 `SshIoDispatcher` 上跑: 那个专用池只服务数据面阻塞读写, exec 解析与
 * `verifySessionRoundTrip` 一致走 `Dispatchers.IO`。
 */
interface RemoteDomainResolver {
    suspend fun resolveIps(
        domain: String,
        timeoutMs: Long = DEFAULT_REMOTE_RESOLVE_TIMEOUT_MS,
    ): RemoteResolveOutcome

    companion object {
        /** 整次 exec 往返超时 (设计 §7.2 冻结 5s)。 */
        const val DEFAULT_REMOTE_RESOLVE_TIMEOUT_MS: Long = 5000L
    }
}
