package cn.srv0.sshinjector.ui.screen.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.usecase.ProxyExitRepository
import cn.srv0.sshinjector.domain.vpn.routing.ExitRule
import cn.srv0.sshinjector.domain.vpn.routing.ExitRuleParser
import cn.srv0.sshinjector.domain.vpn.routing.ExitRuleText
import cn.srv0.sshinjector.domain.vpn.routing.RoutingProbe
import cn.srv0.sshinjector.domain.vpn.routing.RoutingTable
import cn.srv0.sshinjector.domain.vpn.routing.RoutingTableStore
import cn.srv0.sshinjector.domain.vpn.routing.RuleIssueInfo
import cn.srv0.sshinjector.ui.screen.whitelist.InstalledApp
import cn.srv0.sshinjector.ui.screen.whitelist.InstalledAppsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 规则编辑器展示态。 */
data class MultiExitRulesUiState(
    val loading: Boolean = true,
    val text: String = "",
    val savedText: String = "",
    val issues: List<RuleIssueInfo> = emptyList(),
    val unresolvedExits: List<String> = emptyList(),
    /** 未知出口不算语法错, 但要单独提示 (否则用户以为规则坏了)。 */
    val tooManyRules: Boolean = false,
    val knownExits: List<String> = emptyList(),
    /** 规则里是否含 IP/CIDR 目标 (REMOTE 假 IP 模式下它们只对字面量 IP 连接生效, 需要提示)。 */
    val hasNetworkRule: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = true,
) {
    val dirty: Boolean get() = text != savedText
    val canSave: Boolean get() = !tooManyRules && dirty
}

/**
 * 保存完成后的状态合并 (纯函数, 便于单测)。
 *
 * `savedText` = **本次真正落库的文本**, `text` 保持调用瞬间的最新输入 —— 落库是异步的,
 * 期间用户可能继续敲字; 用启动时捕获的旧快照整体覆盖 (老实现) 会把新文本吞掉。
 */
internal fun MultiExitRulesUiState.afterSave(savedText: String): MultiExitRulesUiState = copy(savedText = savedText)

/**
 * 规则编辑器 (设计 §13.3)。
 *
 * 三条纪律:
 * 1. **实时校验用的是同一份解析器** (`ExitRuleParser`), UI 从不自写判定;
 * 2. 语法错只丢该行 (行号提示), 不阻断保存 —— 只有"超行数"才整体拒绝;
 * 3. 保存即写 DataStore + 重建路由快照, 但 P1 阶段快照还没接进数据面 (配置可见、暂不生效)。
 */
@HiltViewModel
class MultiExitRulesViewModel
    @Inject
    constructor(
        private val settingsDataStore: SettingsDataStore,
        private val proxyExitRepository: ProxyExitRepository,
        private val routingTableStore: RoutingTableStore,
        private val installedAppsSource: InstalledAppsSource,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(MultiExitRulesUiState())
        val state: StateFlow<MultiExitRulesUiState> = mutableState.asStateFlow()

        init {
            // 顺序执行而不是两个 launch: 并行时"加载应用列表"可能先完成, 随后被
            // 规则分析里那份 `mutableState.value.apps` (还是空表) 覆盖 —— 应用选择器会永久空着
            viewModelScope.launch {
                val saved = settingsDataStore.multiExitRules.first()
                val exits = proxyExitRepository.getEnabled().map { it.name }
                mutableState.value = analyze(saved, saved, exits, emptyList(), appsLoading = true)

                val apps = installedAppsSource.load()
                mutableState.value = mutableState.value.copy(apps = apps, appsLoading = false)
            }
        }

        fun updateText(text: String) {
            mutableState.value =
                analyze(
                    text = text,
                    savedText = mutableState.value.savedText,
                    knownExits = mutableState.value.knownExits,
                    apps = mutableState.value.apps,
                    appsLoading = mutableState.value.appsLoading,
                )
        }

        fun save() {
            val current = mutableState.value
            if (!current.canSave) return
            val savedText = current.text
            viewModelScope.launch {
                settingsDataStore.setMultiExitRules(savedText)
                routingTableStore.refresh()
                // 落库期间用户可能继续输入: 只把 savedText 记成**本次落库的内容**,
                // text 保留最新输入 (老实现用启动时捕获的 current 整体回写会把新文本吞掉)。
                mutableState.update { it.afterSave(savedText) }
            }
        }

        /** 追加一条应用规则 (应用选择器点"添加"时用); 已存在同目标则不重复追加。 */
        fun appendAppRule(
            packageName: String,
            exitId: String,
        ) {
            // 必须插到**规则区最前面**: 行序即优先级, append 会让 app 规则输给域名规则;
            // 而且文本以 `default -> x` 结尾时 append 会把 default 挤到非末行 → 被解析器丢弃。
            val updated = ExitRuleText.prependAppRule(mutableState.value.text, packageName, exitId)
            if (updated != mutableState.value.text) updateText(updated)
        }

        /** 测试框: 用当前**未保存**的文本评估 (用户改一行就能立刻验证, 不必先保存)。 */
        suspend fun test(query: String): RoutingProbe.Evaluation =
            withContext(Dispatchers.Default) {
                val table = RoutingTable.fromText(mutableState.value.text, mutableState.value.knownExits.toSet())
                RoutingProbe.evaluate(table, query)
            }

        private fun analyze(
            text: String,
            savedText: String,
            knownExits: List<String>,
            apps: List<InstalledApp>,
            appsLoading: Boolean,
        ): MultiExitRulesUiState {
            val parsed = ExitRuleParser.parse(text)
            val known = knownExits.toSet()
            val unresolved =
                parsed.rules
                    .filter { it.exitId != "default" && it.exitId !in known }
                    .map { it.exitId }
                    .distinct()
                    .sorted()
            return MultiExitRulesUiState(
                loading = false,
                text = text,
                savedText = savedText,
                issues = parsed.issues.sortedBy { it.lineNo },
                unresolvedExits = unresolved,
                tooManyRules = !parsed.ok,
                knownExits = knownExits,
                hasNetworkRule = parsed.rules.any { it is ExitRule.Network },
                apps = apps,
                appsLoading = appsLoading,
            )
        }
    }
