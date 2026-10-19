# 域名访问统计 — 性能指标审计 (2026-10-19)

> 审计对象: `docs/specs/2026-10-19-domain-access-stats-design.md`
> 方法: **不采信设计稿里的自估数字**, 逐条回到代码里核实 (file:line), 重算, 再判决。
> 约束前提: 无 adb / 无 profiler → 所有无法实测的项**明确标注**并给出替代自证点。

---

## 0. 结论 (TL;DR)

**总体: 采集侧的设计是安全的 (边际成本是"噪声级"), 但三处数字是错的/漏的, 必须修。**

| # | 判决 | 项 |
|---|---|---|
| **P0-1** | ❌ **数字错** | 内存预算 `20000 × 200B ≈ 4MB` —— 实际 **≈ 6.5MB** (设计稿偏低 1.6×), 根因是 `MutableSet<String> realIps` 每个条目白付一个空 HashSet |
| **P0-2** | ❌ **漏了一条硬纪律** | "超限淘汰最旧 5%" 没写**在哪跑** —— 落到 packetLoop 上就是一次 20000 条排序 (百 µs~ms 停顿)。仓库已有先例 (`LruStringMap.trim()` 只在 30s 任务里调) |
| **P0-3** | ❌ **机制错** | 7.2 的"经 SSH 远端解析"引用了 `execSingleShot` —— 它**每次新建一整条 SSH 会话** (TCP+握手+认证, 实测 0.4~4s), 不是"占一条 exec 通道"。批量解析 = 自伤 |
| **P1-1** | ⚠️ **缺一整块预算** | 设计稿只算了采集侧, **没算 UI 侧**。按现有写法 (`StateFlow<List<DomainStat>>` 隐含) 会踩仓库明令禁止的"热路径 emit 列表"反模式 |
| **P1-2** | ⚠️ **未验证数字** | P1 "落盘每 30s 一次批量事务 ≤ 200ms" —— 无依据; 20000 行单事务量级是**秒**, 且写放大 |
| **P1-3** | ⚠️ **反悔** | §9.3 "快照不加字段" 与 §11 "新增计数器必须进清单" 自相矛盾 —— 快照是唯一诊断面, 应加 (1 行) |
| **P2-1** | ⚠️ | QUIC/UDP 归属是**逐包**热路径 (`UdpRelay` 已每包一次 CHM `merge`), 再加一次 lookup 是同一量级 — 可接受但必须写明 |

**最重要的正面结论**: 采集点 `TcpStateMachine.closeTcpConnection` **已经在做远比这贵的事**
(`target()` 的 CHM 查 + 4 次字符串拼接 + SharedFlow `tryEmit`, 见 §2.2), 而它**每连接只跑一次**,
不在逐包内循环里。所以统计记录的边际成本 **< 现有该行成本的 5%**, 属于噪声级 —— **前提是把 P0-1/2 和 P1-1 三处修掉**。

---

## 1. 审计基线与证据

