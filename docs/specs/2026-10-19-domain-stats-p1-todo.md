# 域名访问统计 P1 (建议 → 应用) — 任务单与冻结契约

> 上游: `2026-10-19-domain-access-stats-design.md` §8 / §9 / §13; P0 实施记录见 `2026-10-19-domain-stats-todo.md`。
> 本轮范围 = **设计稿 §8 的闭环**: 「待确认建议列表 → 复核 → 批量应用 → 即时生效 → 撤销」+ 名单叠加层。
>
> **明确不做 (留给后续轮次)**: Room v5 分块落盘、时间窗 (24h/7天/全部)、远端解析开关、
> 按应用 (uid)、自动建议 (P2)。统计本身仍是**会话内存**。

真机触发: 用户在 v1.2.2 上反馈「列表中也没有可配置到广告列表或域名白名单列表操作选项」——
P0 是只读采集, 缺的正是这条闭环。

---

## 1. 交付物 (实现结果, 全部有单测)

### 1.1 `domain/vpn/DomainSuffixSet.kt` (纯 JVM, 新增)

```kotlin
object DomainSuffixSet {
    fun normalize(host: String): String                        // trim + lowercase + trimEnd('.')
    fun matches(suffixes: Set<String>, host: String): Boolean   // host == s || host.endsWith(".$s")
}
```
逐级剥离最左标签做哈希查找 (O(标签数)), 与 `GfwListMatcher.matchSuffix` 同构。
**集合元素必须已 normalize** (`matches` 只规范化被查询的 host, 避免逐元素分配)。
`DomainSuffixSetTest` 6 例 (含 `notexample.com` / `example.com.evil.net` 不得命中)。

### 1.2 `domain/vpn/StatsRuleText.kt` (纯 JVM, 新增)

统计**自己写进**广告规则键的文本编辑器。与用户手写规则 (`ad_block_rules`)**分键存放**,
所以"移出"永远是精确删行, 不会删错用户规则 (设计 §8.0 纪律 ①)。

```kotlin
object StatsRuleText {
    fun blockRule(domain: String): String        // "||domain^" (入参先 normalize)
    fun exceptionRule(domain: String): String    // "@@||domain^" (加入例外 = 误杀救援)
    fun addLine(text: String, line: String): String     // 幂等; 保留其它行; 丢掉空行
    fun removeLine(text: String, line: String): String  // 只删完全相同的行
    fun contains(text: String, line: String): Boolean
    fun ruleCount(text: String): Int                    // 非空行数
}
```

### 1.3 `domain/vpn/StatsSuggestionStore.kt` (`@Singleton`, 新增)

会话内存, **不持久化** (应用前退出即丢弃 = 刻意的安全默认)。

```kotlin
enum class SuggestionTarget { AD_BLOCK, DOMAIN_LIST }
enum class SuggestionDirection { ADD, REMOVE }
data class SuggestionNote(connCount: Long, downBytes: Long, failedConnCount: Long)  // 数字原样存
data class SuggestionKey(domain: String, target: SuggestionTarget)
data class Suggestion(domain, target, direction, note: SuggestionNote, createdAt) { val key: SuggestionKey }

@Singleton class StatsSuggestionStore @Inject constructor() {
    val state: StateFlow<List<Suggestion>>
    fun toggle(domain, target, currentlyInList: Boolean, note: SuggestionNote): Boolean  // 已有 → 取消并返回 false
    fun put(domain, target, direction, note)
    fun remove(domain, target)
    fun clear()
    fun pendingDirection(domain, target): SuggestionDirection?
}
```

**两处与设计稿的偏差 (有意为之)**:
1. 去重键用 `(domain, target)` 而不是 `(domain, target, direction)` —— 同一对只保留一条,
   交互上"再点一次 = 取消"才无歧义 (设计 §9.2 的原话)。
2. 依据存**结构化数字**而不是拼好的字符串 —— ViewModel 不许拼用户可见文案, 文案由复核页
   按当前语言渲染 (i18n 硬要求)。

### 1.4 `data/local/preferences/SettingsDataStore.kt` (改)

