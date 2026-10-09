package cn.srv0.sshinjector.domain.vpn.tunnel

import cn.srv0.sshinjector.domain.vpn.TunnelChannel

interface TunnelPlugin {
    val id: String

    suspend fun connect(config: TunnelConfig): Result<Unit>

    suspend fun disconnect()

    fun openTcpChannel(
        host: String,
        port: Int,
    ): TunnelChannel?

    val localSocksPort: Int get() = 0

    /**
     * 隧道数据面诊断快照 (周期日志用, 应用内日志是唯一可见面)。
     * 回答"此刻隧道是否在搬数据、卡在哪一侧"。默认空串 = 该插件不提供。
     */
    fun tunnelDiagnostics(): String = ""

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