| 事实 | 证据 |
|---|---|
| 采集点每次连接**只跑一次** (非逐包) | `TcpStateMachine.closeTcpConnection` 在 `tcpConnections.remove(key, conn)` 处终结唯一一条连接 (`TcpStateMachine.kt:1825-1897`) |
| 同点**已有**的成本: 域名反查 + 4 段字符串插值 | `:1851-1856`; `target(conn)` 内 `dnsInterceptor.lookupDomain(ip)` + `"$domain(${conn.dstPort})"` (`:180-184`) |
| 同点还有一次**可能阻塞**的 TUN 写 | `tunWriterProvider()?.writeBlocking(closePacket)` (`:1868`) —— 队列满时按 `queueWaitTimeoutMs`(默认 10s) 等 |
| 同点还有**两次** `synchronized(conn)` | `:1880`, `:1884` |
| 日志落盘成本 | `appLogThrottled` = CHM 查 + (size>512) 全表 `removeIf` + `>2048` 时 `clear()` (`VpnController.kt:331-349`); 连接结束日志**用默认 key = 整条 message** → 每条都是新 key → 节流表持续增长 |
| `appLog` 落点 | `AppMetrics.record(level,…)` + `_logFlow.tryEmit(...)` (`VpnController.kt:257-269`); replay=500 SharedFlow |
| 应用日志**有逐行时间戳** | `LogLine(timestamp, level, message)` (`VpnController.kt:267`) + 导出用 `SimpleDateFormat` 格式化 (`LogViewModel.kt:108-111`) → **这是无 adb 下唯一可用来测时间的方法** |
| 快照装配点 (1 行可插) | `resourceDiagnostics = "TUN 写队列 … · ${dnsInterceptor.diagnostics()}"` (`VpnController.kt:1311-1312`) |
| 内存先例 | `LruStringMap` 每条目额外一个 `Entry` 对象; `trim()` = `sortedBy{}.take(n).forEach{remove}` (**O(n log n) + 一个 20000 元素临时列表**), 且注释明写 **"应在低频任务中调用"** (`LruStringMap.kt:72-83`) |
| ABI 含 **32 位** | `splits.abi` 产出 `arm64-v8a` + `armeabi-v7a` → **普通 `long` 字段在 32 位上非原子, 会撕裂读** (影响 §3.4 的方案选择) |
| P2 的逐包先例 | `droppedByDstPort.merge(dstPort, 1L){a,b->a+b}` — 每个被丢弃的 UDP 包一次 CHM bin 锁 + Long 装箱 (`UdpRelay.kt:75`) |
| 远端命令的**真实**代价 | `execSingleShot` 里 `jsch.getSession(...)` + `s.connect()` + 执行 + finally `disconnect()` (**整条新会话**), 跑在 `Dispatchers.IO`, 轮询用 `Thread.sleep(50)` (`JschSshClient.kt:1337-1385`) |
| 池内 exec 的**正确**模板 | `verifySessionRoundTrip`: `pooled.session.openChannel("exec")`, 复用已有会话, 无握手 (`JschSshClient.kt:853-884`) |
| 现有采集量级 | 一次浏览会话: 下行 25.2MB / 上行 1.38MB; `在途通道` 峰值 **80** → 连接建立速率上限为**突发 ~80/s, 持续量级数十/s** (远低于任何需要担心的阈值) |

---

## 2. 采集侧核算

### 2.1 热路径频率 (先定 QPS, 否则成本讨论无意义)

- 连接关闭 = **每连接一次**, 与包速率**无关**。实测会话里 `在途通道` 峰值 80, 意味着并发 80 = 至多 80 次关闭挤在同一秒内。
- 持续速率: 25.2MB 下行的会话里, 长连接 (HTTP/2) 占多数 → **数十条/秒**量级。
- 取保守上界 **100 次/s**。作为对照, `ConcurrentHashMap` 单线程 get 约 20–50ns、`AtomicLong` 加约 5–10ns。

> **结论**: 100 次/s × (1 次 CHM + 4 次累加) ≈ **< 5µs/s** = 单核的 **0.0005%**。频率上完全不是问题。

### 2.2 边际成本必须**与同点已有成本对比**, 而不是孤立看

`closeTcpConnection` 在统计接入**之前**就已经在做:

| 已有动作 | 量级 |
|---|---|
| `target(conn)` → CHM 查 + 拼接 `"domain(443)"` | 1 次 CHM + **1 次 String 分配** |
| 4 段字符串插值 (`Long`×3 → String) | **3–4 次 String/数组分配** |
| `appLogThrottled`: CHM 查 + 节流表写入; 每 ~1451 次触发一次 2048 条 `removeIf` 全扫 | 1 次 CHM + (摊还) 扫表 |
| `appLog` → `_logFlow.tryEmit` (replay=500) | 1 次 SharedFlow 缓冲写 + 锁 |
| `writeBlocking(closePacket)` 可能阻塞 | **可达 10s** (队列满时) |

即: **每次连接关闭已经付出 ~5 次分配 + 2 次 CHM + 1 次 SharedFlow 写**。

统计记录加了什么 (按修正后的 §3.4 方案): **1 次 CHM get + (仅未命中时) 1 次插入 + 一次极短 `synchronized(entry)` 内 6 次字段更新 + 0 次分配**。

> **判决**: 边际成本 **≤ 已有成本的 5%**, 且两者都在同一条"每连接一次"的路径上。**"统计会不会拖慢数据面"这个担心, 在采集侧可以正式排除。**

### 2.3 ❌ P0-1 内存预算算错了 (最重要的数字错)

设计稿 §4.2: `20000 条 × ~200B ≈ 4MB`。逐项重算 (compressed oops / ART):

**公共部分 (两种方案都要付)**

| 部件 | 字节 |
|---|---|
| key String (平均域名 22 字符, compact Latin1: 16 对象 + 16 数组 + 22) | ~56 |
| `ConcurrentHashMap.Node` (头 16 + hash 4 + k 4 + v 4 + next 4) | 32 |
| 表槽 (摊还) | 4 |
| **小计** | **92** |

