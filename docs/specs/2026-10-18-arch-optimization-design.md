# 架构优化实施方案（F-01…F-05）

- 日期：2026-10-18
- 输入：`docs/specs/2026-10-18-architecture-review.md`（梳理 + 诊断 P-1…P-5 + Top 5）
- 拆解：`docs/specs/2026-10-18-arch-optimization-todo.md`（T-xx 可执行任务）
- 命名：`F-01…F-05` 与梳理文档 Top 5 **一一对应**

---

## 0. 全局约束（每个任务都适用）

1. **纯逻辑/搬迁类改动先行**：能用纯 JVM 单测证明的，必须先有测试。
2. **不变量不可破**（违反即打回）：
   - `writeToTun` 语义 = **只有真正写出去才计数**（`bytesDownCounter` / `packetsSentCounter` / `StageCounters.onTunWrite`）；
   - 回程数据**不可静默丢**（`serverSeq` 在组包时就推进，丢了就是永久洞，本栈无重传）；
   - 关闭语义三条纪律（FIN/RST 区分、`ConcurrentlyHashMap.remove` 恰好一次、`synchronized(conn)` 守卫）；
   - 假 IP acquire/release 必须在 `synchronized(conn)` 内配对；
   - 组包字节必须与旧实现**逐字节一致**。
3. **一个任务一个提交**，Conventional Commits；触不变量的任务可单独 revert。
4. **零行为变更是搬迁类的验收标准**：先搬不改，行为调整单独提交。
5. **四道门**：`ktlintCheck` / `detekt` / `lint` / `testDebugUnitTest`（+ `assembleDebug`）。
6. **真机不可用**（本轮环境无设备）→ 任何需要驱动/内核行为证据的改动**只设计不实施**，
   在任务表里标 `⛔ 待真机`，不得以"应该没问题"为由落地。

---

## F-01 上行数据面零分配

### 问题与证据
`P-2`：上行每包 5–7 次堆分配 —— `parseIpv4/6Header` 2 个 `InetAddress` + 1 个 `ParsedPacket`；
`IpPacketParser.connectionKey`（`:257`）两次 `.address` 克隆；`VpnController.extractDstIp`（`:960`）再 1 个 `InetAddress`；
`TcpStateMachine` 四处重算 key（`:320/:520/:534/:547`）。

### 目标 / 不变量
- 上行每包分配 **7 → 3**（见下方"实测口径"），且**不含**任何 `InetAddress.address` 克隆；
- `connectionKey` 数值**逐位不变**（否则连接错投）；
- 组包字节不变；`TcpWindowGateTest` / `TcpCloseBannerTest` / `PacketProcessorTest` 全绿。

> **实测口径（落地后修正）**：原方案写"5–7 → 0–1"偏乐观。已落地的 A01–A04 去掉的是
> 2×`InetAddress.address` 克隆（`connectionKey`）+ 1×`InetAddress` + N×`address` 克隆（每条排除路由各一次，
> `extractDstIp`/`CidrRoute.matches`）。**剩余的 3 次分配**是 `ParsedPacket` + 两个 `InetAddress`
> （解析器仍要提前建出来给 `createTcpConnection` / 日志用）。
> 要做到 0–1 必须让 `InetAddress` **惰性化**，而惰性化只能靠"持有 buffer + 绝对下标"，一旦有
> 异步路径在下一包 read 之后才读地址就会读到**别人的地址**（帧别名竞态）—— 本栈无可依赖的
> 静态分析来证明所有消费点都在同一帧内，故列为 **T-A05（⛔ 待真机基准）**，本轮不做。

### 方案

**F-01.1 零分配 key 计算（`IpPacketParser.kt`）**

```kotlin
/**
 * 取 IP 地址的"低 32 位": IPv4 = 全部 4 字节; IPv6 = **后 4 字节**。
 * 与既有 connectionKey 的取样规则完全一致 (IPv6 只取低 32 位, 本栈历史行为, 不改)。
 */
fun addressLow32(buffer: ByteBuffer, version: Int, absOffset: Int): Long

/** 零分配组合: 位布局与既有 [connectionKey] 逐位一致, 由 golden 测试锁定。 */
fun connectionKey(srcLow: Long, dstLow: Long, srcPort: Int, dstPort: Int): Long

/** 保留给测试/冷路径 ([InetAddress] 版委托到上面)。 */
fun connectionKey(srcIp: InetAddress, dstIp: InetAddress, srcPort: Int, dstPort: Int): Long
```
位布局（**不得更改**）：`(srcLow shl 48) xor (dstLow shl 32) xor (srcPort shl 16) xor dstPort`。

**F-01.2 `ParsedPacket` 携带 low 值（零分配）**

