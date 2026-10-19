# 域名访问统计 — 可执行任务列表 (P0)

> 设计: `2026-10-19-domain-access-stats-design.md` · 性能审计: `2026-10-19-domain-stats-perf-audit.md`
> **本轮只做 P0** (只读采集: 内存聚合 + 页面 + 本地解析 + 导出)。P1/P2 见 §5。
> 并发开发纪律: **§1 的接口契约是硬约束**, 三路并发按它写, 不许自行改签名; 改动前先看 §3 文件冲突矩阵。

---

## 0. P0 范围与完成定义 (DoD)

**交付**: 设置页新增「域名访问统计」入口 → 页面展示本会话访问过的域名 / 可得的真实 IP /
连接数 / 上下行字节 / 最近访问 / 端口; 可搜索、排序、筛选; 可对单个域名**手动解析真实 IP**; 可导出 CSV; 可清空。

**不做**: 任何名单写入 (P1 的待确认建议列表)、持久化 (P1 的 Room)、远端解析开关 (P1)、按应用 (P2)。

**DoD (全部满足才算完)**:
1. `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew ktlintCheck detekt testDebugUnitTest lint assembleDebug` 全绿;
2. 60s 数据面快照的资源节出现 `域名统计 N/20000(淘汰 K)`;
3. 真机跑 S0/S1 后导出日志, 页面能列出访问过的域名且连接数 ≥ 1 (无 adb, 这条由用户执行);
4. 既有不变量零变化: `背压丢段` / `回程闸门阻塞` / `地址惰性失效` / `TUN 写丢弃` 与 v1.2.1 基线同量级。

---

## 1. 接口契约 (并发开发的唯一真相源)

### 1.1 `domain/vpn/DomainStatsStore.kt` (新文件, T-01 拥有)

包 `cn.srv0.sshinjector.domain.vpn`。**必须是纯 JVM 可测**: 不引用 Android 类、不引用 `VpnController`、
不写日志、不起线程。

```kotlin
/** 连接关闭原因 (由 TcpStateMachine 的 `why` 映射; 只用于 failedConnCount 归类)。 */
enum class CloseKind { CLIENT_CLOSED, REMOTE_EOF, INTERNAL_FAILURE }

/** 不可变快照 (UI / 导出只读这个, 不读内部可变对象)。 */
data class DomainStatSnapshot(
    val domain: String,
    val isIpLiteral: Boolean,
    val connCount: Long,
    val failedConnCount: Long,
    val upBytes: Long,
    val downBytes: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastPort: Int,
    val realIp: String?,
    val realIp2: String?,
)

@Singleton
class DomainStatsStore @Inject constructor() {
    companion object {
        const val MAX_DOMAINS: Int = 20000
        const val TRIM_INTERVAL_MS: Long = 30_000
        const val WINDOW_MS: Long = 7L * 24 * 3600 * 1000
    }

    /** 热路径: 每次 TCP 连接结束调一次。禁止分配 / 禁止 IO / 禁止日志。 */
    fun recordClose(
        domain: String, isIpLiteral: Boolean, dstPort: Int,
        upBytes: Long, downBytes: Long, startedAtMs: Long,
        kind: CloseKind, nowMs: Long,
    )

    /** 真实 IP 标注: 只更新**已存在**的条目 (不新建), 见设计 §5.1-⑤。 */
    fun attachRealIp(domain: String, ip: String)

    /** 全量快照 (未排序); 调用方在 Default 线程排序。 */
    fun snapshot(): List<DomainStatSnapshot>

    fun size(): Int
    fun trimmedTotal(): Long
    fun clear()

    /** 低频: 只在 PacketProcessor 的 100ms 巡检里调, 内部按 TRIM_INTERVAL_MS 门控。返回本次淘汰数。 */
    fun maybeTrim(nowMs: Long): Int

    /** 快照资源节用: "域名统计 123/20000(淘汰 0)"。O(1)。 */
    fun diagnostics(): String
}
```

