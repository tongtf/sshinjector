package cn.srv0.sshinjector.ui.screen.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ProxyExitRepository
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_SYSTEM
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_WHITELIST
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.dnsTransportFor
import cn.srv0.sshinjector.domain.vpn.exit.ProxyExit
import cn.srv0.sshinjector.domain.vpn.routing.RoutingTableStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 出口列表行: 出口 + 服务器名 + 引用它的规则数 (删除前提示)。 */
data class MultiExitRow(
    val exit: ProxyExit,
    val serverName: String,
    val referencedRules: Int,
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
                ) { exits, servers, whitelist -> Data(exits, servers, whitelist.map { it.packageName }) }

            viewModelScope.launch {
                combine(config, data) { c, d -> buildState(c, d) }.collect { mutableState.value = it }
            }
        }

        private fun buildState(
            config: Config,
            data: Data,
        ): MultiExitUiState {
            rulesText = config.rules
            val transport = dnsTransportFor(config.dnsMode, data.whitelistPackages)
            val reason =
                when {
                    config.dnsMode == DNS_MODE_SYSTEM -> MultiExitUiState.UnsupportedReason.SYSTEM_DNS
                    config.dnsMode == DNS_MODE_WHITELIST && data.whitelistPackages.isEmpty() ->
                        MultiExitUiState.UnsupportedReason.EMPTY_WHITELIST
                    else -> null
                }
            val names = data.servers.associateBy { it.id }
            return MultiExitUiState(
                loading = false,
                enabled = config.enabled,
                failoverEnabled = config.failoverEnabled,
                supported = transport != DnsInterceptor.DnsTransport.SYSTEM,
                unsupportedReason = reason,
                exits =
                    data.exits.map { exit ->
                        MultiExitRow(
                            exit = exit,
                            serverName = names[exit.serverId]?.name ?: "—",
                            referencedRules = ProxyExitRepository.countRulesReferencing(config.rules, exit.name),
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
            viewModelScope.launch {
                val result = proxyExitRepository.create(name, serverId)
                mutableState.value = mutableState.value.copy(message = addFailureMessage(result))
                routingTableStore.refresh()
            }
        }

        fun renameExit(
            id: Long,
            name: String,
        ) {
            viewModelScope.launch {
                proxyExitRepository.rename(id, name)
                routingTableStore.refresh()
            }
        }

        fun setExitEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                proxyExitRepository.setEnabled(id, enabled)
                routingTableStore.refresh()
            }
        }

        fun deleteExit(id: Long) {
            viewModelScope.launch {
                proxyExitRepository.delete(id)
                routingTableStore.refresh()
            }
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
        )
    }