`ParsedPacket` 增加 `srcLow: Long` / `dstLow: Long`，在 `parseIpv4Header` / `parseIpv6Header`
**读地址的同一次 `buffer.get` 中顺带算出**（IPv4 读 4 字节时累加；IPv6 读 16 字节时只累加后 4 字节）。

`TcpStateMachine.processTcpPacket` **追加两个带默认值的形参**，生产路径显式传、测试路径零改动：

```kotlin
fun processTcpPacket(
    buffer: ByteBuffer,
    srcIp: InetAddress,
    dstIp: InetAddress,
    payloadStart: Int,
    payloadLength: Int,
    srcLow: Long = addressLow32(srcIp),   // 测试友好; 生产路径显式传, 避免 .address 克隆
    dstLow: Long = addressLow32(dstIp),
): Boolean
```

**F-01.3 `TcpConnection` 缓存 key**

`TcpConnection` 类体内加 `val key: Long`（**不是构造参数**，避免影响 data class 语义，参考 `srcIpBytes` 的既有做法）；
`:520/:534/:547` 三处重算改为 `conn.key`。

**F-01.4 旁路判定改字节前缀比较**

- `CidrRoute` 在类体内预编译 `networkBytes: ByteArray`（构造时一次性 `network.address`）；
- 新增 `CidrRoute.matchesRaw(buffer: ByteBuffer, dstOffset: Int, size: Int, route: CidrRoute): Boolean`
  直接比较 buffer 字节，**不构造 `InetAddress`**；
- `VpnController` 新增 `shouldBypassVpnRaw(buffer, version)`，`extractDstIp` 退化为"仅日志/建连需要时才构造"；
- `computeShouldBypassTcp(dstIp, routes, mode)` **保留为参考实现**，新增等价性测试：
  同一组 `(CIDR 列表 × 目的 IP 矩阵)` 上 `raw` 与 `InetAddress` 两版结果必须一致。

### 迁移步骤
1. F-01.1 + golden 测试（红→绿：先写"新旧 key 必须相等"的测试，再实现 raw 版）。
2. F-01.2 `ParsedPacket` + 两个 `parse*`（`PacketProcessor` 透传）。
3. F-01.3 `TcpConnection.key`。
4. F-01.4 `CidrRoute` 预编译 + raw 匹配 + 等价性矩阵测试。
5. 四道门 + 现有并发测试。

### 风险与回滚
- 风险：key 位布局写错 → 连接错投/丢失（**P0 级**）。→ golden 测试逐个位断言 + 现有并发测试兜底。
- 回滚：每步独立提交，可单步 revert；F-01.4 可独立 revert 而不影响前三步。

---

## F-02 TUN 写改单写者队列

### 问题与证据
`P-3`：`VpnController.writeToTun` `@Synchronized`（`:1106`）+ 锁内阻塞写；调用方 = packetLoop 的 ACK、DNS 投递协程、每条回程线程。

### 目标 / 不变量
- 消除**跨连接** head-of-line：`packetLoop` 写控制包**永不阻塞**；
- 回程数据**不可丢**（必须真的写出去才算成功）；
- 保持"只有成功才计数"语义；
- 单写者 ⇒ 写序列化由结构保证（不再依赖 monitor，也不需要"`write(byte[])` 是否整包原子"的证据）。

### 方案

**F-02.1 `TunSink` 抽象（新文件 `domain/vpn/TunSink.kt`）**

```kotlin
/**
 * TUN 写入出口。两类写入语义不同, 必须分开:
 * - [write] 控制包 (SYN-ACK / ACK / 关闭包): 队列满**可丢**, 客户端会重传/重发;
 * - [writeBlocking] 回程数据 / DNS 应答: **不可丢** —— 组包时 serverSeq 已推进, 丢了就是永久洞。
 */
interface TunSink {
    fun write(data: ByteArray): Boolean
    fun writeBlocking(data: ByteArray): Boolean
}

/** 测试/简单场景: 单个 lambda 同时满足两类写入 (供既有测试零成本适配)。 */
fun tunSinkOf(write: (ByteArray) -> Unit): TunSink
```

**F-02.2 `TunWriter`（新文件 `domain/vpn/TunWriter.kt`）**

