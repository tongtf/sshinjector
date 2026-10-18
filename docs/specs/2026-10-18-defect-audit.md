# 缺陷审计记录（2026-10-18）

- 日期：2026-10-18
- **对应修复设计：`docs/specs/2026-10-18-fix-design.md`**（本文件每条已核实缺陷的落地方案，按 P0/P1/P2 批次）
- **对应开发 TODO：`docs/specs/2026-10-18-fix-todo.md`**（逐条可勾选、含验证/监测/护栏/回滚）
- 方法：读码核验（关键项对照 mwiede/jsch 2.28.7 源码 / 逐行确认调用链），**不做实现**
- 范围：数据面（TcpStateMachine / Socks5ProxyServer / VpnController / DnsInterceptor）、
  VPN 服务与状态机、加密与凭据层、配置/供装编排、Compose UI 流程
- 维度：**功能/流程缺陷（§1-§3）+ 安全（§7）+ 资源泄漏（§8）+ 性能（§9）**
- 性质：**已核实为真实存在的缺陷**（每条附证据）。区分「读码确认」与「待真机复现」
- 说明：本文件是缺陷记录，**不是修复方案**；实现另行排期

> 严重度：**高** = 功能必坏/数据不可用；**中** = 功能/安全/UX 缺陷或竞态；**低** = 健壮性/死代码/一致性

---

## 0. 结论摘要

| # | 严重度 | 位置 | 问题 |
|---|--------|------|------|
| 1 | **高** | `ServerProvisioner.kt:127,172` | 临时路径用 shell `$$`，跨 SSH 会话 PID 不同 → 上传的脚本/公钥与执行路径不一致，一键配置正常路径必失败 |
| 2 | **中** | `ServerProvisioner.kt:204` | 密码 sudo 探测从不喂密码 → 需要 sudo 密码的主机永远判为 LocalOnly |
| 3 | **中** | `ServerEditViewModel.kt:70-84` + `JschSshClient.kt:231-234` | 「重置服务器指纹」只删 known_hosts，不清 DB 指纹 → 置过指纹后重置无效 |
| 4 | **中** | `AndroidKeyStoreIdentity.kt:48-52` | RSA 硬件密钥签名未按 SSH 格式包 `string alg‖string sig` → UI 可选的 RSA 2048 密钥不可用 |
| 5 | **中** | `AndroidKeyStoreIdentity.kt:34-38` | EC 分支把 `PrivateKey` 强转 `ECPublicKey` 恒为 null → P-384 仍按 SHA-256 签名（与声明的曲线不匹配） |
| 6 | **中** | `SshVpnService.kt:863-867` | `withTimeoutOrNull { connectMutex.lock() }` 可能在已获锁后被超时取消 → 返回 null 走无锁分支，`connectMutex` 永久不 unlock |
| 7 | **中** | `SshVpnService.kt:932` | `shouldCleanupOnFailure()` 在锁外判定，`disconnect()` 才进锁 → TOCTOU，可能拆掉并发重建好的新会话 |
| 8 | **中** | `DnsInterceptor.kt:229-269` | 假 IP 计数器只升不降（clamp 取 max），池耗尽后不自愈；注释「未分配区间可复用」与实现不符 |
| 9 | **中** | `WhitelistScreen.kt:85,255` | 白名单「全选/取消全选」按钮只切换自身图标，无任何代码消费 `selectAll` → 空操作 |
| 10 | **中** | `ServerListViewModel.kt:80` + `ServerListScreen.kt:81-88` | 连接失败时 `connectingServerId` 不清 → 服务器卡片转圈永不消失 |
| 11 | **中** | `SettingsScreen.kt:211,227` | 全局 MTU 滑块 1280-1500（校验器 576-1500）、KeepAlive 滑块 10-120（校验器 0-3600，0=关闭）；且无「取消全局覆盖」入口 |
| 12 | **中** | `DomainListSettingsScreen.kt:52` | `urlInput` 用 `remember` 固定初始值，无 `LaunchedEffect(domainListUrl)` 同步 → 自定义 URL 可能被默认值覆盖 |
| 13 | **中** | `ServerEditScreen.kt:132-133,169-183` | 编辑页不校验 `keyAlias` → 可保存无密钥（且无密码）的服务器，连接必失败且无提示 |
| 14 | **中** | `BootReceiver.kt:24-41,35` | 开机自启协程无 catch（DataStore 读失败/FGS 启动异常 → 开机崩溃）；且绕过生物识别门禁 |
| 15 | **中** | `DashboardScreen.kt` / `ServerListScreen.kt`（`connectIfAllowed` 调用点） | 生物识别门禁 fail-open：非 `FragmentActivity` 或取实例失败时直接放行；门禁 AES key 与 SSH 签名 key 无关 |
| 16 | **中** | `WhitelistScreen.kt:158,161` | `hideSystemApps` 默认 true 先滤掉系统应用，filterMode==2 再取 `isSystem` → 「系统应用」标签恒空 |
| 17 | **低** | `ServerWizardViewModel.kt:131-148` | 向导 `when` 未处理 `NAME_TOO_LONG` → 超长名可入库（编辑页会拦，两端不一致） |
| 18 | **低** | `ServerEditScreen.kt:171` / `ServerWizardViewModel.kt:242` | 校验时 `host.trim()`，保存时写原始值 → 带首尾空白的主机名可入库 |
| 19 | **低** | `TcpStateMachine.kt:1936` | `reset()` 只 `fakeIpActiveConns.clear()`，未对活动 IP 调 `releaseFakeIp` → 映射残留靠宽限/LRU 兜底 |
| 20 | **低** | `SshKeyManager.kt:126-139` | 死代码：Ed25519 文件回退用 `private.encoded`（硬件密钥为 null）→ 若可达将 NPE/`doFinal(null)` |
| 21 | **低** | `AesGcmCipher.kt:29,38-40` | 解密失败静默返回 null → 密码变「无密码」fail-open；`enc:v1:` 未作 AAD 绑定（低危） |
| 22 | **低** | `DnsInterceptor.kt:179` + `113-121` | `pendingResponses` 为 UNLIMITED Channel、DNS executor 无界队列 → 投递停摆时无界堆积 |
| 23 | **低** | `DnsInterceptor.kt:717-729` + `146-156` | 非 A/AAAA 回退应答标 DIRECT，REMOTE 模式读查 TUNNEL 标签 → 该路径缓存恒不命中（死缓存） |
| 24 | **低** | `LruStringMap.kt:56-63` | `removeByValue` 先 filter 再按 key `remove`（非原子）→ 并发下可能误删同 key 新条目 |
| 25 | **低** | `UdpRelay.kt:68` | `dstPort == 53 \|\| srcPort == 53` 把入站响应也当查询重造应答 |
| 26 | **低** | 多处（见 §3） | 死字段/死代码/i18n 硬编码/静默失败 |