**方案 A = 设计稿原样 (`MutableSet<String> realIps` + 6× `AtomicLong`)**

| 部件 | 字节 |
|---|---|
| 对象头 | 12 |
| `domain` ref + `isIpLiteral` + 6 个 `AtomicLong` ref + `lastPort` + `realIps` ref + `source` ref | 4+4+24+4+4+4 = 44 |
| **6 个 `AtomicLong` 本体** | 6 × 16 = 96 |
| **一个空 `HashSet`** (= `HashSet` 24 + 内层 `HashMap` 48) | 72 |
| 对象体小计 (对齐后) | 64 + 96 + 72 = **232** |
| **合计/条** | **324 B** |

→ 20000 条 = **6.5MB** (设计稿声称 4MB, **偏低 1.6×**)
→ 且条目里有真实 IP 时再 +~90B/个 (Node 32 + String ~56)

**方案 B = 修正方案 (2 个可空 IP 字段 + 普通 `var` 字段 + `synchronized(entry)`; 见 §3.4)**

| 部件 | 字节 |
|---|---|
| 对象头 12 + `domain` ref 4 + `isIpLiteral` 4 + 6×8 (`long`) + `lastPort` 4 + `realIp` 4 + `realIp2` 4 + `source` ref 4 | 84 → 对齐 **88** |
| **合计/条** | 88 + 92 = **180 B** |

→ 20000 条 = **3.6MB**; 10000 条 = **1.8MB**

> **判决**: 预算表按**方案 B** 给, 并声明 `20000 条 ≈ 3.6MB (含 1.3× 安全系数 → 报 ≤ 5MB)`。
> 若坚持 `AtomicLong` (读侧免锁, 见 §3.4) 则 = 252B/条 → 20000 条 5.0MB, 上限建议降到 **10000**。

### 2.4 ❌ P0-2 淘汰**绝不能**在热路径跑

设计稿 §4.2 只写了"超限时按 `lastSeenAt` 批量淘汰最旧 5%", **没写在哪执行**。

- 20000 条排序 + `take` 临时列表 = **百 µs ~ 数 ms**, 且长这样 (`LruStringMap.trim()` 同款):
  一次 `sortedBy` 会在堆上建一个 20000 元素的列表 → **触发一次 minor GC 的压力**。
- 如果这一步**恰好发生在 `closeTcpConnection` 的调用者线程** (= `packetLoop`), 就是**整栈停顿**:
  所有连接的上行/回程在这几 ms 内全部不动。这是仓库里反复出现的那类"一个人拖死所有人"的故障形态。

**必须**: 超限时**只置一个 `trimPending = true`** (一次 volatile 写), 真正的淘汰由 30s flusher 线程做。
**且** 5% (1000 条) 一次淘汰过大 → 淘汰后立刻又被填充, 形成周期性尖峰。改为**摊还**: 命中上限时每次插入**只淘汰 amortized 若干条**, 或一次淘汰到容量的 **90%** (`LruStringMap` 就是这么做的: `target = maxSize - maxSize/10`)。

> 先例: `LruStringMap` 的 KDoc 明写 **"应在低频任务中调用"** —— 统计的 trim 必须遵守同一条。

### 2.5 五条必须写进设计的**热路径纪律**

| # | 纪律 | 理由 |
|---|---|---|
| 1 | **不分配**: 命中路径禁止 lambda 捕获分配 —— 不要 `computeIfAbsent(key){ DomainStat(...) }` (捕获了 key/now → 每次调用都 new 一个 lambda 对象), 改成 `val e = map[key]; if (e != null) … else putIfAbsent(...)` | 命中是常态 |
| 2 | **不二次查表**: 域名只 `lookupDomain` 一次, 同时喂给日志的 `target(conn)` 与统计 (现在 `target()` 内部已经查过一次) | 省 1 次 CHM + 让两者口径一致 |
| 3 | **不在 `synchronized(conn)` 内** 做统计/查表 | 不延长已有临界区, 也避开与 CHM bin 锁的锁序纠缠。放在 `:1851` 日志那一行附近 (锁外) |
| 4 | **不入库/不落盘/不 emit**: 热路径只动内存 | 仓库既有铁律 (热点路径只碰原子计数) |
| 5 | DNS 侧 (`onDnsResponse`)**只标注已存在的条目, 不新建** | 保持"`connCount ≥ 1` = 真访问过"的语义, 也避免把"解析了但没连"的域名灌进表里 |