**内部实现硬约束** (审计 §2.3/§2.4/§2.5):
- 条目是**普通 class** (不是 `data class`), 可变字段是**普通 `var`**, 只在 `synchronized(entry)` 内读写
  (32 位 ABI 防撕裂读 + 防丢更新); 字段: `connCount/failedConnCount/upBytes/downBytes/firstSeenAt/lastSeenAt/lastPort/realIp/realIp2`。
- `recordClose` 命中路径**零分配**: `val e = map[domain]; if (e != null) { synchronized(e){…}; return }`;
  未命中才建条目 + `putIfAbsent` (并发下拿到已有的就更新它)。**禁止 `computeIfAbsent(key){…}`**。
- 超上限时**只置 `@Volatile trimPending = true`**; 淘汰只在 `maybeTrim` 里做 (排序 → 压回 `MAX_DOMAINS * 9 / 10`),
  并累计 `trimmedTotal`。
- `maybeTrim` 同时清掉 `lastSeenAt < nowMs - WINDOW_MS` 的条目。
- `attachRealIp` 只在条目存在时写入; 去重 (同 IP 不重复写); 满了 `realIp2` 就丢弃 (不引 Set)。

### 1.2 `domain/vpn/DomainStatsCsv.kt` (新文件, T-05 拥有)

```kotlin
internal object DomainStatsCsv {
    /** 表头 + 每行一条; 字段内的 `"` / `,` / 换行按 RFC4180 转义。返回全文 (UTF-8)。 */
    fun render(rows: List<DomainStatSnapshot>): String

    /** "sshinjector-domains-yyyyMMdd-HHmmss.csv" (Locale.US)。 */
    fun fileName(nowMs: Long): String
}
```
列顺序 (固定, 单测锁死): `domain,real_ip,real_ip2,is_ip_literal,conn_count,failed_conn_count,up_bytes,down_bytes,first_seen_ms,last_seen_ms,last_port`

### 1.3 `domain/vpn/DnsInterceptor.kt` (新增一个函数, T-04 拥有)

```kotlin
/**
 * 本地视角解析 (受保护 socket, 不经隧道): 供"解析真实 IP"用。
 * **阻塞**, 必须在 IO 线程或挂起上下文中调用。返回 A/AAAA 字面量 (去重、保序), 失败/未连接返回空表。
 * protectSocket 未设置 (= 未连接) 直接返回 emptyList, 不打 WARNING。
 */
fun resolveLocally(host: String, timeoutMs: Int = LOCAL_RESOLVE_TIMEOUT_MS): List<String>
```
- 复用 `sendDnsOverProtectedSocket` 的姿势: `DatagramSocket()` → `protectSocket?.invoke(s)` → `soTimeout` → send/receive;
- DNS 服务器取 `systemDnsServers.firstOrNull() ?: "8.8.8.8"`;
- 用 dnsjava (`Message` / `Record` / `Type.A` / `Type.AAAA`) 组查询、解析 ANSWER 段;
- **不写 `onDnsResponse`、不进 `dnsCache`、不动 `realIpToDomain`** (纯旁路查询)。

### 1.4 `domain/vpn/TcpStateMachine.kt` + `PacketProcessor.kt` (修改, T-02 拥有)

```kotlin
// TcpStateMachine 构造: 新增**带默认值**的尾部参数 (不改任何既有测试的调用点)
class TcpStateMachine(
    private val tunnelManager: TunnelManager,
    private val tunWriterProvider: () -> TunSink?,
    private val stats: PacketStats,
    private val sshIoDispatcher: SshIoDispatcher,
    private val domainStats: DomainStatsStore = DomainStatsStore(),
)

