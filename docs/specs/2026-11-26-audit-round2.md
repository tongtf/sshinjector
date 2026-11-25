# 第二轮全量审计 (2026-11-26 · main / 1.3.1)

> 触发：1.3.1 发布后，对**接口 / 包 / 类 / 全流程 I/O / 算法复杂度**五个维度做并行只读审计。
> 方法：5 条独立审计线 + 主线程逐条复核代码（标注「已验证」的均为读码确认，不是转述）。
> 代码基线：`main@cdb093b`（v1.3.1）。审计期间未修改代码。

---

## 0. 结论

- **发布**：v1.3.1 的 `Lint & Test` / `Build Debug APK` / `Build Release AAB` 全部成功，Release 已创建。
- **3 个 P0（均已逐行复核，建议 1.3.2 热修）**：
  1. 出口对账协程在第一次 `disconnect()` 后被 `cancelChildren()` 永久杀死（本轮多出口回归）；
  2. 多出口状态 chip 因 `StateFlow` 对**同一批可变实例**的 `equals` 去重而永不更新（本轮回归）；
  3. **DNS 查询 ID 计数器无上限**：进程内第 65536 次上游查询起 dnsjava `setID` 抛异常，此后该进程所有 DNS 失败（历史缺陷，非本轮引入）。
- **1 个 P1 级契约缺陷**（当前未触发，但扩展即坏）：`TunnelPlugin` 的直通回调默认空实现，调用侧把“没抛异常”当“直通可用”。
- 其余集中在：非 `@Volatile` 的状态可见性、两条清理路径分叉、会话计数漏清零、DNS/UDP 无界队列、热路径重复分配、分层塌陷（domain 直连 DAO/UI 日志枚举）。
- **未发现**会在正常使用下立即 OOM 或确定性死锁的 P0（复杂度线）。

---

## 1. P0（已逐条复核）

### P0-1 出口对账协程被 `disconnect()` 永久杀死

- 位置：`domain/usecase/VpnController.kt:180-201`（`init` 内唯一的 `allExitsFlow` collector）、`:801` / `:947`（`disconnect`/`forceReset` 的 `coroutineContext.cancelChildren()`）。
- 证据：全仓只有这一处订阅 `allExitsFlow` 做回收（`MultiExitViewModel` 那处只喂 UI）；`connect()` 不重建该 collector。
- 后果：**从第二个会话开始**，删除服务器/出口不再回收运行中的出口 → 端口 + 3 条 SSH 会话泄漏到进程结束，新连接仍可能派发到已删除出口。P1-4 只在一个会话内有效。
- 核验：读码确认（`cancelChildren` 作用于 `VpnController` 自身 scope；无第二次 `launch`）。
- 建议：把对账 collector 放进**独立** `CoroutineScope(SupervisorJob())`（不随会话取消），或在 `connect()` 内重新 `launch`；补一条“断开→删除→再连接”的测试。

### P0-2 多出口状态 chip 永不更新

- 位置：`domain/vpn/tunnel/TunnelManager.kt:34-52`（`ActiveExit` 是 `data class`，可变字段在构造参数）、`:66-68`（`publishExits` 发布同一批实例）、`:238-257`（健康变化后 publish）。
- 证据：`MutableStateFlow<List<ActiveExit>>` 用 `equals` 去重；`data class.equals` 对同一实例 `this === other` 直接 true → 新列表与旧列表相等 → **不发射**。
- 后果：chip/`N 连`/`失败 N` 永远停在第一次快照（`Ready`）；`Degraded/Failed` 不可见。P1-16 实际未生效。单测只读 `.value`，掩盖了 collect 语义。
- 建议：发布**不可变快照** `ExitSnapshot(id, health, activeConnections, failTotal, ...)`（普通不可变 data class，每次新实例），UI 只读快照；补一条 `collect` 能收到健康变化的测试。

---