---

## 3. 逐条判决表 (设计稿 vs 审计)

| 设计稿原文 | 判决 | 修正 |
|---|---|---|
| §3.3 "只 CHM + Atomic 累加, 无锁无分配无 IO" | ⚠️ 目标对, **但和 §4.1 的 `data class`/`MutableSet` 写法冲突** (必须配 §2.5 五条纪律才成立) | 补纪律 |
| §4.1 `data class DomainStat(... var realIps: MutableSet<String>)` | ❌ 两处错: ① `data class` 的 `equals/toString/copy` 覆盖 11 个字段 (误打进日志时开销大); ② `MutableSet` 每条约 +72B | 改 **普通 class** + `realIp`/`realIp2` 两个可空字段 |
| §4.1 字段用普通 `var Long` | ❌ **32 位 ABI 下 `long` 撕裂读** (armeabi-v7a 在产物里) + 多线程写丢更新 | 见 §3.4 两个可选方案 |
| §4.2 "上限 20000 / 淘汰 5% / 滚动 7 天" | ❌ 缺"在哪跑"; 5% 粒度易抖动 | trim 只由 flusher 做, 一次压回 90% |
| §4.2 "20000 × ~200B ≈ 4MB" | ❌ **偏低 1.6×** | 见 §2.3 |
| §5 "每连接 1 次 / 每丢包 1 次 / 每次真解析" | ✅ 频率判断正确 | 补"逐包路径"的说明 (P2) |
| §6 "P0 = 纯内存" | ✅ 零迁移风险, 正确 | — |
| §6 "P1 落盘 30s, ≤ 200ms" | ⚠️ **无依据** | 见 §3.3 |
| §7.2 "远端解析占一条 exec 通道 (与 SshIoDispatcher 同池)" | ❌ **机制说错了**: `execSingleShot` 新建整条 session, 不在池里 | 见 §3.2 |
| §7.2 "绝不后台批量解析" | ✅ 结论正确, **而且比设计稿以为的更重要** (因为是整条会话) | 保留并加强 |
| §9.2/9.3 UI | ⚠️ **没有性能预算** | 见 §3.1 |
| §9.3 "快照不加字段" | ⚠️ 与 §11 "新增计数器必须进清单" 矛盾 | 快照资源节加 1 行 |
| §11 "内存 ≤ 20000 × 200B ≈ 4MB" | ❌ 同 §4.2 | 见 §2.3 |
| §11 "热路径开销: 每连接 1 CHM + ≤4 原子加" | ⚠️ 数字对, **但没给参照系** | 补 §2.2 对比 |
| §11 验收方法 | ⚠️ 只有"上线前用 S2 核对快照各项" | 补 §5 的**可执行**方法 |
| §12 隐私 | ✅ 无性能影响 | — |

### 3.1 ⚠️ P1-1 缺 UI 侧预算 (最容易出事的一块)

设计稿没写列表怎么到 UI。如果实现成 `StateFlow<List<DomainStat>>` 并在连接关闭时更新 →
**每次连接都拷贝一个 20000 元素列表** (160KB 数组 + 全量 diff/recompose), 每秒几十次 → 必卡。
这正是仓库明令禁止的反模式 ("per-packet hot paths only touch AtomicLong … no per-packet MutableStateFlow emission")。

**必须写死的 UI 预算**:

| 操作 | 成本 | 约束 |
|---|---|---|
| 暴露 `countFlow: StateFlow<Int>` (设置页 subtitle / 徽标用) | O(1) (`map.size`) | 由 flusher 每 30s 推一次即可 |
| `snapshot(): List<DomainStat>` (全量拷贝引用) | ~0.2ms (160KB 数组) | **只**在开屏 / 手动刷新 / 页面可见时的低频轮询调用; 在 `Dispatchers.Default` |
| 排序 20000 条 | **3–8ms** | `Dispatchers.Default`, 结果缓存, **不要**在每次 recomposition 里重排 |
| 搜索/筛选 | 1–3ms | **300ms debounce** |
| `LazyColumn` | 只组合可见行 | 必须 `key = domain` |
| 导出 CSV (20000 行 ≈ 1.5MB) | 数十 ms | **流式写** (`BufferedWriter`), 在 IO 线程, 不复用拼接大字符串 |

> **禁止**: 任何从 `closeTcpConnection` 触发的 UI 通知。列表只在**用户主动看**的时候生成。