> 上表为**功能/流程**类（§1-§3）。**安全** 见 §7（SEC-1~10，含 1 高 / 2 中）、**资源泄漏** 见 §8（LEAK-1~5，含 2 中）、**性能** 见 §9（PERF-1~10，含 4 中）。

---

## 1. 高危（功能必坏）

### 1-A 一键配置：`$$` 临时路径跨会话不一致【读码确认，建议真机复现】

`ServerProvisioner.kt:127` `val pubKeyPath = "/tmp/sshinjector_pubkey_$$.pub"`
`ServerProvisioner.kt:172` `val scriptPath = "/tmp/sshinjector_setup_$$.sh"`

Kotlin 中 `$$` 不是模板（`$` 后接非标识符字符 → 字面量 `$$`），因此该字符串原样发到远端；
远端 shell 把 `$$` 展开为**该命令所在 shell 的 PID**。而 `execSingleShot`
（`JschSshClient.kt:1162-1280`）**每次调用都新建 `JSch()` + `getSession` + `connect`**，
每个 exec channel 由 sshd 起一个新的登录 shell → **PID 每次都不同**。

调用链：
1. `uploadAndVerifyScript` 一条 exec：`cat > /tmp/sshinjector_setup_$$.sh && chmod ... && sha256sum ...`
   → 文件按 PID=P1 落盘（同一命令行内 `$$` 一致，哈希校验因此能过）。
2. `uploadAndRun` 里 `cat > /tmp/sshinjector_pubkey_$$.pub` → PID=P2。
3. `sh /tmp/sshinjector_setup_$$.sh /tmp/sshinjector_pubkey_$$.pub` → PID=P3
   → 找的是 P3 的两个文件，**都不存在** → `EXECUTE_SCRIPT` 失败。

现行 `ServerProvisionerTest` 用 mock executor 只做字符串匹配，无法发现（PID 展开发生在远端）。

**修复方向**：本地生成唯一后缀（`UUID`/`SecureRandom`）拼进路径并在所有 exec 调用中复用同一字符串。

### 1-B 密码 sudo 探测从不喂密码【读码确认】

`ServerProvisioner.kt:203-208`：
```kotlin
val sudoPass = exec(login, null, "$SUDO_WITH_PASSWORD true")   // sudoPrefix = null
if (sudoPass.exitCode == 0) return SUDO_WITH_PASSWORD
```
`exec()` 只在 `sudoPrefix == SUDO_WITH_PASSWORD` 时把密码前置进 stdin（`:225-239`）。
此处传 `null` → `effectiveStdin = null` → `sudo -S -p '' true` 从 stdin 读到 EOF → 必失败。
结果：**需要 sudo 密码的主机一律被判「无 root/sudo」→ 返回 `LocalOnly`**，
`SUDO_WITH_PASSWORD` 这条实际执行路径对探测而言不可达。

**修复方向**：探测时传 `SUDO_WITH_PASSWORD` 作为 `sudoPrefix`，让密码进 stdin。

