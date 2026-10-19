# 域名访问统计 (Domain Access Stats) — 功能设计

> 状态: 设计稿 (2026-10-19)。**未动代码**。开工前先确认第 12 节的待决问题。
> 一句话: 设置里加一页「域名访问统计」, 记录使用期间访问过的域名 / 可得的真实 IP / 访问频率,
> 并能据此把域名加入或移出「广告过滤规则」或「域名代理名单」: **先收集为待确认建议, 复核后批量应用**。

---

## 0. 为什么值得做

现在"该不该把某个域名加进广告过滤 / 代理名单"完全靠猜: 用户看到某个应用在偷偷连一堆域名,
但拿不到"它到底连了哪些域名、连了多少次、走了多少流量"。本功能把这层观测补齐,
并且把结论**回流**到既有的两个名单机制里 (闭环), 而不是只做个好看的表。

---

## 1. 目标 / 非目标

### 目标

| 编号 | 目标 |
|---|---|
| G1 | 记录使用期间访问过的**域名**列表 (TUN 内所有 TCP 连接) |
| G2 | 记录每个域名的**访问频率**: 连接次数 + 上行/下行字节 + 首次/最近访问时间 |
| G3 | 记录可得的**真实 IP**, 缺失时如实标"未知"而不是编一个 |
| G4 | 从统计结果**增/减**「广告过滤规则」与「域名代理名单」, 且**可逆**(能撤回) —— 流程为"收集建议 → 复核 → 批量应用" (第 8 节) |
| G5 | 导出 (CSV / 纯域名清单), 便于离线分析或粘贴到别的工具 |

### 非目标 (明确不做, 避免范围膨胀)

| 编号 | 非目标 | 理由 |
|---|---|---|
| N1 | 记录 URL / path / 请求内容 / SNI | 只有 IP 层可见性, 且是隐私红线 |
| N2 | 记录 DNS 查询报文原文 | 与统计无关, 日志里已有概览 |
| N3 | 云端上传 / 跨设备同步 | 无服务端, 且这是本机诊断数据 |
| N4 | 自动改名单 (未经确认) | 误杀代价高 (见第 7.3 节), 一律"建议 + 用户确认" |

---

## 2. 现有可复用资产 (盘点)

| 组件 | 位置 | 对本功能的意义 |
|---|---|---|
| **TCP 连接终点** | `TcpStateMachine.closeTcpConnection` (`TcpStateMachine.kt:1825`) | **唯一的采集点**。每条 TCP 连接都从这里消失, 且此处**已经在**拼日志: 域名 (`target()` `:180` → `dnsInterceptor.lookupDomain(ip)`)、`forwardedBytes`(上行 `:203`)、`downstreamBytes`(下行)、`createdAt`(存活)、`dstPort`。加统计不引入新的热路径成本 |
| 假 IP → 域名 | `DnsInterceptor.ipToDomain` / `lookupDomain` (`:315`,`:330`) | 拿到域名的主力 |
| 真实 IP → 域名 | `DnsInterceptor.realIpToDomain` (cap **8192**, `:318`, 只由 `onDnsResponse` 写入 `:814`) | **真实 IP 的唯一来源**, 且只在"本机真解析"时才有 (见 3.1) |
| 广告过滤 | `AdBlocker` (`AdBlocker.kt:44` 命中即 0.0.0.0) + `AdBlockManager` + `settingsDataStore.adBlockRules` (`:107`) + 内置 `assets/adblock.txt` | 关联动作 A 的目标。规则编译在连接时 (`VpnController.loadAdBlockRules` `:151-163`) |
| 域名代理名单 | `DomainListManager` (`DomainListManager.kt`) + `settingsDataStore.domainListUrl` (`:123`) | 关联动作 B 的目标。`matches(host)==true` → DOMAIN_SPLIT 下走隧道 |
| 设置页骨架 | `SettingsSection` / `SettingsRow` (`SettingsScreen.kt:574`,`:606`) + `NavGraph` (`NavGraph.kt:63-81`) | 入口与页面挂载 |
| 设置持久化 | `SettingsDataStore` (DataStore Preferences) | 存"统计生成的规则"叠加层、开关、保留策略 |
| 远端命令 | `RemoteCommandExecutor` + `JschSshClient.verifySessionRoundTrip` (会返回 `UNAVAILABLE`) | 7.2 的"按需解析真实 IP"(远端视角为**可选开关**) |

