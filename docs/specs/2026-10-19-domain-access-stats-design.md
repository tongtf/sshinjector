# 域名访问统计 (Domain Access Stats) — 功能设计

> 状态: **P0 已落地 (2026-10-19, 见 `2026-10-19-domain-stats-todo.md` §6)**; P1/P2 未动代码。
> 四个关键决策已拍板 (见第 15 节);
> 性能指标已按 `2026-10-19-domain-stats-perf-audit.md` 审计并修正 (内存/trim/远端解析/UI 四处)。
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
所以纪律是 (逐条展开见 §5.1):

- 只 `map[key]` 取已有条目 + (未命中才) `putIfAbsent`, 加一/加字节在 `synchronized(entry)` 内
  (方案 B) 或 `AtomicLong.addAndGet` (方案 A) —— **零分配、零 IO**;
- **不要**用 `computeIfAbsent(key) { … }` (捕获参数的 lambda 每次调用都新建一个对象, 见 §5.1-①);
- **不**在热路径写 Room / 文件 / DataStore, **也不**在热路径 emit 任何 UI 列表 (§9.4);
- 淘汰 (trim) **绝不**在 packetLoop 上跑, 只置标志位, 由 flusher 做 (§4.2);
- 落盘与 UI 发布由独立的 30s 定时器批量做 (与既有 `statsFlushLoop` 同款思路)。

---

## 4. 数据模型

### 4.1 聚合条目 (内存)

> ⚠️ 本节的字段布局经**性能审计**修正 (见 `2026-10-19-domain-stats-perf-audit.md` §2.3/§3.4):
> 原稿用 `MutableSet<String> realIps` + 6× `AtomicLong` = **324B/条** (20000 条 6.5MB);
> 改成下面这版 = **180B/条** (20000 条 3.6MB)。

```kotlin
/** 普通 class (非 data class): equals/toString/copy 覆盖 11 个字段, 误打进日志时开销大。 */
class DomainStat(
    val domain: String,          // 复用 lookupDomain 返回的实例, 不再 lowercase/复制 (同 key 引用同上)
    val isIpLiteral: Boolean,    // true = 没反查到域名, 只是 IP
    // 可变字段全部只在 synchronized(this) 内读写 (32 位 ABI 下裸 long 会撕裂读, 见 §4.1.1)
    var connCount: Long = 0,              // 新连接次数 (= G2 主口径)
    var failedConnCount: Long = 0,        // 内部失败/超时那部分 (why != 客户端关闭/远端正常关闭)
    var upBytes: Long = 0,
    var downBytes: Long = 0,
    var firstSeenAt: Long = 0,
    var lastSeenAt: Long = 0,
    var lastPort: Int = 0,                // 最近一次目的端口 (443/80/…)
    var realIp: String? = null,           // 不引入 Set: 绝大多数条目 0~2 个 IP (§3.1 拿不到 IP 是常态)
    var realIp2: String? = null,
    var realIpTotal: Int = 0,             // 最近一次解析的地址总数 (让"截断"可见, §7.3)
    var appUids: IntArray? = null,        // ④ 发起方 uid 槽 (最多 3; null = 尚未拿到任何一个, 惰性分配)
    var source: StatSource = StatSource.TUN,  // TUN / (预留) UDP / (预留) 直连
)
```

### 4.1.1 字段并发方案 (必选其一, 不许裸 `var`)

产物含 **`armeabi-v7a`** → 32 位上普通 `long` 读/写**非原子**, 裸读会看到"下行 9.2EB"这类脏值;
多线程 `x += n` 也会丢更新。两个可选实现 (性能审计 §3.4):

| | **方案 B (推荐)**: `synchronized(entry)` + 普通 `var` | 方案 A: 6× `AtomicLong` |
|---|---|---|
| 内存/条 | **88B** → 20000 条 **3.6MB** | 160B → 20000 条 5.0MB |
| 读侧 | 快照时也要持同一把 entry 锁 (20000 次轻量锁 ≈ 0.5–2ms, 仅在开屏/刷新时) | 免锁 |
| 纪律 | **持锁期间绝不调日志/IO/组包** —— 只做字段更新 | 对象数 +12 万 |

选方案 A 的话, 上限降到 **10000** (2.5MB)。

### 4.2 容器 (有界, 必须)

- `ConcurrentHashMap<String, DomainStat>`, **上限 `MAX_STAT_DOMAINS = 20000`** (方案 B 下 ≈ 3.6MB, 报 ≤5MB);
- **超限时只置 `trimPending = true`** (一次 volatile 写) —— **淘汰绝不能在 `closeTcpConnection` 的
  调用线程上跑**: 对 20000 条排序 = 百 µs~数 ms + 一个 20000 元素临时列表, 而那正是 packetLoop,
  会造成**全连接停顿** (同 `LruStringMap.trim()` KDoc 那句"应在低频任务中调用");