---

## 2. 中危

### 2-A 重置服务器指纹无效【读码确认】

- `ServerEditViewModel.kt:70-84` `resetHostKey` 只调 `knownHostsManager.removeHostKey(host, port)`。
- `JschSshClient.kt:231-234`：`check()` 只要 `config.hostKeyFingerprint` 非空就**以 DB 指纹为准**，
  不再看 known_hosts。
- `Daos.kt:71-75` 仅有 `updateHostKeyFingerprint(id, fp)`，**没有清空该列的 DAO**。

首连 TOFU `persistFingerprint` 会把指纹写进 DB（`JschSshClient.kt:627-642`），此后重置按钮成为空操作，
用户卡在「host key changed」。**修复方向**：重置时一并清空 `servers.hostKeyFingerprint`（新增 DAO/仓储方法）。

### 2-B 硬件 RSA 密钥签名格式错误（UI 可选）【读码确认 + 对照 jsch 源码】

- UI 生成密钥对话框提供 `"ECDSA P-256","RSA 2048","ECDSA P-384","Ed25519"`
  （`KeyManagerScreen.kt:610`），`algorithm==1` → `KeyPairGenerator.getInstance("RSA","AndroidKeyStore")`
  （`SshKeyManager.kt:97,102,110`）→ 不可导出 → 走 `AndroidKeyStoreIdentity`。
- jsch 的 RSA 签名必须包成 `string alg ‖ string sig`（`KeyPairRSA.getSignature:390-411`
  `Buffer.fromBytes([alg, sig])`），`UserAuthPublicKey:314-321` 把该 blob 直接 `putString`。
- 本类 RSA 分支（`AndroidKeyStoreIdentity.kt:48-52` `else -> derSig`）**直接返回裸签名**，
  且未覆写 2 参 `getSignature(data, alg)`（jsch 调的是 2 参版）。→ 硬件 RSA 密钥鉴权必失败。

**修复方向**：RSA 分支同样 `writeString(alg)+writeString(sig)` 包装；补 2 参覆写。

### 2-C P-384 用 SHA-256 签名【读码确认】

`AndroidKeyStoreIdentity.kt:34-38`：`(privateKey as? ECPublicKey)?.w?.affineX?...` ——
`PrivateKey` 永远不是 `ECPublicKey` → `keySize` 恒为默认 `256` → `SHA256withECDSA`，
而 `getAlgName()`（`:167-183`，用 `ECKey.params.order`）却声明 `ecdsa-sha2-nistp384`。
严格校验曲线↔哈希绑定的服务器会拒绝。**修复方向**：用 `ECKey`/`ECPrivateKey` 取 size。

### 2-D `forceDisconnect` 可能永久持锁【读码确认】

`SshVpnService.kt:862-867`：
```kotlin
val lockAcquired = withTimeoutOrNull(FORCE_DISCONNECT_LOCK_WAIT_MS) {
    connectMutex.lock(); true
} ?: false
if (!lockAcquired) { teardownSession(); return }   // ← 无锁分支
```
若 `lock()` 已成功、而超时恰好在该边界触发取消，`withTimeoutOrNull` 返回 `null` 走无锁分支，
**`connectMutex` 已被持有但永不 `unlock()`** → 之后所有 connect/disconnect/rebuild 全部死锁。
**修复方向**：不要用超时包 `lock()`；若超时用「acquire 后立刻 try{}」，或在无锁分支确保锁未被拿到。

### 2-E Failed 观察者 TOCTOU 拆新会话【读码确认】

`SshVpnService.kt:917-920` `shouldCleanupOnFailure()` 在锁外判定，
`SshVpnService.kt:932` 判定通过后 `disconnect(userInitiated=false)` 才在 `:792` 进 `connectMutex`。
窗口内网络切换/池失败触发 `autoReconnect` 并重建成功 → 观察者随后拿到锁把**新会话**拆掉。
**修复方向**：把重查 `isReconnecting/poolFailReconnectPending` 移入持锁的断开路径内部。

### 2-F 假 IP 计数器只升不降 → 池耗尽不自愈【读码确认】

`DnsInterceptor.kt:229-269`：`resetFakeIpCounters` 用 `clampFakeIpCounter(cur, maxSeen)`，
而后者 `maxOf(cur, maxSeen, base)` —— **永不回拨**。`domainToIp` 被 LRU trim 后 `maxSeen` 变小，
但 `cur` 保持旧峰值；IPv4 段仅 131072 个地址（`198.18.0.0/15`）；一旦分配超过总量，
`rawIp > FAKE_IP_MAX` 恒真 → REMOTE 模式所有 A/AAAA 解析永久失败且**不自愈**（30s trim 也救不回）。
注释「未分配区间可复用」与实现不符。（clamp 无符号语义本身是对的，见 `AGENTS.md` 假 IP 生命周期条；
问题在于只有「防回拨」没有「安全回收」。）
**修复方向**：在**无并发分配窗口**（或带代次戳）下允许回拨到 `maxSeen`，或改按空闲链表分配。

