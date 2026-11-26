# 多出口规则简单化 + 一键路由 + 建议/导入导出 (2026-11-26)

目标：把"文本规则编辑器"改成**默认出口 + 少量例外**的简单决策；从域名统计/连接日志一键生成规则（可撤销）；
基于统计自动给出候选建议；支持导入/导出。**数据面、匹配语义、存储格式不变**（仍以 `multi_exit_rules` 文本为唯一权威）。

## 文件归属（并行工作流，禁止跨域改文件）

| 工作流 | 文件 |
|---|---|
| A 契约/纯逻辑 | 新建 `domain/vpn/routing/` 下：`SimpleRoutingPolicy.kt`、`RoutingRuleText.kt`、`RoutingRuleEditor.kt`、`RoutingSuggestions.kt`、`LogDomainParser.kt`；对应测试 |
| B 规则页 | `ui/screen/routing/MultiExitRulesViewModel.kt`、`MultiExitRulesScreen.kt`；新建 `res/values*/strings_routing_editor.xml` |
| C 域名统计 | `ui/screen/settings/DomainStatsViewModel.kt`、`DomainStatsScreen.kt`；新建 `res/values*/strings_domain_routing.xml` |
| D 日志页 | `ui/screen/settings/LogViewModel.kt`、`LogScreen.kt`；新建 `res/values*/strings_log_routing.xml` |

A 的新类型先写出来（B/C/D 按下列签名编码，编辑期不依赖编译）。禁止改 `ExitRuleParser`/`RoutingTable`/`SettingsDataStore`/`RoutingTableStore`。

## A. 共享 API（必须逐字实现）

### `SimpleRoutingPolicy.kt`

```kotlin
data class SimpleAppRoute(val packageName: String, val exitId: String)
data class SimpleDomainRoute(val suffix: String, val exitId: String)

data class SimpleRoutingPolicy(
    val defaultExit: String = ExitId.DEFAULT,
    val apps: List<SimpleAppRoute> = emptyList(),
    val domains: List<SimpleDomainRoute> = emptyList(),
) {
    fun upsertApp(packageName: String, exitId: String): SimpleRoutingPolicy
    fun removeApp(packageName: String): SimpleRoutingPolicy
    fun upsertDomain(suffix: String, exitId: String): SimpleRoutingPolicy
    fun removeDomain(suffix: String): SimpleRoutingPolicy
    fun withDefault(exitId: String): SimpleRoutingPolicy
}

enum class SimplePolicyUnsupported { ADVANCED_RULE, INVALID_RULE, TOO_MANY_RULES }

sealed interface SimplePolicyResult {
    data class Ok(val policy: SimpleRoutingPolicy) : SimplePolicyResult
    data class Unsupported(val reason: SimplePolicyUnsupported) : SimplePolicyResult
}

object SimpleRoutingPolicyCodec {
    fun parse(text: String): SimplePolicyResult
    fun toText(policy: SimpleRoutingPolicy): String
    fun normalizeDomain(raw: String): String?   // trim/lowercase/去 `*.`；非法返回 null
    fun isValidPackage(raw: String): Boolean
}
```

规则：
- `parse` 用 `ExitRuleParser.parse`；出现 `INVALID_*`/`DEFAULT_ROUTE_REJECTED`/`DEFAULT_NOT_LAST`/`TOO_MANY_RULES` → `Unsupported(INVALID_RULE)`；
- `Network` 或任何 `port != null` 的 App/Domain → `Unsupported(ADVANCED_RULE)`；
- `App`/`Domain`/`Default` 转成简单模型；无 `default` 行时 `defaultExit = ExitId.DEFAULT`；
- `toText`：app 行保持列表顺序；domain 行按后缀长度降序（同长按字母）；`default -> x` 永远最后；
- `upsert*` 去重（同 package/suffix 只留一条）；`normalizeDomain` 允许 `example.com` / `*.example.com`；
- 纯 JVM、无 Android 依赖。

### `RoutingRuleText.kt`

```kotlin
object RoutingRuleText {
    fun upsertApp(text: String, packageName: String, exitId: String): String
    fun upsertDomain(text: String, domain: String, exitId: String): String
}
```

- 行级编辑，**保留注释/空行/高级行/用户排版**；
- `upsertApp`：删掉所有 `app:<pkg>`（含 `:port` 变体）后插到开头注释区之后（`ExitRuleText.prependAppRule` 的位置语义）；
- `upsertDomain`：删掉同名/同后缀（含 `*.`、`:port` 变体）后，按"最长后缀优先"插到第一个**不更长**的域名规则之前；没有域名规则则插到 `default` 之前；没有 `default` 则末尾；
- 非法 exit/package/domain 原样返回。