- 真正的淘汰由 **30s flusher 线程**做, 且一次**压回容量的 90%** (`LruStringMap` 同款
  `target = maxSize - maxSize/10`) —— 一次只淘汰 5% (1000 条) 会被立刻填满, 形成周期性尖峰;
- 另有独立的 **滚动时间窗** `STAT_WINDOW_DAYS = 7` (可配 1/7/30/不限), 过期条目同样只在 flusher 里清;
- 每条计数在 `synchronized(entry)` 内更新 (方案 B) 或 `addAndGet` (方案 A), 均不读-改-写。

> 选项: 可直接复用现成的 `domain/vpn/LruStringMap`, 但**不要**复用实例 (它只存 String、无计数)。
> 淘汰计数 `统计淘汰` 必须进快照 (见 §11)。

---

## 5. 采集点

| 采集点 | 位置 | 采集内容 | 频率 |
|---|---|---|---|
| **主** | `TcpStateMachine.closeTcpConnection` | domain, connCount++, up/down 字节, lastSeenAt, 失败计数 (按 `why`), dstPort | 每连接 1 次 |
| 辅 | `DnsInterceptor.onDnsResponse` | 真实 IP 关联 (**只标注已存在的条目, 不新建**; 写入 `realIp`/`realIp2`) | 每次真解析 |
| 辅 (P2 ④) | `TcpStateMachine.closeTcpConnection` → `AppUidResolver` 线程 | 发起方 uid 关联 (**只标注已存在的条目**, 写入 `appUids` 槽) | 每连接 1 次 (异步, 不在数据面线程) |
| 可选 P2 | `UdpRelay.processUdpPacket` | QUIC/443 丢弃按**假 IP → 域名**归属 (现在只有端口分布) | 每丢包 1 次 |

> P2 的价值: 现在 `UDP 丢弃 114 个 (443→114)` 只能看出"QUIC 很多", 看不出是哪个域名在死磕 QUIC。

### 5.1 热路径五条纪律 (性能审计 §2.5, 违反任何一条都会把"噪声级成本"变成真实回归)

| # | 纪律 | 依据 |
|---|---|---|
| 1 | **命中路径零分配**: 不要 `computeIfAbsent(key) { DomainStat(...) }` (捕获 key/now → 每次调用都 new 一个 lambda)。写成 `val e = map[key]; if (e != null) … else map.putIfAbsent(key, fresh)` | 命中是常态 |
| 2 | **域名只查一次**: `lookupDomain` 的结果同时喂给日志的 `target(conn)` 和统计 (现有 `target()` 内部已经查过一次, 别查第二遍) | 省 1 次 CHM + 口径一致 |
| 3 | **不在 `synchronized(conn)` 内**做统计 —— 放在 `TcpStateMachine.kt:1851` 那行日志附近 (锁外) | 不延长已有临界区、避开锁序 |
| 4 | **热路径不落盘/不 emit**: 只动内存; UI 列表只在开屏/手动刷新时生成 (§9.4) | 仓库既有铁律 |
| 5 | DNS 侧**只标注不新建**, 保持 `connCount ≥ 1` = "真访问过" | 语义 + 防灌表 |

> 参照系 (为什么这条路走得通): 同一个调用点**已经**在付 `target()` 的 CHM 查 + 4 次字符串分配 +
> SharedFlow `tryEmit`, 甚至可能阻塞在 `writeBlocking(closePacket)` (队列满时可达 10s)。
> 统计只多 1 次 CHM get + 一次极短 entry 锁, **< 该行成本的 5%**, 且它**每连接只跑一次**, 不在逐包循环里。

---

## 6. 存储与保留 (三方案; 已定 A → B 分阶段)

| 方案 | 说明 | 优点 | 代价 |
|---|---|---|---|
| **A. 仅内存 (会话级)** | 进程内聚合; 新会话 (connect) 清零, 与既有 `resetSessionCounters()` 口径一致 | 零迁移风险, 与 AGENTS 现有不变量一致 ("stats are in-memory") | 进程被杀即丢 (断开后到下次连接前仍在) |
| **B. Room (`domain_stats` 表, DB v4→v5)** | 30s 只 upsert **本周期变化过的条目 (dirty set)**, 单事务**分块** (500 行/事务, 块间 `yield()`); 时间窗用 `DELETE WHERE lastSeenAt < ?` | 可跨会话累计、可 SQL 排序/聚合/留存 | 需要 migration 4→5; 全量重写会写放大, 必须分块 (审计 §3.3) |
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
| 远端视角 (开关打开时) | **池内会话** exec `getent ahosts <domain>` | **VPS 出口实际会连的那个 IP** (CDN 就近节点正确) | 用户显式打开「远端解析」后 |

