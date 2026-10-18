# 架构梳理与结构性优化评估（SSHInjector）

- 日期：2026-10-18
- 范围：`app/src/main/java/cn/srv0/sshinjector/`（90 个 Kotlin 文件 / 23,228 行）
- 定位：**架构层**梳理，不含功能缺陷（功能缺陷见 `2026-10-18-defect-audit.md`）
- 产出：本文（梳理 + 诊断 + Top 5）→ `2026-10-18-arch-optimization-design.md`（方案）→ `2026-10-18-arch-optimization-todo.md`（任务）

---

## 1. 总体架构

### 1.1 分层与职责

| 层 | 目录 | 职责 | 规模 |
|---|---|---|---|
| UI | `ui/` | Compose 20 屏 + Hilt VM；状态渲染统一走 `ui/StatusDisplay.build(state, resolve)`；四语言资源 | ~7.5k 行 |
| 编排 | `domain/usecase/` | `VpnController`（会话唯一运行态真源）、`ServerRepository`（唯一服务器读写边界） | ~1.8k |
| 数据面 | `domain/vpn/` | 自研 TCP 栈 + SOCKS5 服务端 + DNS 拦截 + 隧道插件 SPI | ~5.4k |
| 数据 | `data/` | Room v4（2 实体）、DataStore、JSch 会话池、Keystore 密钥、一键配置 | ~3.5k |
| 宿主 | `vpn/` | `SshVpnService`（前台服务 / VPN 接口 / 观察者 / 重连）、`BootReceiver` | ~1.3k |
| DI | `di/` | `AppModule`（`@Provides`）+ `TunnelModule`（`@Binds @IntoMap @StringKey`） | 小 |

**唯一扩展点**是隧道插件：`TunnelPlugin` + `TunnelConfigDescriptor` + `TunnelModule` 绑定。当前仅 `socks5` 一个实现，其余协议靠"远端 SOCKS5 出口"覆盖 —— 有意的架构选择，非缺口。

### 1.2 数据流

```
上行  TUN ──read(单 32KB ByteBuffer, VpnController.kt:87/748)──► VpnController.packetLoop（单线程）
        └► IpPacketParser 定界（粘连包按 IP 头 total length 逐包）
             └► PacketProcessor.processIpv4/6Packet（vpn/PacketProcessor.kt:91/119）
                  ├ TCP ─► TcpStateMachine.processTcpPacket（:273）
                  │        └► forwardThroughLocalSocks（非阻塞 SOCKS5，三阶段预算）
                  │             └► 127.0.0.1:<socksPort> ─► Socks5ProxyServer（:1304 行）
                  │                  └► ChannelDirectTCPIP（JSch）─► 远端
                  ├ :53 UDP ─► DnsInterceptor（假 IP / 系统 DNS 绕过）
                  └ 其它 UDP ─► 显式丢弃 + 计数（QUIC 已知降级）

回程  SshIoDispatcher 线程 ──► Socks5ProxyServer.relayFromTarget
        └► registerTunCallback(零拷贝 offset/len) ─► TcpStateMachine.writeTcpPayloadToTun（:1069）
             └► awaitFlowCapacity（客户端窗口闸门）─► tunWriter ─► VpnController.writeToTun（:1106 @Synchronized）─► TUN
```

三条**不经过数据面**的旁路：
1. TCP bypass（排除路由命中 / DOMAIN_SPLIT 未命中，拿到真实 IP）→ 用户态 protected socket 直连；
2. `SYN` 期命中 bypass → 直接 protected socket；
3. DNS 非 A/AAAA 或 SYSTEM/DOMAIN_SPLIT 未命中 → protected `DatagramSocket` 转发（`VpnController.forwardDnsBypassPacket`）。

健康观测是**双通道**：
- 端到端 `ConnectivityProber`（15s，loopback SOCKS + 域名型 CONNECT，归因 PROXY/TUNNEL/REMOTE/SSH）；
- 分阶段 `StageCounters` + `StageHealthEvaluator`（15s 取样，TUN 写 / DNS 投递 / 转发建立，归因 TUN/DNS/FORWARD）。

两者合并后**只降级显示，不杀连接**（D3 原则，`docs/specs/2026-09-30-connectivity-health-spec.md`）。

### 1.3 线程模型（理解这套代码的关键）

