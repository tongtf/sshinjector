package cn.srv0.sshinjector.ui.screen.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ProxyExitRepository
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_DOMAIN_SPLIT
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_SYSTEM
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_WHITELIST
import cn.srv0.sshinjector.domain.vpn.exit.ExitHealth
import cn.srv0.sshinjector.domain.vpn.exit.ProxyExit
import cn.srv0.sshinjector.domain.vpn.routing.ExitRuleText
import cn.srv0.sshinjector.domain.vpn.routing.RoutingTableStore
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 出口的会话内运行态 (只读快照; null = 本次会话未启动)。
 *
 * 只从 [TunnelManager.ExitSnapshot] 构造 —— **不要**再回头读 `plugin.activeConnections`:
 * 那是可变对象上的瞬时值, 读它等于绕开快照发布, chip 会在两次发布之间显示过期/为 0 的数据
 * (P0-2 的成因)。
 */
data class ExitRuntimeUi(
    val health: ExitHealth,
    val activeConnections: Int,
    val failTotal: Long,
)

/** 出口列表行: 出口 + 服务器名 + 引用它的规则数 (删除前提示) + 会话运行态。 */
data class MultiExitRow(
    val exit: ProxyExit,
    val serverName: String,
    val referencedRules: Int,
    val runtime: ExitRuntimeUi? = null,
)

/** 多出口页的展示态。 */
data class MultiExitUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val failoverEnabled: Boolean = true,
    /** 当前 DNS 模式是否可能产生 TUN 流量 (SYSTEM / 空白名单 = 不可用)。 */
    val supported: Boolean = true,
    val unsupportedReason: UnsupportedReason? = null,
    val exits: List<MultiExitRow> = emptyList(),
    val servers: List<ServerConfig> = emptyList(),
    val message: String? = null,
) {
    enum class UnsupportedReason {
        SYSTEM_DNS,
        EMPTY_WHITELIST,
        DOMAIN_SPLIT,
    }
}

/**
 * 多出口配置页 (设计 §13)。
 *
 * P1 阶段这里的开关**不影响数据面** (P2 才接线); 但配置必须能保存、能校验, 且 DNS 模式不支持时
 * 必须明确告知 —— 否则用户配了半天规则毫无反应 (设计 §6.4 约束 1)。
 */