### 两个"白送"的好处

1. **探针流量天然不进统计**: 连通性探测走 `127.0.0.1:1080` loopback, 不经 TUN →
   `TcpStateMachine` 根本看不到 → 不会像 `故障归因` 那样被 `www.gstatic.com` 污染。
2. **`地址惰性失效` / 假 IP 纪律不受影响**: 统计只读 `lookupDomain`(已有调用), 不新增映射表,
   不改 acquired/release 配对。

---

## 3. 三个必须先说清的现实约束

### 3.1 真实 IP 在「假 IP 模式」下**拿不到** (最重要)

默认 DNS 模式是 **REMOTE (假 IP)**: `DnsInterceptor` **不查上游直接答** 198.18.x.x,
真正的解析发生在 VPS 上的 sshd (`direct-tcpip` 用的是**域名**)。也就是说:

> **手机自己从头到尾不知道真实 IP**, 它只知道域名和那个假 IP。

真实 IP 只有在"解析发生在手机"时才有, 具体是:

| 场景 | 有真实 IP? |
|---|---|
| REMOTE / WHITELIST (假 IP) | ❌ 永远没有 |
| SYSTEM (本地系统 DNS) | ✅ `onDnsResponse` → `realIpToDomain` |
| DOMAIN_SPLIT 的**直连腿** (名单未命中) | ✅ |
| REMOTE 下非 A/AAAA 查询的回退 | ✅ (MX/TXT/PTR) |
| 列表超限被 LRU 淘汰 / 进程重启 | ⚠️ 丢失 (cap 8192) |

**结论**: 真实 IP 必须是**可空字段**; UI 上缺失时显示 `— · 远端解析(不可得)`, 并给一个**手动**"解析真实 IP"动作 (7.2)。
不要假装能自动拿到 —— 那会变成一个永远显示 198.18.x.x 的假列。

### 3.2 访问频率**不能**用"DNS 查询次数"

- REMOTE 模式下 DNS 有本地缓存 (`dnsCache`), 一次查询可以对应上百次连接;
- 反之缓存命中时查询数 = 0, 频率会被算成 0。

**正确口径 = TCP 连接数 + 字节数** (在 `closeTcpConnection` 聚合)。DNS 查询数只能作为
**辅助**指标 (且只统计"本机真解析过"的部分)。

### 3.3 统计**绝不能**进数据面热路径

`closeTcpConnection` 已经在 packetLoop 调用链上 (`:1838` 还有 `synchronized(conn)`)。
所以纪律是:

- 只 `ConcurrentHashMap.computeIfAbsent` + `LongAdder`/`AtomicLong` 加一, **无锁、无分配、无 IO**;
- **不**在热路径写 Room / 文件 / DataStore;
- 落盘与 UI 发布由独立的 30s 定时器批量做 (与既有 `statsFlushLoop` 同款思路)。

---

## 4. 数据模型

### 4.1 聚合条目 (内存)

```kotlin
data class DomainStat(
    val domain: String,          // 小写、无尾点; lookupDomain 拿不到时用 "ip:port" 兜底
    val isIpLiteral: Boolean,    // true = 没反查到域名, 只是 IP
    var connCount: Long,         // 新连接次数 (= G2 主口径)
    var failedConnCount: Long,   // 内部失败/超时那部分 (why != 客户端关闭/远端正常关闭)
    var upBytes: Long,
    var downBytes: Long,
    var firstSeenAt: Long,
    var lastSeenAt: Long,
    var lastPort: Int,           // 最近一次目的端口 (443/80/…)
    var realIps: MutableSet<String>,  // 可空集合; 多为 0~2 个 (见 3.1)
    var source: StatSource,      // TUN / (预留) UDP-DNS / (预留) 直连
)
```