### 2-G 白名单「全选」空操作【读码确认，grep 全仓】

`WhitelistScreen.kt:85` 声明 `selectAll`，`:255` 只在图标按钮里 `selectAll = !selectAll`，
`:259,265` 仅决定显示 Check/Done 图标；**全仓无其它引用**（`enabledPackages` 未被写入）。
搜索态下承诺的「全选搜索结果」完全无效。

### 2-H 连接失败后卡片转圈不消失【读码确认】

`ServerListViewModel.kt:80` 连接时置 `_connectingServerId = id`；
`ServerListScreen.kt:81-88` 仅当 `server.isActive == true` 才 `clearConnecting()`。
连接失败时 `isActive` 仍 false → 转圈保留，直到用户手动再点一次（触发 disconnect 才清）。

### 2-I 全局设置滑块范围/语义与校验器不一致【读码确认】

- `SettingsScreen.kt:211` MTU `valueRange = 1280f..1500f`；校验器 `MTU_MIN=576`（`ServerFormValidator.kt:28`）→ 无法设 576-1279。
- `SettingsScreen.kt:227` KeepAlive `valueRange = 10f..120f`；校验器 `0..3600`（`:30`）且 **0 = 关闭**
  （`JschSshClient.startSessionKeepAlive` 语义）→ 全局**无法把 keepAlive 设为 0 关闭**。
- 全局字段可空（`null` = 回退 per-server，`SshVpnService.mergeGlobalSettings:476-482`），
  但拖动即写入具体值，UI **没有恢复为 null 的入口**。

### 2-J 域名列表 URL 输入框不同步【读码确认】

`DomainListSettingsScreen.kt:52` `var urlInput by remember { mutableStateOf(domainListUrl) }`，
全文件无 `LaunchedEffect(domainListUrl)`（对比 `AdBlockSettingsScreen.kt:65-67` 有同步）。
`domainListUrl` 初值为默认 URL，首次组合若拿到默认值即被 `remember` 固定；
用户带自定义 URL 进入后点「保存」会把持久值覆盖为默认。

### 2-K 编辑页不校验 keyAlias【读码确认】

`ServerEditScreen.kt:132-133` `firstError` 只含 name/host/port/username/socksPort/mtu/keepAlive，
**不含 keyAlias**；`:169-183` 直接 `keyAlias = keyAlias` 保存。新建手动服务器未生成/选择密钥时
可落库 `keyAlias=""`，连接必失败且无提示。

### 2-L 开机自启：无异常兜底 + 绕过门禁【读码确认】

`BootReceiver.kt:24-41` 只有 `try/finally` 无 `catch`：`settings.autoConnect.first()`（DataStore 读失败）
或 `context.startForegroundService`（`ForegroundServiceStartNotAllowedException`）会逃逸到裸
`CoroutineScope(Dispatchers.IO)` → 开机瞬间崩溃。`:35` 直接 `startForegroundService(ACTION_CONNECT)`，
使受生物识别保护的 server 也走程序化启动路径，绕过 UI 门禁。另未处理
`ACTION_LOCKED_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED`。

### 2-M 生物识别门禁 fail-open 且未绑定签名 key【读码确认】

- 调用点（`DashboardScreen.kt` / `ServerListScreen.kt` 的 `connectIfAllowed`）形如
  「若 `fragmentActivity != null && biometricAuth != null` 才弹框，否则直接 `onGranted()`」——
  取不到宿主机/实例时**直接放行**（fail-open），与 `SshKeyManager.isBiometricProtected` 的
  fail-closed 设计不一致。
- `BiometricAuth.kt:92-121` 用独立 AES key `sshinjector_biometric_key` 做 `CryptoObject`，
  与真正 SSH 签名（`AndroidKeyStoreIdentity.getSignature` 的 `initSign`）**不是同一把 key**，
  门禁未在密码学上绑定签名操作。

---

## 3. 低危 / 健壮性 / 一致性