// PacketProcessor 构造: 新增注入; 并新增一个诊断出口
class PacketProcessor @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val sshIoDispatcher: SshIoDispatcher,
    private val domainStats: DomainStatsStore,
) {
    fun domainStatsDiagnostics(): String = domainStats.diagnostics()
}
```
`PacketProcessor` 的 100ms 巡检循环里追加 `domainStats.maybeTrim(System.currentTimeMillis())`。

`closeTcpConnection` 里 (**锁外**, 紧邻 `TCP 连接结束` 那行日志, 见 `:1851`):
- **域名只查一次**: `val d = dnsInterceptor?.lookupDomain(conn.dstIp.hostAddress)`, 日志里用它拼
  `"$d(${conn.dstPort})"` / `"${conn.dstIp.hostAddress}:${conn.dstPort}"` **取代该处的 `target(conn)`**
  (`target()` 有 14 个调用点, 别动它本身);
- 调 `domainStats.recordClose(...)`, `kind` 由 `why` 映射:
  `graceful` → `REMOTE_EOF`; `!notifyBrowser` → `CLIENT_CLOSED`; 其余 → `INTERNAL_FAILURE`。

### 1.5 `ui/screen/settings/DomainStatsViewModel.kt` (新文件, T-03 拥有)

```kotlin
enum class DomainStatsSort { CONNECTIONS, TRAFFIC, RECENT, DOMAIN }
enum class DomainStatsFilter { ALL, WITH_REAL_IP, UNKNOWN_IP, FAILED }

data class DomainStatsUiState(
    val rows: List<DomainStatSnapshot> = emptyList(),
    val total: Int = 0, val cap: Int = 20000, val trimmed: Long = 0,
    val query: String = "", val sort: DomainStatsSort = DomainStatsSort.CONNECTIONS,
    val filter: DomainStatsFilter = DomainStatsFilter.ALL,
    val loading: Boolean = false, val resolving: String? = null, val message: String? = null,
)

