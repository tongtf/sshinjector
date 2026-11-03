package cn.srv0.sshinjector.domain.vpn.tunnel

/** 一个出口的会话累计流量 (字节)。 */
data class SessionTraffic(
    val upBytes: Long,
    val downBytes: Long,
    /** 本会话累计接受的连接数 (含已关闭)。 */
    val connections: Long,
)

interface TunnelPlugin {
    val id: String

    /**
     * 出口身份 (多出口维度, 与 [id] 的协议名正交)。
     * `"default"` = 主出口; 其余为用户命名 (`hk`/`jp`)。
     * 默认空串 = 该插件不区分出口 (旧实现/测试替身), 日志不加前缀。
     */
    val exitId: String get() = ""

    /**
     * 当前活跃连接数 (瞬时值)。单一来源必须是本地代理的连接表
     * (`Socks5ProxyServer.connections.size`), **不要**另设计数器 —— 两处增减不同步会让
     * 单出口软上限永不触发或永久触发 (I-19)。默认 0 = 该插件无此概念。
     */
    val activeConnections: Int get() = 0

    suspend fun connect(config: TunnelConfig): Result<Unit>

    suspend fun disconnect()

    val localSocksPort: Int get() = 0

    /**
     * 这个出口现在还能不能接新连接。
     *
     * 默认判据 = 本地 SOCKS 端口还在 (监听未拆)。SOCKS5 插件会额外检查 SSH 池里还有没有活口 ——
     * 只信 `ExitHealth` 是不够的: 探活/降级状态机还没接线时 `health` 恒为 `Ready`, 一个池已死的
     * 附加出口会被反复选中, 于是**每条新连接都失败而主出口明明可用** (设计 §9.1 的降级本意)。
     */
    val isHealthy: Boolean get() = localSocksPort > 0

    /**
     * 隧道数据面诊断快照 (周期日志用, 应用内日志是唯一可见面)。
     * 回答"此刻隧道是否在搬数据、卡在哪一侧"。默认空串 = 该插件不提供。
     */
    fun tunnelDiagnostics(): String = ""

    /**
     * 本出口的**会话累计**流量 (多出口快照 §12.1 的 `↑↓`)。
     *
     * 累计而不是瞬时: 长连接一关闭就从"活跃连接"里消失, 只看瞬时值会让快照看不出
     * "哪个出口干了多少活"。默认 null = 该插件不提供 (测试替身)。
     */
    val sessionTraffic: SessionTraffic? get() = null

    /**
     * 本地 SOCKS5 认证凭据 (RFC 1929, 用户名/密码)。
     * null = 未提供认证能力; 服务端 fail-closed 拒绝一切连接, 客户端也会拒绝发起。
     */
    val socksAuth: Pair<String, String>? get() = null

    /**
     * 注册回向直通回调: 远端数据不经本地 SOCKS socket 中转,
     * 由插件直接回调写回 TUN, 省一次往返与一个阻塞读协程。
     * key 为本地 SOCKS 客户端端口。数据以 (data, offset, length) 零拷贝传递,
     * 回调返回后数组即失效, 不得持有引用。
     */
    fun registerTunCallback(
        clientPort: Int,
        callback: (ByteArray, Int, Int) -> Unit,
    ) {
        // 默认无操作; 需要直通能力的插件自行实现
    }

    fun removeTunCallback(clientPort: Int) {
        // 默认无操作
    }

    /**
     * 注册远端结束通知: key 同 [registerTunCallback]; 形参 `graceful` = 是否 clean EOF。
     *
     * 主回程走直通回调时, 调用方不再跑 startRelayFromSocks, 收不到 read==-1 ——
     * 缺这根线会让远端的 FIN 无处上报, 只能等客户端下次上行失败才发 RST,
     * 对端应用看到 connection reset 而非正常收尾。
     *
     * `graceful=true` (远端 clean EOF) → 调用方发 FIN+ACK;
     * `graceful=false` (本栈主动关闭 / 回程异常) → 发 RST 立即 abort,
     * 且能解掉"纯下行连接无通知挂到 300s 陈旧清理"的死等。
     */
    fun registerTargetEofCallback(
        clientPort: Int,
        callback: (Boolean) -> Unit,
    ) {
        // 默认无操作; 需要直通能力的插件自行实现
    }

    fun removeTargetEofCallback(clientPort: Int) {
        // 默认无操作
    }
}
