# 架构优化可执行任务列表（T-A/B/C/E）

- 日期：2026-10-18
- 输入：`2026-10-18-architecture-review.md`（梳理） + `2026-10-18-arch-optimization-design.md`（方案 F-01…F-05）
- 任务 ID 前缀：`T-A*` = F-01 上行零分配，`T-B*` = F-02 TUN 单写者，`T-C*` = F-03 拆分，`T-E*` = F-05 观测事件化
- 状态：`☐ 待办` / `◐ 进行中` / `✅ 完成` / `⛔ 排后`
- **每个任务一条提交**，提交前跑 `ktlintCheck / detekt / lint / testDebugUnitTest / assembleDebug`

---

## 0. 全局护栏（每个任务都适用）

| 编号 | 护栏 | 违反后果 |
|---|---|---|
| G-1 | **组包/建 key 字节必须与旧实现逐位一致** | 连接错投、丢包（P0） |
| G-2 | **回程数据不可静默丢**（`serverSeq` 组包时已推进，本栈无重传） | 永久洞 |
| G-3 | **只有真正写出去才计数**（`bytesDownCounter` / `packetsSentCounter` / `StageCounters.onTunWrite`） | 健康误判 |
| G-4 | 关闭语义三条纪律（FIN/RST 区分、`ConcurrentHashMap.remove` 恰好一次、`synchronized(conn)` 守卫） | 连接卡死 |
| G-5 | 假 IP acquire/release 与 `state = Established` 同持 `synchronized(conn)` | 假 IP 池泄漏 |
| G-6 | 需要**驱动/内核行为证据**的改动（TUN 写原子性等）**只设计不实施**，标 `⛔ 待真机` | 无设备验证的赌博 |
| G-7 | 文案改动会使升级后用户以为行为变了 → 观测迁移必须**一字不差** | 体验回退 |

---

## 1. 任务表

### F-01 上行数据面零分配

#### T-A01 `IpPacketParser` 零分配 key — 状态 ✅
- **目标**：新增 `addressLow32(buffer, version, absOffset): Long` 与 `connectionKey(srcLow, dstLow, srcPort, dstPort): Long`；旧 `InetAddress` 版委托到新实现。
- **步骤**
  1. 先写 golden 测试：对 IPv4/IPv6 × 一组固定包，断言新旧 `connectionKey` 结果**相等**（含 IPv6 只取低 32 位的历史行为）。
  2. 实现 `addressLow32`：IPv4 读绝对下标 4 字节；IPv6 读 **后 4 字节**（`offset+12..15`）。
  3. 实现 4 参 `connectionKey`，位布局 `(srcLow shl 48) xor (dstLow shl 32) xor (srcPort shl 16) xor dstPort`。
  4. 旧 4 参（两 `InetAddress`）版改为委托。
- **验证**：`IpPacketParserTest` 新增 golden 用例；`testDebugUnitTest` 全绿。
- **监测**：无（纯函数）。
- **护栏**：G-1。
- **回滚**：单文件 revert，无调用方影响。
- **DoD**：新旧实现等价测试通过；`ktlintCheck`/`detekt` 无新增告警。

#### T-A02 `ParsedPacket` 携带 low 值 — 状态 ✅
- **目标**：`ParsedPacket` 增 `srcLow: Long` / `dstLow: Long`，在 `parseIpv4Header` / `parseIpv6Header` 读地址的同一次遍历中算出（**零分配**）；`PacketProcessor` 透传。
- **步骤**
  1. `ParsedPacket` 增两字段（构造顺序放末尾，避免打乱既有具名调用）。
  2. `parseIpv4Header`：`readIpv4Address` 改为读字节同时累加 low（或复用 `addressLow32`）。
  3. `parseIpv6Header`：同上，取后 4 字节。
  4. `PacketProcessor.processIpv4/6Packet` 把 `parsed.srcLow/dstLow` 传给 `processTcpPacket`。
- **验证**：T-A01 golden 覆盖解析结果；`PacketProcessorTest` 全绿。
- **监测**：无。
- **护栏**：G-1。
- **回滚**：可独立 revert（`ParsedPacket` 新字段有默认值 0 → 但会被 `processTcpPacket` 默认形参覆盖，注意顺序：先 A02 后 A03）。
- **DoD**：解析路径不再产生额外 `InetAddress` 克隆（代码审查确认）；单测全绿。