```kotlin
class TunWriter(
    private val capacity: Int = DEFAULT_QUEUE_CAPACITY,        // 512 包
    private val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS, // 5000
    private val onWritten: (Int) -> Unit,   // 成功: bytes/packets/StageCounters.onTunWrite(true)
    private val onFailed: () -> Unit,       // 失败: StageCounters.onTunWrite(false) + 日志
    private val onDropped: (Int) -> Unit,   // 控制包因队列满被丢
) : TunSink, AutoCloseable
```
- 单守护线程 `tun-writer` + `ArrayBlockingQueue<Pending>`；
- `write(data)` = `offer`（满 → 丢弃 + `onDropped`）；
- `writeBlocking(data)` = `put` + `CountDownLatch.await(writeTimeoutMs)`，返回**真实写入结果**；
- 队列 FIFO ⇒ 顺序由结构保证；
- `close()`：停止接收 → 排空队列 → join(超时) → 线程退出。
- `attach(OutputStream)` / 未 attach 时的写入按失败计数（对应既有"流为 null 也计失败"语义）。

**F-02.3 接线（`VpnController` / `PacketProcessor` / `TcpStateMachine`）**
- `PacketProcessor.setTunWriter(...)` → `setTunSink(TunSink)`；
- `TcpStateMachine.tunWriterProvider: () -> TunSink?`：
  - `:1069 writeTcpPayloadToTun`（回程数据）→ `writeBlocking`；
  - `:1758 sendAckToBrowser`、`:594/:979/:357` SYN-ACK、`:1826` 关闭包 → `write`；
- `VpnController`：`connect` 建 `TunWriter` 并 attach，`disconnect` `close()`；
  `writeToTun(data)` 保留为**数据语义**（委托 `writeBlocking`），`writeDnsResponse` / `forwardDnsBypassPacket` 继续用它；
- 计数与 `StageCounters` 移入 `TunWriter` 回调（写线程内），`VpnController` 不再持有写计数逻辑；
- 60s 快照新增 **`TUN 写丢弃 N 个(控制包)`**（`throttleKey` 与日志级别按 `AGENTS.md` 规范）。

### 迁移步骤
1. F-02.1 + F-02.2 + `TunWriterTest`（纯 JVM：顺序、满队列丢控制包、数据阻塞、写失败计数、close 排空/超时）。
2. F-02.3a `PacketProcessor` + `TcpStateMachine` 换型 + 三个测试文件适配（用 `tunSinkOf`）。
3. F-02.3b `VpnController` 接线 + 快照字段 + 现有并发测试。
4. 四道门。

### 风险与回滚
- 风险：`close()` 与重连竞态（旧 writer 仍在写新 TUN）→ writer 持 `OutputStream` 快照 + `volatile` 关闭标志；
  写线程未启动时 `writeBlocking` 超时兜底。
- 风险：`writeBlocking` 超时后数据丢失 → 超时必须计失败并打 WARNING（可观测），且超时值远大于正常写延迟。
- 回滚：F-02.3b 可单独 revert 退回 `@Synchronized`（F-02.1/2 保留无副作用）。

---

## F-03 拆 `VpnController`（四片外移）

### 问题与证据
`P-4`：1441 行同时承担 TUN 读写 / DNS 投递 / 统计发布 / 健康 / 日志 sink / 路由 / 白名单 / protect。

### 目标
`VpnController` 只留**编排与生命周期**；四个协作对象各自可单测。**零行为变更**（先搬不改）。

### 分片（每片一个提交，可独立 revert）

| 片 | 外移内容 | 目标文件 | 依赖 |
|---|---|---|---|
| C-1 | TUN 写出口 + 写计数 | `vpn/TunWriter.kt` | **F-02 已完成即视为 C-1 完成** |
| C-2 | 统计发布（`statsFlushLoop` + 60s 快照 + 计数器持有） | `domain/vpn/StatsPublisher.kt` | 无 |
| C-3 | DNS 投递（`dnsResponseDeliveryLoop` + `writeDnsResponse` + `pollResponse`） | `domain/vpn/DnsDelivery.kt` | C-2（共用 60s 快照注入） |
| C-4 | 会话状态与观测面（`_vpnState` / `_connectionStats` / `_logFlow` / `appLog` sink / `StageCounters` 取样） | `domain/vpn/SessionState.kt` | C-2 |

### 不变量
`isRunning` / `tunGeneration` 的可见性与顺序不变；`appLog` sink 必须仍在 `init` 注册（DI 拿不到实例的类靠它写日志）。

### 风险与回滚
- 风险：状态拆分引入新的可见性问题。→ **机械搬迁**（字段与函数原样移动，只改调用点），逐步替换字段访问。
- 回滚：每片独立提交。

---

## F-04 连接对象化（本轮仅设计，实施排后）

### 问题与证据
`P-1`：一条连接的状态散在 `TcpConnection`（12 字段）+ `Socks5Connection` + `pendingReturn` + 两把锁 +
两个标志位；关闭语义要在三条建立路径各写一次守卫。