### 4.2 容器 (有界, 必须)

- `ConcurrentHashMap<String, DomainStat>`, **上限 `MAX_STAT_DOMAINS = 20000`**;
- 超限时按 `lastSeenAt` **批量**淘汰最旧 5% (不要逐条, 也不要随机清半 —— 同 `LruStringMap` 的理由);
- 另有独立的 **滚动时间窗** `STAT_WINDOW_DAYS = 7` (可配 1/7/30/不限), 过期条目在定时器里清;
- 计数全部 `AtomicLong`, 写入用 `addAndGet` (不读-改-写)。

> 选项: 直接用现成的 `domain/vpn/LruStringMap` 的淘汰思路, 但**不要**复用实例 (它无计数)。

---

## 5. 采集点

| 采集点 | 位置 | 采集内容 | 频率 |
|---|---|---|---|
| **主** | `TcpStateMachine.closeTcpConnection` | domain, connCount++, up/down 字节, lastSeenAt, 失败计数 (按 `why`), dstPort | 每连接 1 次 |
| 辅 | `DnsInterceptor.onDnsResponse` | 真实 IP 关联 (写 `realIpToDomain` 时顺带把 IP 塞进对应 `DomainStat.realIps`) | 每次真解析 |
| 可选 P2 | `UdpRelay.processUdpPacket` | QUIC/443 丢弃按**假 IP → 域名**归属 (现在只有端口分布) | 每丢包 1 次 |

> P2 的价值: 现在 `UDP 丢弃 114 个 (443→114)` 只能看出"QUIC 很多", 看不出是哪个域名在死磕 QUIC。

---

## 6. 存储与保留 (三方案; 已定 A → B 分阶段)

| 方案 | 说明 | 优点 | 代价 |
|---|---|---|---|
| **A. 仅内存 (会话级)** | 进程内聚合; 新会话 (connect) 清零, 与既有 `resetSessionCounters()` 口径一致 | 零迁移风险, 与 AGENTS 现有不变量一致 ("stats are in-memory") | 进程被杀即丢 (断开后到下次连接前仍在) |
| **B. Room (`domain_stats` 表, DB v4→v5)** | 30s 批量 upsert 一次事务; 时间窗用 `DELETE WHERE lastSeenAt < ?` | 可跨会话累计、可 SQL 排序/聚合/留存 | 需要 migration 4→5; 需要注意"不能在热路径写" |
| C. 文件 (JSON + 原子替换) | 同 `DomainListManager` 的落盘方式 | 不动 DB | 查询/淘汰要自己写, 数据量大时解析慢 |

**已定 (2026-10-19)**:
- **P0 = 方案 A**(会话级内存), 页面顶部标注"本会话"; 跨会话靠**导出**带数据。
  先验证"采集对不对", **不碰 DB** —— 零迁移风险。
- **P1 = 方案 B**, 且**保留策略必须显式**: 默认只留 7 天 / 上限 20000 域名 / 提供"清空统计"。
  迁移只是 `CREATE TABLE`, 风险低。

> ⚠️ 方案 B 会**打破** AGENTS 里"entities are only Server + WhitelistApp"的既有约定 ——
> 落地时必须同步更新 AGENTS 那一行 (说清"为什么现在要持久化"), 否则下一个读文档的人会以为它错了。

---

## 7. 真实 IP 的来源与"按需解析"

### 7.1 自动可得 (免费)

`realIpToDomain` 命中时 (SYSTEM / DOMAIN_SPLIT 直连腿 / 非 A-AAAA 回退) 直接带到统计里。

### 7.2 按需解析 (手动, 一次一个域名)

给列表里某一行加动作「解析真实 IP」。**默认走本地视角**; 远端视角由页面顶部一个开关控制
(默认 **关**, 状态记忆到 DataStore):