#### T-A03 连接 key 缓存 + 签名透传 — 状态 ✅
- **目标**：`TcpConnection` 增 `val key: Long`；`:520/:534/:547` 三处 key 重算改读字段；`processTcpPacket` 追加带默认值的 `srcLow`/`dstLow` 形参（测试零改动）。
- **步骤**
  1. `TcpConnection` 类体内加 `val key: Long = IpPacketParser.connectionKey(...)`（参考既有 `srcIpBytes` 做法，不作为主构造参数）。
  2. `processTcpPacket(..., srcLow: Long = ..., dstLow: Long = ...)`，内部 key 用形参。
  3. 三处 `IpPacketParser.connectionKey(conn.srcIp, conn.dstIp, ...)` → `conn.key`。
- **验证**：`TcpWindowGateTest` / `TcpCloseBannerTest` / `TcpBypassTest` 全绿（它们直接构造 `TcpStateMachine`，走默认形参）。
- **监测**：无。
- **护栏**：G-1、G-4（`remove(key, conn)` 双参不变）。
- **回滚**：独立 revert。
- **DoD**：上行不再有 `conn.srcIp.address` 克隆（`grep` 确认）。

#### T-A04 旁路判定字节化 — 状态 ✅
- **目标**：`CidrRoute` 预编译 `networkBytes`；新增 `matchesRaw(buffer, dstOffset, size, route)`；`VpnController` 增 `shouldBypassVpnRaw(buffer, version)`；`computeShouldBypassTcp` 保留为参考实现。
- **步骤**
  1. `CidrRoute` 类体内 `val networkBytes: ByteArray = network.address`。
  2. 实现 `matchesRaw`（`prefixLen` 逐字节比较，处理非 8 倍数位）。
  3. `VpnController` 增 raw 版判定；`extractDstIp` 仅在日志/建连需要时构造 `InetAddress`。
  4. 新增等价性矩阵测试：`(CIDR 列表 × 目的 IP 集)` 上 raw 与 `InetAddress` 版结果一致。
- **验证**：新测试 + `VpnControllerTest` 全绿。
- **监测**：无。
- **护栏**：G-1。
- **回滚**：`VpnController` 单点 revert（注意与 T-B04 同文件，需按序）。
- **DoD**：等价性矩阵覆盖 IPv4/IPv6、命中/未命中、边界（`/32`、`/0`、非 8 倍数）。

### F-02 TUN 写改单写者队列

#### T-B01 `TunSink` 抽象 — 状态 ✅
- 新文件 `domain/vpn/TunSink.kt`：`interface TunSink { write / writeBlocking }` + `fun tunSinkOf(lambda)`。
- **验证**：编译。
- **回滚**：删文件。
- **DoD**：KDoc 写清\"控制包可丢 / 数据包不可丢\"的语义与原因。

#### T-B02 `TunWriter` 实现 + 单测 — 状态 ✅
- 新文件 `domain/vpn/TunWriter.kt`：`ArrayBlockingQueue` + 单守护线程 + `CountDownLatch` 完成信号 + 回调计数。
- `TunWriterTest`（纯 JVM）：① FIFO 顺序；② 满队列：`write` 丢弃并计数、`writeBlocking` 阻塞直到有空位；③ 写失败 → `onFailed`；④ `close()` 排空队列并退出线程；⑤ `writeBlocking` 超时返回 false；⑥ 未 attach 时写入计失败。
- **护栏**：G-2、G-3。
- **DoD**：≥6 条用例；不依赖 Android/设备。

#### T-B03 数据面换型（`PacketProcessor` / `TcpStateMachine`）— 状态 ✅
- `setTunWriter((ByteArray)->Unit)` → `setTunSink(TunSink)`；`tunWriterProvider: () -> TunSink?`。
- `writeTcpPayloadToTun`（回程数据）→ `writeBlocking`；SYN-ACK/ACK/关闭包 → `write`。
- 三个测试文件用 `tunSinkOf { ... }` 适配。
- **验证**：`TcpCloseBannerTest`（三条关闭用例）/`TcpWindowGateTest`/`TcpBypassTest` 全绿。
- **护栏**：G-2、G-4。
- **回滚**：B01/B02 保留，本任务单独 revert。