新增 3 个键 (与用户手写规则分键, 设计 §8.0), 空值 = 移除键:

| 键 | 类型 | 用途 |
|---|---|---|
| `ad_block_rules_from_stats` | String | 统计生成的广告规则行 (`||d^` / `@@||d^`) |
| `domain_list_added_from_stats` | Set<String> | 强制走隧道 (代理名单叠加) |
| `domain_list_removed_from_stats` | Set<String> | 强制直连 (优先级最高) |

每条键配 `Flow` (UI) + `get*()` (读-改-写) + `set*()` (写)。

### 1.5 `data/local/DomainListManager.kt` (改)

叠加层 (设计 §8.3)。**`update()` 结构上不可能 touch 叠加层** —— 它只写磁盘清单文件 +
`domain_list_last_update`, 叠加层是独立键 + 独立内存字段。

```kotlin
val overlayVersion: StateFlow<Long>                  // 应用/撤销后自增; 统计页据此重算成员关系
fun applyOverlay(added: Set<String>, removed: Set<String>)   // 入参 normalize, 空串丢弃
suspend fun reloadOverlay()                          // 从 DataStore 两键读入
fun matches(host: String): Boolean                   // removed → false; added → true; else base
init { scope.launch { loadFromDisk(); reloadOverlay() } }
```

### 1.6 `domain/usecase/VpnController.kt` (改)

```kotlin
private suspend fun loadAdBlockRules(): GfwListMatcher   // 三源: 基础清单 + 用户手写 + 统计生成
private suspend fun readRules(reader: suspend () -> String): String?   // 单源失败不影响其它源
suspend fun adBlockMatcherSnapshot(): GfwListMatcher       // 按三源编译一份 (不动运行中的 matcher, 不看总开关)
suspend fun reloadAdBlockRules()                           // 已连接 → 重编译 + setMatcher (INFO); 未连接 → 只记 INFO
```

### 1.7 `domain/vpn/StatsListOps.kt` (纯 JVM, 新增) + `domain/usecase/StatsListApplier.kt` (`@Singleton`, 新增)

纯变换与 IO 分离: 变换全在 [StatsListOps] (可测, 11 例), 应用层只做读-改-写 + 让两边生效。

```kotlin
object StatsListOps {
    /** 三个键的快照 —— 它就是撤销 token: 应用前存一份, 撤销时原样写回 (精确可逆)。 */
    data class State(adBlockRulesFromStats: String?, domainListAdded: Set<String>, domainListRemoved: Set<String>)
    fun apply(state: State, items: List<Suggestion>): State
}

@Singleton class StatsListApplier @Inject constructor(settings, domainListManager, vpnController) {
    suspend fun apply(items: List<Suggestion>): StatsListOps.State   // 返回"应用前"快照 = 撤销 token; 幂等
    suspend fun restore(token: StatsListOps.State)                    // 原样写回 + 重新生效
}
// 内部私有: private val applyMutex = Mutex() 串行读-改-写 (广告规则是文本键, 并发会互相覆盖)
//          reload(touchedAdBlock) = reloadOverlay() [+ reloadAdBlockRules()]
```

**方向语义 (写死, `StatsListOpsTest` 锁死)**:
- `AD_BLOCK` + `ADD` → 追加 `||d^`, **并先摘掉统计自己写的 `@@||d^`** —— 例外优先级高于命中,
  不摘会出现"点了加入却没反应";
- `AD_BLOCK` + `REMOVE` → 有统计写的 `||d^` 就精确删行; **否则**追加 `@@||d^` (即"加入例外",
  对"基础清单/用户手写命中"的误杀同样有效, 且可逆);
- `DOMAIN_LIST` + `ADD`/`REMOVE` → 两集合互斥 (加一边必摘另一边)。

### 1.8 UI

- `DomainStatsScreen`: 每个**非 IP 字面量**行两个 chip「广告」「代理」—— 点击只进/出**待确认**,
  不直接改名单; 视觉: 实心 = 已在名单或已挂待确认, 后缀 `+`/`−` = 待确认方向, `?` = 成员关系未知。
  底部条 `待确认 N 条 · 查看` (pendingCount > 0 才显示) → 复核页 (新路由 `domain_stats_suggestions`)。