@HiltViewModel
class DomainStatsViewModel @Inject constructor(
    private val store: DomainStatsStore,
    private val dnsInterceptor: DnsInterceptor,
) : ViewModel() {
    val state: StateFlow<DomainStatsUiState>
    fun refresh()
    fun setQuery(q: String)          // 300ms debounce
    fun setSort(s: DomainStatsSort)
    fun setFilter(f: DomainStatsFilter)
    fun clear()
    fun resolveRealIp(domain: String)   // viewModelScope + Dispatchers.IO; 回写 store + 刷新
    suspend fun exportCsv(context: Context): Result<String>
    fun consumeMessage()
}
```
- 排序/筛选一律在 `Dispatchers.Default`; **绝不**从数据面触发 emit (审计 §3.1);
- 导出复用 `LogViewModel.exportToDownloads` 的 MediaStore 姿势 (`RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS`),
  内容由 `DomainStatsCsv.render(...)` 生成。

### 1.6 `ui/screen/settings/DomainStatsScreen.kt` (新文件, T-03 拥有) + 接线

```kotlin
@Composable
fun DomainStatsScreen(onBack: () -> Unit = {}, viewModel: DomainStatsViewModel = hiltViewModel())
```
接线 (两处编辑):
- `ui/screen/settings/SettingsScreen.kt`: 加参数 `onNavigateToDomainStats: () -> Unit = {}` + 在「网络」section
  的「域名列表」**上方**加一个 `SettingsRow` (title `R.string.settings_domain_stats`, subtitle `…_desc`);
- `ui/navigation/NavGraph.kt`: `composable("domain_stats") { DomainStatsScreen(onBack = { navController.popBackStack() }) }`
  + settings 块里加 `onNavigateToDomainStats = { navController.navigate("domain_stats") }`。

**字面量**: 所有用户可见文案走 `stringResource`, **同步 4 份** `values` / `values-en` / `values-ru` / `values-zh-rCN`。
长度格式化复用 `MainViewModel.formatBytes(...)` (`ui/viewmodel/MainViewModel.kt:440` 的 companion 函数)。

### 1.7 `VpnController.kt` (2 行编辑, T-06 拥有)

`:1311` 资源节: `resourceDiagnostics = "TUN 写队列 … · ${dnsInterceptor.diagnostics()} · ${packetProcessor.domainStatsDiagnostics()}"`

---

## 2. 任务卡

### T-01 · `DomainStatsStore` 聚合器 + 单测 【Wave A】
- 新增: `domain/vpn/DomainStatsStore.kt`, `app/src/test/java/cn/srv0/sshinjector/domain/vpn/DomainStatsStoreTest.kt`
- 实现 §1.1 全部; 遵守 §1.1 的三条内部硬约束
- 单测必须覆盖 (每条一个用例, 名字用反引号中文/英文均可):
  1. 首次 `recordClose` 建条目, `connCount=1 / upBytes / downBytes / firstSeenAt=lastSeenAt=now / lastPort`;
  2. 同域名二次 `recordClose` 累加, `firstSeenAt` 不变, `lastSeenAt` 前进;
  3. `kind != CLIENT_CLOSED && != REMOTE_EOF` → `failedConnCount` 递增 (另两种不增);
  4. `attachRealIp` 对**不存在**的域名**不建条目**;
  5. `attachRealIp` 去重 + 第二个 IP 进 `realIp2` + 第三个被丢弃;
  6. `snapshot()` 返回的是副本 (改快照不影响后续 `recordClose` 结果);
  7. `clear()` 后 `size()==0` 且 `trimmedTotal()` 归零;
  8. **淘汰**: 上限临时调不下去 → 用 `internal` 测试钩子 `trimNowForTest()` 造 `MAX_DOMAINS+1` 条 →
     `maybeTrim` 后 `size() <= MAX_DOMAINS * 9 / 10` 且 `trimmedTotal > 0`; **且断言 `recordClose` 自身不触发排序**
     (通过"超限后 size 仍 > MAX 直到 maybeTrim 被调用"来表达 —— 这条锁的是"trim 不在热路径");
  9. `maybeTrim` 清掉超窗条目 (`lastSeenAt < now - WINDOW_MS`);
  10. `diagnostics()` 文案格式 `域名统计 N/20000(淘汰 K)`;
  11. **并发**: 8 线程 × 各 200 次 `recordClose` 同一域名 → `connCount == 1600` 且 `upBytes` 精确 (无丢更新)。
  12. `maybeTrim` 在 `TRIM_INTERVAL_MS` 内二次调用是 no-op (返回 0)。
- ⚠️ 测试用**有界**等待/纯同步, 禁止无界 `while` 自旋 (CI 曾因此挂 26 分钟)
- 若为造超限数据需要, 可加 `internal fun trimNowForTest(): Int` 与 `internal val capForTest` ——
  **只允许测试钩子, 不许改 §1.1 的公开签名**

### T-04 · `DnsInterceptor.resolveLocally` 【Wave A】
- 编辑: `domain/vpn/DnsInterceptor.kt` (只加函数 + 一个 companion 常量, 不动既有逻辑)
- 实现 §1.3; 加常量 `LOCAL_RESOLVE_TIMEOUT_MS = 3000`
- 必须: `protectSocket == null` → 直接 `emptyList()` (静默); 异常 → 空表 + `Log.d` (不打 WARNING, 避免污染故障归因);
  两个查询 (A、AAAA) 各用独立 socket, 单查询超时 `timeoutMs`, `finally { socket.close() }`
- 单测: 只覆盖**纯部分**——若不好拆, 就**不写单测**, 在卡片末尾标注 `⛔ 待真机` (设备上点"解析真实 IP"看结果)

### T-05 · `DomainStatsCsv` 渲染器 + 单测 【Wave A】
- 新增: `domain/vpn/DomainStatsCsv.kt`, `app/src/test/java/cn/srv0/sshinjector/domain/vpn/DomainStatsCsvTest.kt`
- 实现 §1.2
- 单测: 表头精确匹配; 普通行; **含 `,` 的字段被引号包裹**; **含 `"` 的字段被翻倍转义**; 空表只有表头;
  `fileName` 正则 `^sshinjector-domains-\d{8}-\d{6}\.csv$`

### T-02 · 数据面接线 + 快照出口 【Wave B】
- 编辑: `domain/vpn/TcpStateMachine.kt`, `domain/vpn/PacketProcessor.kt`
- 新增: `app/src/test/java/cn/srv0/sshinjector/domain/vpn/TcpDomainStatsTest.kt`
- 严格按 §1.4; 注意 `closeTcpConnection` 里统计调用**必须在 `synchronized(conn)` 之外** (审计 §2.5-③)
- 单测: 沿用 `TcpCloseBannerTest` 的构造姿势 (`mock<TunnelManager>` + `tunSinkOf{…}` + `PacketStats()` +
  `SshIoDispatcher()` + 注入自己的 `DomainStatsStore`); 断言一条连接关闭后 `store.size()==1` 且
  `connCount==1`; 用 `awaitUntil` 有界等待 (5s deadline, daemon 线程)
