# 审计报告：VPN IO 性能三阶段优化

- 日期：2026-08-06
- 审计对象：`perf/phase1-io` 分支提交 `f538a95`(Phase 1)、`1ba5c87`(Phase 2)、`12a2af0`(Phase 3)
- 审计方式：dev-workflow Phase 4 AUDIT（quality-check + design 双轴 + 交叉验证）
- 门禁状态：`testDebugUnitTest` / `ktlintCheck` / `detekt` 全绿，工作区干净

## 审计范围

| 提交 | 文件 | 核心变更 |
|------|------|---------|
| f538a95 | SshIoDispatcher, Socks5ProxyServer, Socks5TunnelPlugin | SSH 阻塞 IO 隔离 + 写移出 eventLoop + 有界队列背压 |
| 1ba5c87 | TunnelPlugin, Socks5TunnelPlugin, TcpStateMachine | 回向直通回调（跳过本地 socket 往返） |
| 12a2af0 | TcpStateMachine | 出向入队 + ACK 门控 + 写协程（packetLoop 不阻塞） |

## 严重问题（必须沟通）

无架构级致命问题。三阶段实现与既定设计方案一致，未发现数据丢失/死锁/资源泄漏的确定性缺陷。

## 中等问题（建议修复）

### M1. 跨线程可见性：`TcpConnection.forwardedBytes`/`browserSeq` 非 `@Volatile`
- 位置：`TcpStateMachine.kt:62,65`
- 写：packetLoop 线程（`processTcpPacket` `:170/:183`）；读：回向协程（Phase 2 直通后 callback 在 sshDispatcher 线程，`buildTcpResponsePacket` `:674` 读 `forwardedBytes`/`browserSeq`）
- 同一 data class 的 `state`/`lastActivity` 已标 `@Volatile`，但三个 seq 字段遗漏 → 可见性不一致
- 后果：回向 ACK/seq 可能读到过期值，导致对端重传或吞吐抖动（Phase 2 使跨线程频率显著上升，风险被放大）
- 修复：`browserSeq`/`serverSeq`/`forwardedBytes` 加 `@Volatile`（低成本，改动 3 行）

### M2. 取消语义不一致：`startOutgoingWriteLoop` 缺 `isActive`
- 位置：`TcpStateMachine.kt:1067`（`startSshWriteLoop` `Socks5ProxyServer.kt:818` 有 `isActive`）
- 后果：scope 取消后出向写协程仅依赖 `sock.isOpen` 退出（依赖 `closeTcpConnection` 先关 channel），语义不统一，取消延迟不确定
- 修复：while 条件补 `&& isActive`

### M3. 测试覆盖缺口（三阶段零单测）
- 三阶段提交均无测试文件；`grep` 确认无测试引用 `TcpStateMachine`/`Socks5ProxyServer` 新逻辑
- 背压队列挂起保序（`offerToSsh`/`offerOutgoing`）、ACK 门控、OP_READ 暂停/恢复均为并发复杂逻辑，纯真机验证风险高
- 建议补充：
  1. `offerToSsh`/`offerOutgoing` 单测：队列满→挂起→腾空→有序不丢
  2. ACK 门控单测：队列满不 ACK、腾空后恢复、重传收敛
  3. Socks5Connection 背压：慢 SSH 写 → OP_READ 暂停/恢复

## 低影响问题

### L1. Duplicated Code：两处"有界队列+挂起槽+锁"模式
- Phase 1：`Socks5ProxyServer.pendingToSsh` + `suspendedToSshBlocks` + `offerToSsh`
- Phase 3：`TcpStateMachine.outgoingSocks` + `suspendedOutgoing` + `offerOutgoing`
- 同一模式两份拷贝。可提取共享 `BoundedQueueWithBackpressure` 模块并单测，但两处锁域不同（写协程分别跑 sshDispatcher / IO），提取需谨慎，非紧急

### L2. `pendingTunCallbacks` 单 adapter seam
- `TunnelPlugin` 接口新增两个默认空方法，仅 socks5 实现——TunnelPlugin 本就是扩展点（未来加隧道），可接受；接口轻微污染

### L3. 连接关闭时队列残留数据静默丢弃
- 写协程随 channel 关闭退出，`pendingToSsh`/`outgoingSocks` 未消费数据丢弃——连接关闭语义可接受

## Design 视角（模块深度 / seam）

- **SshIoDispatcher**：浅而有效的隔离点，leverage 高（一处隔离，全应用受益），depth 合理
- **背压队列**：封装在连接内部，接口小、行为复杂（depth 好），但重复两处损害 locality（见 L1）
- **TunnelPlugin 回调 seam**：直通回向放在插件接口上，位置正确；注册时机关联 `clientPort`，与 `handleAccept` 一致

## 结论

实现符合设计，门禁全绿，无严重缺陷。建议按 **M1 → M3 → M2** 顺序修复后再进行真机验证（M1 三行低成本、M3 补关键单测、M2 统一取消语义）。修复完成后可进入 grill 复核。
