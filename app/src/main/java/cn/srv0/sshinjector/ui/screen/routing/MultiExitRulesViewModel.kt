package cn.srv0.sshinjector.ui.screen.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.usecase.ProxyExitRepository
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.domain.vpn.routing.ExitRule
import cn.srv0.sshinjector.domain.vpn.routing.ExitRuleParser
import cn.srv0.sshinjector.domain.vpn.routing.ExitRuleText
import cn.srv0.sshinjector.domain.vpn.routing.RoutingProbe
import cn.srv0.sshinjector.domain.vpn.routing.RoutingRuleEditor
import cn.srv0.sshinjector.domain.vpn.routing.RoutingTable
import cn.srv0.sshinjector.domain.vpn.routing.RuleIssueInfo
import cn.srv0.sshinjector.domain.vpn.routing.SimpleAppRoute
import cn.srv0.sshinjector.domain.vpn.routing.SimpleDomainRoute
import cn.srv0.sshinjector.domain.vpn.routing.SimplePolicyResult
import cn.srv0.sshinjector.domain.vpn.routing.SimplePolicyUnsupported
import cn.srv0.sshinjector.domain.vpn.routing.SimpleRoutingPolicy
import cn.srv0.sshinjector.domain.vpn.routing.SimpleRoutingPolicyCodec
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

/** 规则编辑器的两种视图 (设计 2026-11-26 §B)。 */
enum class RulesEditorMode {
    /** 默认出口 + 少量例外 (新用户默认看到这一屏)。 */
    SIMPLE,

    /** 原始文本编辑器 (含网络段规则 / 端口限定等简单模式表达不了的能力)。 */
    ADVANCED,
}

/**
 * 规则编辑器展示态。
 *
 * 简单模式的三组数据 (`defaultExit` / `appRoutes` / `domainRoutes`) **永远由 `text` 派生** ——
 * 规则文本是唯一权威 (设计 §B), 状态里不留第二份可写副本, 否则"界面显示"与"实际生效"会分叉。
 */
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
    /** 当前视图; 文本无法用简单模型表达时恒为 [RulesEditorMode.ADVANCED]。 */
    val mode: RulesEditorMode = RulesEditorMode.ADVANCED,
    /** 当前文本能否用简单模型表达 (`false` = 只能留在高级模式)。 */
    val simpleSupported: Boolean = true,
    /** 不能表达的具体原因 (banner 提示用); `null` = 可表达。 */
    val simpleUnsupported: SimplePolicyUnsupported? = null,
    val defaultExit: String = ExitId.DEFAULT,
    val appRoutes: List<SimpleAppRoute> = emptyList(),
    val domainRoutes: List<SimpleDomainRoute> = emptyList(),
    /** 已启用的附加出口名 (不含主出口 `default`), 供下拉框使用。 */
    val exits: List<String> = emptyList(),
    /** 一次性提示: **字符串资源键名** (ViewModel 拿不到 Context, 文案由页面解析)。 */
    val message: String? = null,
) {
    val dirty: Boolean get() = text != savedText
    val canSave: Boolean get() = !tooManyRules && dirty

    /** 出口下拉候选: 主出口 + 已启用的附加出口。 */
    val exitOptions: List<String> get() = (listOf(ExitId.DEFAULT) + exits).distinct()
}

/**
 * 保存完成后的状态合并 (纯函数, 便于单测)。
 *
 * `savedText` = **本次真正落库的文本**, `text` 保持调用瞬间的最新输入 —— 落库是异步的,
 * 期间用户可能继续敲字; 用启动时捕获的旧快照整体覆盖 (老实现) 会把新文本吞掉。
 */
internal fun MultiExitRulesUiState.afterSave(savedText: String): MultiExitRulesUiState = copy(savedText = savedText)

/**
 * 规则编辑器 (设计 §13.3 + 2026-11-26 §B 简单模式)。
 *
 * 四条纪律:
 * 1. **实时校验用的是同一份解析器** (`ExitRuleParser`/`SimpleRoutingPolicyCodec`), UI 从不自写判定;
 * 2. 语法错只丢该行 (行号提示), 不阻断保存 —— 只有"超行数"才整体拒绝;
 * 3. 保存即写 DataStore + 重建路由快照;
 * 4. 简单模式的每次编辑都**重新序列化成文本**再整体重算, 于是 `dirty` / `savedText` 语义与高级模式一致。
 */