- ⚠️ 不许改 `target(conn)` 本身 (14 个调用点)

### T-03 · 统计页 + 接线 + i18n 【Wave B】
- 新增: `ui/screen/settings/DomainStatsScreen.kt`, `ui/screen/settings/DomainStatsViewModel.kt`
- 编辑: `ui/screen/settings/SettingsScreen.kt`, `ui/navigation/NavGraph.kt`, `res/values{,-en,-ru,-zh-rCN}/strings.xml`
- 实现 §1.5 + §1.6; UI 布局按设计 §9.2 (但**不含** P1 的「广告」「代理」chip 与待确认列表)
- UI 性能纪律 (审计 §3.1): 只在开屏/手动刷新产生列表; `LazyColumn(key = domain)`; 排序在 `Default`;
  搜索 300ms debounce; 不写任何 per-item 的 `remember` 重算
- 新增文案 key (4 份都要有, 值可直译):
  `settings_domain_stats`, `settings_domain_stats_desc`, `domain_stats_title`, `domain_stats_empty`,
  `domain_stats_search_hint`, `domain_stats_sort_connections`, `domain_stats_sort_traffic`,
  `domain_stats_sort_recent`, `domain_stats_sort_domain`, `domain_stats_filter_all`,
  `domain_stats_filter_with_ip`, `domain_stats_filter_unknown_ip`, `domain_stats_filter_failed`,
  `domain_stats_resolve_ip`, `domain_stats_resolving`, `domain_stats_export`, `domain_stats_clear`,
  `domain_stats_cleared`, `domain_stats_export_ok`, `domain_stats_export_failed`,
  `domain_stats_resolve_failed`, `domain_stats_ip_unavailable`, `domain_stats_count`,
  `domain_stats_label_up`, `domain_stats_label_down`, `domain_stats_label_failed`

### T-06 · 快照资源节 【Wave B】
- 编辑: `domain/usecase/VpnController.kt` (§1.7, 只改 `:1311` 那一行)

### T-07 · 文档同步 【Wave C】
- `AGENTS.md`: 可观测性小节加 `域名统计` 计数; `Tests` 节加新单测条目
- `docs/specs/2026-10-18-device-verification-checklist.md` §4: 加 `域名统计 N/M(淘汰 K)` 行 + 阈值
- 设计稿 §13 标注 P0 已落地

---

## 3. 波次与文件冲突矩阵

| Wave | 并发任务 | 触碰文件 (互不重叠) |
|---|---|---|
| **A** | T-01, T-04, T-05 | `DomainStatsStore.kt`+test · `DnsInterceptor.kt` · `DomainStatsCsv.kt`+test |
| **B** | T-02, T-03, T-06 | `TcpStateMachine.kt`+`PacketProcessor.kt`+test · `DomainStatsScreen.kt`+`DomainStatsViewModel.kt`+`SettingsScreen.kt`+`NavGraph.kt`+4×strings · `VpnController.kt` |
| **C** | T-07 + 门禁 | 仅 md |
| **D** | 门禁 + 修 + 版本号 + 出包 | `app/build.gradle.kts` |

**依赖**: B 依赖 A 的**契约** (已在 §1 钉死, 故可并行); C 依赖 A+B; D 依赖 C。

---

