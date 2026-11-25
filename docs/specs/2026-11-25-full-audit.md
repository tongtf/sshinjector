# 全量审计报告 (2026-11-25)

> 分支 `feat/multi-exit-routing` @ `db71e2b`，136 个 Kotlin 源文件。
> 审计方式：从 Android 入口 (`SshInjectorApplication` / `MainActivity` / `SshVpnService` / `BootReceiver`)
> 出发，按 8 条互不重叠的条线并行梳理：**入口生命周期 / 上行数据面 / 回程与 TUN / SSH 与隧道 /
> DNS / 多出口路由 / UI 与观测 / 持久化与供给**。只读审计，未改代码。
> 严重度口径：P0 = 核心功能确定不可用；P1 = 可复现的悬挂/泄漏/数据错乱/状态不可恢复；P2 = 局部缺陷、
> 观测缺口、死代码、文档冲突。

---

## 0. 总览

| 严重度 | 数量 | 主题 |
|---|---|---|
| P0 | 1 | 一键配置向导公钥恒被判非法，永远无法完成 |
| P1 | 18 | 建连早期失败状态悬挂、并发 connect 互拆、级联删除出口悬空、两个数据面死循环/竞态、DNS 跨会话投递、孤儿出口、SSH 取消泄漏、D-5 文档冲突等 |
| P2 | 约 30 | 观测缺口、死代码、UI 状态、i18n、Keystore 竞态、文档口径冲突 |

**最需要立刻处理的 5 项**：P0-1（向导）、P1-1（早期失败永久 Connecting）、P1-8（flush 忙循环）、
P1-9（OP_WRITE 竞态）、P1-13（孤儿出口）。

---

## 1. P0

| # | 位置 | 异常状态 | 证据 | 修法 |
|---|---|---|---|---|
| P0-1 | `data/remote/ssh/SshKeyManager.kt:679`、`ServerProvisioningUtils.kt:16-17`、`ServerProvisioner.kt:137`、`ServerWizardViewModel.kt:195` | **一键配置向导恒失败于 `UPLOAD_PUBKEY`「公钥格式非法」**，永远拿不到 `FullSuccess` | `encodePublicKeyToOpenSSH` 的 comment 是 `android-generated@${Date()}`；`Date().toString()` 默认含空格（如 `Wed Nov 25 12:34:56 GMT 2026`），`split(Regex("\\s+"))` 得到 7+ 段，而 `isValidPublicKey` 要求 `parts.size in 2..3`。现有 `SecurityTest` / `ServerProvisionerTest` 只用手写单 token comment 的假 key，**门禁测不出来** | 校验只约束前两个 token，comment 允许任意非换行文本（仍拒 shell 元字符）；或把 comment 改成单 token `android-generated@<millis>`。补一条"真实生成 key → 校验通过"的回归测试 |

---

## 2. P1

### 2.1 入口 / 生命周期 / 状态机