| 执行体 | 数量 | 职责 | 风险 |
|---|---|---|---|
| `VpnController.packetLoop` | 1 线程 | 唯一 TUN 读者 + 上行分发 | 任何阻塞 = 所有连接停 |
| `SshIoDispatcher` | 16…128 动态（`SynchronousQueue` + `CallerRunsPolicy`，`SshIoDispatcher.kt:29/36/38`） | 每连接 1 个阻塞 JSch 读线程 + 建连 | 池满时 `CallerRunsPolicy` 落到调用者线程 |
| `Dispatchers.IO` | 共享 64 | `SshVpnService.scope`、JSch、各 VM、仓库 | 与 SOCKS 握手轮询竞争 |
| `dns-bypass` cached pool | 弹性（`VpnController.kt:204`） | 阻塞 `DatagramSocket` 绕过 | 超时会堆线程 |
| `dns-cleanup` | 1（`DnsInterceptor.kt:214`） | 假 IP 宽限释放 + LRU trim（30s） | — |
| `DnsInterceptor` 线程池 | 固定（`DnsInterceptor.kt:125`） | DNS 查询解析 | — |
| Main | 1 | Compose + VM | 重组 |

**并发原语**：42 处 `@Synchronized` / `synchronized`；24 个 `@Singleton`；132 处 `appLog*`。
关键锁：TUN 写 monitor、`pendingReturnLock`、`backpressureLock`、`flowLock`、`synchronized(conn)`、`fakeIpAllocLock`。

> 结论：**这套系统的正确性目前主要由注释与测试承载**（`AGENTS.md` 中 200+ 行不变量即证据），而不是由类型/结构承载。

### 1.4 规模画像

前 6 个文件占全仓 **36.4%**（8,445 / 23,228 行）：

| 文件 | 行数 | 承担的职责 |
|---|---|---|
| `domain/vpn/TcpStateMachine.kt` | 1989 | TCP 状态机 + 窗口闸门 + 回程缓存 + 关闭语义 + 诊断 |
| `domain/usecase/VpnController.kt` | 1441 | 编排 + TUN 读写 + DNS 投递 + 统计发布 + 健康 + 日志 sink + 路由 + 白名单 |
| `data/remote/ssh/JschSshClient.kt` | 1337 | 会话池 + 健康探活 + 通道 + keepAlive |
| `domain/vpn/Socks5ProxyServer.kt` | 1304 | SOCKS5 服务端 + 连接持有 + 背压队列 + 回程中继 |
| `vpn/SshVpnService.kt` | 1232 | 前台服务 + 生命周期 + 重建 + 观察者 |
| `domain/vpn/DnsInterceptor.kt` | 1142 | 假 IP 分配 + 缓存 + 多模式解析 + 系统 DNS |

---

## 2. 结构性问题诊断

> 判定标准：**不是 bug，而是"让 bug 容易发生"的形状**。每条附证据。

### P-1 数据面不变量跨 4 个文件，靠注释传递（最高风险）

一条连接的状态分散在：`TcpConnection`（12 字段，`TcpStateMachine.kt:163`）、`Socks5ProxyServer.Socks5Connection`、`pendingReturn` 队列、`synAckWritten` / `fakeIpAcquired` 标志、`flowLock` / `backpressureLock` 两把锁。关闭语义要在 **SOCKS / bypass / direct 三条建立路径**上各写一次
`synchronized(conn) { if (state == Closed) return }` 守卫。
历史 P0（关闭语义、双 ISN、假 IP 配对、泵水管冻结）**全部**落在这一区域。

### P-2 上行每包 5–7 次堆分配

- `IpPacketParser.parseIpv4Header` / `parseIpv6Header`：每包 2 个 `InetAddress` + 1 个 `ParsedPacket`；
- `IpPacketParser.connectionKey`（`:257`）：`srcIp.address` / `dstIp.address` **各克隆一个 ByteArray**；
- `VpnController.extractDstIp`（`:960`）：排除路由非空时每包再建 1 个 `InetAddress` + 1 个 `ByteArray`；
- `TcpStateMachine` 在 `:320/:520/:534/:547` 四处重算 key。

Android 大堆 GC 一停，`packetLoop` 就停；`packetLoop` 停 = **所有连接一起停**。

### P-3 TUN 写是全局单点