| # | 位置 | 问题 |
|---|------|------|
| 3-1 | `ServerWizardViewModel.kt:131-148` | `when` 未处理 `NAME_TOO_LONG`，超长名进入下一步并入库 |
| 3-2 | `ServerEditScreen.kt:171`、`ServerWizardViewModel.kt:242-243` | 校验 `host.trim()` 但保存原始值（带空白主机名可入库） |
| 3-3 | `TcpStateMachine.kt:1936` | `reset()` 清 `fakeIpActiveConns` 不 release 假 IP，映射残留（与 2-F 叠加） |
| 3-4 | `SshKeyManager.kt:126-139` | 死代码：Ed25519 文件回退用 `private.encoded`（硬件密钥为 null）→ 若可达 NPE/`doFinal(null)` |
| 3-5 | `SshKeyManager.kt:530,576-594`、`ServerRepository.kt:59-68` | 死 API：`canAccessKey`/`sign`/`saveServer`；`useLegacyEcdsaFormat` 无 setter 的可变静态 |
| 3-6 | `AndroidKeyStoreIdentity.kt:40` | Ed25519 用 `"SHA512withEdDSA"`（非标准 JCA 名），未知名时静默返回 null |
| 3-7 | `AesGcmCipher.kt:29,38-40` | 解密失败静默返回 null → `password=null` fail-open；`enc:v1:` 未作 AAD 绑定 |
| 3-8 | `SshKeyManager.kt:334-353` | `getOrCreateImportWrapperKey` 在读路径上创建新 key（旧密文静默不可解） |
| 3-9 | `DnsInterceptor.kt:179`、`113-121`、`596` | `pendingResponses` UNLIMITED、executor 无界队列、`pendingQueries` 无界（投递停摆时堆积） |
| 3-10 | `DnsInterceptor.kt:717-729` + `146-156` | 非 A/AAAA 回退应答标 DIRECT，REMOTE 读 TUNNEL → 该路径缓存恒不命中 |
| 3-11 | `DnsInterceptor.kt:403`、`UdpRelay.kt:43` | `protectSocket` / `dnsInterceptor` 非 `@Volatile`（跨线程读写） |
| 3-12 | `LruStringMap.kt:56-63` | `removeByValue` filter→`remove(key)` 非原子（并发误删） |
| 3-13 | `DnsInterceptor.kt:822-869` | 假 IP 分配 `get`→`increment`→`put` 非原子（并发同域名可产生孤儿映射） |
| 3-14 | `UdpRelay.kt:68` | `srcPort == 53` 入站响应被当查询重造应答 |
| 3-15 | `Icmpv6Responder.kt:28,66,83-94` | 未用形参 `ignoredDstIp`；对任意 fd00::/8 回 NA、任意源回 RA 无限制 |
| 3-16 | `ServerRepository.kt:90` | `saveServerEdit` 行不存在时静默 `return existingId`（当作成功） |
| 3-17 | `ServerProvisioner.kt:141-154` | VERIFY 的 `\|\|` 分支可被上一次遗留的 dropbear marker 满足 |
| 3-18 | `ServerProvisioner.kt:166-181` | 校验 SHA-256 与 `sh <file>` 执行之间 TOCTOU（文件非 root owner） |
| 3-19 | `entities/Entities.kt:17` | 遗留 `keyPassphrase` 列明文保留（当前无写入路径） |
| 3-20 | `VpnController.kt:798`、`SshVpnService.kt:464,1190` | `VpnState.error` 写入但 UI 从不读（死字段）；`tunFd` 只写不读 |
| 3-21 | `MainViewModel.kt:572-579,594-598` | `startForegroundService`/`startService` 未捕获异常（与 `ServerListViewModel` 有 catch 不一致） |
| 3-22 | `MainViewModel.kt` 多处 collector、`SshVpnService.kt:591` | 无 try/catch（单帧异常静默取消流） |
| 3-23 | ViewModel/Compose 多处 | i18n 硬编码中文（`ServerListViewModel`/`ServerEditViewModel`/`ServerWizardViewModel`/`KeyManagerViewModel` 错误文案；`ServerEditScreen.kt:371,381,391` 全局设置提示） |
| 3-24 | `KeyAddScreen.kt`（导入/生成后） | 失败仅 `_error.tryEmit`，页面无 `error.collect` 且立即 `onNavigateBack()` → 静默失败 |
| 3-25 | `KeyManagerScreen.kt:663-744` | 导入私钥无 passphrase 输入 → 加密私钥（带口令）无法从 UI 导入 |
| 3-26 | `KeyManagerViewModel.kt:117-125` | `deleteKey`/`deleteAllKeys` 主线程做 Keystore/文件删除 |
| 3-27 | `AdBlockSettingsViewModel.kt:76-85` | 注释称「未保存则加载内置清单」，实现 `saved.orEmpty()`（空串）→ 注释与实现不符 |
| 3-28 | `ServerWizardScreen.kt:459-466` | `Tampered` 结果只有文字、无重试/返回按钮（死路） |

---

## 4. 已核实「非缺陷」（避免重复误报）

- **AES-GCM 加密实现本身正确**（`AesGcmCipher.kt` / `CredentialCrypto.kt`）：AES-256-GCM + Keystore key +
  每次操作随机 IV + 写端不降级明文（CodeQL 的「broken crypto」确已修）。