开关语义: 页面顶部 `[ ] 使用远端解析 (经 SSH)`。**默认关**。

> ⚠️ **机制纠错 (性能审计 §3.2)**: **不要用 `RemoteCommandExecutor.execSingleShot`** —— 它每次
> `jsch.getSession()` + `s.connect()` 建**一整条新 SSH 会话** (`JschSshClient.kt:1345-1385`),
> 代价 **0.4~4s** 且会在服务器侧堆积会话 (AGENTS 记录过"累积会话 → SSH connect 超时")。
> 正确做法二选一:
> - **① 复用池内会话**: 照抄 `verifySessionRoundTrip` (`JschSshClient.kt:853-884`) 的
>   `pooled.session.openChannel("exec")` 模板 → 一次 channel open + RTT ≈ **数十 ms**;
> - **② 单会话批量 (更优)**: 把本页所有待解析域名合成**一条**命令
>   (`getent ahosts a; echo ---; getent ahosts b; …`) → 一次 RTT 解 N 个, 天然限流。
>
> 两种都**不在 `SshIoDispatcher` 里**(原稿那句"与 SshIoDispatcher 同池"不成立) —— 与
> `verifySessionRoundTrip` 一致跑 `Dispatchers.IO`。

**必须处理的前提**: exec 可能不可用 —— `verifySessionRoundTrip` 会返回
`ROUND_TRIP_UNAVAILABLE` (OpenWrt/dropbear 常见), 且**秒级被拒**才算"不支持 exec", 慢失败是黑洞。
所以:
- 开关开着但 exec 不可用 → **不要报错**, 静默回退本地视角, UI 标注 `本地视角 (远端不可用)`;
- **超时 5s** + 启动前 `isSaturated()` 预检 + **单飞** (同时只允许一个解析在跑);
- 结果**缓存进该条目** (`realIp`/`realIp2`), 不重复解析;
- **绝不后台批量解析**: 即使是路径 ①, N 次往返也是 N 个 RTT 的自伤捷径 (路径 ② 才允许批量, 且仍是用户触发)。

### 7.3 展示纪律

- 多个 IP 时显示第一个 + `+N`;
- `realIp == null` 时显示 `— · 远端解析(不可得)`, 并提供 7.2 的按钮;
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
- 首页「数据面快照」**加 1 行** (性能审计 §4.1 撤回了原稿的"不加字段"): 在
  `VpnController.kt:1311` 的资源节追加 `· ${domainStats.diagnostics()}` →
  `域名统计 1234/20000(淘汰 0)`。这是"统计表有没有失控增长"在无 adb 现场唯一可看的地方。

### 9.4 UI 性能预算 (原稿缺失; 性能审计 §3.1)

**禁止**任何从 `closeTcpConnection` 触发的 UI 通知 —— 每次连接都拷贝一个 20000 元素的
`StateFlow<List<…>>` 是仓库明令禁止的反模式 (每连接 160KB 数组 + 全量 recompose)。

| 出口 | 成本 | 约束 |
|---|---|---|
| `countFlow: StateFlow<Int>` (设置页 subtitle / 待确认徽标) | O(1) (`map.size`) | 由 flusher 每 30s 推一次 |
| `snapshot(): List<DomainStat>` | ~0.2ms (拷贝 160KB 引用数组) + 方案 B 下 20000 次轻量锁 ≈ 0.5–2ms | **只**在开屏 / 手动刷新 / 页面可见时的低频轮询调用; `Dispatchers.Default` |
| 排序 20000 条 | **3–8ms** | `Dispatchers.Default`, 结果缓存, **不要**在每次 recomposition 里重排 |
| 搜索 / 筛选 | 1–3ms | **300ms debounce** |
| `LazyColumn` | 只组合可见行 | 必须 `key = domain` |
| 导出 CSV (20000 行 ≈ 1.5MB) | 数十 ms | **流式写** (`BufferedWriter`) 在 IO 线程, 不拼大字符串 |

---

## 10. i18n / 无障碍

- 新增文案同步 **四份** `values/`, `values-en/`, `values-ru/`, `values-zh-rCN/` 的 `strings.xml`;
- Compose 内一律 `stringResource` (别用 `LocalContext.getString`, Android Lint 报
  `LocalContextGetResourceValueCall`);