### 目标形态
```kotlin
class Connection(val key: Long, val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int) {
    private val lock = Any()
    var state: TcpState          private set
    val tx: SendQueue            // 背压队列 (原 Socks5ProxyServer 的 Channel + pending slot)
    val rx: ReturnWindow         // 原 TcpStateMachine 的 pendingReturn + flowLock 闸门
    fun onClientSegment(seg: TcpSegment): Boolean
    fun onRemoteData(data: ByteArray, off: Int, len: Int)
    fun close(reason: CloseReason)   // graceful => FIN, 否则 RST; 恰好一次
}
```
`TcpStateMachine` 退化为"解析 → `conn.onClientSegment`"，`Socks5ProxyServer` 退化为"SOCKS 协商 + 把通道交给 `Connection`"。

### 为什么不本轮实施
- 触碰最高缺陷密度区（`TcpStateMachine` + `Socks5ProxyServer` ≈ 3.3k 行）；
- 关闭语义/窗口/背压的不变量**只能靠并发测试 + 真机回归矩阵**证明，本轮无设备；
- 必须先让 F-01/F-02 把数据面硬化（否则在移动的地基上重构）。

### 前置条件（DoD 的入口）
1. F-01、F-02 已合并且线上无回归；
2. 真机回归矩阵可用（连接建立/关闭/大文件下载/HTTP2 多路复用/QUIC 降级/断连重连）；
3. 三步拆解：① `ConnectionRegistry`（只做注册/查找/关闭）→ ② 发送侧（背压队列）→ ③ 接收侧（窗口闸门 + 回程缓存）。

---

## F-05 观测事件化

### 问题与证据
`P-5`：132 处 `appLog*`；60s 快照运行时拼串；40+ 手写 `throttleKey`（漏传会使节流表失效并被定期 `clear()` 全量解除）。

### 方案

**F-05.1（本轮实施）：基础设施 + 双写**
```kotlin
// ui/viewmodel/LogLevel 已存在; 新增 domain/obs/AppEvent.kt
sealed interface AppEvent {
    val level: LogLevel
    val throttleKey: String       // 由事件类型静态派生, 不再手写
    fun render(): String          // 渲染为**现有中文文案** (UI 不变)
    data class UpstreamDropped(val domain: String, val bytes: Int) : AppEvent
    ...
}
object AppMetrics {  // 原子计数, 快照直接读
    val events: ConcurrentHashMap<AppEventKind, AtomicLong>
}
```
- `VpnController.appLog(event)` 与 `appLogThrottled(event)` 新增入口：**渲染 + 计数双写**，旧字符串入口保留；
- 失败/丢弃类事件优先迁移（它们才是排障面）；
- **文案必须与现有一字不差**（否则用户在升级后会以为行为变了）。

**F-05.2（排后）：生命周期 INFO 迁移 + 快照读指标**
**F-05.3（排后）：诊断包导出**（metrics + 最近 N 条事件）

### 风险与回滚
- 风险：文案漂移 + 节流行为变化。→ 事件渲染单测逐条断言文案；`throttleKey` 由类型派生后**不可能再漏**。
- 回滚：旧入口保留，单事件回退 = 改回一行。

---

## 附：本轮实施范围

| 项 | 本轮 | 理由 |
|---|---|---|
| F-01 全量（含旁路 raw 匹配） | ✅ 实施（T-A01…A04） | 纯逻辑 + golden 可证，收益直接 |
| F-01 惰性地址（T-A05） | ⛔ 排后 | 需帧别名安全性的真机/基准证据（见上"实测口径"） |
| F-02 全量（含 TunWriter） | ✅ 实施（T-B01…B04） | 新类可纯 JVM 单测；单写者不依赖真机证据 |
| F-03 C-2（StatsPublisher 全量搬迁） | ◐ 部分（T-C01） | 先搬**快照文案 + 速率算术**（纯函数 + 单测）；计数器与 flush 循环移出需更大回归窗，维持排后 |
| F-03 C-3 / C-4 | ⛔ 排后 | 依赖 C-2 稳定 + 更多回归 |
| F-04 | ⛔ 排后 | 需真机回归矩阵（见上） |
| F-05.1 | ⛔ 排后（仅设计） | 基础设施本身零收益，收益全在 F-05.2/3 的调用点迁移；无设备验证的前提下不先铺空壳 |
| F-05.2 / F-05.3 | ⛔ 排后 | 依赖 F-05.1 落地 |

> 本轮收尾状态: F-01、F-02 全量落地并全门通过（5 个提交 + 2 个 refactor/optimization 提交）;
> F-03 只做了"可测性最高、风险最低"的那一片。F-05 未动 —— 理由见上表, 不隐藏。