| # | 位置 | 异常状态 | 证据 | 修法 |
|---|---|---|---|---|
| P1-1 | `SshVpnService.kt:478-490`、`VpnController.kt:750,803-811`、`MainViewModel.kt:297-308` | 建连在 `vpnController.connect()` **之前**失败（服务器不存在、establish 返回 null、DataStore 异常）→ controller 的 `vpnState` 永久停在 `Connecting`；`reportHealth` 只改 `verified/failedStep` 不改 status；导出日志末尾也没有最终 `故障归因` 行（`disconnect()` 因 `!isRunning` 早退） | catch 只调 `disconnectInternal()`，其中 `if (vpnController.isVpnRunning())` 为 false 时跳过 `vpnController.disconnect()`；UI collector 对 `status=Connecting` 一直写 "Connecting" | catch 路径无条件发布终态（Failed/Disconnected + `failedStep`），并把 `AppMetrics.summary()` 移到早退判断之前 |
| P1-2 | `SshVpnService.kt:418-432`（`connectIntent` 在 `connectMutex` **外**）、`:571-576`、`VpnController.kt:440-443` | 两个 ACTION_CONNECT（快速双击 / BootReceiver 与用户点击）并发：第二次先关掉第一次的 TUN，再被 "VPN already running" 拒绝 → "连上又断" | `when (connectIntent(...))` 位于 `connectMutex.withLock` 之前；`establishVpnInterface` 会关闭旧 fd | 把 `connectIntent` 判定与 SWITCH/ALREADY_CONNECTED 分支都移入 `connectMutex.withLock` |
| P1-3 | `SshVpnService.kt:516-518,1198` | 切换服务器时若已有重连在途，`autoReconnect` 的 CAS 直接 return：目标 `lastServerId`/`isActive` 已写入但隧道仍连旧服务器，UI 永久转圈 | `switchServer` 先写两个持久化值再调 `autoReconnect`；后者首行 `if (!reconnecting.compareAndSet(false, true)) return`，无返回值 | `autoReconnect` 返回 Boolean；CAS 失败时回滚写入或排队覆盖目标配置 |
| P1-4 | `ServerRepository.kt:160-163`、`Entities.kt:60-80`、`MainViewModel.kt:597-601`、`ServerListViewModel.kt:49-57` | 删除服务器 → FK CASCADE 删掉 `proxy_exits`，但路由快照不刷新、出口不回收；会话内新连接仍派发给"已删除"的出口（池还在跑），UI 里无法停用；`lastServerId` 悬空 → 开机 `Server not found` | 三条删除路径都只调 `deleteServer`；`allExitsFlow` 只喂 UI；`BootReceiver.kt:31-36` 直取 `lastServerId` | 删除统一收敛到 `ServerRepository.deleteServer` `@Transaction`：刷新快照、对被级联出口调 `onExitDisabled`、被删 id == `lastServerId` 时清键/提升新默认 |
| P1-5 | `ServerRepository.kt:93-100`、`ServerEditScreen.kt:107,190` | 编辑页「设为默认」**关不掉**：保存后 UI 显示关闭，DB 仍 `isActive=1` | `isActive = if (setAsDefault) false else existing.isActive`，`incoming.isActive=false` 被忽略，无 deactivate 分支 | 三态（不变/设为/取消），取消时 `deactivateAll()` |
| P1-6 | `SshKeyManager.kt:86-91,118-123,429-446,717-721` | 用户录入新指纹后，所有 `requireBiometric` 生成的 key 被 Keystore 永久失效；连接只报「无法访问私钥」，UI/应用内日志无提示、无重建入口 | 两处生成都 `setInvalidatedByBiometricEnrollment(true)`；无 `KeyPermanentlyInvalidatedException` 捕获 | 显式识别失效原因并返回给 UI，提供"重新生成/重新导入"入口 |
| P1-7 | `ServerRepository.kt:141-145`、`SshInjectorApplication.kt:29-31`、`Daos.kt:20-21` | 启动期明文密码补加密与用户编辑并发 → 整行 UPDATE 吞掉刚保存的 name/host/port/updatedAt | `getAllBlocking()` 全实体 + `update(entity.copy(password=...))`，与 `saveServerEdit` 无事务/互斥 | 改为只更新密码列的 `@Query`，或整段 `@Transaction` 重查后写 |

### 2.2 数据面（上行 / 回程 / TUN / DNS）

