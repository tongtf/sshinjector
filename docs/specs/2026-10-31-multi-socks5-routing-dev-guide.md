# 多出口 SOCKS5 路由 — 开发指南 (施工手册)

> 配套: 设计文档 `2026-10-27-multi-socks5-routing-design.md` (语义与取舍)、
> 验收标准 `2026-10-31-multi-socks5-routing-acceptance.md` (什么算做完)。
> **本文只写"怎么改"**: 顺序、文件、骨架、每步自检。语义有疑问回设计文档, 不要在这里改语义。
>
> 分支: `feat/multi-exit-routing`。行号是当前分支锚点, 改动后自行 `grep` 复核。

---

## 0. 铁律 (动手前先读)

1. **P0 是"零行为变化"的纯结构改造**。P0 的每一个 commit 都必须满足:
   `multi_exit_enabled` 不存在也不影响 → 运行时对象图与今天一致 → 数据面快照逐字一致。
2. **不改数据面** (TUN / 组包 / 窗口闸门 / 假 IP 生命周期 / 回程缓存)。要改 = 设计文档先改。
3. **`getActiveOrFallback()` 改可空后, 三个调用点必须同一 commit 修完** (否则编译不过, 这是好事):
   `SshVpnService:216` / `VpnController:1333` / `TcpStateMachine:569`。
4. **`@Inject` 构造不能加 `String`/`Int` 之类无绑定参数** (Dagger 会去找绑定)。出口身份放 body 属性或显式工厂参数。
5. **`TcpConnection` 新增字段一律 body 属性** (不进构造参数) —— 否则破坏 data class 的 `equals/copy`,
   `forwardThroughLocalSocks` 的 close-old→create-fresh 分支会踩到。
6. **`stopAll()` 顺序**: 先 `sessionGeneration++` → cancel 在途 start Job → 再 stop 实例。反了就是跨会话泄漏。
7. 每个 commit 前跑门禁 (见 §1), **不许"先攒一堆再编译"**。

---

## 1. 环境与门禁

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

./gradlew assembleDebug            # 每步至少跑这个 (最快暴露类型/DI 错误)
./gradlew testDebugUnitTest        # 有单测改动时
./gradlew ktlintCheck detekt lint  # 提交前 (分开跑, 合并跑会 OOM)
```

> detekt + lint 一起跑在 CI 上会 OOM, 本地也一样 —— **分开跑**。

---

## 2. 分支与提交约定

- 分支: `feat/multi-exit-routing` (已创建)。
- Conventional Commits, 一个 P0 子步骤一个 commit:

| 步骤 | commit message |
|---|---|
| P0-1 | `fix(tunnel): Socks5 配置透传 serverId 与主机指纹 (C-1)` |
| P0-2 | `feat(ssh): JschSshClient 增加出口标签, 池失败日志按出口分流 (C-5)` |
| P0-3 | `refactor(tunnel): 插件改为工厂创建, 去掉 @Singleton (C-4 前置)` |
| P0-4 | `refactor(tunnel): TunnelManager 按出口分键, fallback 改可空` |
| P0-5 | `fix(tcp): 回程回调插件改为连接级字段 (C-2)` |
| P0-6 | `fix(probe): 探活使用插件实际监听端口 (C-3)` |
| P0-7 | `feat(vpn): 全出口共享连接预算 (默认 512, 行为等价)` |
| P1 | `feat(routing): 规则解析/匹配 + Room v5 + 规则编辑器` (可拆多个) |
| P2 | `feat(routing): 多出口运行时接线 (附加出口/应用分流/故障隔离)` |

- **C-1 与 C-5 单独提交 + 单独写发布说明** (它们改变可见行为, 见设计附录 C)。

---

## 3. P0 施工步骤

### P0-1 `TunnelConfig` 透传 `serverId` / `hostKeyFingerprint` (修 C-1)

**文件**: `domain/vpn/tunnel/TunnelConfig.kt`

```kotlin
data class Socks5(
    // ... 现有字段不变 ...
    val serverId: Long = 0,                  // 0 = 临时配置 (向导/测试), 跳过指纹落库
    val hostKeyFingerprint: String? = null,  // 既有 DB 指纹 → 运行时强校验
)

fun forSocks5(server: ServerConfig, password: String? = null): Socks5 =
    Socks5(
        // ... 现有赋值不变 ...
        serverId = server.id,
        hostKeyFingerprint = server.hostKeyFingerprint,
    )