## 2. P1（已复核，按影响排序）

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| I-1 | `TunnelPlugin.kt:69-100` + `TcpStateMachine.kt:1203-1218` | `registerTunCallback`/`registerTargetEofCallback` 默认空实现；调用侧注册不抛异常即 `directRelay=true`，跳过 `startRelayFromSocks` | 任何不实现直通的插件 → 回程 0 字节、远端 EOF 不上报、零日志。当前只有 SOCKS5 实现，**尚未触发** |
| I-2 | `VpnController.kt:167,302,303` | `protectDatagramChannel`/`excludedRoutes`/`transportMode` 非 `@Volatile`；`updateDnsMode()` 在 packetLoop 运行时改写 | 运行期切 DNS 模式可能长期不生效；`transportMode` 与 `DnsInterceptor` 各存一份 |
| I-3 | `Socks5TunnelPlugin.kt:27,43-44` | `socksServer`/`_socksAuth` 非 `@Volatile`，由 connect 协程写、packetLoop/快照/探测读 | 读侧可能见 null → `isHealthy=false`（全部回退主出口）或 SOCKS 认证 fail-closed |
| I-4 | `SshVpnService.kt:894-947` vs `:983-1003` | 两条清理路径分叉：`teardownSession` 漏 `poolFailReconnectPending=false`、不 `reportHealth` | 强制断开后残留 true → 下一次 Failed 观察者永远跳过清理，UI/资源卡住 |
| I-5 | `VpnController.kt:675-679` | `connect()` 的 `catch (Exception)` 吞 `CancellationException` | 正常取消被打成 ERROR + 进「故障归因」 |
| I-6 | `VpnController.kt:157-164` + `:1577-1580` | 4 个 TUN 丢弃计数不随会话清零 | 新会话快照带上一会话的丢弃值 |
| I-7 | `ServerRepository.kt:81-85` | `existingId=-1` 表新建；传 `0`（`ServerConfig.id` 默认值）会静默 `return`，调用方仍 `saved=true` | 保存“成功”但零写入；并发删除编辑同样静默丢输入 |
| I-8 | `SettingsDataStore.kt:102` + `SshVpnService.kt:916,992` + `ServerDeleter.kt:40` | `lastServerId` 类型 `Long?`，清除写 `0`（双哨兵） | 新消费者按 `!= null` 判断会开机连 `id=0` → `Server not found` |
| I-9 | `StatsListApplier.kt:60-64` | 三键分三次 `DataStore.edit`，非事务 | 中途失败 → 半更新且无撤销 token |
| I-10 | `ServerFormValidator`（仅 UI） vs `ServerRepository.saveServerEdit`（零校验） | 范围不变量只在 UI | 非 UI 路径可落 `mtu=9000/port=0`，运行时才炸 |
| I-11 | `UdpRelay.kt:82-89` | 每丢一个 UDP 包都 `portBreakdown()`（排序+拼接）后再交给节流 | QUIC 洪峰下纯 CPU/GC 浪费；60s 节流对实参求值无效 |
| I-12 | `TcpStateMachine.startRelayFromSocks:1789` | bypass/fallback 中继占 `SshIoDispatcher` 线程 + 8ms 空闲轮询 | 128 条空闲直连可打满 SSH 池 → 隧道新连接被 0x05 拒 |
| I-13 | `DnsInterceptor.kt:182-190` | `dns-probe` 固定 2 线程 + **无界** `LinkedBlockingQueue` | 上游慢时任务无限排队，查询 30s 过期后静默失败 |
| I-14 | `DnsInterceptor.kt:253` | `pendingResponses` 为 `Channel.UNLIMITED` | TUN 投递停摆时可无界堆积 |
| I-15 | `MultiExitViewModel.kt:5,80` | UI 直接注入 `WhitelistDao`（全仓唯一） | 违反 UI→domain 纪律；Entity 变更击穿 VM |
| I-16 | `ui/viewmodel/LogLevel.kt` + `MainViewModel.kt:727` | `LogLevel`/`LogLine` 在 UI 包，14 个 domain/data 文件反向 import | 分层倒置；模块化时必爆 |
| I-17 | `domain/usecase/ServerRepository.kt` / `ProxyExitRepository.kt` | domain 包直连 Room DAO/Entity/Keystore | 分层名存实亡；domain 单测必须 mock 数据层 |
| I-18 | `MainViewModel.kt:127` + `DashboardScreen.kt:1094-1110` | `UiState.dnsMode` 是本地化 String，Dashboard 用 `stringResource` 反比字符串决定配色 | 应用内切语言后 chip 配色静默回退默认色 |

---

## 3. P2 / 设计选择（摘要）