| # | 位置 | 异常状态 | 证据 | 修法 |
|---|---|---|---|---|
| P1-8 | `TcpStateMachine.kt:1484-1526` | `flushPendingUpstream` 写返回 0 或抛 `IOException` 时把整段放回队首后**立即下一轮**，无状态检查/退避/取消点 → 占死一条 `Dispatchers.IO` 线程；SYN-ACK 已发但回程 relay 永不启动，连接假活 | 循环唯一正常出口是 `removeFirstOrNull() ?: return`；`written < data.size → addFirst(rest)`；`conn.state == Closed` / `channel.isOpen` / `isActive` 均未检查；全仓仅一处调用，无"下次重刷"触发点（**已人工复核确认**） | 写 0/异常时退出本次 flush，用连接级"待重刷"标志由后续上行或定时协程再试；循环内检查 Closed/channel.isOpen；加退避 |
| P1-9 | `Socks5ProxyServer.kt:592-599` ↔ `:1109-1116` | **丢 OP_WRITE → 连接永久悬挂**：`pendingWrites` 有数据但 OP_WRITE 被清，selector 不再报可写；`wakeup()` 只唤醒 select 不补 interestOps | `handleWrite` 的 `isEmpty()` 判定在锁**外**，清位在 `synchronized(backpressureLock)` 内；`sendReply` 的 `pendingWrites.add` 在锁外、置 OP_WRITE 在锁内（sshIoDispatcher 线程，与 eventLoop 真并发）。交错后队列非空而 OP_WRITE=0（**已人工复核确认**） | 把判空也移进 `backpressureLock`，进锁后再次判空才清位；或让队列增删与 interestOps 决策共用同一把锁 |
| P1-10 | `TcpStateMachine.kt:2212-2218` | RST 关闭包走 `writeBlocking` → TunWriter 队列满时 packetLoop 等待最多 10s，不读 TUN → **所有连接 + DNS 投递 + 窗口 ACK 一起卡住** | 其余四个控制包写点（`:474/:752/:1261/:2076`）都用非阻塞 `write`；`TunSink` KDoc 明确"关闭包用 write"，仅此处例外 | 改 `write(closePacket)`；丢包由 `onControlDropped` 计数 + WARNING + 进快照 |
| P1-11 | `DnsInterceptor.kt:765-825,970-977`、`VpnController.kt:748,757,763` | 断开后 in-flight DNS（socket 5s 超时）仍把响应投入 `pendingResponses`，**跨会话投给新 TUN**；且断线后 `DnsInterceptor.protectSocket` 仍持有旧 lambda | `clearPendingResponses()` 是一次性排空；`dns-bypass` 线程池与 `pendingQueries` 不随会话取消，`executor.shutdown` / `pendingQueries.clear` 全仓无调用；`setProtectFunction(null)` 只清 VpnController 自己的字段 | 会话代际号写入 pending query，入队前校验；disconnect 取消在途并把 `DnsInterceptor` 的 protect 函数置空 |
| P1-12 | `DnsInterceptor.kt:674-689,941-948` | `dnsCache` 跨会话不清；空应答/SERVFAIL 被按 NOERROR 缓存 300s → 域名恢复后仍"解析不到" | `cacheDns` 不看 `rcode`，`records.minOrNull() ?: 300`；`dnsCache.clear()` 全仓无调用 | 会话边界清缓存；仅 `rcode==NOERROR && records.isNotEmpty()` 才缓存 |

### 2.3 SSH / 隧道 / 多出口