- **主机指纹强校验真实生效**（非「只存不验」）：`JschSshClient.kt:231-242` + `StrictHostKeyChecking=yes`。
- **SOCKS5 认证 fail-closed**：无凭据/无 `0x02` 方法一律拒（`Socks5ProxyServer.kt:570-574`）。
- **回程窗口闸门 / 关闭语义 / 假 IP acquire-release 配对 / outgoing 背压闸**：读码与既有单测一致，未发现新问题。
- **F9/R6 等历史 waived 项**：与 `docs/specs/2026-09-29-review-fixes-spec.md` P3 结论一致。
- **JSch 通道 `connect()` 失败会自清** —— 故「`createDirectChannel` 打开通道后 `connect` 抛异常未手动 `disconnect` = 泄漏」**不成立**：JSch 的 `Channel.connect(int)` 在 catch 内先 `connected=false; disconnect()` 再 `throw`（对照 mwiede 2.28.7 源码 `com/jcraft/jsch/Channel.java:162-174`）。该 catch 无需补 `disconnect`。
- **`UdpRelay` 的 per-packet `Log.d` 已 `IS_DEBUG` 门控**（`UdpRelay.kt:76-78`），且 release 由 R8 剥离 `Log.d` —— 不是热点日志缺陷（见 §9 PERF-6 的口径修正）。
- **`@Singleton` 进程级 scope/executor 不 cancel/shutdown 是设计**（进程存活期复用，无 per-session 累积）：`PacketProcessor` / `TcpStateMachine` / `VpnController` / `DnsInterceptor` / `SshIoDispatcher` / `AdBlockManager` / `DomainListManager`。仅当它们**累积 per-session 条目**时才构成泄漏（见 §8）。

---

## 5. 待真机复现（代码证据充分，但建议实测坐实）

1. #1 一键配置正常路径（预期 `EXECUTE_SCRIPT` 失败）——最优先，因它决定「向导是否可用」。
2. #2 需要 sudo 密码的主机（预期回落 `LocalOnly`）。
3. #4 用「RSA 2048」生成密钥后连接（预期鉴权失败）。
4. #14 开机自启（Android 14+ FGS-from-boot 限制 + 异常兜底）。
5. #15 生物识别门禁绕过（程序化 `am start-foreground-service`）。

---

## 6. 建议修复优先级

- **P0（先修）**：#1（向导不可用）、#2（sudo 密码主机不可用）、#3（重置无效误导用户）、#4（RSA 密钥不可用）、**SEC-1（dropbear 路径把隧道密钥写进 root）**。
- **P1**：#6（潜在永久死锁）、#7（拆新会话）、#8（假 IP 池耗尽不自愈）、#10、#13、#14、#15、**LEAK-1（会话池不排空）**、**SEC-3（首连静默 TOFU）**。
- **P2**：#5、#9、#11、#12、#16、**LEAK-2** 及 §3 一致性/健壮性项、§9 性能项（按实际瓶颈取舍）。
- 修复后需同步：`AGENTS.md`（相关 Key Gotchas）、`docs/diagrams/*`（如涉及流程）、相关单测。

---

## 7. 安全（security）

> 已核实为**真实**的问题；「已核实非缺陷」的安全好实践列在本节末尾，避免后续重复怀疑。

| # | 严重度 | 位置 | 问题 |
|---|--------|------|------|
| SEC-1 | **高** | `assets/ssh_setup_script.sh:31,187-197` | dropbear/OpenWrt 路径把隧道账号的公钥**同时追加到 `/root/.ssh/authorized_keys`** → 手机私钥可直接以 **root** 登录（越权），OpenSSH 路径的「专用账号 + chroot」硬化在 dropbear 路径失效 |
| SEC-2 | **中** | `DashboardScreen.kt:617-627`、`ServerListScreen.kt:199-207`、`BiometricAuth.kt:32,50-57,148` | 生物识别门禁 fail-open + 未与 SSH 签名 key 密码学绑定（见 #15 交叉引用） |
| SEC-3 | **中** | `JschSshClient.kt:236-242`（`StrictHostKeyChecking=yes` 见 `:586`） | 首连主机密钥**静默 TOFU**：无任何带外确认即信任并落库；配置向导连接同样 TOFU 且因 `id<=0` **不落库指纹**（`persistFingerprint` 只在 `config.id>0` 时写）→ 首连 MITM 被无条件信任 |
| SEC-4 | 低 | `AndroidManifest.xml:68-75` | 导出 `BootReceiver` 仅由 `RECEIVE_BOOT_COMPLETED`（normal 权限）保护 → 任意本机 app 可显式广播触发连接（DoS 级，非数据泄露） |
| SEC-5 | 低 | `assets/ssh_setup_script.sh:158,160-161,167` | 以 root 用可预测 `/tmp/sshinjector_sshd_test.err` 重定向 → 本机非 root 用户可预置符号链接（TOCTOU，需服务器上已有低权账号） |
| SEC-6 | 低 | `JschSshClient.kt:407-412` | 主机密钥/公钥算法白名单仍含 **`ssh-rsa`(SHA-1)**（cipher/MAC/KEX 已正确排除 SHA-1/CBC）→ 可降级 SHA-1 |
| SEC-7 | 低 | `AndroidManifest.xml:9`、`MainViewModel.kt:236-252` | 申请运行时敏感权限 `READ_PHONE_STATE`，仅用于打印「4G/5G」标签（`ConnectivityManager` 即可，无需该权限） |
| SEC-8 | 低 | `SshKeyManager.kt:86-88,116-118` | SSH 签名 key 设了 `setUserAuthenticationRequired(true)`+`setUserAuthenticationParameters(300,…)`，但**未** `setInvalidatedByBiometricEnrollment` → 录入新指纹不失效旧 SSH key（门禁 AES key 反而设了，防御深度不一致） |
| SEC-9 | 低 | `ServerProvisioningUtils.kt:12-24` | 公钥校验仅语法（算法白名单+base64 字符集+单行），不解码 blob → 语法合法但结构非法的 key 仍会被写入 `authorized_keys`（仅功能/DoS） |
| SEC-10 | 低 | `entities/Entities.kt:17` | 遗留 `keyPassphrase` 明文列（无写入路径、无迁移删除）——与 #3-19 同一项 |