#### T-B04 `VpnController` 接线 + 计数迁移 + 快照 — 状态 ✅
- `connect` 建 `TunWriter` 并 attach；`disconnect` `close()`；`writeToTun(data)` 委托 `writeBlocking`。
- 计数（`bytesDownCounter`/`packetsSentCounter`/`StageCounters.onTunWrite`）移入 `TunWriter` 回调。
- 60s 快照新增 `TUN 写丢弃 N 个(控制包)`；丢弃日志按 `AGENTS.md`（`throttleKey` 静态 + 级别）。
- **护栏**：G-3、G-6（**不删** `@Synchronized` 的遗留讨论 → 本任务用\"单写者\"替代 monitor，不声称\"写原子性已证\"）。
- **回滚**：仅 revert 本任务 → 退回 `@Synchronized`。
- **DoD**：`VpnControllerTest`/并发测试全绿；快照字段有断言或人工核对。

### F-03 拆 `VpnController`

#### T-C01 提取 `StatsPublisher` — 状态 ✅
- 新文件 `domain/vpn/StatsPublisher.kt`：承接 `statsFlushLoop` + 60s 快照拼装 + 计数器持有（`bytesUp/Down`、`packets*`）。
- **机械搬迁**：字段与函数原样移动，仅改调用点；零行为变更。
- **护栏**：`statsFlushLoop` 整段 `try/catch`（`delay` 留在 try 外，`CancellationException` rethrow）必须保留。
- **回滚**：独立 revert。
- **DoD**：搬迁前后快照输出字段一致。

#### T-C02 / T-C03（⛔ 排后）
- C-3 DNS 投递外移、C-4 会话状态外移：依赖 C-2 稳定 + 更长回归观察窗。

### F-05 观测事件化

#### T-E01 `AppEvent` / `AppMetrics` 基础设施 + 双写入口 — 状态 ✅
- 新文件 `domain/obs/AppEvent.kt`（sealed interface + `level` + `throttleKey` + `render()`）、`domain/obs/AppMetrics.kt`（原子计数）。
- `VpnController.appLog(event)` / `appLogThrottled(event)`：渲染 + 计数双写，**旧字符串入口保留**。
- 渲染单测：每个事件 `render()` 文案与旧字符串**逐字相等**（G-7）。
- **回滚**：旧入口未删，单事件回退 = 改回一行。

#### T-E02 失败/丢弃类迁移（⛔ 排后）
- 仅迁移排障面事件（上行丢段/回程溢出/零回程/握手失败…）；生命周期 INFO 保持默认 `throttleKey`。

### F-04 连接对象化 — ⛔ 排后（仅设计）
- 见 design 文档 F-04；入口条件：F-01/F-02 落地 + 真机回归矩阵可用。

---

## 2. 依赖图

```
T-A01 ──► T-A02 ──► T-A03                (同一文件链, 必须串行)
T-A04 (CidrRoute + VpnController)        (与 A01-A03 无文件重叠)
T-B01 ──► T-B02 ──► T-B03 ──► T-B04      (串行)
T-C01 (VpnController)                    
T-E01a (新文件) ──► T-E01b (VpnController 入口)
```

**VpnController 是唯一的多任务冲突点**：`T-A04` / `T-B04` / `T-C01` / `T-E01b` 都会改它 → **必须串行**。

## 3. 文件冲突矩阵

| 文件 | A01 | A02 | A03 | A04 | B01 | B02 | B03 | B04 | C01 | E01a | E01b |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `IpPacketParser.kt` | ✍ | ✍ | | | | | | | | | |
| `PacketProcessor.kt` | | ✍ | | | | | ✍ | | | | |
| `TcpStateMachine.kt` | | | ✍ | | | | ✍ | | | | |
| `CidrRoute.kt` | | | | ✍ | | | | | | | |
| `VpnController.kt` | | | | ✍ | | | | ✍ | ✍ | | ✍ |
| `TunSink.kt`（新） | | | | | ✍ | | | | | | |
| `TunWriter.kt`（新） | | | | | | ✍ | | | | | |
| `StatsPublisher.kt`（新） | | | | | | | | | ✍ | | |
| `AppEvent.kt`/`AppMetrics.kt`（新） | | | | | | | | | | ✍ | |
| 三个数据面测试 | | | ✔ | | | | ✔ | | | | |

✔ = 需适配；✍ = 写入。

## 4. 并行波次（执行计划）

