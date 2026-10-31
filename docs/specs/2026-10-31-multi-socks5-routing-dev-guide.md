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
- [ ] P0-1 TunnelConfig 透传 (C-1)
- [ ] P0-2 exitLabel 日志分流 (C-5)
- [ ] P0-3 工厂 + 去单例
- [ ] P0-4 TunnelManager 分键 + 可空 fallback
- [ ] P0-5 conn.tunnelPlugin (C-2)
- [ ] P0-6 探活端口 (C-3)
- [ ] P0-7 ConnectionBudget

### P1
- [ ] AppIdentity
- [ ] 规则解析/匹配 + 单测
- [ ] Room v5 + 迁移
- [ ] DataStore 键
- [ ] UI (入口/列表/编辑器/应用选择器)
- [ ] 快照占位

### P2
- [ ] AppUidResolver 句柄
- [ ] forwardSynToTunnel 接线 (bypass 先行)
- [ ] ForwardOutcome + 重试
- [ ] 附加出口生命周期 + 探活
- [ ] DNS 谓词
- [ ] 观测面
- [ ] S17–S26 真机