**已核实的安全好实践（非缺陷）**：`SshVpnService` 由 signature 级 `BIND_VPN_SERVICE` 保护；仅 `MainActivity` 与上述 receiver 导出；无导出 Provider；`allowBackup=false` + `dataExtractionRules`/`backup_rules` 排除 db/prefs/files；`networkSecurityConfig` 禁明文、只信系统 CA；无 `debuggable`/`usesCleartextTraffic`；两个 `PendingIntent` 均 `FLAG_IMMUTABLE`、通知 `VISIBILITY_PRIVATE`；本地代理**只绑 `127.0.0.1`**、RFC-1929 认证恒强制且 fail-closed、口令比较走 `MessageDigest.isEqual`、每次连接随机令牌、3s 未认证超时 + 512 连接上限；口令 AES-GCM 落库（Keystore 包钥）+ 旧明文懒迁移；导入 PEM/passphrase 与软件 Ed25519 已加密；无 WebView/动态加载/`MODE_WORLD_*`/外部存储密钥；`Log.*`/`appLog*` 无密钥/口令插值；R8 无宽泛 `-keep class **`。

---

## 8. 资源泄漏（未释放）

> 判定口径：`@Singleton` 进程级资源不 cancel 属设计（见 §4），只记**会随会话/重连累积**的泄漏。

| # | 严重度 | 位置 | 问题（应释放路径缺失） |
|---|--------|------|------|
| LEAK-1 | **中** | `JschSshClient.kt:940-941`（+ `checkPoolHealth():928-938`、`connect():489`） | `disconnect()` 首行 `if (!isConnectedFlag.getAndSet(false)) return true`；而 `checkPoolHealth()` 已把 `isConnectedFlag.set(false)` 且**不排空 `pool`** → 池失败重连触发的 `disconnect()` 恒早退，`while (pool.isNotEmpty()){ session.disconnect(); keepAliveJob?.cancel(); scope.cancel() }`（`:945-953`）**永不可达**；下一次 `connect()` 在 `:489 pool.add(pooled)` 叠加到滞留会话上 → 每次池失败重连累积最多 3 条 Session/CoroutineScope |
| LEAK-2 | **中** | `JschSshClient.kt:775-800` | `verifySessionRoundTrip` 的 exec 通道在 `catch`（`:788-797`）不 `disconnect()`；且 `withTimeoutOrNull(...)`（`:800`）超时后 `pooled.scope.async(Dispatchers.IO)`（`:775`）仍阻塞在 socket 上（代码注释亦承认「泄漏的 IO 线程自会结束」）→ 每次往返探活超时泄漏 1 个 `Dispatchers.IO` 线程直到 socket 超时 |
| LEAK-3 | 低 | `JschSshClient.kt:452` | `scope` 是**死字段**（全仓无读/用点），永不 cancel（无子任务，无害但应删） |
| LEAK-4 | 低 | `DnsInterceptor.kt:178-179`、`BackpressureGate.kt:29` | `pendingResponses`(UNLIMITED `Channel`) 与 `BackpressureGate.channel` 永不 `close()`（前者仅排空、后者随连接 GC）—— 见 #3-9 |
| LEAK-5 | 低 | `vpn/SshVpnService.kt`、`PacketProcessor.kt:32`、`TcpStateMachine.kt:81`、`VpnController.kt:76,204`、`DnsInterceptor.kt:113,202`、`SshIoDispatcher.kt:27`、`AdBlockManager.kt:58`、`DomainListManager.kt:55` | 进程级单例 scope/executor 永不 cancel/shutdown（**设计如此**，记录备查；daemon 线程 + `allowCoreThreadTimeOut`，无 per-session 累积即非泄漏） |

**已核实清洁**：`Socks5ProxyServer.stop()`/`Socks5Connection.close()` 释放 selector+channel+tunnel；`TcpStateMachine` 所有早退分支关 socket；`ConnectivityProber`/`forwardDnsBypassPacket` 在 `finally` 关 socket；`RemoteListFetcher` `use{}`+`disconnect()`；`execSingleShot` `finally` 关 channel+session；`SshVpnService.onDestroy` 注销 network callback/receiver 并 cancel job/scope；`teardownSession` 关 `ParcelFileDescriptor` + `stopForeground`；无 WakeLock/WifiLock/SensorManager/Cursor/`GlobalScope`。