```

**文件**: `data/remote/tunnel/Socks5TunnelPlugin.kt` `connect()`

```kotlin
val sshConfig = ServerConfig(
    id = c.serverId,                          // 新增
    hostKeyFingerprint = c.hostKeyFingerprint, // 新增
    name = "SOCKS5",
    // ... 其余不变 ...
)
```

**自检**

- [ ] `assembleDebug` 通过
- [ ] 单测 `TunnelConfigSocks5Test` (新增): `id==0` 时不落库, 非 0 时透传; 指纹透传
- [ ] 手工确认 `JschSshClient.connect` 读的是 `config.hostKeyFingerprint` (`:712`), 不是别的字段

**注意**: 不要把 `exitId` 塞进 `TunnelConfig` (设计 §8.1) —— 出口身份只在插件实例上。

---

### P0-2 `JschSshClient.exitLabel` + 两处日志分流 (修 C-5)

**文件**: `data/remote/ssh/JschSshClient.kt`

```kotlin
class JschSshClient @Inject constructor(...) {
    /** 出口标签: "" = 主出口 (日志与今天逐字一致); 非空 = 附加出口 (WARNING, 不声称整条会话断开) */
    @Volatile var exitLabel: String = ""
```

**改动点 1**: `checkPoolHealth()` (`:1297` 附近)

```kotlin
if (allDead) {
    if (exitLabel.isEmpty()) {
        VpnController.appLogThrottled(
            "SSH 会话全部丢失 · 连接即将断开并触发重连",
            level = LogLevel.ERROR,
            throttleKey = "SSH 会话全部丢失",
        )
    } else {
        VpnController.appLogThrottled(
            "出口 SSH 会话全部丢失 · $exitLabel — 新连接改走 default",
            level = LogLevel.WARNING,
            throttleKey = "出口降级",
        )
    }
}
```

**改动点 2**: `createDirectChannel()` 无可用会话 (`:1426` 附近) 同理
(`出口无可用会话 · $exitLabel — 新连接改走 default`, WARNING, key `出口降级`)。

**自检**

- [ ] `exitLabel == ""` 时**逐字**仍是原文案 (复制粘贴比对, 别顺手改标点)
- [ ] `MultiExitIsolationTest` (P2 才写) 的判据已在设计里: 附加出口日志不含"连接即将断开"
- [ ] 全仓 `grep "连接即将断开"` 只有主出口分支

---

### P0-3 插件去单例 + 工厂 (为 C-4 / 多实例铺路)

**新增** `data/remote/tunnel/TunnelPluginFactory.kt`

```kotlin
interface TunnelPluginFactory {
    val protocolId: String
    fun create(exitId: String, label: String): TunnelPlugin
}

@Singleton
class Socks5TunnelPluginFactory @Inject constructor(
    private val primaryClient: JschSshClient,      // 主出口复用 Hilt 单例
    private val keyManager: SshKeyManager,
    private val knownHosts: KnownHostsManager,
    private val serverDao: ServerDao,
    private val dnsInterceptor: DnsInterceptor,
    private val sshIoDispatcher: SshIoDispatcher,
    private val budgetHolder: ConnectionBudgetHolder,   // P0-7 引入
) : TunnelPluginFactory {
    override val protocolId = "socks5"
    override fun create(exitId: String, label: String): TunnelPlugin =
        Socks5TunnelPlugin(
            client = if (exitId == ExitId.DEFAULT) primaryClient
                     else JschSshClient(keyManager, knownHosts, serverDao).also { it.exitLabel = exitId },
            dnsInterceptor = dnsInterceptor,
            sshIoDispatcher = sshIoDispatcher,
            budgetHolder = budgetHolder,
            exitId = exitId,
            label = label,
        )
}
```

**文件**: `data/remote/tunnel/Socks5TunnelPlugin.kt`

- 去掉 `@Singleton`
- ctor: `client` 参数名 (原 `jschClient`), 新增 `budgetHolder`, `exitId: String`, `label: String`
- 新增 `override val exitId` / `override val activeConnections`
- `connect()` 里 `Socks5ProxyServer(client, dnsInterceptor, sshIoDispatcher, exitId = exitId, budget = budgetHolder.current)` 

**文件**: `domain/vpn/tunnel/TunnelPlugin.kt`

```kotlin
val exitId: String get() = ""
fun healthState(): ExitHealth = ExitHealth.Ready          // P1 起按出口维护
val activeConnections: Int get() = 0
```

**文件**: `di/TunnelModule.kt`

```kotlin
@Binds @IntoMap @StringKey("socks5")
abstract fun bindSocks5Factory(impl: Socks5TunnelPluginFactory): TunnelPluginFactory
```

**自检**

- [ ] `grep -r "Socks5TunnelPlugin" app/src/main` 只剩工厂 + 模块 + 测试
- [ ] 编译期就能发现漏改的注入点 (这是本次重构的收益)

---

### P0-4 `TunnelManager` 按出口分键 + fallback 可空

**文件**: `domain/vpn/tunnel/TunnelManager.kt`

```kotlin
@Singleton
class TunnelManager @Inject constructor(
    private val factories: Map<String, @JvmSuppressWildcards TunnelPluginFactory>,
    private val routingTableStore: RoutingTableStore,   // P1 才注入; P0 可先传空实现
) {
    data class ActiveExit(
        val id: String, val label: String, val serverHost: String,
        val plugin: TunnelPlugin, val startedAt: Long,
        @Volatile var health: ExitHealth, @Volatile var degradedSince: Long,
        @Volatile var lastProbeOkAt: Long,
        @Volatile var connTotal: Long = 0, @Volatile var failTotal: Long = 0,
        @Volatile var degradeTotal: Long = 0, @Volatile var lastError: String = "",
    )

    private val exits = ConcurrentHashMap<String, ActiveExit>()
    private val startJobs = ConcurrentHashMap<String, Job>()
    private val exitMutex = Mutex()
    private val perExitMutex = ConcurrentHashMap<String, Mutex>()
    @Volatile private var sessionGeneration = 0L

    suspend fun startExit(id: String, protocolId: String, label: String, serverHost: String,
                          config: TunnelConfig): Result<Unit>
    fun exit(id: String): ActiveExit?
    fun resolveExit(target: RouteTarget): ActiveExit      // P0: 恒返回 DEFAULT
    suspend fun stopExit(id: String)
    fun exitsSnapshot(): List<ActiveExit>
    fun exitDiagnostics(): String = ""                     // P0: 恒空串
    suspend fun stopAll()
    fun getActiveOrFallback(): TunnelPlugin?               // 改可空
}
```

**三个调用点 (同一 commit)**

| 文件 | 改法 |
|---|---|
| `SshVpnService:216` | `val plugin = tunnelManager.getActiveOrFallback() ?: return` (跳过本轮探活, 打 DEBUG) |
| `VpnController:1333` | `tunnelManager.getActiveOrFallback()?.tunnelDiagnostics() ?: ""` |
| `TcpStateMachine:569` | `val plugin = tunnelManager.getActiveOrFallback() ?: run { closeTcpConnection(...); return@launch }` (保持现有"无本地端口"语义) |

**自检**

- [ ] 单出口跑一遍 `S0/S1/S2` 场景 (手工), 快照文本无变化
- [ ] `stopAll()` 有 `sessionGeneration++`, 且 `startExit` 的每个 `suspend` 之后都复查 generation

---

### P0-5 `conn.tunnelPlugin` 替换机器级字段 (修 C-2)

**文件**: `domain/vpn/TcpStateMachine.kt`

- 删除 `:177` `@Volatile private var tunCallbackPlugin: TunnelPlugin? = null`
- `TcpConnection` body 增加 `var tunnelPlugin: TunnelPlugin? = null`
- `:887` `tunCallbackPlugin = plugin` → `conn.tunnelPlugin = plugin`
- `:1974-1975` → `conn.tunnelPlugin?.removeTunCallback(conn.socksLocalPort)` / `removeTargetEofCallback`
  (之后 `conn.tunnelPlugin = null`)

**自检**

- [ ] `grep tunCallbackPlugin` 为 0
- [ ] 单测 `MultiExitCallbackTest` (P2) 的 C-2 回归点在 P0 就能先写: 两条连接 A/B 并发注册/关闭, 只摘自己

---

### P0-6 探活端口用 `plugin.localSocksPort` (修 C-3)

**文件**: `vpn/SshVpnService.kt` `runHealthProbe` (`:213-218`)

```kotlin
val plugin = tunnelManager.exit(ExitId.DEFAULT)?.plugin
val socksPort = plugin?.localSocksPort?.takeIf { it > 0 } ?: config.socksPort
val prober = ConnectivityProber(socksPort = socksPort, socksAuth = plugin?.socksAuth, endpointUrl = endpoint)
```

**自检**

- [ ] 单出口: 探活仍成功 (端口数值相同)
- [ ] `socksAuth == null` 时不要 NPE: `ConnectivityProber` 自己会返回 `PROXY` 失败, 记 WARNING 即可

---

### P0-7 全出口共享连接预算 (默认 512, 行为等价)

**新增** `domain/vpn/ConnectionBudget.kt`

```kotlin
class ConnectionBudget(private val total: Int = 512) {
    private val used = AtomicInteger(0)
    fun tryAcquire(): Boolean {
        while (true) {
            val cur = used.get()
            if (cur >= total) return false
            if (used.compareAndSet(cur, cur + 1)) return true
        }
    }
    fun release() { used.updateAndGet { if (it > 0) it - 1 else 0 } }
    fun inUse(): Int = used.get()
}
```

**新增** `domain/vpn/ConnectionBudgetHolder.kt`

```kotlin
@Singleton
class ConnectionBudgetHolder @Inject constructor() {
    @Volatile var current: ConnectionBudget? = null
}
```

**文件**: `domain/vpn/Socks5ProxyServer.kt`

- ctor 增加 `private val exitId: String = ""`, `private val budget: ConnectionBudget? = null`
- `diagnostics()` 有 `exitId` 时前缀 `[$exitId] `
- `handleAccept` (`:252-307`): **构造完 `Socks5Connection` 之后、`start()` 之前** `budget?.tryAcquire()`;
  失败 → `sendErrorReplyAndClose(0x01)` + `budgetRejectTotal++`
- `onClosed` lambda: `budget?.release()` (恰好一次; `close()` 的 `Closed` 早退已保证)
- 新增 `val activeConnections: Int get() = connections.size` 与 `totalConnections: AtomicLong`

**自检**

- [ ] 单测 `ConnectionBudgetTest` (新增, 5 例): 3 出口共享; 拒绝不泄漏; close 恰好 release 一次;
      构造期异常不泄漏; acquire 前被关闭仍 release
- [ ] `MultiExitSnapshotTest` 基线: `budget == null` 时快照与今天逐字一致

---

## 4. P1 施工步骤 (数据与规则, 数据面零改动)

1. **`AppIdentity`** (纯查表): 注入 `(Int) -> String?`, 命中才缓存, **不写负缓存**, `clear()`。
2. **规则层**: `ExitId` / `ExitHealth` / `ExitRule` / `RoutingTable` / `RoutingTableStore`。
   - 解析顺序: `app:` → 域名 → CIDR/IP → `default`
   - 未知出口: **保留 + `unresolved=true`**, 匹配时 `continue`
   - `hasDomainRule(domain)` **忽略端口、忽略 app 规则**
3. **Room v5**: `ProxyExitEntity` + `ProxyExitDao` + `MIGRATION_4_5` (SQL 见设计 §7.1)。
4. **DataStore**: `multi_exit_enabled` (false) / `multi_exit_rules` ("") / `multi_exit_failover_enabled` (true)。
5. **UI**: 入口行 (模式门禁) → 列表页 → 规则编辑器 (实时校验 + 测试框) → 应用选择器
   (复用 `InstalledAppsSource`, 白名单模式下非白名单应用标灰)。
6. **快照**: `DataPlaneSnapshot.exitDiagnostics = ""` + `render()` 非空才追加。

**P1 完成判据**: 能配置、能测试框验证、但**不生效** (resolveExit 恒 default) —— 这是刻意的。

---

## 5. P2 施工步骤 (运行时接线)

按依赖顺序, 每个子步都可单独编译:

1. **`AppUidResolver` 请求句柄** (`request(...): UidRequest`)
   - 线程 1→2~4; 队列满**立即** `complete(NO_UID)`
   - 所有出口路径都 `complete` (成功/`-1`/异常/拒绝/超限/窗口暂停)
   - `createTcpConnection` 存句柄到 `conn.uidRequest`; 统计侧 `onComplete { conn.appUid = it }`
2. **`forwardSynToTunnel` 接线** (顺序不能变):
   `domain` → `bypass`(true 则直接返回, **不等 uid**) → `uidRequest.await(50)` → `pkg` → `RouteTarget` → `resolveExit` → `conn.exitId`
3. **`ForwardOutcome`** + `releaseHandshakeResources` + 一次性重试 (8 条条件, 见设计 §9.5)
4. **附加出口生命周期**: `launchAdditionalExits(gen)` 错峰 300ms; `stopExit`; 并发探活
5. **`DnsInterceptor`**: `shouldTunnel(mode, inList, hasExitRule)` 单函数 + `exitRulePredicate`
6. **观测面**: 快照 `出口`/`应用` 行、日志定级、计数器

**P2 完成判据**: 验收文档 §5 的 S17–S26 全绿。

---

## 6. 常见坑 (踩过的, 别重踩)

| 坑 | 现象 | 正确做法 |
|---|---|---|
| 把 uid 等待放在 bypass 之前 | 分流模式下每条直连 +50ms, "网页整体变慢" | bypass 先行 (§5.2) |
| `ActiveExit` 里另记 `activeConns` | 与 `connections.size` 不同步 → 软上限永不触发/永久触发 | 单一来源 `plugin.activeConnections` |
| 软上限也约束 default | 等于把 512 悄悄降到 128 | 只约束非 default; default 走 `isSaturated()` |
| `startExit` 之后才查 generation | 断开后出口"复活", 端口/会话泄漏 | 每个 suspend 后复查 |
| 重试按 `rep` 码判 | NXDOMAIN 每次多一次 SSH 建连 | 只按**阶段** (CONNECT 之前) |
| 未知出口规则加载时丢弃 | 改名/迁移时序不一致 → 用户配置永久丢失 | 保留 + unresolved |
| 附加出口打全局 ERROR | `故障归因` 多假故障, S21 失败 | `exitLabel` 分流 |
| `TcpConnection` 新字段进构造参数 | data class equals/copy 语义坏 → 连接错配 | body 属性 |
| `getActiveOrFallback` 改可空漏调用点 | 编译能过 (若用了 `!!`) 但运行时 NPE | 三处同 commit 判空 |
| 在 packetLoop 上 await uid | 全连接停顿 | 只在 `forwardSynToTunnel` 协程 |

---

## 7. 回滚手册

| 层级 | 动作 | 影响 |
|---|---|---|
| 关功能 | `multi_exit_enabled=false` | 不启附加出口、不装载规则、快照不加节、resolveExit 恒 default |
| 只关应用分流 | 规则里删掉所有 `app:` 行 | 跳过 uid 等待 (句柄仍为统计发起) |
| 回滚 P2 | `git revert` P2 的 commit | 回到"可配置但不生效", 数据面不变 |
| 回滚 P0 | 按 commit 逆序 revert (C-1/C-5 需单独说明) | 回到单出口; 注意 C-1 会重新打开指纹失效 |
| 数据降级 | Room v5 → v4 **不可逆** | 发布说明必须写"降级需清除应用数据" |

---

## 8. 进度追踪

### P0
- [x] P0-1 TunnelConfig 透传 (C-1) — `9633b7b`
- [x] P0-2 exitLabel 日志分流 (C-5) — `c54ff77`
- [x] P0-3 工厂 + 去单例 — `2a61c2f`
- [x] P0-4 TunnelManager 分键 + 可空 fallback — `d5ef4a9`
- [x] P0-5 conn.tunnelPlugin (C-2) — `08079c8`
- [x] P0-6 探活端口 (C-3) — `32d81eb`
- [x] P0-7 ConnectionBudget — `7a7db56`

#### P0 实施结果与偏差 (2026-11-02)

| 项 | 实际落地 | 与文档的差异 / 原因 |
|---|---|---|
| P0-3 | `TunnelPluginFactory` + `Socks5TunnelPluginFactory` 落在 `data/remote/tunnel/`;`Socks5TunnelPlugin` 去 `@Singleton`, ctor 增加 `exitId`/`label`/`budgetHolder` | `TunnelManager` 在 domain 层直接引用 data 层工厂接口 —— 与既有 `VpnController → DomainListManager` 同向, 单模块内可接受 |
| P0-3 | `getActiveOrFallback()` 改为**可空** | 插件不再有"DI 里那个未连接实例"可兜底。三个调用点: 探活 (跳过本轮 + DEBUG)、快照 (`?: ""`)、转发 (记 `隧道插件异常` + 关连接) |
| P0-4 | `startExit(id, protocolId, label, serverHost, config)` + `stopExit` + `exit(id)` + `exitsSnapshot()` + `stopAll()` | **不持锁跨 `connect()`**: 阻塞式建池可达数秒, 持锁会让 `forceReset()` 的 `stopAll()` 卡在后面。串行化由 `VpnController.connectMutex` 保证, 绕过它的 `forceReset` 由 `sessionGeneration` 兜底 (启动完成时代际已变 → 自我 `disconnect` 且不登记) |
| P0-4 | `ActiveExit` 已带 `health/degradedSince/lastProbeOkAt/connTotal/failTotal/degradeTotal/lastError` | 为 P2 探活/快照预留;**活跃连接数不在这里记账** (单一真相源 = `plugin.activeConnections`) |
| P0-7 | `ConnectionBudget` + `ConnectionBudgetHolder`;`Socks5ProxyServer` ctor 增加 `budget`;`handleAccept` 在**构造后、注册前** acquire, `onClosed` 用 `AtomicBoolean` 保证恰好 release 一次 | 预算拒绝回 `0x01` (新增 `Socks5Connection.rejectAndClose`);注册失败时走 `connection.close()` 归还 permit —— 否则连接表与预算双泄漏。`MAX_CONNECTIONS` 改为引用 `ConnectionBudget.DEFAULT_LIMIT` (同为 512) |
| 全局 | 新增单测: `ExitIdTest`(5) / `TunnelManagerExitTest`(4) / `MultiExitCallbackTest`(1) / `ConnectionBudgetTest`(7) | `MultiExitCallbackTest` 在旧实现 (机器级字段) 下**必然失败**: 关连接 1 会摘掉出口 B 的回调 |

### P1
- [x] AppIdentity — `19a6d8a`
- [x] 规则解析/匹配 + 单测 — `96b6ba7`
- [x] Room v5 + 迁移 — `19a6d8a`
- [x] DataStore 键 — `19a6d8a`
- [x] UI (入口/列表/编辑器/应用选择器) — `ddf731a`
- [x] 快照占位 — `19a6d8a`

#### P1 实施结果与偏差 (2026-11-03)

| 项 | 实际落地 | 与文档的差异 / 原因 |
|---|---|---|
| 规则层 | `domain/vpn/routing/`: `ExitRule` / `ExitRuleParser` / `RoutingTable` / `RoutingSnapshot`+`RoutingTableStore` / `RoutingProbe` | 解析器按形态拆成 `parseAppTarget`/`parseBracketedTarget`/`parseCidrTarget`/`parseHostTarget` 等子函数 —— detekt `ReturnCount` 上限 5, 单个 `parseTarget` 有 17 个 `return` |
| IPv6 语法 | 同时支持 `[2001:db8::]/32` (设计 §6.2 示例) 与 `2001:db8::/32` | 附录 A 的 EBNF 只写了前者之外的形式; 设计示例是用户最先抄的写法, 不支持就是"照文档配了没反应" |
| IPv4 + 端口 | 支持 `1.2.3.4:443` | EBNF 里 IPv4 不带端口; 但用户一定会写。若按域名解析会得到永不命中的规则 (静默失效), 故按 IP + 端口处理 |
| 规则表 | `RoutingTable(rules, issues, knownExits)`; 未知出口**保留**并跳过, `withKnownExits` 重建 | 出口在 Room、规则在 DataStore, 没有事务; 改名/导入时序不一致时删行 = 永久丢配置 |
| Room | `proxy_exits` + `ProxyExitDao` + `MIGRATION_4_5`, schema 导出 `5.json` | **迁移 DDL 不含 `DEFAULT`** (设计 §7.1 示例带 DEFAULT 是笔误): Room 用 `TableInfo` 比对, 多一个默认值就 `Migration didn't properly handle`。`ProxyExitSchemaTest` 逐字比对 `5.json` 兜底 (androidTest 的 MigrationTestHelper 不跑 CI) |
| DataStore | `multi_exit_enabled`(false) / `multi_exit_rules`("") / `multi_exit_failover_enabled`(true) | 关闭时 `RoutingSnapshot.build` **不解析规则文本** —— 关闭态不产生解析成本与解析告警 |
| AppIdentity | `@Singleton class AppIdentity(lookup: (Int) -> String?)` + `AppModule` 提供 `fromPackageManager` | 不用 `@Inject constructor(context)` + 默认参数: Dagger 不认默认值, `(Int) -> String?` 会变成未绑定依赖 |
| UI | `ui/screen/routing/`: 列表页 (开关/出口/引用计数/删除提示) + 规则编辑器 (实时校验/测试框/应用选择器); 设置 → 网络 入口 | 应用列表抽成 `InstalledAppsSource` 单例 (白名单页也改用它) —— 两份 5 分钟缓存会让两个页面看到不同快照 |
| 快照 | `DataPlaneSnapshot.exitDiagnostics = ""`, 仅在非空时追加 | 关闭多出口时快照与改造前逐字节一致 |
| 新增单测 | `RoutingTableTest`(22) / `RoutingSnapshotTest`(4) / `RoutingProbeTest`(9) / `AppIdentityTest`(5) / `ProxyExitRepositoryTest`(6) / `ProxyExitSchemaTest`(2) | 解析器与匹配的关键不变量都锁在纯 JVM 测试里 (无 Robolectric) |

### P2
- [x] AppUidResolver 句柄 — `7c1234d`
- [x] forwardSynToTunnel 接线 (bypass 先行) — `7c1234d`
- [x] ForwardOutcome + 重试 — `3078ae7`
- [x] 附加出口生命周期 + 探活/降级 — `7c1234d` / `3baebc5` / `58a4c88`
- [x] DNS 谓词 (D-5 隧道提升) — `3078ae7`
- [x] 观测面 (每出口 连接数 + 健康 + 未解析规则 + 应用分流计数) — `7c1234d` / `486e794` / `58a4c88`
- [ ] S17–S30 真机 (**未做**, 本机无 adb; 决策: 真机通过后才合并 main)

#### P2 实施结果与偏差 (2026-11-03)

**已接线 (功能可用, 关闭时零行为变化)**

| 项 | 实际落地 | 说明 |
|---|---|---|
| uid 句柄 | `AppUidResolver.request(...): UidRequest` (`await(50)` + `onComplete`), `lookup` 变兼容层 | 一次查询两个消费者: 路由等 50ms, 域名统计用完成回调。**句柄一定完成** (无能力/拒绝/队列满/超时都给 `NO_UID`), 这是 `await` 有界的前提 |
| 路由接线 | `TcpStateMachine.selectExit(conn)`: 关闭/空表 **立刻 null** → 主出口; 否则 uid → 包名 → 域名 → `RoutingTable.select` | 位置在 **bypass 判定之后** (直连不为 uid 等 50ms), 在 SYN-ACK 之前完成 (客户端只看到 SYN 重传) |
| 出口可用性 | `exitId != default` 时必须 `tunnelManager.exit(id)` 存在、`health == Ready`、`localSocksPort > 0` | 任一不满足 → **回主出口** (失败不阻断连接); 这是 P2 阶段的故障回退基线 |
| 生命周期 | `VpnController.connect`: `routingTableStore.refresh()` → `startAdditionalExits(snapshot)` | 附加出口 `TunnelConfig.forSocks5(server).copy(socksPort = 0)` —— **必须 0 (ephemeral)**, 否则第二个出口与主出口抢 1080 必 bind 失败 |
| 快照 | `exitDiagnostics()`: `主出口(host): N 连 · ↑1.2MB ↓8.4MB · Ready · 失败 2 · 降级 1 · 出口 hk(host): M 连 · ↑… ↓… · Degraded · 未解析规则 N` | `enabled=false` 返回空串 → `render()` 不追加 (关闭态逐字节不变), 单测 `DataPlaneSnapshotTest` 锁住; `bytesText` 固定 `Locale.ROOT` |

**已补 (review 后, 2026-11-03)**

| 项 | 落地 | 提交 |
|---|---|---|
| 选路测试缝 | `RoutingSnapshotSource` 接口 + `TcpExitSelectionTest`(3): 命中→附加出口 / 不可用→主出口 / failover 关→RST | `3baebc5` |
| `failoverEnabled=false` | 命中不可用出口 → 直接 RST (不再悄悄换出口) | `3baebc5` |
| 出口可用性 | `TunnelPlugin.isHealthy` (端口在 + SSH 池有活口), 池全死不再被选中 | `486e794` |
| 禁用出口 | 会话中途禁用: 新连接立即停派发, **空闲后**回收 SSH 池 + 监听 (有连接时保留, 最长等 120s) | `3baebc5` |
| 并行启动 | `startAdditionalExits` 曾改 `async/awaitAll`; **2026-11-25 按设计改为非阻塞 `launch` + `delay(300L*index)` 错峰**, 并在错峰窗口后复查 `isRunning` / 出口是否仍启用 | `3baebc5` → 2026-11-25 |
| DOMAIN_SPLIT | UI 明确提示"该模式规则暂不生效" (D-5 未实现前不误导) | `3baebc5` |
| 健康巡检 | `ExitHealthEvaluator` (纯逻辑, 6 例单测): 失健康立即 Degraded / 连续 2 次健康才恢复 / 终态不复活; 15s 节拍 | `58a4c88` |
| 应用分流计数 | 快照追加 `应用分流 等待 N · 拿到 uid M` (会话级清零) | `58a4c88` |
| 未解析规则 | 快照追加 `未解析规则 N` | `486e794` |

**决策记录 (2026-11-03, 用户拍板)**

| 项 | 决定 |
|---|---|
| 建连前重试 (§9.5) | **做**, 完全按设计的 8 个条件, 只重试一次 → `3078ae7` |
| D-5 隧道提升 | **做** (原建议延后, 用户要求现在做) → `3078ae7` |
| `Failed` 终态 | 暂不做: 只有 `Ready ↔ Degraded`。2026-11-25 用户要求给出建议方案 (见 `2026-11-25-full-audit.md` §2), 待裁决 |
| 附加出口上限 | 4 个 (主出口之外) → `ef55cf7` |
| 非 default 软上限 | 启用 128 → `3078ae7`; default 不受此限 |
| 会话中途开总开关 | **不**当场拉起出口, UI 明确提示"下次连接生效" → `ef55cf7` |
| 禁用/删除出口 | 新连接立即停派发; 已有连接保留, 空闲后回收资源 |
| 改名/删除出口 | 弹窗默认勾选"同时更新/删除引用它的规则" → `ef55cf7` |
| 共享 uid | 选择器显示 `共享 uid` 角标 + 说明 → `ef55cf7` |
| 快照出口段 | 单行 ` · ` 拼接 (与设计 §12.1 一致); 已含 `N 连 · ↑↓ · 健康 · 失败/降级` —— 每出口 ↑↓ 由 `Socks5ProxyServer` 在连接关闭时累加会话总量 (`TunnelPlugin.sessionTraffic`), 集成测试 `Socks5TrafficTotalsTest` 用真实 socket + 假隧道验证双向计数 |
| 合并时机 | **真机 S17–S30 通过后再合并 main** |
| 版本号 | 1.3.0 (versionCode 21) → `ef55cf7` |

#### Review 第二轮修复 (2026-11-03)

| 问题 | 修法 |
|---|---|
| `appendAppRule` 追加到末尾 → app 规则优先级最低, 且会把 `default` 挤到非末行被丢弃 | `ExitRuleText.prependAppRule`: 插到规则区最前面 (跳过开头注释), `default` 恒在最后 |
| `onExitDisabled` 等待窗口内用户重新启用出口 → 仍会被停掉且本会话不重启 | 轮询里复查快照, 重新启用则取消回收 |
| `startAdditionalExits` 单个出口异常会取消其余并让 `connect()` 失败 | 每个 `async` 内部 try/catch (`CancellationException` 除外) |
| 启用多出口但一个出口都没起来 → 快照没有 `出口` 段, 看起来像功能没开 | 输出 `无运行中的出口` |
| 两条 `default` + 第一条不在末尾 → 两条都被丢 | default 单独收集, 只保留"最后一条且在末尾" |
| 握手期重试绕过 `failoverEnabled=false` | 关闭时**不重试**, 直接 RST (与 UI 文案一致) |
| D-5 在出口未就绪时也提升域名 → 本应直连的域名绕进主出口 | **2026-11-25 反悔此修法**: 用户裁决"不可用也需要走隧道" —— 谓词改为**纯文本判定** (`DomainExitPromotion.shouldPromote`), 不看出口健康; 可用性由 TCP 阶段 `selectExit` 回退 default, 单测 `DomainExitPromotionTest` 锁死 |
| IP/CIDR 规则在 REMOTE 假 IP 下不生效 (设计 §6.4 表格写错) | 设计表已改为"只对字面量 IP"; 规则页加提示文案 |
| 全数字目标 (`1.2.3.4.5`) 被当作域名规则接受 | `isValidDomain` 拒绝全数字标签 (多打一个点的笔误) |
| `RoutingTableStore.refresh` 两次写非原子 / 并发 refresh 交错 | 加 `Mutex` 串行化 |
| 出口列表页在主线程按"出口×规则行"算引用数 | 组合流 `flowOn(Dispatchers.Default)` |
| 快照只有"连接数", 看不出每个出口干了多少活 (§12.1) | `Socks5ProxyServer` 关闭连接时累加会话总量 → `TunnelPlugin.sessionTraffic` → 快照 `↑1.2MB ↓8.4MB`; `DataPlaneSnapshot.bytesText` 固定 `Locale.ROOT`(俄语小数点是逗号); 集成测试跑真实 socket + 假隧道验证双向计数 |
| `ExitHealth.Starting` 不可观测; 启动中途断开时 SSH 池残留; `ActiveExit` 计数是死字段 | `TunnelManager.startExit` **先登记 (health=Starting) 再 connect**: `stopAll/stopExit` 能回收"正在建池"的出口; 启动中的重复调用明确失败; 新增 `noteConnection/noteHandshakeFailure/noteDegraded/noteRecovered` 填四个计数, 快照显示 `失败 N · 降级 N` |

#### 2026-11-25 裁决落地

| 议题 | 裁决 | 落地 |
|---|---|---|
| D-5 谓词 | **不可用也需要走隧道** (纯文本, 设计 §6.5/§8.8) | `DomainExitPromotion.shouldPromote`; 单测 `DomainExitPromotionTest`(5) 锁死"出口未启动仍提升"; 出口可用性交给 `selectExit` 回退 |
| `ExitHealth.Failed` | 先给建议方案 (见 `2026-11-25-full-audit.md` 附录 A), 暂不改代码 | 待用户确认 |
| 附加出口上限 | **4** (总 5 出口 / 15 会话) | 设计 D-7 与资源表已同步 |
| 附加出口启动 | **按设计**: 不阻塞 `connect()` + 300ms 错峰 | `startAdditionalExits` 改 `launch + delay(300L*index)`, 错峰后复查 `isRunning` 与出口仍启用 |
| 资源验收 | **3 出口与 5 出口两套都测** | 验收 §6 加内存/ MaxStartups 两行, S23 注明两套 |

#### 真机第一轮 (2026-11-03)

详见 `2026-11-03-multi-exit-device-round1.md`。**多出口运行时本身正常** (三个附加出口可同时就绪),
但暴露两条会让用户误判成"多出口坏了"的缺陷, 均已修复:

| 现象 | 根因 | 修复 |
|---|---|---|
| 出口 `jz` 永久 `SSH 会话创建失败 — Algorithm negotiation fail: kex`, 其它出口正常 | 白名单里的 `curve25519-sha256` 在 Android 上被 JSch **静默过滤** (`DHXEC` 反射加载 XDH provider 不可用), 只剩 DH-GEX/group14; 新 OpenSSH 只开 PQ/curve25519/ecdh → 交集为空 | `KEX_ALGORITHMS` 补 `ecdh-sha2-nistp256/384/521` + `group16-sha512`; 回归用例按"curve25519 全不可用"算交集 (`0e149a4`) |
| 已连接时点另一台服务器"点了没反应", 该行永久转圈 | `SshVpnService.connect()` 在 `isVpnRunning()` 时静默 `return` (只有 logcat), 而 UI 已乐观置成 `Connecting`, 不会再有事件改回来 | 抽 `connectIntent()` 纯函数: 同一台 → INFO 不重连; 另一台 → `switchServer()` 走 `autoReconnect` + 写 `lastServerId`/`setActiveServer` (`fbd581b`) |

**仍未做**

1. **`Failed` 终态** (按决策不做)。
2. **S17–S30 真机全量**: 目前只完成"连接 + 出口就绪 + 快照观察"; S17 关闭态逐字节 diff、S19–S30 全部未跑 ——
   这是合并前的唯一门禁 (其余开发项已全部完成, 单测 524)。