- 若新增 `enum class` 状态带 `@StringRes labelRes`, 必须同步扩 `MainViewModelFormatTest` 的
  `R.string → 中文` 映射表, 否则该测试会直接抛异常 (这是既有的 i18n 防线)。

---

## 11. 性能与资源预算 / 自证点 (已按性能审计修正)

> 完整核算过程见 `2026-10-19-domain-stats-perf-audit.md` §2 / §4。

| 项 | 修正后预算 | 依据 | 自证方式 |
|---|---|---|---|
| 热路径频率 | ≤ 100 次/s (突发 80) | `在途通道` 峰值 80 + 会话量级 | 导出日志里 `TCP 连接结束` 的**行时间戳间隔** |
| 热路径边际成本 | **< 现有该行成本的 5%**, 0 分配 | 同点已有 2 次 CHM + 5 次字符串分配 + SharedFlow 写 | 代码评审 (对比 `TcpStateMachine.kt:1851`) |
| 内存 (采集) | **方案 B: 20000 条 ≈ 3.6MB (报 ≤5MB)**; 方案 A → 上限降到 10000 | 逐项字节表 (审计 §2.3) | 快照 `域名统计 N/M(淘汰 K)` + 页面 `已记录 N` |
| 内存 (UI 快照) | 峰值 ≈ 160KB 数组 + 排序临时表 | 审计 §3.1 | — |
| trim | **只由 30s flusher 做**, 一次压回 90% | 审计 §2.4 | 计数器 `统计淘汰` |
| 落盘 (P1) | 追 **dirty set** + 分块事务 (500 行/事务, 块间 `yield()`); **实测耗时进快照** | 审计 §3.3 (原稿 "≤200ms" 无依据, 20000 行单事务是秒级 + 写放大) | 快照 `统计落盘 Tms` |
| 远端解析 | 池内 exec / 单会话批量; 超时 5s; 单飞 | 审计 §3.2 | 日志 `远端解析 · <域名> — Nms` |
| UDP 归属 (P2) | 逐包 +1 CHM (与既有 `merge` 同量级), 建议**每流首次**计数 | 审计 §2.5 先例 | 现有 `UDP 丢弃` 节 |
| 断电/被杀 | 丢失 ≤ 30s 数据 (P1) | — | 可接受 |

**新增计数器必须同步更新** `docs/specs/2026-10-18-device-verification-checklist.md` 第 4 节
(仓库既有的硬纪律: 计数器不进清单等于不存在)。本功能至少应加:

| 指标 | 通过 | 失败信号 |
|---|---|---|
| `域名统计 N/M(淘汰 K)` (快照资源节) | K 不持续增长 | 单调爬到上限 = 上限太小或域名爆炸 |
| `统计淘汰` | 不出现 / 偶发 (INFO) | 持续增长; **且若它出现在连接结束日志之间造成 gap = trim 跑错线程了** |
| `统计落盘 Tms` (P1) | 稳定在数十 ms | 接近/超过周期 (30s) = 写放大失控 |
| `统计落盘失败` (P1) | 不出现 | 出现 = DataStore/Room 写失败 (权限/磁盘) |
| 页面条目数 | ≤ 上限; 关页面后内存不涨 | 单调爬到上限不动 |

> **明确无法验证**: 真实堆占用 (需 profiler/adb)、CPU 占用、GC 频率。
> 上述内存数字是**解析式估算** (逐项列了字节 + 1.3× 安全系数), **不是实测** —— 引用时别当实测结论。

---

## 12. 隐私

- 数据**只在本机**; 导出是用户显式动作;
- **对外可解释**: 文档/隐私说明里明确"记录域名与连接次数, 不含 URL/内容";
- ④ 起额外记录**发起方应用 uid** (仅用于在列表里标注"哪个 App 连的", 只在本机内存里, 与域名同期清理);
  不产出任何 "应用 × 域名" 的外部上报, 导出 CSV 仍只含域名维度;
- 提供"清空统计"一键;
- **清空时机**: P0 内存统计随会话 (新会话清零, 与既有 `resetSessionCounters()` 口径一致);
  P1 起由 Room 跨会话累计, 用户要清只能显式"清空统计" (默认**不**自动清);
- 域名本身可能暴露用户访问偏好 → 导出文件不要自动进共享目录 (与日志导出一致: `Download/` 且用户主动)。

---

## 13. 分阶段实施

