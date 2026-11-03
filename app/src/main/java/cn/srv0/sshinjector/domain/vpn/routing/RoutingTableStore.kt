package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.usecase.ProxyExitRepository
import cn.srv0.sshinjector.domain.vpn.exit.ProxyExit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 路由表快照 (规则 + 当前出口集合)。
 *
 * `table` 不可变、`@Volatile` 交换: 数据面每次新建连接只读一次引用, 不在 SYN 路径上加锁/解析。
 */
data class RoutingSnapshot(
    val enabled: Boolean = false,
    val failoverEnabled: Boolean = true,
    /** 当前启用的附加出口 (priority 升序)。主出口 `default` 不在此列 (它恒等于当前连接的服务器)。 */
    val exits: List<ProxyExit> = emptyList(),
    val table: RoutingTable = RoutingTable.EMPTY,
) {
    /** 出口名集合 (`default` 由 [RoutingTable] 内部恒视为可用, 不需要放进来)。 */
    val knownExitNames: Set<String> get() = exits.mapTo(HashSet()) { it.name }

    companion object {
        /** 关闭态的规范快照: 数据面必须逐字节等同改造前。 */
        val DISABLED =
            RoutingSnapshot(
                enabled = false,
                failoverEnabled = true,
                exits = emptyList(),
                table = RoutingTable.EMPTY,
            )

        /**
         * 纯构造 (可单测, 不碰 DataStore/Room)。
         *
         * `enabled=false` 时**不解析规则文本** —— 关闭状态下连解析成本与解析告警都不该出现,
         * 这是 P0/P1 "零行为变化" 的具体含义。
         */
        fun build(
            enabled: Boolean,
            failoverEnabled: Boolean,
            rulesText: String,
            enabledExits: List<ProxyExit>,
        ): RoutingSnapshot {
            if (!enabled) return DISABLED
            val names = enabledExits.mapTo(HashSet()) { it.name }
            return RoutingSnapshot(
                enabled = true,
                failoverEnabled = failoverEnabled,
                exits = enabledExits,
                table = RoutingTable.fromText(rulesText, names),
            )
        }
    }
}

/**
 * 路由快照的唯一持有者 (设计 §8.10)。
 *
 * 谁会读它: P2 之后 `TcpStateMachine.forwardSynToTunnel` 每次新建连接读一次
 * [current] 的 `table`; 探活/快照读 [current] 的 `exits`。
 *
 * 谁会写它: `VpnController.connect` (会话开始) 与规则/出口变更后的 [refresh]。
 * **P1 阶段只读不生效**: `resolveExit` 尚未接线, 配置可在 UI 里改、能在测试框里验证。
 */
@Singleton
class RoutingTableStore
    @Inject
    constructor(
        private val settingsDataStore: SettingsDataStore,
        private val proxyExitRepository: ProxyExitRepository,
    ) {
        private val mutableSnapshot = MutableStateFlow(RoutingSnapshot.DISABLED)

        /** UI 观察入口 (规则/出口变化后可重新 [refresh] 推送)。 */
        val snapshots: StateFlow<RoutingSnapshot> = mutableSnapshot.asStateFlow()

        @Volatile
        private var current: RoutingSnapshot = RoutingSnapshot.DISABLED

        /** 数据面无锁读入口 (每次新建连接调一次)。 */
        fun current(): RoutingSnapshot = current

        /** 从 DataStore + Room 重新解析。连接前/规则变更后调用。 */
        suspend fun refresh(): RoutingSnapshot {
            val enabled = settingsDataStore.multiExitEnabled.first()
            val failover = settingsDataStore.multiExitFailoverEnabled.first()
            val rules = settingsDataStore.multiExitRules.first()
            val exits = proxyExitRepository.getEnabled()
            val snapshot = RoutingSnapshot.build(enabled, failover, rules, exits)
            current = snapshot
            mutableSnapshot.value = snapshot
            return snapshot
        }

        /** 断开/回滚到关闭态 (之后所有连接走 default, 与改造前一致)。 */
        fun reset() {
            current = RoutingSnapshot.DISABLED
            mutableSnapshot.value = RoutingSnapshot.DISABLED
        }
    }