| # | 位置 | 异常状态 | 证据 | 修法 |
|---|---|---|---|---|
| P1-13 | `TunnelManager.kt:159-164`（对照 `:213-221`）、`Socks5TunnelPlugin.kt:26,30` | `stopExit()` 不递增 `sessionGeneration` → 出口在 `connect()` 挂起中被禁用时，`plugin.disconnect()` 什么都拆不掉（socksServer 仍 null），随后 `connect()` 完成并建好池+监听，但 entry 已被摘出 → **孤儿出口**：端口 + 3 条 SSH 会话跨会话泄漏，`stopAll()` 永远收不到 | `startExit` 只在挂起点后复查 gen；`stopExit` 只 `exits.remove` + `plugin.disconnect()`；`onExitDisabled` 判 `activeConnections == 0` 时启动中的插件为 0 | `stopExit()` 也 `sessionGeneration++`；`startExit` 每个挂起点后复查 `exits[id] === entry`；SSH 建连支持 cancel 早退 |
| P1-14 | `JschSshClient.kt:674-690,681-690,709-715,799-803` | `connect()` 被取消时新建的 Session/keepAlive 泄漏；`CancellationException` 被 `catch (e: Exception)` 吞成普通失败 → ERROR 进「故障归因」，而 `startExit:146-150` 的取消清理分支永远进不去 | `coroutineScope{async}` 取消时跳过入池循环；`createSession` 内阻塞 `s.connect()` 不响应取消，返回后无条件建独立 `sessionScope` + keepAlive | 局部列表收集所有已建 session，`finally` 对未入池者逐个 disconnect+cancel；单独 rethrow `CancellationException` |
| P1-15 | `VpnController.kt:494-508`、`DnsInterceptor.kt:169-172,215-225,694-704`；设计 §6.5/§8.8 vs dev-guide:510 | **D-5 把出口健康当 DNS 谓词输入**：目标出口 Starting/Degraded 时不提升 → DNS 回真实 IP → DOMAIN_SPLIT 下真实 IP 不进 TUN → 规则彻底静默失效；出口降级/恢复还会让同一域名在假 IP / 真实 IP 间翻转，缓存键随之切换 | 设计明确"谓词只判断文本有规则，可用性由 `resolveExit` 处理；不可用仍走隧道"，实现要求 `Ready && isHealthy`；两份文档口径冲突（**已人工复核确认**） | **需用户裁决**：以设计为准则改回纯文本谓词；以 dev-guide 为准则修订设计并接受缓存抖动 |
| P1-16 | `MultiExitViewModel.kt:136-145`、`MultiExitScreen.kt:29-33,148-155`、`VpnController.kt:494-510` | 会话中途开总开关：UI 立刻显示"已启用"（只有短暂 Snackbar），但出口不拉起；同时 `refresh()` 让 DNS 谓词立刻生效 → 规则当场改变真假 IP；UI 无持久"已启用·未启动"状态 | `setEnabled` 写完 DataStore 即 `refresh()`；`promotesDomain` 闭包每次查询现取 `current()`；`startAdditionalExits` 只在 `connect()` 调用 | 会话内冻结 `enabled` 快照；行内增加持久状态 chip |
| P1-17 | `SshVpnService.kt:212-237`、`VpnController.kt:1849-1879`、`Socks5TunnelPlugin.kt:39-40` | 附加出口**没有独立 `ConnectivityProber`**，只用 `isHealthy`（端口>0 + 池有活口）；池活但 SOCKS/远端实际不通时不会降级，规则持续投过去；验收 S29 的"连续 2 次成功才恢复"退化成布尔值 | 全仓 `ConnectivityProber(` 只有主探活一处 | 15s 节拍内并发探测每个出口（5s 预算），结果喂 `ExitHealthEvaluator` |
| P1-18 | `VpnController.kt:651-671`、设计 §5.4 / I-13 | 附加出口 `awaitAll()` **阻塞 `connect()` 返回**（UI 停在 Connecting 直到全部建完），且无 300ms 错峰 → 多出口同时 SSH 握手易撞 sshd `MaxStartups` | `coroutineScope { exits.map { async }; jobs.awaitAll() }`，无 `delay(300L*index)` | 后台 launch + 错峰；需与用户确认是否接受"出口未就绪即可用"（设计说不该阻塞） |
| P1-19 | `MainViewModel.kt:522-533,544-546`、`DashboardScreen.kt:110-113` | 拒绝/取消 VPN 授权后该服务器行**永久 Connecting**：`connect()` 在 `prepare()` 之前就写 "Connecting"，denied 只清 `vpnPermissionIntent`，不清 `serverConnectionStatus`，且无 vpnState 事件来收尾 | `clearVpnPermissionRequest()` 仅清 intent | 把乐观状态推迟到授权通过之后再写，或 denied 时恢复原值 |

---

## 3. P2（按主题压缩）

### 3.1 观测 / 计数 / 日志

| 位置 | 问题 |
|---|---|
| `TcpStateMachine.kt:669-676,1035-1045` | `failoverEnabled=false` 握手期失败只 RST，缺 `出口不可用 · 已按设置直接断开` WARNING，单连接级判据无法取证 |
| `TcpStateMachine.kt:1007-1023` | 快速重试未检查主出口 `localSocksPort > 0`（违反设计 §9.5 条件 8），会对 `:0` connect 并污染 `快速重试` 计数 |
| `TcpStateMachine.kt:1147-1156` | `socksAuth == null` 归 `FAILED` 而非可重试的 `EXIT_UNAVAILABLE`（AUTH 阶段） |
| `TcpStateMachine.kt:958-987`、`DataPlaneSnapshot` | `exitFailoverTotal` / `exitRetrySuccessTotal` / `uidTimeoutTotal` vs `uidMissTotal` 未实现，验收 §6 阈值不可执行 |
| `Socks5ProxyServer.kt:99-104` | `diagnostics()` 文案"上行/下行累计"只统计活跃连接，短连接关闭后归零，与新增的会话累计口径冲突，排障会误判"出口没干活" |
| `TcpStateMachine.kt:1421-1426` | `writeBlocking` 返回 false 仍 `downstreamBytes += chunkLen`，域名统计虚高且抑制真实"零回程"上报 |
| `TcpStateMachine.kt:539-568` | 客户端 FIN 的 ACK 被控制包队列丢弃后，重传 FIN 全进孤儿桶，无补发 ACK（TunSink KDoc 声称"重传 FIN 同理"但没实现） |
| `JschSshClient.kt:579-585,943-953` | 通道统计计数器是 companion 静态（所有出口共享），文案里的"池 N 会话"却是本实例；5 分钟内只有最先成功的出口打统计，附加出口不可见 |
| `VpnController.kt:1725-1737` | 旁路 DNS 失败不计 `StageCounters.onDnsError()`，分阶段健康把它当空闲窗口 |
| `LogScreen.kt:185-193` + `LogViewModel.kt:106-112` | 按级别筛选后导出的是筛选子集，`故障归因` 行（INFO）被排除，导出证据与结论对不上 |
| `LogScreen.kt:73-78,92-95` | 日志级别 chip 显示英文枚举名，四种语言均无 `log_level_*` 资源（违反 i18n） |
| `SshInjectorApplication.kt:29-38` | 明文密码迁移失败只写 logcat，无应用内日志（无 adb 现场零可见性） |