- `TcpStateMachine`/`Socks5ProxyServer`/`UdpRelay` 共用 `TAG="PacketProcessor"`（logcat 过滤串类）。
- `ServerRepository` 兼白名单 CRUD；`RemoteListFetcher` 在 `data.local`；`InstalledAppsSource` 在 `ui.screen.whitelist`。
- `TcpConnection`/`PooledSession` 是 `data class` 且可变字段在构造参数（`copy()`/`indexOf` 陷阱）。
- `RoutingTable.withKnownExits` 死代码；`StepResult` 死类型；`useLegacyEcdsaFormat` 进程级全局开关。
- `ConnectionResult(success,error)` 允许矛盾态；`disconnect()` 恒 true。
- 端口/服务器 id 有多套哨兵（0/-1/null）。
- `RemoteDomainResolver` 恒绑主出口，但承诺“出口实际会连的 IP”（多出口语义不符）。
- `appLogThrottled` 的 map 会被**生命周期 INFO（默认 key）**灌大，2048 时整表 clear 会解除全部节流窗口。
- `excludedRoutes` 由 DB 提供、UI 无编辑入口；若开放用户编辑必须设上限（当前 ≤4）。
- 设计选择（不算缺陷）：`TunSink` 双语义、`ExitChoice` 密封、`UidRequest` 必完成、`RouteTarget` 同帧不变量、`LruStringMap` 独立淘汰、`DomainStatsStore` 热路径零分配。

---

## 4. 算法复杂度（已验证的结论）

| 结论 | 位置 | 量级 / 触发 |
|---|---|---|
| 逐包路径基本 O(1)、零分配（连接 key） | `IpPacketParser`/`VpnController.packetLoop` | ✅ |
| **UDP 丢弃日志每包排序** | `UdpRelay:82-89` | O(k log k)+字符串/包，QUIC 洪峰是纯浪费（P1） |
| **bypass 中继 8ms 轮询占 SSH 池** | `TcpStateMachine:1789-1818` | 125 唤醒/s·条；128 条打满池（P1） |
| 规则匹配每次 SYN 重复 `lowercase`/`address` | `RoutingTable.select` | 512 规则 → 512 次分配/连接（P2） |
| DNS 直查队列无界 | `DnsInterceptor:182` | 上游慢 → 无限排队（P1） |
| `pendingQueries`/`tcpConnections`/`pendingWrites` 无硬上限 | 多处 | DNS 洪水 / bypass 连接 / 降级中继（P2） |
| 快照/统计/日志均周期性、有界 | `DataPlaneSnapshot`/`DomainStatsStore`/`AppMetrics` | ✅ 30s/60s 门控，无热路径排序 |
| 回程批量 64 段 + `downlinkLock` | `writeTcpPayloadToTun` | ✅ 交接次数 /64，顺序有保证 |

---

## 5. 建议的 1.3.2 热修范围

**P0（必须）**
1. P0-1 对账 collector 独立 scope / connect 重建。
2. P0-2 改为不可变 `ExitSnapshot` 发布。
3. **F1 `setID(queryId and 0xFFFF)`（或计数器在 1..65535 内回绕）** + 单测跨过 65535。
4. I-4 `teardownSession` 与 `disconnectInternal` 合并（或补齐 `poolFailReconnectPending=false` + `reportHealth`）。

**P1（低成本、强收益）**
4. **F2** 白名单过滤后为空 → 回退全部直连 + WARNING；**F3** SYN-ACK 写失败不置 `synAckWritten`（或关连接）；**F4** 批量写失败关连接；**F5** `dataOffset` 校验。
5. I-2/I-3 三处 `@Volatile`。
5. I-5 `CancellationException` rethrow + `NonCancellable` 清理。
6. I-6 TUN 丢弃计数在 `connect()` 清零。
7. I-7 `existingId: Long?`；I-8 `clearLastServerId()`。
8. I-11 UDP 日志移入节流后（或非 DNS 快路径）。
9. I-12 bypass 中继移出 `SshIoDispatcher`（独立小池）或空闲退避 ≥50ms。
10. I-13/I-14 DNS 队列加上限 + 丢弃计数。

**P2/架构（单独立项）**
- I-15/I-16/I-17 分层收敛（WhitelistDao、LogLevel、domain 仓储）。
- `TcpConnection`/`PooledSession` 去 data class；`RoutingTable` 预计算。
- `readBuffer` 按 generation 独占（重建期帧污染风险）。

---

## 6. 全流程 I/O 与边界（第五线，已复核关键项）

### P0

| # | 位置 | 输入 → 期望 → 实际 | 影响 |
|---|---|---|---|
| **F1** | `DnsInterceptor.kt:255,720,737,1123,1137` | 上游查询 ID = `AtomicInteger.incrementAndGet()`，无 0xFFFF 掩码；`Message.header.setID()` 在 >65535 时抛 `IllegalArgumentException` | 进程第 **65536** 次上游查询起：`setID` 抛异常 → 被 catch 成 `DNS 查询处理失败`，计数器继续增长 → **此后所有 DNS 永久失败**，只能杀进程恢复。SYSTEM / 非 A/AAAA / DOMAIN_SPLIT 直连名都会消耗计数 |