| 路径 | 机制 | 拿到的是 | 何时用 |
|---|---|---|---|
| **本地视角 (默认)** | `protect` socket 直查 (同 `ConnectivityProber` 思路) | 本机看到的 IP (国内可能被污染) | 默认动作; 零 SSH 依赖、零额外通道 |
| 远端视角 (开关打开时) | 经当前 SSH 会话 exec `getent ahosts <domain>` | **VPS 出口实际会连的那个 IP** (CDN 就近节点正确) | 用户显式打开「远端解析」后 |

开关语义: 页面顶部 `[ ] 使用远端解析 (经 SSH)`。**默认关** —— 远端解析要占一条 exec 通道
(与 `SshIoDispatcher` 的阻塞线程同池), 且并非所有服务端都支持。

**必须处理的前提**: `verifySessionRoundTrip` 会返回 `UNAVAILABLE` (OpenWrt/dropbear 常见 exec 受限)。
所以:
- 开关开着但 exec 不可用 → **不要报错**, 静默回退本地视角, UI 标注 `本地视角 (远端不可用)`;
- 结果**缓存进该条目** (`realIps`), 不重复解析;
- **绝不后台批量解析**: 那是 N 次 DNS / SSH 往返, 等于自开一条打满通道的捷径。

### 7.3 展示纪律

- 多个 IP 时显示第一个 + `+N`;
- `realIps` 为空时显示 `— · 远端解析(不可得)`, 并提供 7.2 的按钮;
- 不要用"域名 → 常见 IP"的任何推测。

---

## 8. 关联动作 (本功能的核心闭环) — 「先建议, 后应用」

### 8.0 两条通用纪律

**① 可逆 + 可溯源**: **绝不能**把统计生成的规则直接混进用户手写的规则/名单里 —— 那样"移出"
要么删错、要么删不掉。设计上分开存 (独立键):

| 目标 | 用户手写 (既有) | 统计生成 (新增, 独立键) |
|---|---|---|
| 广告过滤 | `adBlockRules` (`:107`) | `adBlockRulesFromStats` |
| 域名代理名单 | gfwlist 原文 (`domain_list.txt` + URL/内置) | `domainListAddedFromStats` / `domainListRemovedFromStats` |

**② 先建议, 后应用** (已定): 在统计页点选**不立即改名**单, 而是进「待确认建议列表」,
用户复核后**批量应用**。理由: 误杀代价高 (域名进了广告规则就彻底不可用), 而统计页是
"批量浏览"场景, 手滑概率高。

### 8.1 待确认建议列表 (新增的核心对象)

```kotlin
enum class SuggestionTarget { AD_BLOCK, DOMAIN_LIST }
enum class SuggestionDirection { ADD, REMOVE }

data class Suggestion(
    val domain: String,
    val target: SuggestionTarget,
    val direction: SuggestionDirection,
    val note: String,          // 例如 "128 次连接 · 下行 8.4MB"
    val createdAt: Long,
)
```

- 存储: `MutableStateFlow<List<Suggestion>>`; **默认不持久化** (应用前退出即丢弃 —— 刻意的安全默认);
- **去重 / 方向校验**: 同 `(domain, target, direction)` 只留一条; 只允许与**当前生效状态相反**
  的方向 (已在广告名单 → 只允许 `REMOVE`; 不在 → 只允许 `ADD`);
- 「待确认 N」徽标同时出现在**设置页入口**与统计页底部条;
- 条目可单删, 也可"全部丢弃"。

### 8.2 应用: → 「广告过滤」

- `ADD` → `adBlockRulesFromStats` += `||<domain>^` (gfwlist 后缀语法, 覆盖子域名);
- `REMOVE` → 从该键删除;
- **即时生效是必须解决的**: `AdBlocker` 的 matcher **只在连接时编译一次**
  (`VpnController.loadAdBlockRules` `:445`)。改完不重编译, 用户会看到"应用了没反应"。
  → 新增 `VpnController.reloadAdBlockRules()` (读三源 → `adBlocker.setMatcher(...)`), 由"应用"触发;
  **未连接时只落库**, 下次连接自然带上。