`VpnController.writeToTun` 是 `@Synchronized`（`:1106`）且**锁内阻塞调用** `FileOutputStream.write`。
调用方包括：`packetLoop` 的 ACK（`TcpStateMachine.sendAckToBrowser`）、DNS 投递协程（`writeDnsResponse`）、
每条 SSH 回程线程（`writeTcpPayloadToTun`）。TUN 缓冲一满 → 跨连接 head-of-line，且持锁者可能是最慢的那个。
（对照：Windows/Linux 上 TUN 写阻塞是常态。）

### P-4 编排层过载

`VpnController`（1441 行）同时承担：TUN 读写、DNS 投递、统计发布（100ms + 60s 快照）、健康归因、
日志 sink、排除路由、白名单注入、protect 回调、生命周期状态机。任何一项改动都要在 1400 行里定位。

### P-5 观测是字符串，不是数据

132 处 `appLog*`，60s 快照是**运行时拼串**；节流靠 40+ 个手写 `throttleKey`（`AGENTS.md` 已记录：
漏传 `throttleKey` 会让节流表被动态文案撑爆并触发全表 `clear()` 解除所有窗口）。
无结构化计数器、无趋势、无法自动化判据 —— 无 adb 现场**只能靠人读日志**。

---

## 3. Top 5 优化建议

| # | 优化 | 解决的问题 | 影响面 | 工作量 | 可验证性 |
|---|---|---|---|---|---|
| F-01 | 上行数据面零分配 | P-2 | 吞吐 / GC 稳定性 | 中 | 高（golden + 现有并发测试） |
| F-02 | TUN 写改单写者队列 | P-3 | 多连接公平性 | 中 | 高（新类可纯 JVM 单测） |
| F-03 | 拆 `VpnController` | P-4 | 维护成本 / 改错率 | 中 | 高（纯搬迁） |
| F-04 | 连接对象化 | P-1 | 缺陷率 | 大 | 中（依赖真机回归矩阵） |
| F-05 | 观测事件化 | P-5 | 可诊断性 | 小-中 | 高（事件渲染单测） |

**建议顺序**：F-01 → F-05(E01) → F-02 → F-03(分片) → F-04。
理由：F-01 无行为变更且直接降 GC；F-02 依赖"控制包可丢 / 数据包不可丢"的语义拆分，是 F-03 的第一片；
F-04 风险最大，必须在 F-01/F-02 把数据面硬化之后再动。

### 各项要点

- **F-01**：把 `connectionKey` 的计算下沉到"解析时就拿到地址低 32 位"（零分配），
  `TcpConnection` 缓存 key，`extractDstIp` 改为字节前缀比较。目标：上行每包分配 5–7 → 0–1。
- **F-02**：引入 `TunSink`（`write` = 非阻塞 offer / `writeBlocking` = 等待真写入），
  控制包（SYN-ACK / ACK / 关闭）可丢并计数，回程数据必须写出去（丢了就是永久洞，因为 `serverSeq` 已推进）。
- **F-03**：按 `TunIo` → `StatsPublisher` → `DnsDelivery` → `SessionState` 四片外移，每片独立提交、零行为变更。
- **F-04**：`Connection` 一等对象（身份 + 状态 + 序号/窗口 + 发送队列 + 关闭回调），
  把两把锁与三条守卫路径收进它的内部实现。
- **F-05**：`AppEvent`（枚举 + 参数）+ `AppMetrics`（原子计数），统一渲染为现有中文文案，
  `throttleKey` 由枚举自动派生。

---

## 4. 与既有文档的关系

| 文档 | 关注点 | 与本文关系 |
|---|---|---|
| `2026-10-18-defect-audit.md` | 具体缺陷（功能/安全/资源/性能） | 已修 22/24；本文是**架构层**评估，不重复 |
| `2026-10-18-fix-design.md` / `-fix-todo.md` | 缺陷修复方案与任务 | 已完成；本文的 T-20/T-21/T-22 遗留在此继续 |
| **本文** | 架构梳理 + 结构性诊断 + Top 5 | — |
| `2026-10-18-arch-optimization-design.md` | F-01…F-05 代码级方案 | 本文的落地设计 |
| `2026-10-18-arch-optimization-todo.md` | 可执行任务 + 依赖/冲突/波次 | 设计文档的拆解 |