### `RoutingRuleEditor.kt`

```kotlin
@Singleton
class RoutingRuleEditor @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val routingTableStore: RoutingTableStore,
) {
    data class ApplyResult(val previousText: String, val enabled: Boolean)
    suspend fun upsertApp(packageName: String, exitId: String): ApplyResult
    suspend fun upsertDomain(domain: String, exitId: String): ApplyResult
    suspend fun replaceText(text: String): ApplyResult
    suspend fun undo(previousText: String)
}
```

- `Mutex` 串行读改写；`multiExitRules.first()` + `multiExitEnabled.first()`；
- 写后 `routingTableStore.refresh()`；`undo` 原样回写（空串 = 清空）。

### `RoutingSuggestions.kt`

```kotlin
enum class RoutingSuggestionReason { FAILED, TRAFFIC }

data class RoutingSuggestion(
    val domain: String,
    val reason: RoutingSuggestionReason,
    val failedConnCount: Long,
    val downBytes: Long,
    val lastSeenAt: Long,
)

object RoutingSuggestions {
    const val MIN_FAILED = 2
    const val MIN_TRAFFIC_BYTES = 10L * 1024 * 1024
    fun pick(
        rows: List<DomainStatSnapshot>,
        hasDomainRule: (String) -> Boolean,
        canRoute: Boolean,
        limit: Int = 5,
    ): List<RoutingSuggestion>
}
```

- `canRoute=false` → 空；跳过 `isIpLiteral`、已有域名规则；
- 命中 `failedConnCount >= MIN_FAILED` 或 `downBytes >= MIN_TRAFFIC_BYTES`；
- 排序：失败次数降序 → 下行字节降序 → `lastSeenAt` 降序；最多 `limit`。

### `LogDomainParser.kt`

```kotlin
object LogDomainParser {
    fun domainOf(message: String): String?
}
```

识别：`TCP 连接结束 · host(port)`、`隧道通道就绪 · host(port)`、`远端不解析该目标 (…) · host:port`、
`SOCKS 连接被拒 · host(port)`；返回 host；IP 字面量/`?`/空 → null。

## B. 规则页（简单模式 + 高级 + 导入/导出）

- `MultiExitRulesUiState` 增加：`mode: RulesEditorMode`、`simpleSupported: Boolean`、`simpleUnsupported: SimplePolicyUnsupported?`、
  `defaultExit: String`、`appRoutes: List<SimpleAppRoute>`、`domainRoutes: List<SimpleDomainRoute>`、`exits: List<String>`、`message: String?`。
- 初始化：读 `multiExitRules` + `getEnabled()`；`SimpleRoutingPolicyCodec.parse` 成功 → SIMPLE，否则 ADVANCED + banner。
- 简单模式 UI：
  - 顶部 `默认出口` 下拉（`default` = 主出口，显示本地化"主出口"）；
  - `应用例外` 列表（app 名 + 出口下拉 + 删除）；`添加应用` 复用现有应用选择器；
  - `域名例外` 列表（域名 + 出口下拉 + 删除）；`添加域名` 弹输入框；
  - 小字说明：规则自动按 `应用 > 域名(最长后缀) > 默认` 排序；
  - 高级模式保留现有文本框/校验/测试框/应用选择器。
- 模式切换：SIMPLE→ADVANCED 先把 policy 写回 text；ADVANCED→SIMPLE 先 parse，失败留在 ADVANCED 并提示。
- 保存：SIMPLE 用 `codec.toText(policy)` 写 `setMultiExitRules` + `routingTableStore.refresh()`；ADVANCED 走原逻辑。
- 导入/导出（顶部 `TextButton`）：
  - `ActivityResultContracts.CreateDocument("text/plain")` + `OpenDocument`；
  - 文件名 `sshinjector-routing-yyyyMMdd-HHmmss.txt`；导入上限 64KB；导入后重新 parse/mode；
  - 结果用 Toast/Snackbar。
- 不新增导航路由。

字符串键（`strings_routing_editor.xml`，4 个 locale）：
`routing_mode_simple` `routing_mode_advanced` `routing_default_exit` `routing_main_exit`
`routing_apps` `routing_domains` `routing_add_app` `routing_add_domain`
`routing_domain_hint` `routing_domain_invalid` `routing_no_apps` `routing_no_domains`
`routing_priority_note` `routing_simple_unsupported` `routing_saved`
`routing_import` `routing_export` `routing_import_ok` `routing_import_failed`
`routing_export_ok` `routing_export_failed` `routing_remove`