- 误杀救援: 详情页提供「加入例外」(`@@||<domain>^`, 优先级高于命中; matcher 已支持 `@@`,
  见 `GfwListMatcher` 头部注释), 走同一条待确认流程。

### 8.3 应用: → 「域名代理名单」

现状: `DomainListManager` 只有 `BUILTIN / DISK / URL` 三态, **没有"用户叠加"概念**,
且 `update()` 会用远端内容整体覆盖 (`:93-97`)。所以必须新增叠加层:

```
matches(host) =
    if (userRemoved.matches(host)) false          // 强制直连, 优先级最高
    else if (userAdded.matches(host)) true        // 强制走隧道
    else base.matches(host)                       // 既有 gfwlist 语义 (含 @@)
```

- `userAdded` / `userRemoved` 来自 `domainListAddedFromStats` / `domainListRemovedFromStats`
  (Set<String>, 域名后缀), 持久化在 DataStore;
- **硬纪律: `update()` 不许 touch 它们** (用户数据不被远端覆盖) —— 用单测锁死;
- `matches()` 每次实时查 → 应用后**立即生效** (DOMAIN_SPLIT 每次查询都读它), 不需要重连;
  为让 UI 徽标即时刷新, 加一个 `StateFlow` 版本号即可。

### 8.4 撤销

- 应用成功后 Snackbar `已应用 N 条 — 撤销`; 撤销 = 把那批键值反向操作一遍
  + (广告过滤) 再 `reloadAdBlockRules()` 一次。**不依赖历史栈** —— 反向操作本身就是幂等的。

### 8.5 自动建议 (P2)

在"手动点选"之外, 再由统计自动**生成**候选 (仍进待确认列表, 绝不自动应用):

| 规则 | 生成的候选 |
|---|---|
| 连接次数 ≥ N 且不在任何名单 | 加代理名单 |
| 被广告规则命中 (**本应回 0.0.0.0**) 却仍有下行流量 | 规则可能失效 → 提示复核 |
| 高频连接 + 长期零下行 | 加广告过滤 |

---

## 9. UI 设计

### 9.1 设置页入口

放在「网络」section 内, 「域名列表」**(域名代理名单)** 的**上方**(它俩是同一族概念):

```
设置 → 网络
  连接模式
  域名访问统计      ← 新增: subtitle "已记录 128 个域名 · 最近 3 分钟前"
  域名列表
```

- subtitle 用现有 `SettingsRow(title, subtitle, trailing, onClick)` (SettingsScreen.kt:606);
- 未接入时 subtitle 走 i18n: `尚未采集数据`; 有待确认建议时 trailing 挂 `待确认 N` 徽标;
- `SettingsScreen` 加 `onNavigateToDomainStats` 参数 + `NavGraph` 加 `composable("domain_stats")`。

### 9.2 统计页 (`DomainStatsScreen`)

```
┌ 域名访问统计 ─────────────────────────── [导出] [清空] ┐
│ [本会话] [24 小时] [7 天] [全部]       (时间窗, P1 起)  │
│ [ ] 使用远端解析 (经 SSH)               (默认关, 见 7.2) │
│ 🔍 搜索域名 / IP                                       │
│ 排序: 连接数 ▾   筛选: [全部] [广告名单] [代理名单] [未知]│
├───────────────────────────────────────────────────────┤
│ accounts.google.com       — 远端解析   128 次 [广告][代理]│
│   上行 1.2MB · 下行 8.4MB · 最近 12s · :443            │
│ connect.rom.miui.com  106.11.26.14      64 次  …       │
│ ...                                                    │
├───────────────────────────────────────────────────────┤
│ 待确认 3 条                        [查看] [应用]        │ ← 底部条
└───────────────────────────────────────────────────────┘
```

- 行右侧两个 chip: 「广告」「代理」= **当前是否已在**名单 (实心 = 在)。点击**不直接改名单**,
  而是把 `(domain, target, ADD|REMOVE)` 送进**待确认列表** (见 8.1), chip 变虚线 + 「待定」;