### 3.2 ❌ P0-3 "远端解析"的机制错了 (代价差一个数量级)

设计稿写"经当前 SSH 会话 exec `getent ahosts`", 并举 `RemoteCommandExecutor` 为机制。
实际代码: `execSingleShot` 是 **`jsch.getSession()` → `s.connect()` → 执行 → `disconnect()`**,
一条**全新的 SSH 会话** (`JschSshClient.kt:1345-1385`), 跑在 `Dispatchers.IO`, 内部 `Thread.sleep(50)` 轮询。

| 路径 | 代价 | 副作用 |
|---|---|---|
| **设计稿说的** (池内 exec) | 一次 channel open + RTT ≈ **数十 ms** | 无 |
| **实际会发生的** (`execSingleShot`) | **0.4 ~ 4s** (TCP+握手+认证; 真机日志里 SSH 建连最慢 4186ms) | **服务器侧会话堆积** —— AGENTS 明确记录过 "repeated connect/disconnect 累积服务端会话 → SSH connect 超时" |

而且它**不在 `SshIoDispatcher` 里**, 所以设计稿 §7.2 那句"与 SshIoDispatcher 阻塞线程同池"**也不成立**
(无 adb 环境里, 这类"机制与实况不符"的描述最危险 —— 后续排障会按错的模型找原因)。

**修正 (三选一, 推荐 ②)**:
1. 复用池内会话写 `JschSshClient.execOnPooledSession(pooled, cmd, timeout)` —— 直接照抄
   `verifySessionRoundTrip` (`:853-884`) 的 `pooled.session.openChannel("exec")` 模板, 复用已有会话, 无握手。
2. **一次会话跑一批**: 把本页所有待解析域名合成一条命令
   (`getent ahosts a; echo ---; getent ahosts b; …`), 一次 RTT 解 N 个。
   —— 既省会话又天然限流, 且和"手动触发"语义一致。
3. 砍掉远端解析 (只留本地视角)。若嫌 exec 受限 (OpenWrt/dropbear → `ROUND_TRIP_UNAVAILABLE`) 麻烦, 这是最省的选择。

**无论选哪个都必须**: 超时 (建议 5s) + `isSaturated()` 预检 + 单飞 (同一时刻只允许一个解析在跑)。

### 3.3 ⚠️ P1-2 落盘预算无依据

"每 30s 一次批量 upsert, ≤ 200ms" —— 20000 行的单事务在 SQLite 上量级是**秒级**, 且
**每 30s 全量重写 = 写放大** (flash 寿命 + I/O 抢占)。

**修正**: 追 **dirty set** (只 upsert 本周期变化过的条目), 单事务**分块** (如 500 行/事务, 块间 `yield()`)。
另外: P1 落盘应复用 `statsFlushLoop` 同款纪律 (**整个 body try/catch, `delay()` 留在 try 外,
`CancellationException` rethrow**) —— 否则发布协程抛一次就永久停更。
并把**实测耗时**记成计数器进快照 (这是唯一能证伪"≤200ms"的办法)。

### 3.4 字段并发方案 (32 位撕裂读) — 两个选项

`armeabi-v7a` 在产物里 → 普通 `var Long` 的读写在 32 位上**非原子** (会读到高位/低位拼接的脏值),
UI 上会看到"下行 9.2 EB"这种数。两个可选实现:

| | 方案 B: `synchronized(entry)` + 普通 `var` | 方案 A: `AtomicLong` |
|---|---|---|
| 内存/条 | **88 B** (字段 inline) | 252 B (6 个 AtomicLong 本体 +96B) |
| 20000 条 | **3.6 MB** | 5.0 MB |
| 写侧 | 非竞争轻量锁 (ART thin lock, ~ns) | CAS, 无锁 |
| 读侧 (快照) | **必须持同一把 entry 锁**, 20000 次轻量锁 ≈ 0.5–2ms (只在开屏时) | 免锁 |
| 风险 | 持锁期间**绝不能**调日志/IO (会变成新热点) | 多 12 万个对象 |

**推荐方案 B** (内存减半, 且与同文件已有的 `synchronized(conn)` 风格一致);
若评审认为"锁上热路径"心理负担太大, 选方案 A 并把上限降到 **10000** (2.5MB)。
**两者都禁止**用普通 `var` 裸读裸写。

---

## 4. 修正后的预算表 (替换设计稿 §11)