@HiltViewModel
class MultiExitViewModel
    @Inject
    constructor(
        private val settingsDataStore: SettingsDataStore,
        private val proxyExitRepository: ProxyExitRepository,
        private val serverRepository: ServerRepository,
        private val routingTableStore: RoutingTableStore,
        private val vpnController: VpnController,
        private val tunnelManager: TunnelManager,
        whitelistDao: WhitelistDao,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(MultiExitUiState())
        val state: StateFlow<MultiExitUiState> = mutableState.asStateFlow()

        private var rulesText: String = ""

        init {
            val config =
                combine(
                    settingsDataStore.multiExitEnabled,
                    settingsDataStore.multiExitFailoverEnabled,
                    settingsDataStore.multiExitRules,
                    settingsDataStore.dnsMode,
                ) { enabled, failover, rules, dnsMode -> Config(enabled, failover, rules, dnsMode) }

            val data =
                combine(
                    proxyExitRepository.allExitsFlow,
                    serverRepository.allServersFlow,
                    whitelistDao.getEnabled(),
                    tunnelManager.exitsState,
                ) { exits, servers, whitelist, runtime ->
                    Data(
                        exits = exits,
                        servers = servers,
                        whitelistPackages = whitelist.map { it.packageName },
                        runtime = runtime.associateBy { it.id },
                    )
                }

            viewModelScope.launch {
                combine(config, data) { c, d -> buildState(c, d) }
                    // 每次开关/规则变化都要按出口数 × 规则行数算引用数: 放主线程会在长规则列表下卡 UI
                    .flowOn(Dispatchers.Default)
                    .collect { mutableState.value = it }
            }
        }

        private fun buildState(
            config: Config,
            data: Data,
        ): MultiExitUiState {
            rulesText = config.rules
            val reason =
                when {
                    config.dnsMode == DNS_MODE_SYSTEM -> MultiExitUiState.UnsupportedReason.SYSTEM_DNS
                    config.dnsMode == DNS_MODE_WHITELIST && data.whitelistPackages.isEmpty() ->
                        MultiExitUiState.UnsupportedReason.EMPTY_WHITELIST
                    // 域名分流: 只有域名规则能生效 (IP/应用规则在 DNS 阶段不可见);
                    // 命中规则的域名会被 D-5 提升进隧道 —— 可用, 但必须说清边界
                    config.dnsMode == DNS_MODE_DOMAIN_SPLIT -> MultiExitUiState.UnsupportedReason.DOMAIN_SPLIT
                    else -> null
                }
            val names = data.servers.associateBy { it.id }
            return MultiExitUiState(
                loading = false,
                enabled = config.enabled,
                failoverEnabled = config.failoverEnabled,
                // DOMAIN_SPLIT 可用但有限制 (提示而非禁用); SYSTEM / 空白名单才真的不可用
                supported = reason == null || reason == MultiExitUiState.UnsupportedReason.DOMAIN_SPLIT,
                unsupportedReason = reason,
                exits =
                    data.exits.map { exit ->
                        MultiExitRow(
                            exit = exit,
                            serverName = names[exit.serverId]?.name ?: "—",
                            referencedRules = ProxyExitRepository.countRulesReferencing(config.rules, exit.name),
                            runtime =
                                data.runtime[exit.name]?.let { snapshot ->
                                    ExitRuntimeUi(
                                        health = snapshot.health,
                                        activeConnections = snapshot.activeConnections,
                                        failTotal = snapshot.failTotal,
                                    )
                                },
                        )
                    },
                servers = data.servers,
                message = mutableState.value.message,
            )
        }

        fun setEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsDataStore.setMultiExitEnabled(enabled)
                routingTableStore.refresh()
                // 会话中途打开: 附加出口不会当场拉起 (那会把 SSH 建池塞进 UI 操作)。
                // 明确告知"重连后生效", 否则用户会以为规则立刻生效。
                if (enabled && vpnController.isVpnRunning()) {
                    mutableState.value = mutableState.value.copy(message = MSG_RECONNECT_REQUIRED)
                }
            }
        }

        fun setFailoverEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsDataStore.setMultiExitFailoverEnabled(enabled)
                routingTableStore.refresh()
            }
        }

        fun addExit(
            name: String,
            serverId: Long,
        ) {
            if (mutableState.value.exits.size >= MAX_ADDITIONAL_EXITS) {
                mutableState.value = mutableState.value.copy(message = MSG_MAX_EXITS)
                return
            }
            viewModelScope.launch {
                val result = proxyExitRepository.create(name, serverId)
                mutableState.value = mutableState.value.copy(message = addFailureMessage(result))
                routingTableStore.refresh()
            }
        }

        /**
         * 改名；[updateRules] = 同时把规则里的旧名改写为新名（否则那些规则会静默失效）。
         */
        fun renameExit(
            id: Long,
            name: String,
            updateRules: Boolean,
        ) {
            val from =
                mutableState.value.exits
                    .firstOrNull { it.exit.id == id }
                    ?.exit
                    ?.name
                    ?: return
            viewModelScope.launch {
                val result = proxyExitRepository.rename(id, name)
                if (result.isFailure) {
                    mutableState.value = mutableState.value.copy(message = MSG_RENAME_FAILED)
                    return@launch
                }
                if (updateRules && from != name) {
                    rewriteRules { ExitRuleText.renameExit(it, from, name) }
                }
                routingTableStore.refresh()
            }
        }

        fun setExitEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            val name =
                mutableState.value.exits
                    .firstOrNull { it.exit.id == id }
                    ?.exit
                    ?.name
            viewModelScope.launch {
                proxyExitRepository.setEnabled(id, enabled)
                routingTableStore.refresh()
                // 禁用: 新连接立即不再派发 (快照已重算); 已建立的连接保留, 空闲后回收资源。
                // 启用: 本次会话不自动拉起 (下次连接生效) —— 中途新建 SSH 池会让 UI 卡住且
                // 与 connect/disconnect 共享状态, 收益不值这个风险。
                if (!enabled && name != null) vpnController.onExitDisabled(name)
            }
        }

        /** 删除出口；[deleteRules] = 同时删掉引用它的规则行（否则那些规则会变成 unresolved）。 */
        fun deleteExit(
            id: Long,
            deleteRules: Boolean,
        ) {
            val row = mutableState.value.exits.firstOrNull { it.exit.id == id } ?: return
            viewModelScope.launch {
                proxyExitRepository.delete(id)
                if (deleteRules) {
                    rewriteRules { ExitRuleText.removeExitRules(it, row.exit.name).first }
                }
                routingTableStore.refresh()
                // 该出口可能还握着 SSH 池: 新连接已不再派发, 空闲后回收 (与"禁用"同一条路径)
                vpnController.onExitDisabled(row.exit.name)
            }
        }

        /** 规则文本的读-改-写 (只在内容真的变化时写回, 避免无谓的 DataStore 发射)。 */
        private suspend fun rewriteRules(transform: (String) -> String) {
            val current = settingsDataStore.multiExitRules.first()
            val updated = transform(current)
            if (updated != current) settingsDataStore.setMultiExitRules(updated)
        }

        /**
         * 添加失败的用户可读原因。
         *
         * 唯一索引冲突 (`ABORT`) 由 SQLite 抛 `SQLiteConstraintException`, 消息里带 `UNIQUE constraint
         * failed`, 这里翻译成"名字已存在"; 其余 (服务器不存在的外键失败等) 归"添加失败"。
         */
        private fun addFailureMessage(result: Result<Long>): String? {
            val error = result.exceptionOrNull() ?: return null
            val text = error.message.orEmpty()
            return if (text.contains("UNIQUE") || text.contains("constraint", ignoreCase = true)) {
                MSG_NAME_TAKEN
            } else {
                MSG_ADD_FAILED
            }
        }

        fun clearMessage() {
            mutableState.value = mutableState.value.copy(message = null)
        }

        companion object {
            const val MSG_NAME_TAKEN = "NAME_TAKEN"
            const val MSG_ADD_FAILED = "ADD_FAILED"
            const val MSG_MAX_EXITS = "MAX_EXITS"
            const val MSG_RENAME_FAILED = "RENAME_FAILED"
            const val MSG_RECONNECT_REQUIRED = "RECONNECT_REQUIRED"

            /**
             * 附加出口数量上限 (每个出口 = 独立 SSH 池 = 3 条会话 + 回程线程)。
             * 主出口之外最多 4 个: 再多就是 15 条常驻 SSH 会话, 手机侧没有收益只有耗电。
             */
            const val MAX_ADDITIONAL_EXITS = 4
        }

        private data class Config(
            val enabled: Boolean,
            val failoverEnabled: Boolean,
            val rules: String,
            val dnsMode: Int,
        )

        private data class Data(
            val exits: List<ProxyExit>,
            val servers: List<ServerConfig>,
            val whitelistPackages: List<String>,
            /** 出口名 → 运行态**不可变快照** (UI 不持有插件/可变条目)。 */
            val runtime: Map<String, TunnelManager.ExitSnapshot>,
        )
    }