- 再次点同一 chip = 从待确认列表移除 (取消选择);
- 底部「待确认 N」条 → **复核页** (逐条显示域名 + 依据 note + 方向, 可勾选/删除), 底部「应用」;
- 行点击 → 详情 (全部 IP、端口分布、失败数、**解析真实 IP**、加入例外);
- 长按 → 多选 → 批量送入待确认列表;
- 空状态: "连接后开始采集"。

### 9.3 交互细节

- **应用**才写库 + (广告过滤) 触发重编译, Snackbar `已应用 N 条 — 撤销`;
- 待确认列表**默认不持久化** (应用前退出即丢弃) —— 刻意的安全默认;
- 首页「数据面快照」不加字段 (统计是 UI 侧功能, 不进快照; 仅在要"淘汰 N 条"这类自证点时才加, 见第 11 节)。

---

## 10. i18n / 无障碍

- 新增文案同步 **四份** `values/`, `values-en/`, `values-ru/`, `values-zh-rCN/` 的 `strings.xml`;
- Compose 内一律 `stringResource` (别用 `LocalContext.getString`, Android Lint 报
  `LocalContextGetResourceValueCall`);
- 若新增 `enum class` 状态带 `@StringRes labelRes`, 必须同步扩 `MainViewModelFormatTest` 的
  `R.string → 中文` 映射表, 否则该测试会直接抛异常 (这是既有的 i18n 防线)。

---

## 11. 性能与资源预算 / 自证点

| 项 | 预算 | 自证方式 |
|---|---|---|
| 热路径开销 | 每连接 1 次 CHM 查找 + ≤4 次 AtomicLong 加 | 代码评审 + 与既有日志同点 (日志已在拼字符串, 统计只多几次原子操作) |
| 内存 | ≤ 20000 条 × ~200B ≈ 4MB 上界 | 页面显示 "已记录 N/20000"; 超限淘汰打 INFO |
| 落盘 (P1) | 每 30s 一次批量事务, ≤ 200ms | 复用 `statsFlushLoop` 的 try/catch 纪律 |
| 断电/被杀 | 丢失 ≤ 30s 数据 | 可接受 (P1 明确写入文档) |

**新增计数器必须同步更新** `docs/specs/2026-10-18-device-verification-checklist.md` 第 4 节
(仓库既有的硬纪律: 计数器不进清单等于不存在)。本功能至少应加:

| 指标 | 通过 | 失败信号 |
|---|---|---|
| `统计淘汰` | 不出现 / 偶发 | 持续增长 = 上限太小或域名爆炸 |
| `统计落盘失败` (P1) | 不出现 | 出现 = DataStore/Room 写失败 (权限/磁盘) |
| 统计页条目数 | ≤ 上限, 关页面后内存不涨 | 单调爬到 20000 不动 |

---

## 12. 隐私

- 数据**只在本机**; 导出是用户显式动作;
- **对外可解释**: 文档/隐私说明里明确"记录域名与连接次数, 不含 URL/内容";
- 提供"清空统计"一键;
- **清空时机**: P0 内存统计随会话 (新会话清零, 与既有 `resetSessionCounters()` 口径一致);
  P1 起由 Room 跨会话累计, 用户要清只能显式"清空统计" (默认**不**自动清);
- 域名本身可能暴露用户访问偏好 → 导出文件不要自动进共享目录 (与日志导出一致: `Download/` 且用户主动)。

---

## 13. 分阶段实施