@HiltViewModel
class MultiExitRulesViewModel
    @Inject
    constructor(
        private val settingsDataStore: SettingsDataStore,
        private val proxyExitRepository: ProxyExitRepository,
        private val routingRuleEditor: RoutingRuleEditor,
        private val installedAppsSource: InstalledAppsSource,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(MultiExitRulesUiState())
        val state: StateFlow<MultiExitRulesUiState> = mutableState.asStateFlow()

        init {
            // 顺序执行而不是两个 launch: 并行时"加载应用列表"可能先完成, 随后被
            // 规则分析里那份 `mutableState.value.apps` (还是空表) 覆盖 —— 应用选择器会永久空着
            viewModelScope.launch {
                val saved = settingsDataStore.multiExitRules.first()
                val enabled = proxyExitRepository.getEnabled().map { it.name }
                val base =
                    MultiExitRulesUiState(
                        exits = enabled,
                        knownExits = enabled,
                        appsLoading = true,
                    )
                // 文本可表达 → 直接进简单模式 (新用户默认体验); 否则高级模式 + banner
                mutableState.value = recompute(saved, saved, base, mode = initialMode(saved))

                val apps = installedAppsSource.load()
                mutableState.update { it.copy(apps = apps, appsLoading = false) }
            }
            // 出口增删/启停即时反映到下拉候选; 只更新候选与派生字段, 绝不覆盖用户正在编辑的策略
            viewModelScope.launch {
                proxyExitRepository.allExitsFlow.collect { exits ->
                    val names = exits.filter { it.enabled }.sortedBy { it.priority }.map { it.name }
                    mutableState.update { current ->
                        when {
                            current.exits == names -> current
                            // 初始加载还没发出第一条状态: 只补候选, 不要让空文本覆盖真正的规则
                            current.loading -> current.copy(exits = names, knownExits = names)
                            else ->
                                recompute(
                                    text = current.text,
                                    savedText = current.savedText,
                                    base = current.copy(exits = names, knownExits = names),
                                )
                        }
                    }
                }
            }
        }

        fun updateText(text: String) {
            val current = mutableState.value
            mutableState.value = recompute(text, current.savedText, current)
        }

        /** 模式切换: 简单 → 高级先把策略写回文本; 高级 → 简单先试解析, 失败原地提示。 */
        fun setMode(mode: RulesEditorMode) {
            val current = mutableState.value
            if (current.mode == mode) return
            if (mode == RulesEditorMode.ADVANCED) {
                // 简单模式的每次编辑都已写回 text; 只在文本与策略不一致时才重新序列化,
                // 否则"什么都没改就切到高级"会凭空 dirty (注释/排版被抹掉也算一次修改)
                val text = canonicalOrOriginal(current.text, policyOf(current))
                mutableState.value = recompute(text, current.savedText, current, mode = RulesEditorMode.ADVANCED)
                return
            }
            when (val result = SimpleRoutingPolicyCodec.parse(current.text)) {
                is SimplePolicyResult.Ok ->
                    mutableState.value =
                        recompute(current.text, current.savedText, current, mode = RulesEditorMode.SIMPLE)
                is SimplePolicyResult.Unsupported ->
                    mutableState.update {
                        it.copy(
                            mode = RulesEditorMode.ADVANCED,
                            simpleSupported = false,
                            simpleUnsupported = result.reason,
                            message = MSG_SIMPLE_UNSUPPORTED,
                        )
                    }
            }
        }

        fun setDefaultExit(exitId: String) {
            val current = mutableState.value
            if (current.mode != RulesEditorMode.SIMPLE) return
            val normalized = normalizeExitRef(exitId) ?: return
            applyPolicy(policyOf(current).withDefault(normalized))
        }

        /** 添加应用例外 (应用选择器点"添加"时用); 同包名已存在则改出口 (upsert)。 */
        fun addAppRoute(
            packageName: String,
            exitId: String,
        ) {
            val current = mutableState.value
            if (current.mode != RulesEditorMode.SIMPLE) return
            if (!SimpleRoutingPolicyCodec.isValidPackage(packageName)) return
            val normalized = normalizeExitRef(exitId) ?: return
            applyPolicy(policyOf(current).upsertApp(packageName, normalized))
        }

        fun removeAppRoute(packageName: String) {
            val current = mutableState.value
            if (current.mode != RulesEditorMode.SIMPLE) return
            applyPolicy(policyOf(current).removeApp(packageName))
        }

        /** 改应用例外出口 —— 与 [addAppRoute] 同一条 upsert 路径。 */
        fun setAppExit(
            packageName: String,
            exitId: String,
        ) = addAppRoute(packageName, exitId)

        /** 添加域名例外; 非法域名只提示, 不改文本。 */
        fun addDomainRoute(
            domain: String,
            exitId: String,
        ) {
            val current = mutableState.value
            if (current.mode != RulesEditorMode.SIMPLE) return
            val suffix = SimpleRoutingPolicyCodec.normalizeDomain(domain)
            if (suffix == null) {
                mutableState.update { it.copy(message = MSG_DOMAIN_INVALID) }
                return
            }
            val normalized = normalizeExitRef(exitId) ?: return
            applyPolicy(policyOf(current).upsertDomain(suffix, normalized))
        }

        fun removeDomainRoute(domain: String) {
            val current = mutableState.value
            if (current.mode != RulesEditorMode.SIMPLE) return
            val suffix = SimpleRoutingPolicyCodec.normalizeDomain(domain) ?: return
            applyPolicy(policyOf(current).removeDomain(suffix))
        }

        /** 改域名例外出口 —— 与 [addDomainRoute] 同一条 upsert 路径。 */
        fun setDomainExit(
            domain: String,
            exitId: String,
        ) {
            val current = mutableState.value
            if (current.mode != RulesEditorMode.SIMPLE) return
            val suffix = SimpleRoutingPolicyCodec.normalizeDomain(domain) ?: return
            val normalized = normalizeExitRef(exitId) ?: return
            applyPolicy(policyOf(current).upsertDomain(suffix, normalized))
        }

        /**
         * 导入 (SAF): 文本整体替换, 按可表达性切模式; **不自动保存** —— 用户仍需点保存,
         * 这样才能先看校验/提示再决定是否落库。
         */
        fun applyImportedText(text: String) {
            val current = mutableState.value
            mutableState.value =
                recompute(text, current.savedText, current, mode = initialMode(text), message = MSG_IMPORT_OK)
        }

        /** 导出内容 = 当前会生效的文本 (简单模式先序列化, 否则导出的可能是旧排版)。 */
        fun exportText(): String {
            val current = mutableState.value
            return if (current.mode == RulesEditorMode.SIMPLE) {
                SimpleRoutingPolicyCodec.toText(policyOf(current))
            } else {
                current.text
            }
        }

        fun save() {
            val current = mutableState.value
            if (!current.canSave) return
            val savedText =
                if (current.mode == RulesEditorMode.SIMPLE) {
                    SimpleRoutingPolicyCodec.toText(policyOf(current))
                } else {
                    current.text
                }
            viewModelScope.launch {
                // 与统计页/日志页共用同一把编辑锁: 并发一键路由不会互相覆盖
                routingRuleEditor.replaceText(savedText)
                // 落库期间用户可能继续输入: 只把 savedText 记成**本次落库的内容**,
                // text 保留最新输入 (老实现用启动时捕获的 current 整体回写会把新文本吞掉)。
                mutableState.update { latest ->
                    val merged = latest.afterSave(savedText).copy(message = MSG_SAVED)
                    // 简单模式的落库内容就是规范化文本: 没有并发编辑时一并同步 text,
                    // 否则"导入的手写排版"保存完仍会显示可保存 (text != savedText)
                    if (latest.mode == RulesEditorMode.SIMPLE && latest.text == current.text) {
                        merged.copy(text = savedText)
                    } else {
                        merged
                    }
                }
            }
        }

        /** 一次性提示消费 (页面 Toast 后调用)。 */
        fun consumeMessage() {
            mutableState.update { if (it.message == null) it else it.copy(message = null) }
        }

        /** 追加一条应用规则 (高级模式的应用选择器用); 已存在同目标则不重复追加。 */
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

        /** 初始/导入时的模式判定: 能表达 → 简单模式, 否则高级模式。 */
        private fun initialMode(text: String): RulesEditorMode =
            when (SimpleRoutingPolicyCodec.parse(text)) {
                is SimplePolicyResult.Ok -> RulesEditorMode.SIMPLE
                is SimplePolicyResult.Unsupported -> RulesEditorMode.ADVANCED
            }

        /**
         * 以 [base] 的出口/应用列表为基底, 用 [text] 重算全部派生字段。
         *
         * 简单模式三组数据始终由文本派生 (见 [MultiExitRulesUiState] KDoc), 所以这里只做"解析 → 铺字段",
         * 不保留任何调用方传入的策略副本。
         */
        private fun recompute(
            text: String,
            savedText: String,
            base: MultiExitRulesUiState,
            mode: RulesEditorMode = base.mode,
            message: String? = base.message,
        ): MultiExitRulesUiState {
            val parsed = ExitRuleParser.parse(text)
            val known = base.knownExits.toSet()
            val unresolved =
                parsed.rules
                    .filter { it.exitId != ExitId.DEFAULT && it.exitId !in known }
                    .map { it.exitId }
                    .distinct()
                    .sorted()
            val policyResult = SimpleRoutingPolicyCodec.parse(text)
            val policy = (policyResult as? SimplePolicyResult.Ok)?.policy
            return MultiExitRulesUiState(
                loading = false,
                text = text,
                savedText = savedText,
                issues = parsed.issues.sortedBy { it.lineNo },
                unresolvedExits = unresolved,
                tooManyRules = !parsed.ok,
                knownExits = base.knownExits,
                hasNetworkRule = parsed.rules.any { it is ExitRule.Network },
                apps = base.apps,
                appsLoading = base.appsLoading,
                mode = mode,
                simpleSupported = policy != null,
                simpleUnsupported = (policyResult as? SimplePolicyResult.Unsupported)?.reason,
                defaultExit = policy?.defaultExit ?: ExitId.DEFAULT,
                appRoutes = policy?.apps ?: emptyList(),
                domainRoutes = policy?.domains ?: emptyList(),
                exits = base.exits,
                message = message,
            )
        }

        /** 简单模式编辑的统一出口: 策略 → 文本 → 整体重算 (`savedText` 不动 → dirty 语义自然保留)。 */
        private fun applyPolicy(policy: SimpleRoutingPolicy) {
            val current = mutableState.value
            val text = SimpleRoutingPolicyCodec.toText(policy)
            mutableState.value = recompute(text, current.savedText, current)
        }

        private fun policyOf(state: MultiExitRulesUiState): SimpleRoutingPolicy =
            SimpleRoutingPolicy(
                defaultExit = state.defaultExit,
                apps = state.appRoutes,
                domains = state.domainRoutes,
            )

        /** 文本已表达同一策略时原样返回, 避免切换模式就抹掉用户的注释/排版。 */
        private fun canonicalOrOriginal(
            text: String,
            policy: SimpleRoutingPolicy,
        ): String {
            val parsed = SimpleRoutingPolicyCodec.parse(text)
            return if (parsed is SimplePolicyResult.Ok && parsed.policy == policy) {
                text
            } else {
                SimpleRoutingPolicyCodec.toText(policy)
            }
        }

        /** 规则引用里的出口名规范化 (允许 `default`, 其余走 [ExitId.normalize])。 */
        private fun normalizeExitRef(raw: String): String? {
            val trimmed = raw.trim().lowercase()
            if (trimmed == ExitId.DEFAULT) return ExitId.DEFAULT
            return ExitId.normalize(trimmed)
        }

        companion object {
            /** 保存成功。 */
            const val MSG_SAVED = "routing_saved"

            /** 当前文本无法用简单模式表达 (切模式被拒 / banner 同源)。 */
            const val MSG_SIMPLE_UNSUPPORTED = "routing_simple_unsupported"

            /** 域名例外输入非法。 */
            const val MSG_DOMAIN_INVALID = "routing_domain_invalid"

            /** 导入成功 (文件读失败由页面自己提示)。 */
            const val MSG_IMPORT_OK = "routing_import_ok"
        }
    }