### 3.2 状态 / 竞态 / 死代码

| 位置 | 问题 |
|---|---|
| `TunnelManager.kt:134-136`、`ExitHealth.kt:19-20`、`TcpStateMachine.kt:1009` | `ExitHealth.Failed` 生产代码永不赋值，死守卫；启动失败出口直接从快照消失 |
| `TunnelManager.kt:134-143` | 一个附加出口启动失败打两条日志，其中 ERROR `隧道插件启动失败 · socks5` 丢出口名并进「故障归因」，造成假故障 |
| `RoutingTableStore.kt:68,80-101` | `refresh()` 持 Mutex 但 `reset()` 不持锁 → 断开与 UI toggle 并发时旧 refresh 可跨会话写入；KDoc 与实现漂移 |
| `MultiExitRulesViewModel.kt:89-96` | 保存时 launch 内用捕获的旧 state 写回，覆盖用户刚敲入的新文本 |
| `RoutingProbe.kt:19-20,102,116` | 测试框用 `0.0.0.0` 占位 IP，但精确规则 `0.0.0.0 -> hk` 会被接受并"命中"，生产永不命中 → UI 说命中、数据面走 default |
| `MultiExitViewModel.kt:29-33` / `MultiExitScreen.kt:148-155` | 出口卡片无状态 chip / 活跃连接 / 流量，Starting/Ready/Degraded 在 UI 完全不可达 |
| `ui/screen/whitelist/InstalledApp.kt:3-15` | WHITELIST 模式下非白名单应用未标灰、无提示，给它们配 `app:` 规则必然静默无效 |
| `ProxyExitRepository.kt:40-43,110-124`、`Daos.kt:152-157`、`Entities.kt:78-79` | `getAll()/getByName()/setPriority()` 与 `createdAt/updatedAt` 全仓无调用（死代码） |
| `MainViewModel.kt:378-401,362,621` | `lastCpuTime/lastCpuTimeRead` 跨线程读改写无同步，32 位 ABI 裸 long 撕裂 / 显示乱值 |
| `ConnectivityAppUidResolver.kt:247,253,263-268` | `DisableWindow.untilMs` 非 volatile，`trip()` 与 `request()` 跨线程 → 暂停窗口异常；`reason` 却是 volatile（漏了一个） |
| `ConnectivityAppUidResolver.kt:76,91-106,132-137` | `pending` 非原子 + 线程池 `DiscardPolicy` 静默丢任务 → `UidRequest` 永不 complete，违反"句柄一定完成"契约 |
| `DashboardScreen.kt:490-507` | 外层 `contains(" MB")` 时内层 `contains(" GB")` 永假 → ≥1GB 内存恒显绿色（死分支） |
| `JschSshClient.kt:642` | 类级 `scope` 初始化后全文件无读取（死字段） |
| `JschSshClient.kt:652`、`DashboardScreen.kt:828-835` | `ConnectionState.EstablishingTunnel` 从未赋值（死状态） |
| `JschSshClient.kt:1018-1021,1092-1097` | `resolveIps` 承诺不外抛异常，但会话 scope 取消时 `CancellationException` 沿调用链传播 → 用户点"解析真实 IP"不走本地回退 |
| `JschSshClient.kt:1777-1781` | `execSingleShot` 关闭后只读一次 8192B，>8KB 输出被截断却返回成功 exitCode |
| `JschSshClient.kt:1661-1667` | 通道/会话重连复用 `pooled.scope` 取消时取消在途 DNS 探测（与上一条同源） |
| `SshVpnService.kt:70,407,577,885,952,1230` | `tunFd` 只写不读（6 处赋值、0 处读取），死状态 |
| `SshVpnService.kt:860-861,940-941,1265` | `poolFailReconnectPending` 在补跑任务取消时残留 true → Failed 观察者跳过清理，`isRunning` 保持 true |
| `SshVpnService.kt:840-862,937-943` | `disconnectInternal` 不取消 `reconnectJob`，断开后 2s 的重连任务会拆掉刚建的新会话 |
| `SshVpnService.kt:334-351,421-425,778-790` | 已连接同服务器再发 ACTION_CONNECT，通知被占位配置覆盖且无事件恢复 |
| `VpnController.kt:789-795,915-920,957-962` | 三个 close 放同一 try，首个抛异常会跳过 `closeTunWriter()` 和输出流关闭 → 单写者线程/fd 跨会话残留 |
| `VpnController.kt:635-639,661-665` | `connect()` 的 `catch (e: Exception)` 吞 `CancellationException`，取消被当连接失败并打 ERROR |
| `CredentialCrypto.kt:27-45`、`AesGcmCipher.kt:27-40` | 首次取 key 的 check-then-generate 无同步 → 二次生成替换 Keystore 密钥，已加密密码全部解不开且 `decrypt` 静默返回 null |
| `ServerRepository.kt:62-71,246-265` | `saveServer` 生产死代码（仅 androidTest 用），且会用默认值覆盖 `keyPassphrase/dnsMode/...` 与 TOFU 指纹 |
| `ServerWizardViewModel.kt:121-124,183-189` | 向导重试每次生成新 keystore alias，失败不清理 → 累积无人引用密钥；同毫秒重试撞 alias |
| `AdBlockSettingsViewModel.kt:116-119`、`LocaleManager.kt:25-43` | `setRemoteUrl()` / `applyLanguage()` 全仓无调用（死代码） |
| `Entities.kt:19,25,26,28` | `keyPassphrase/dnsMode/remoteDnsServer/allowedPackages` 只有默认值写入，生产无读取（兼容列需文档化） |