## 4. 门禁与验收

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew ktlintCheck --offline
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew detekt --offline
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest --offline
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew lint --offline
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug --offline
```
**已知会踩的坑** (写代码时就要避开, 别等门禁):
- 行宽 **120**; 缩进 4, 续行 8;
- detekt `LongParameterList` 是 **函数 ≥6 个 / 构造器 ≥10 个** (`detekt.yml:133-136`) —— 函数超了要么减参,
  要么按仓库既有先例加 `@Suppress("LongParameterList")` (见 `PacketWriter.kt:33`);
  `recordClose` 的 8 参是热路径刻意不包 config 对象, 已 suppress;
- detekt 禁 `catch (IOException)`; 分开 catch (子类在前);
- 魔法数字白名单 0-4096, 其余必须具名常量 (行宽/超时/上限);
- Compose 里取字符串用 `stringResource` (用 `LocalContext.getString` 会被 Android Lint 报
  `LocalContextGetResourceValueCall`);
- 别忘了 `@Suppress` 不是第一选择 —— 先改代码。

**真机验收 (用户执行, 无 adb)**: 装 APK → 连接 → 浏览几个站点 → 打开设置→域名访问统计 →
截图/导出日志回来; 判读点: 列表有域名、`域名统计 N/20000(淘汰 0)`、四个不变量零变化。

---

## 5. 本轮不做 (P1/P2, 已在设计稿)

- P1: 待确认建议列表 + 批量应用 + `adBlockRulesFromStats` / `domainList{Added,Removed}FromStats` 叠加层 +
  `reloadAdBlockRules()` + 撤销 + Room v5 (dirty set 分块) + 远端解析开关 (池内 exec)
- P2: 按应用 uid、QUIC/UDP 域名归属、自动建议

---

## 7. 真机反馈修复 (2026-10-19, v1.2.3)

用户装 v1.2.2 后手测, **采集侧正常** (数据面快照 `域名统计 4/20000 → 13 → 16(淘汰 0)`, 全部丢弃计数与
v1.2.1 基线一致), 但暴露两点:

1. **「解析真实 IP」每次都"解析不到 IP"** — 真 bug, 已修。根因 (两层):
   - 白名单模式 (dnsMode=2) 下 App 自己的流量也进隧道 (不做 `addDisallowedApplication`),
     `ConnectivityManager.activeNetwork` **就是本 VPN**, 而它的 DNS 恒为 `VpnNetwork.TUN_IP`
     (`SshVpnService.buildVpnBuilder` 的 `addDnsServer(10.0.0.2)`) → `systemDnsServers = ["10.0.0.2"]`;
     受保护 socket 把查询发到**物理链路**上的 10.0.0.2 = 本机 TUN 地址, 物理网上无人应答 → 3s×2 静默超时。
   - 诊断盲: 失败只 `Log.d` (不进应用内日志) → 用户只看到"解析不到", 现场零线索。
   修法: `getSystemDnsServers()` 遍历 `cm.allNetworks` 并排除 `hasTransport(TRANSPORT_VPN)` (App 实际会用的
   那条物理网络优先, 其余按 `NET_CAPABILITY_VALIDATED` 排前, v4 先于 v6 去重); `resolveLocally` 再滤一次
   `VpnNetwork.TUN_IP` 兜底 (上游只剩它就**直接返回空、一个 socket 都不建**, 单测
   `DnsInterceptorTest.resolveLocally never queries the vpn tun ip as upstream` 用 `protectSocket = fail` 钉住);
   IP 字面量条目 (`isIpLiteral`) 不再给解析按钮 (解析的是域名, 那种行点了必然空等超时)。
   诊断补强: 失败/不可用打 **INFO** (`本地解析无结果/不可用 · 域名 — 上游 …`), 成功打 DEBUG —— `故障归因`
   只数 WARNING/ERROR, 用户点一次解析不会凭空多一类故障。
   **顺带修好一条既有哑路**: 同一个 `systemDnsServers` 还喂"非 A/AAAA (MX/TXT) 的受保护直查", 那条路径在
   白名单模式下本来就是黑的。
2. **列表里没有"加入广告过滤 / 域名白名单"的操作入口** — **设计如此, 不是遗漏**: P0 只读采集,
   「先收集为待确认建议列表, 复核后批量应用」(用户决策 ③) 属于 P1 (设计稿 §8), 本轮未做。

门禁: ktlint / detekt / test (323 例, +1) / lint / assembleDebug 全绿。


---

## 6. 实施结果 (2026-10-19)

**三波并发 + 四次集中验证**, 五道门全绿 (`ktlintCheck` / `detekt` / `testDebugUnitTest` (**322 例, 新增 21**) /
`lint` / `assembleDebug`)。

**新增**: `domain/vpn/DomainStatsStore.kt` (+`DomainStatsStoreTest`, 12 例) ·
`domain/vpn/DomainStatsCsv.kt` (+`DomainStatsCsvTest`, 8 例) ·
`ui/screen/settings/DomainStatsViewModel.kt` · `ui/screen/settings/DomainStatsScreen.kt` ·
`test/.../TcpDomainStatsTest.kt` (1 例)。
**修改**: `TcpStateMachine`(+采集点) · `PacketProcessor`(+注入/巡检/诊断出口) · `DnsInterceptor`(+`resolveLocally`) ·
`VpnController`(快照资源节 +1 字段) · `SettingsScreen` · `NavGraph` · `res/values{,-en,-ru,-zh-rCN}/strings.xml`。

### 6.1 与契约的偏差 (全部为契约缺陷或门禁要求, 逐条记录)

| # | 偏差 | 原因 |
|---|---|---|
| 1 | `attachRealIp(domain, ip, nowMs)` → **`attachRealIp(domain, ip)`** | `nowMs` 在实现里用不到, 只能挂 `@Suppress("UnusedParameter")`; 契约我自己写的, 趁 T-03 未开工改掉 |
| 2 | `recordClose` 8 参 → 需 `@Suppress("LongParameterList")` | **TODO §4 写错了**: detekt 的 `functionThreshold` 是 **6** (构造器才是 10, `detekt.yml:133-136`)。仓库既有先例 `PacketWriter.kt:33` |
| 3 | `Entry.accumulate` 6 参 → 同样 `@Suppress` | 同上; 拆 config 对象会在**每次连接关闭**多一次分配, 与热路径纪律冲突 |
| 4 | `DnsInterceptor` 类级 `@Suppress("TooManyFunctions")` | 该类**本来就已 39 个函数** (阈值 40), `resolveLocally` 是第 40 个。曾把私有辅助 `queryRecordsLocally` **内联进 `resolveLocally`** 消掉一个, 仍差一个 —— 函数片段必须复用该类**私有**的 `protectSocket` / `systemDnsServers`, 拆出去要另开一条配置通道。仓库既有先例: `TcpStateMachine` |
| 5 | ktlint 三条隐性规则 (契约没写) | ① **函数 ≥2 参必须换行** (ktlint 官方风格默认, `.editorconfig` 没覆盖); ② `@Inject constructor` 不能写在类名同行 → 类头三行, **类体整体 +4 缩进** (与 `DnsInterceptor`/`PacketProcessor` 同形); ③ import 顺序按字典序 → `DnsInterceptor` 要排在 `DomainStatSnapshot` **之前** |
| 6 | `DomainStatsCsv` 文件名模式必须单引号包字面量 | `"sshinjector-domains-yyyyMMdd…"` 里的 `i` 被 `SimpleDateFormat` 当模式字母抛 `IllegalArgumentException` —— **被单测真抓到** (`fileName is deterministic…` 用例), 已写成 `"'sshinjector-domains-'yyyyMMdd-HHmmss'.csv'"` |
| 7 | i18n 28 个 key (契约 26) | 多出 `domain_stats_label_first` / `domain_stats_label_last` (展开项的时间标签), 四份文件**集合完全一致** (脚本校验) |
| 8 | ViewModel 的 `message` 是**语言无关哨兵** (`MSG_EXPORT_OK` 等) | VM 拿不到 `Context`, 不能在 VM 里解析资源; 页面映射成 `stringResource` 再 Toast |

### 6.2 明确没做 (遗留, 不是遗漏)

- **DNS 侧顺手标注真实 IP** (设计 §5 辅: `onDnsResponse` 命中 A/AAAA 时对**已存在**条目 `attachRealIp`):
  本轮**故意没做** —— 要给 `DnsInterceptor` 加一个 store 依赖 (动它 39→41 个函数 + 单测构造点), 而 P0
  的"真实 IP"由手动解析按钮已经覆盖。P1 再收。
- 真机验收 (S12): **未做** —— 构建机无 adb, 需用户装包后走 S12 并导出日志 (判据已写进清单)。
- P1 (待确认建议列表 / 名单叠加层 / Room v5 / 远端解析开关) 与 P2 (按应用 uid) 未动。

