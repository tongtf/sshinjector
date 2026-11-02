package cn.srv0.sshinjector.domain.vpn

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持有**当前会话**的连接预算 (设计 §10 I-8)。
 *
 * holder 本身可以是单例, 但它持有的 budget **必须每会话新建**:
 * 复用同一个 budget 会让上一会话泄漏的 permit 永久吃掉新会话容量, 而且断开时无法兜底归零。
 *
 * 生命周期: `VpnController.connect` → [startSession]; `disconnect` / `forceReset` → [clearSession]。
 * 为 null = 不做全局限额 (测试 / 尚未进入会话), 行为与改造前一致。
 */
@Singleton
class ConnectionBudgetHolder
    @Inject
    constructor() {
        @Volatile
        var current: ConnectionBudget? = null
            private set

        /** 会话开始: 新建预算并发布 (旧预算直接被丢弃, 不回收 —— 它的连接已随会话关闭)。 */
        fun startSession(limit: Int = ConnectionBudget.DEFAULT_LIMIT): ConnectionBudget {
            val budget = ConnectionBudget(limit)
            current = budget
            return budget
        }

        /** 会话结束: 置空。之后到达的 accept 不再受限额约束, 与改造前一致。 */
        fun clearSession() {
            current = null
        }
    }