## C. 域名统计页（一键出口 + 建议 + 撤销）

- `DomainStatsUiState` 增加：`routingExits: List<String>`（`default` + 启用出口）、`routingEnabled: Boolean`、
  `domainExits: Map<String, String>`（有效出口）、`routingSuggestions: List<RoutingSuggestion>`、
  `routingMessage: String?`、`routingCanUndo: Boolean`。
- 注入 `ProxyExitRepository`、`RoutingTableStore`、`RoutingRuleEditor`；`init` 收集 `allExitsFlow` 的 enabled 项；
  `refresh()` 里用 `routingTableStore.current().table` 计算 `domainExitFor` 与 `RoutingSuggestions.pick`。
- 行内（非 IP 字面量）增加 `出口: <name>` chip；点开 `DropdownMenu` 选出口 → `editor.upsertDomain`；
  成功后刷新并弹 **Snackbar + 撤销**（`editor.undo(previous)`）。
- 顶部/列表首项增加"路由建议"卡片（`routingSuggestions` 非空时）：域名 + 原因（失败 N 次 / 下行 X）+ 出口下拉；
  应用同样走 `editor.upsertDomain` + 撤销。
- 多出口未启用时仍可保存，`routingMessage` 区分"已保存（多出口未启用）"。
- 不新增路由。

字符串键（`strings_domain_routing.xml`，4 个 locale）：
`domain_routing_exit` `domain_routing_main_exit` `domain_routing_pick_exit`
`domain_routing_applied` `domain_routing_applied_disabled` `domain_routing_undo`
`domain_routing_failed` `domain_routing_suggestions` `domain_routing_suggestion_failed`
`domain_routing_suggestion_traffic` `domain_routing_apply` `domain_routing_failed_apply`

## D. 日志页（一键固定域名 + 撤销）

- `LogEntry` 增加 `val domain: String? = null`；收集日志时用 `LogDomainParser.domainOf(line.message)` 填充。
- 注入 `RoutingRuleEditor`、`ProxyExitRepository`、`SettingsDataStore`；`exits: StateFlow<List<String>>`（default + 启用）；
  `routingMessage`/`routingCanUndo`。
- 日志行：`domain != null` 时尾部一个"出口"TextButton；点开统一 `AlertDialog` 列出出口（default 主出口）；
  选择后 `editor.upsertDomain(domain, exit)`，Snackbar + 撤销。
- 导出/复制仍是全量条目，不因新增按钮改变。
- 不新增路由。

字符串键（`strings_log_routing.xml`，4 个 locale）：
`log_routing_action` `log_routing_title` `log_routing_main_exit`
`log_routing_applied` `log_routing_applied_disabled` `log_routing_undo` `log_routing_failed`

## 验收

- 新纯逻辑单测：`SimpleRoutingPolicyTest`、`RoutingRuleTextTest`、`RoutingSuggestionsTest`、`LogDomainParserTest`。
- 全量 `testDebugUnitTest`、ktlint、detekt、lint、assembleDebug、assembleRelease。
- 不改变数据面/匹配语义；规则文本仍是唯一存储。


---

## 实施结果 (2026-11-26)

- **A 纯逻辑**: `SimpleRoutingPolicy`/`RoutingRuleText`/`RoutingRuleEditor`/`RoutingSuggestions`/`LogDomainParser` + 45 条单测（含新增 integration 用例：生成文本经 `RoutingTable` 命中 app → 最长域名 → default）。
- **B 规则页**: SIMPLE/ADVANCED 双模式、默认出口、应用/域名例外、自动优先级、SAF 导入/导出（64KB 上限）、保存走 `RoutingRuleEditor` 与一键写入串行化。
- **C 域名统计**: 行内 `出口: <name>` chip、建议卡片（失败≥2 / 下行≥10MB）、Snackbar 撤销；`default` 本地化为主出口；无效域名/单标签不写规则。
- **D 日志页**: `LogEntry.domain` 由 `LogDomainParser` 填充；行尾"出口"→统一出口对话框；Snackbar 撤销；`APPLIED_DISABLED` 区分多出口未启用。
- **验证**: 633 单测全绿、ktlint、detekt、lint、assembleDebug、assembleRelease 全绿；数据面/匹配语义/存储格式未改。