| 波 | 可并行任务 | 说明 |
|---|---|---|
| W1 | `T-A01`+`T-A02`（`IpPacketParser`/`PacketProcessor`） ‖ `T-B01`+`T-B02`（新文件） ‖ `T-E01a`（新文件） | 文件零重叠，可真正并行 |
| W2 | `T-A03`（`TcpStateMachine`） ‖ `T-A04`（`CidrRoute`+`VpnController`） ‖ `T-B03`（`PacketProcessor`+`TcpStateMachine`） | ⚠ `T-A03` 与 `T-B03` 同文件 → **串行**（A03 先）；其余可并行 |
| W3 | `T-B04` → `T-C01` → `T-E01b`（均改 `VpnController`） | **串行**（冲突点） |
| W4 | 四道门 + 回归 + 文档状态更新 | — |

## 5. 复核结论（任务列表合理性审查）

### 5.1 依赖与顺序 ✅
- `A01→A02→A03` 顺序正确：`A02` 依赖 `A01` 的 `addressLow32`；`A03` 依赖 `A02` 的字段（否则 `conn.key` 无处取值）。
- `B01→B02→B03→B04` 正确：抽象 → 实现 → 数据面换型 → 接线。
- `A04` 与 `A01-A03` 无文件重叠，可并行；**已修正原\"A04 归 W1\"的想法**——`A04` 会碰 `VpnController`，与 `B04` 冲突，故放到 W2 且与 `B04` 保持先后。

### 5.2 冲突分析 ✅
- 唯一高风险冲突点是 `VpnController`（4 个任务）与 `TcpStateMachine`（`A03`/`B03`）。
- 已将 `TcpStateMachine` 两任务放入同波但**强制串行**；`VpnController` 四任务独立成 W3 串行。
- 新文件（`TunSink`/`TunWriter`/`StatsPublisher`/`AppEvent`）互不冲突，是并行的主要来源。

### 5.3 可验证性 ✅
- `T-A*`：golden + 等价性矩阵，**不依赖设备**。
- `T-B02`：新类纯 JVM 可测（假 sink 模拟满/失败/慢），**不依赖设备**。
- `T-B03/B04`：现有并发测试（`TcpCloseBannerTest`/`TcpWindowGateTest`/`Socks5BackpressureTest`）+ `VpnControllerTest`。
- `T-C01`：搬迁类 → 靠\"零行为变更\"审查 + 现有测试。
- `T-E01`：渲染单测逐字断言。
- ⚠ **缺口**：`T-B04` 的真机 TUN 行为（写阻塞/丢弃策略在真实 `FileOutputStream` 上的表现）**无法在本轮验证** → 已通过\"控制包可丢 + 数据包阻塞\"的设计把风险限制在可重传路径，并在快照加丢弃计数作为现场判据。

### 5.4 风险排序与降级策略 ✅
| 风险 | 任务 | 缓解 |
|---|---|---|
| key 位布局错误（P0） | A01/A02/A03 | golden 逐位断言 + 现有并发测试 |
| 回程数据被丢（P0） | B02/B03/B04 | 数据走阻塞写 + 超时计失败 + 快照计数 |
| 搬迁引入可见性 bug | C01 | 机械搬迁 + 独立提交 |
| 文案漂移 | E01 | 渲染单测逐字断言 |

### 5.5 范围决策 ✅
- `F-04`（连接对象化）与 `F-03 C-3/C-4` **本轮不实施**：触碰最高缺陷密度区且无真机回归，符合护栏 G-6。
- 已在 design 文档末尾\"附：本轮实施范围\"与本表状态列显式标注，不隐藏。
- `T-20`（摘 `@Synchronized`）**不被本计划替代为\"已证明安全\"**：`T-B04` 用单写者消除阻塞，但**不声称**`write(byte[])` 整包原子性已证 —— 该遗留项在 `2026-10-18-fix-todo.md` 中维持原状态。

### 5.6 遗漏检查 ✅
- 是否漏了 `UdpRelay` 的 TUN 写？→ `buildUdpResponsePacket` 经 `writeToTun`（数据语义）→ 归 `B04` 覆盖。
- 是否漏了 `Icmpv6Responder`？→ 同样走 `writeToTun` → `B04` 覆盖。
- 是否漏了 `VpnController.forwardDnsBypassPacket` 的 `writeToTun`？→ 已在设计文档点名（数据语义）。
- 是否漏了 `VpnControllerTest` 对 `extractDstIp` 的既有断言？→ `A04` 保留参考实现，测试不改。
- `throttleKey` 形参语义（表键）→ `E01` 由枚举派生，天然满足。