### 3.3 文档互相冲突（需用户裁决，不是代码 bug）

| 议题 | 口径 A | 口径 B | 实现 |
|---|---|---|---|
| D-5 谓词是否要求出口 Ready | 设计 §6.5 / §8.8：只判断文本，可用性交给 `resolveExit`，不可用仍走隧道 | dev-guide:510：要求 `Ready && isHealthy` | 取 B（见 P1-15） |
| `ExitHealth.Failed` | 设计 §9.1：start fail → Failed | dev-guide:489：不做 Failed | 实现不做 |
| 附加出口上限 | 设计 D-7：2（总 3 出口/9 会话） | dev-guide/实现：4（总 5 出口/15 会话） | 4 |
| 附加出口启动是否阻塞 connect | 设计 §5.4 / I-13：不阻塞 + 300ms 错峰 | dev-guide/实现：`awaitAll` 阻塞 | 阻塞、无错峰 |
| 验收 §6 资源数字 | 按 3 出口（9 会话）写 | 实现 5 出口（15 会话） | 需同步 |

---

## 4. 已核对通过的关键不变量（抽样）

- **入口链**：Application → Activity/Service → connectMutex 串行化 → establish → controller.connect → DNS/路由/TunWriter → Connected；`forceDisconnect` 用 tryLock 配对，未发现 Mutex 嵌套死锁。
- **bypass 先行**：`forwardSynToTunnel` 先判 bypass 再 `selectExit`；无 app 规则时不付 50ms uid 等待。
- **SYN-ACK 双字段**：`synAckSeq` 组包即发布用于重传复用 ISN，`synAckWritten` 写 TUN 后发布用于回程闸门；4 个写点都紧跟置位。
- **pendingReturn 三纪律**：锁内用 `synAckWritten` 判据、flush 块 `fromFlush=true` 直写、队列满显式计数+告警。
- **关闭语义**：clean EOF → FIN+ACK，其余 RST；EOF 回调 remove-and-invoke 恰好一次；`closeTcpConnection` identity 删 map 且幂等。
- **假 IP 生命周期**：无符号 clamp、引用计数配对、60s 宽限期摘除；反向表按值删。
- **白名单空名单退化**：`dnsTransportFor` 统一决策；上游 DNS 排除 VPN 网络，仅剩 TUN_IP 时零 socket。
- **JSch**：32KB 管道修复（1MB buffer）、三态探活 UNAVAILABLE 不置死、候选预算双层、秒拒归 INFO、activeChannels 配对、exitLabel 日志分流。
- **Room**：2→3→4→5 迁移与 `5.json` 逐字一致；`ProxyExitSchemaTest` 兜底（androidTest 不跑 CI）。
- **域名统计**：单写点、锁内读写、trim 只走 100ms 巡检、P1 三键与手写规则分键、撤销=回写应用前快照。
- **日志分级**：默认 INFO、失败显式定级、动态文案静态 throttleKey、AppMetrics 只计 WARNING/ERROR 且汇总行自身 INFO。