| 项 | 修正后预算 | 依据 | 自证方式 |
|---|---|---|---|
| 热路径频率 | ≤ 100 次/s (突发 80) | `在途通道` 峰值 80 + 会话量级 | 导出日志里 `TCP 连接结束` 的**行时间戳间隔** |
| 热路径边际成本 | **< 现有该行成本的 5%**; 0 分配 | §2.2 对比表 | 代码评审 (对比 `:1851` 那行) |
| 内存 (采集) | **方案 B: 20000 条 ≈ 3.6MB (报 ≤5MB)** | §2.3 逐项表 | 快照 `域名统计 N/M(淘汰 K)` + 页面 `已记录 N` |
| 内存 (UI 快照) | 峰值 ≈ 160KB 数组 + 排序临时表 | §3.1 | — |
| trim | **只由 flusher 做**, 一次压回 90% | §2.4 | 计数器 `统计淘汰` |
| 落盘 (P1) | 追 dirty set + 分块事务; **实测耗时进快照** | §3.3 | 快照 `统计落盘 Tms` |
| 远端解析 | 池内 exec 或单会话批量; 超时 5s; 单飞 | §3.2 | 日志 `远端解析 · domain — Nms` |
| UDP 归属 (P2) | 逐包 +1 CHM (与既有 `merge` 同量级), 建议**每流首次**计数 | §1 先例 | `UDP 丢弃` 节现有 |

### 4.1 ❌ P1-3 撤回"快照不加字段"

设计稿 §9.3 说"首页数据面快照不需要加字段", 与 §11 "新增计数器必须同步更新清单"矛盾。
仓库的立场是明确的: **快照是唯一诊断面**, 而"统计表有没有失控增长"恰恰是只能在快照里看的量。

**改为**: `VpnController.kt:1311` 的资源节追加一段 (O(1) 成本):

```
resourceDiagnostics = "TUN 写队列 … · ${dnsInterceptor.diagnostics()} · ${domainStats.diagnostics()}"
// → "域名统计 1234/20000(淘汰 0)"
```

并在 `docs/specs/2026-10-18-device-verification-checklist.md` §4 增加该行 (阈值: 淘汰数不持续增长)。

---

## 5. 验收方法 (无 adb 下**可执行**的版本)

设计稿只写了"上线前用 S2 核对快照各项"。补三条**可操作**的:

1. **频率与停顿自证**: 导出日志有逐行时间戳 → 对同一会话统计 `TCP 连接结束` 行数与
   **相邻两行的时间差**。若统计引入了停顿, 会看到某个间隔突然出现 `统计淘汰` 那条 WARNING 之后
   的 gap。→ 直接验证 §2.4 (trim 不在热路径)。
2. **不变量比对** (沿用清单): S2 (大文件下载) 跑前后, 快照里 `背压丢段` / `回程闸门阻塞` /
   `地址惰性失效` / `TUN 写丢弃` **必须与 v1.2.1 基线同量级**; 新增 `域名统计 N/M(淘汰 K)` 正常。
3. **内存口径**: 页面显示 `已记录 N/20000`; 淘汰计数**不持续增长** = 上限够用。

> **明确无法验证**: 真实堆占用 (需要 profiler / adb)、CPU 占用、GC 频率。
> 上文的内存数字是**解析式估算** (逐项列了字节), 有 1.3× 安全系数, 但**不是实测**。别把它当实测结论引用。

---

## 6. 审计后的行动项 (按优先级)

| # | 动作 | 落在哪 |
|---|---|---|
| 1 | `DomainStat` 改普通 class + `realIp`/`realIp2` 两字段 (去 HashSet) | 设计稿 §4.1 |
| 2 | 字段并发方案二选一 (推荐 `synchronized(entry)` + 普通 `var`) | 设计稿 §4.1 (新) |
| 3 | trim 只由 flusher 做, 压回 90% | 设计稿 §4.2 |
| 4 | 补 §2.5 五条热路径纪律 | 设计稿 §5 |
| 5 | 内存预算表改用 180B/条口径 | 设计稿 §6/§11 |
| 6 | 新增 UI 侧预算 (禁止热路径 emit) | 设计稿 §9 (新) |
| 7 | 远端解析改用池内 exec / 单会话批量 | 设计稿 §7.2 |
| 8 | 落盘改 dirty set + 分块 | 设计稿 §6 (P1) |
| 9 | 快照资源节加 `域名统计 N/M(淘汰 K)` + 更新清单 | 设计稿 §9.3/§11 + checklist §4 |