| 阶段 | 内容 | 交付判据 |
|---|---|---|
| **P0 只读采集** ✅ **已落地 2026-10-19** | 内存聚合 (第 4/5 节) + `DomainStatsScreen` + 设置入口 + 真实 IP **本地解析** + 导出 CSV + i18n | 浏览 10 个站点后, 列表出现对应域名、连接数 ≥ 1、加密站点显示 `— · 远端解析(不可得)`; 数据面快照各项不变量不变 |
| **P1 建议→应用 + 持久化** | 待确认建议列表 (8.1) + 复核页 + 批量应用 (8.2/8.3) + `adBlockRulesFromStats` + `domainList{Added,Removed}FromStats` 叠加层 + `reloadAdBlockRules()` + 撤销 (8.4) + 远端解析开关 (池内 exec, §7.2) + Room v5 (**dirty set 分块落盘**) + 时间窗 | 应用广告规则后**无需重连**即生效 (被拦域名回 0.0.0.0); 撤销后恢复; 重启进程统计仍在; 远端 `update()` 不动叠加层 (单测); 快照 `统计落盘 Tms` 远小于 30s |
| **P2 增强** | ✅ 按应用归属 (uid 反查, 附录 A) · QUIC/UDP 域名归属 · 自动建议 (8.5) · 按端口/失败率筛选 | 自动建议只进待确认列表, 绝不自动应用; uid 只进展示, 不做隧道决策 |

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

## 附录 A. P2: 按应用维度 (uid 反查) — ✅ 已落地 (前提已在本地 SDK 取证更正)

需求: "哪个 App 在连哪些域名"。

**前提更正 (2026-10-19, 本地 SDK 取证 —— 本节初判是错的)**: `ConnectivityManager.getConnectionOwnerUid(int, InetSocketAddress, InetSocketAddress)`
**不是 `@SystemApi`, 而是公开 API**:

1. `platforms/android-37.0/android.jar` 的公开 stub 里 `javap` 可见该签名; 同 SDK 的 `@SystemApi`
   (`startTethering`/`getTetheringSupported`) 与 `@hide` 的 `android.os.ServiceManager` 都不在 → 这个
   jar 是严格公开面;
2. `platforms/android-37.0/data/api-versions.xml` 记该签名 `since="29"` (Android 10 起公开);
3. stub javadoc 明确写给**活动 VpnService** ("if it is associated with the calling VPN app's
   VpnService tunnel"; `@throws SecurityException if the caller is not the active VpnService"),
   `annotations.zip` 里**无** `@RequiresPermission`。

含义: 不需要 `PACKAGE_USAGE_STATS`, 不需要 root/Shizuku; 本应用连上后**就是**该 API 的目标调用者。
约束: TUN 侧 `srcIp` 恒为 `10.0.0.2` (`VpnNetwork.TUN_IP`) 且**不含 uid**, 所以 uid 只能**反查**:
`(TCP, local = srcIp:srcPort, remote = dstIp:dstPort)` 与内核里的那条 socket 完全同键 (本栈不做 NAT)。

落地形态 (与初判时的"先验证再动数据模型"一致 —— 先落探针取证, 再按取证结果落数据面):

| 步骤 | 交付 | 说明 |
|---|---|---|
| 取证 (v1.2.5) | `domain/vpn/AppUidProbe.kt` + 统计页顶栏 `i` | 未连接直接 `VPN_NOT_ACTIVE` (未连接时的 `SecurityException` 是误判不是结论); 连上后用**本进程内一条真实回环连接**双向各查一次 → `AVAILABLE`/`NO_OWNER`/`DENIED` |
| 数据面 (v1.2.6) | `domain/vpn/AppUidResolver.kt` + `ConnectivityAppUidResolver.kt` | `closeTcpConnection` 里**只入队**(原子计数 + `execute`, 无 binder/无阻塞), 查询跑**单线程有界队列**; 拿到才回调回写 |
| 展示 | `DomainStatsStore.attachAppUid` + 统计页「应用」列 | 每域名最多 3 个 uid 槽 (惰性 `IntArray`), 溢出计入资源节 `归属溢出 N`; 应用名由 `ui/screen/settings/AppUidLabels.kt` 解析 (uid 数量级 = 用户真用过的应用数, 带缓存) |

`NetworkStatsManager.queryDetailsForUid` (需 `PACKAGE_USAGE_STATS`, 只有每应用**总流量**) 仍**只作退路**:
它与本功能是**正交维度**, UI 上必须分开呈现, 不能塞进同一张表冒充"应用 × 域名"矩阵。
**`appUid` 只进展示, 绝不参与任何隧道决策** (域名/路由/名单判定都不看它)。