---

## 附录 A · `ExitHealth.Failed` 建议方案（2026-11-25 用户要求给出建议）

**推荐：方案 A —— 轻量 Failed（登记但不重试）**

| 步骤 | 改动 | 理由 |
|---|---|---|
| 1 | `TunnelManager.startExit` 失败时**不再 `exits.remove(id, entry)`**，改为 `entry.health = ExitHealth.Failed` + `entry.lastError = message`，保留条目 | 失败出口不再"凭空消失"，快照能看出"配了但没起来"及其原因 —— 这是"规则配了没反应"最难排的形态 |
| 2 | `selectExit` 无需改：现有 `health != Ready → unusable → 回 default` 天然跳过 Failed | 保持单一判据，不给失败出口任何新连接 |
| 3 | `ExitHealthEvaluator` 无需改：现有"其它状态不动"分支让 Failed 不被探活复活 | 符合设计"本次会话不再自动恢复" |
| 4 | `exitDiagnostics` 补 `出口 jz: Failed · <lastError>`（现有 `失败 N · 降级 N` 保留） | 无 adb 现场靠日志判读，必须可定位 |
| 5 | UI 出口卡片状态 chip 顺带显示 Failed（审计 P2-10） | 用户不用导出日志也能看到 |
| 6 | 测试：`TunnelManagerExitTest` 加"失败保留 Failed + 不被选中 + stopAll 可回收"；`ExitHealthEvaluatorTest` 加"Failed 不被探活复活" | 锁住终态语义 |

**不选方案 B（删掉 Failed 枚举）**：用户无法区分"配了但没启动"与"启动失败"，出口从快照消失后
排障只能靠猜。
**不选方案 C（Failed 自动重试）**：与设计"本次会话不再自动恢复"冲突，且会和 300ms 错峰、
网络切换/池失败重连节拍打架，把坏服务器反复握手。

下次 `connect()` 重建隧道管理器时条目自然清空 —— 恢复路径唯一且可预期。

## 5. 建议处理顺序（待用户确认后实施）

1. **P0-1** 向导公钥校验（含回归测试）—— 一行注释改单 token 即可，先解掉"功能不可用"。
2. **P1-1 / P1-19** 早期失败与授权拒绝的终态发布 —— 用户可见的"永久转圈"。
3. **P1-8 / P1-9** 两个数据面悬挂 —— 用有界等待 + 锁内判空，补并发单测。
4. **P1-13 / P1-14** 孤儿出口与 SSH 取消泄漏 —— 补代际检查与 finally 清理。
5. **P1-4 / P1-5 / P1-6 / P1-7** 持久化与 Keystore —— 收敛删除入口、密码列只更新、失效恢复。
6. **裁决 §3.3 五处文档冲突** 后再动 D-5 / 出口上限 / 探活节拍。
7. P2 批量清理（死代码 + 计数 + i18n），每批单独提交并跑全部门禁。

> 本次审计**没有修改任何代码**。所有 P1/P2 均为可定位、可复现的条目，建议按上述顺序分批改；
> 涉及 P0 与两个数据面悬挂的修复建议加回归测试后再合入。
