package cn.srv0.sshinjector.domain.vpn.exit

/**
 * 出口健康状态 (设计 §9.1)。
 *
 * 只有 [Ready] 接新连接; 其余状态一律回退 `default`, 但**已在跑的连接不受影响** ——
 * 出口降级只影响"新连接" (I-1), 否则一次抖动会把正在下载的连接全部 RST。
 */
enum class ExitHealth {
    /** 启动中 (建池/绑端口): 还不接新连接。 */
    Starting,

    /** 唯一可服务的状态。 */
    Ready,

    /** 会话级失败: 新连接改走 default; 隔离窗口后按探活节拍尝试恢复。 */
    Degraded,

    /** 本次会话不再自动恢复 (需要用户重连)。 */
    Failed,

    /** 用户禁用 / 断开。 */
    Stopped,
}