| 阶段 | 内容 | 交付判据 |
|---|---|---|
| **P0 只读采集** | 内存聚合 (第 4/5 节) + `DomainStatsScreen` + 设置入口 + 真实 IP **本地解析** + 导出 CSV + i18n | 浏览 10 个站点后, 列表出现对应域名、连接数 ≥ 1、加密站点显示 `— · 远端解析(不可得)`; 数据面快照各项不变量不变 |
| **P1 建议→应用 + 持久化** | 待确认建议列表 (8.1) + 复核页 + 批量应用 (8.2/8.3) + `adBlockRulesFromStats` + `domainList{Added,Removed}FromStats` 叠加层 + `reloadAdBlockRules()` + 撤销 (8.4) + 远端解析开关 + Room v5 + 时间窗 | 应用广告规则后**无需重连**即生效 (被拦域名回 0.0.0.0); 撤销后恢复; 重启进程统计仍在; 远端 `update()` 不动叠加层 (单测) |
| **P2 增强** | 按应用归属 (uid 反查, 附录 A)、QUIC/UDP 域名归属、自动建议 (8.5)、按端口/失败率筛选 | 自动建议只进待确认列表, 绝不自动应用 |

**建议先只做 P0**: 它能独立验证"采集对不对", 不碰 DB、不碰名单, 风险最低。

---

## 14. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 统计写热路径 | 上行性能回归 | 纪律: 纯原子累加; 上线前用 S2 (大文件下载) 核对快照各项 |
| 关联动作误杀 (把重要域名加了广告规则) | 该域名彻底不可用 | 三重缓解: **先建议后应用** (8.1) + 应用后可撤销 (8.4) + 提供 `@@` 例外 |
| 叠加层被远端 `update()` 覆盖 | 用户数据丢失 | 硬纪律: `update()` 不 touch 叠加层; 单测锁死 |
| Room 迁移 4→5 出错 | 启动崩溃 (服务器列表一起丢) | 迁移只是 `CREATE TABLE`; 上线前 `assembleDebug` + 真机装一次 |
| 域名表爆炸 | 内存 4MB+ / 页面卡 | 上限 20000 + 时间窗 + 淘汰计数 |

**回滚**: 全部是新增 (新页面/新键/新表)。关掉设置项开关即可停用; 必要时删 API 与页面,
既有的 `adBlockRules` / `domain_list.txt` **从未被本功能写过** → 不会留下脏数据。

---

## 15. 已定 (2026-10-19)

| # | 问题 | 决定 |
|---|---|---|
| 1 | 持久化 | **P0 = 会话级内存 + 导出**; P1 才上 Room (DB v4→v5) |
| 2 | 真实 IP | **默认本地解析**; 「使用远端解析 (经 SSH)」是页面开关, **默认关**; exec 不可用则回退本地并标注 |
| 3 | 关联强度 | **先收集为「待确认建议列表」, 复核后批量应用** (绝不直接改名) |
| 4 | 按应用维度 | **要**, 进 P2 (附录 A) |

---

## 附录 A. P2: 按应用维度 (uid 反查) — 可行性待验证

需求: "哪个 App 在连哪些域名"。**这是 P2 里唯一有平台可行性风险的一项**, 先摆事实:

- TUN 侧每个连接只有 `(srcIp, dstIp, srcPort, dstPort)`; `srcIp` 恒为 `10.0.0.2`
  (`VpnNetwork.TUN_IP`), **不含发起方 uid**。
- 官方没有给普通应用"连接 → uid"的 API:
  `ConnectivityManager.getConnectionOwnerUid()` 是 `@SystemApi`
  (`NETWORK_STACK` 权限), 普通应用调不到。
- 可行的近似路径:
  | 路径 | 能力 | 代价 |
  |---|---|---|
  | `NetworkStatsManager.queryDetailsForUid` | **每个应用的总流量** (给不了"连了哪些域名") | 需 `PACKAGE_USAGE_STATS` 特殊权限 (用户在系统设置里手动授权), 且是聚合值 |
  | 读 `/proc/net/*` (socket inode → `/proc/<pid>/fd` → uid) | 理论可到 `连接 → uid` | 需要 root / Shizuku, 易碎, 不做 |
- **结论**: P2 落地前必须先在真机上验证"能拿到什么"。如果只能到"每应用总流量",
  那它与本功能 (每域名) 是**两个正交维度**, UI 上必须分开呈现, 不能塞进同一张表冒充
  "应用 × 域名"矩阵。**在验证通过前, 不要把 `appUid` 写进数据模型**。