---

## 9. 性能（performance）

> 本项目为**用户态 TCP/IP 栈**，packetLoop 单线程 + 每包分配是主要瓶颈面。逐条已读码确认。

| # | 严重度 | 位置 | 成本 |
|---|--------|------|------|
| PERF-1 | **中** | `VpnController.kt:1101-1102` | `@Synchronized fun writeToTun(data){ outputStream.write(data) }` —— 单一 monitor 串行化**全部** TUN 写（packetLoop ACK + DNS 投递 + 每条 SSH relay 的回程），TUN 缓冲满时 `write()` 阻塞**持锁**→ 跨连接 head-of-line。（整包原子性是必需的，但代价明确；可考虑扩容 TUN 写队列或按队列投递） |
| PERF-2 | **中** | `VpnController.kt:919-920,960-980`；`IpPacketParser.kt:240-252,258-259` | 配置了排除路由时**每包**调 `extractDstIp` → `ByteArray(4/16)`+`InetAddress`；`IpPacketParser` 每包 2×地址解析 + `connectionKey` 又 `.address` 克隆 2 次 → TCP 每包约 5-7 次堆分配，全在 packetLoop 单线程 |
| PERF-3 | **中** | `TcpStateMachine.kt:1397-1410`（另见 `:1498,1570,1623`） | 回程组包每 1460B 段：`conn.dstIp.address`/`conn.srcIp.address` 各克隆 byte[]（`:1397-1398`）+ `ByteBuffer.allocate(totalLen)`（`:1410`，HeapByteBuffer+backing）≈ 每段 4 次堆分配；KDoc「1 alloc + 1 copy」低估 |
| PERF-4 | **中** | `TcpStateMachine.kt:81,486-529` | `forwardSynToTunnel` 跑在共享 `Dispatchers.IO` 且握手用 `Thread.sleep(2)` 忙等（未走专用 `SshIoDispatcher`）→ 每次新建连占 1 个 IO 池线程并忙轮询 |
| PERF-5 | 低 | `MainViewModel.kt:363-373` + `DashboardScreen.kt:96` | `connectionStats`（100ms 发布）→ `_uiState.update{}`，而 Dashboard 顶层 `uiState.collectAsState()` → 连接期间约 **10 次/秒全量重组**（无 `derivedStateOf`/字段级订阅） |
| PERF-6 | 低 | `UdpRelay.kt:76`（已门控）；`VpnController.kt:1021`、`TcpStateMachine.kt:492`（未门控但 per-SYN） | 高频 `Log.d` 的**口径**：release 由 R8 剥离 `Log.d`（含字符串模板），仅 debug 付代价；UdpRelay 那条已有 `IS_DEBUG` 门 → 非发布版瓶颈 |
| PERF-7 | 低 | `LruStringMap.kt:38-42,65-73` | `get()` 每次 `System.currentTimeMillis()` 刷新 LRU（DNS/CONNECT 热路径）；`trim()` 全表排序 O(n log n)（16384 容量，低频） |
| PERF-8 | 低（待确认） | `Socks5ProxyServer.kt:882-886` | `startRelayFromTarget` 直接 `scope.launch(sshIoDispatcher.dispatcher)`，**无** `connectToTarget`（`:730`）那样的 `isSaturated()` 前置检查；池满时 `CallerRunsPolicy` 可能在当前线程内联跑无限回程循环。实际调用方是 ssh-io 线程（非 eventLoop），影响有限 —— 需实测确认 |
| PERF-9 | 低 | `PacketWriter.kt:47`；`TcpStateMachine.kt:171` | 每包 `System.currentTimeMillis()` 填 IPv4 id / 刷 `lastActivity`（每包一次系统调用 + 时钟回拨隐患，`nanoTime`/计数器更廉价） |
| PERF-10 | 低（设计，需知情） | `AndroidManifest.xml:31` | `android:largeHeap="true"`：本栈确有大量常驻缓冲（每连接 64KB relay buffer、每 session 1MB JSch `max_input_buffer_size`、8MB 窗口），但 largeHeap 同时抬高堆上限、掩盖泄漏并加大 GC 停顿 —— 保持它需有实测依据（可加内存快照对照） |

**已核实清洁**：`SshIoDispatcher` 池参数与设计一致且 `isSaturated()` 在 `connectToTarget` 前置；JSch 反射方法 `by lazy` 缓存 + `createSession` 一次性 `loggerSet`（无每连接 `declaredMethods` 扫描）；`ChecksumCalculator.tcpChecksum` 流式无临时缓冲；`PacketStats` 全 `AtomicLong` 且 100ms 才发 StateFlow。