### P1

| # | 位置 | 输入 → 期望 → 实际 | 影响 |
|---|---|---|---|
| **F2** | `SshVpnService.kt:716-776` | 白名单非空 → 只允许名单应用；实际循环跳过自身包、`NameNotFoundException` 只记日志；若过滤后 **0 个** `addAllowedApplication` 成功，Builder 的 allowed list 为空 = **全设备进 TUN** | 名单只含自身/应用全被卸载时，原文案写"该应用将直连"，实际全设备代理（隐私/语义反转） |
| **F3** | `TcpStateMachine.kt:812,1345,528` | SYN-ACK 用 `write`（队列满即丢），但无条件 `synAckWritten=true`；回程闸门只认该字段 | 控制包丢失 + 回程已直写 → 客户端丢弃回程但 `serverSeq` 已推进 → 永久洞，卡到 300s |
| **F4** | `TcpStateMachine.flushReturnBatch:1547` | `writeBlockingBatch` 返回 false 时只回退计数，**不关连接**；`serverSeq` 已推进 | 后续段全部乱序被客户端丢弃，下行永久 0，卡到 300s |
| **F5** | `TcpStateMachine.kt:467,482-489` | TCP `dataOffset` 未校验 20..60；`<20` 把 TCP 头当载荷，`>payloadLength` 抛异常 | 畸形包会把 20B 头写进隧道/每包异常（需 raw socket，正常内核不会造） |
| **F6** | `DnsInterceptor.kt:1135-1138` | 非 A/AAAA 重建查询 `Message(originalQueryId)`（flags=0），未 `setFlag(RD)`、未带 OPT | 递归解析器可能回 referral/REFUSED；MX/TXT/PTR 长期解析失败 |
| **F7** | `TcpStateMachine.kt:1410-1422` | SOCKS5 域名字段 `size.toByte()` 无 ≤255 校验；dnsjava 转义名可超长 | 长度字节回绕（1000→0xE8），本地 SOCKS 错位解析/连错目标 |
| **F8** | `VpnController.kt:1734-1737` | SYSTEM 旁路 DNS 用 512B 缓冲，超长响应静默截断、不置 TC | 大响应（EDNS/长 TXT）解析失败 |

### P2 / 记录

- F9 首页流量计数器进程级、不随会话清零（与"连接时长"口径不一致）。
- F10 `dnsMode` 在建 TUN 与 `connect()` 各读一次，毫秒级窗口可组合出不一致策略。
- F11 出口探活 `withTimeoutOrNull(3s)` 包住**全阻塞** `probe()`，超时不会中断（最多 ~10s，结果被丢弃）。
- F14 `pendingResponses` UNLIMITED + `dns-probe` 无界队列（与复杂度线 I-13/I-14 同）。
- F17 `TunWriter` 的 in-flight `Pending` 在 `close()` 时无 latch 释放（TUN 写永久阻塞的极端场景）。
- F19 删除当前服务器后 `lastServerId` 不清（与 I-8 同源）。
- F20 老库/导入的 `mtu/port/socksPort` 无运行时复验（与 I-10 同源）。
- F22 假 IP 的 60s 待摘除登记跨会话保留，新连接 acquire 不取消登记（低概率 0x03）。
- F12/F18/F21 等 `FALSE_POSITIVE_RISK` 高，随相关代码顺手加固即可。

### 已验证安全的边界（抽选）

多包定界（IPv4/IPv6 长度、flow label 不当长度）、IPv6 扩展头/上限 10、SYN 选项有界解析、u32 序号 RFC1982、窗口闸门锁纪律、上行部分写/队列优先、关闭语义与 `downlinkLock`、`pendingReturn` 锁内读写、假 IP 无符号 clamp/配对/宽限、DNS 会话代际/缓存标签/空答案不缓存、回程组包 IPv6 校验和 0xFFFF、SOCKS 服务端分段解析、规则解析上限/保序、出口 Starting 登记/身份复查/Failed 重建、统计热路径零分配、CSV/日志导出契约、系统 DNS 防自环、凭据/TOFU/认证。

---

## 7. 复核方法说明

- 每条 P0/P1 均在主线程重新打开源码确认（文件/行号见上）；未依赖审计线的转述。
- 未做：真机 profiler、heap dump、pps 实测；复杂度结论基于静态量级推算，已标注需要真机证据的项。
- 误报/设计选择单列，避免把刻意取舍当成缺陷。