- `SuggestionReviewScreen` (+ `SuggestionReviewViewModel`): 新条目默认勾选, 逐条可取消勾选 / 单删 /
  全部丢弃, 底部 `应用 N 条`; 应用后 Snackbar `已应用 N 条 — 撤销` (精确回写 token)。
  **应用只在这一个页面** (单一路径 = 单一一套撤销逻辑)。
- `SettingsScreen`: 域名访问统计行 trailing 挂 `待确认 N` 徽标 (0 时不显示)。
- i18n: 四份 `strings.xml` 各新增 22 个键; 方向/目标文案用 `when` + `stringResource`
  (**不新增 `@StringRes` 枚举字段** → 不必动 `MainViewModelFormatTest` 的映射表)。

---

## 2. 纪律 (违反即回归)

① **名单成员判定不进热路径**: 只在开屏/手动刷新时算一次, 且带预算
   `MEMBERSHIP_BUDGET_MS = 60`: 用户自配的远端广告清单可能含上千条 `*` 正则, 20000 行逐个匹配会秒级
   卡住 refresh。超预算的行 `inAdBlock` 留 `null` (chip 显示 `?`), 用户点 chip 时再单独问一次。
   代理名单侧是纯内存后缀查表, 一直算完。
② **读-改-写必须串行** (`StatsListApplier.applyMutex`): 广告规则是文本键, 并发 apply 互相覆盖。
③ **撤销 = 精确回写原值** (不是"反向操作一遍"): 反向操作在"该域名本来就有统计规则"时会越撤越乱。
④ `故障归因` 只数 WARNING/ERROR: 应用/撤销成功打 **INFO**, 重载失败打 **WARNING**
   (文案 `广告名单重载失败 — <细节>`, 归因按 ` — ` 前缀切成静态键)。
⑤ 未连接时应用**只落库** (`reloadAdBlockRules` 记 INFO `下次连接生效`), 不是错误。
⑥ 只写"统计生成"的三个键, **绝不碰** `ad_block_rules` (用户手写) 与基础清单。

## 3. 波次与文件边界 (实际执行)

| 波 | 文件 | 结果 |
|---|---|---|
| W1 | `DomainSuffixSet` / `StatsRuleText` / `StatsSuggestionStore` + 3 测试 | 纯逻辑, 其余都依赖 |
| W2-A | `SettingsDataStore` + `DomainListManager` | 数据层 |
| W2-B | `VpnController` (三源 + reload + snapshot) | 核心 |
| W2-C | `StatsListOps` + `StatsListApplier` + 2 测试 | 应用层 |
| W3 | 统计页 chip/底部条 + 复核页 + 设置页徽标 + NavGraph + i18n ×4 | 展示层 |
| W4 | 本文档 / AGENTS.md / 验收清单 S13 / 版本号 → 发布 | 收尾 |

## 4. 验收

**单测 (323 → 360, +37)**: `DomainSuffixSetTest`(5) / `StatsRuleTextTest`(9) / `StatsSuggestionStoreTest`(8) /
`StatsListOpsTest`(11) / `StatsListApplierTest`(4, 覆盖三键写法 + reload 接线 + token 语义)。

**门禁**: ktlint / detekt / lint / testDebugUnitTest / assembleDebug / assembleRelease 六条全绿。

**真机 (见验收清单 S13)**:
1. 统计页点「广告 +」→ 底部 `待确认 1 条` → 复核页 → 应用 → 该域名**无需重连**即回 `0.0.0.0`;
2. Snackbar 撤销 → 立刻恢复可解析;
3. 代理名单 chip → 应用 → `DNS 传输模式 = DOMAIN_SPLIT` 下命中即走隧道 (日志 `命中域名代理名单`);
4. 应用时日志出现 `广告名单已重载 — 命中规则即时生效 (无需重连)`; 未连接时出现
   `广告名单已保存 · 未连接 — 下次连接生效`;
5. 四不变量 (`背压丢段`/`回程闸门阻塞`/`地址惰性失效`/`TUN 写丢弃`) 仍为 0, 无新增故障类。
