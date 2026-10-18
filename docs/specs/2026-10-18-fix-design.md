# 缺陷修复设计文档（2026-10-18）

- 日期：2026-10-18
- 输入：**`docs/specs/2026-10-18-defect-audit.md`**（功能/流程 + 安全 §7 + 资源 §8 + 性能 §9，共 50 项）
- 范围：**只出设计，不含实现**；实现按批次走 implement
- **对应开发 TODO（可实施/可验证/可监测）：`docs/specs/2026-10-18-fix-todo.md`**（`T-xx` 与本文 `F-xx` 一一对应）
- 状态：初稿（方案按 audit 已核实项编写；标注「待真机/待确认」的按 audit 口径）

## 设计原则

1. **不改变对外行为语义**（唯一的例外是明确声明的 UX 变更：门禁 fail-closed、首连指纹可见化、滑块范围对齐）。
2. **优先修根因**，不做只压现象的表面补丁。
3. **尊重 `AGENTS.md` 既有纪律**：窗口闸门/关闭语义/背压闸/假 IP acquire-release 配对/`connectMutex` 串行/`SshIoDispatcher` 隔离 —— 修复不得破坏这些不变量。
4. **每项必须有回归测试**（能在纯 JVM 单测里锁死的就加单测；涉及 Keystore/远端/真机的给出手工验证步骤）。
5. **最小改动面**：能局部改的不动结构；需要动不变量时在条目里显式写出「被触及的不变量」。

## 批次划分

| 批次 | 条目 | 原则 |
|------|------|------|
| **P0** | F-01 F-02 F-03 F-04 F-05 | 向导可用性 + 硬件密钥可用性 + 安全越权（功能必坏或安全） |
| **P1** | F-06 F-07 F-08 F-09 F-10 F-11 F-12 | 并发死锁/竞态 + 会话资源泄漏 + 假 IP 池 + 首连信任可见化 + 开机/门禁健壮性 |
| **P2** | F-13 ~ F-24 | UX 一致性 + 性能（数据面分配/锁/日志口径）+ 低危硬化 |
| **Waived** | W-1 ~ W-5 | 明确不修（含「看起来很严重但核实为非缺陷」的复述） |

映射：`F-xx` → audit 编号（`#n`）/ `SEC-x` / `LEAK-x` / `PERF-x`。

---

# P0 详细设计

## F-01 一键配置临时路径跨会话不一致【audit #1】

**现状**：`ServerProvisioner.kt:127,172` 用字面量 `$$`（`/tmp/sshinjector_{setup,pubkey}_$$.{sh,pub}`），远端 shell 展开成**当前命令 shell 的 PID**；而 `execSingleShot` 每次调用新建 JSch session（`JschSshClient.kt:1162-1280`），上传与执行分属不同 PID → `sh /tmp/..._<P3>.sh` 找不到 `<P1>` 写入的文件。

**根因**：把「路径唯一化」错误地交给远端 shell（`$$` 跨调用不稳定）。

**设计**：在 `runProvision` 内**本地生成一次性 runId**，把它作为参数贯穿所有 exec 调用，远端不再出现任何 `$$`：

```kotlin
private suspend fun runProvision(login, publicKey, emit): Outcome {
    val runId = UUID.randomUUID().toString().replace("-", "").take(12)
    ...
    val uploaded = uploadAndVerifyScript(login, sudoPrefix, script, scriptHash, runId)
    val scriptPath = uploaded.scriptPath ?: return uploaded.outcome
    val executeError = uploadAndRun(login, sudoPrefix, publicKey, runId, emit)
    ...
}

// uploadAndVerifyScript(..., runId: String)
val scriptPath = "/tmp/sshinjector_setup_$runId.sh"

// uploadAndRun(..., runId: String)
val pubKeyPath = "/tmp/sshinjector_pubkey_$runId.pub"
val run = exec(login, sudoPrefix, "sh $scriptPath $pubKeyPath")   // scriptPath 由 uploadAndVerifyScript 返回, 已确定
```

**要点**
- 保留 `uploadAndVerifyScript` 返回 `scriptPath` 的结构不变（`scriptPath` 与 `runId` 一致），避免二次求值。
- 清理（所有 `rm -f $scriptPath $pubKeyPath`）随同一 `runId`，天然对齐。
- **禁止**改用 `$RANDOM`/`$(mktemp)` 等远端展开 —— 同一问题。
- 路径字符集：`runId` 仅 `[a-f0-9]`，无注入面。

**影响面**：`ServerProvisioner.kt`（`runProvision`/`uploadAndVerifyScript`/`uploadAndRun` 签名各加一个 `runId`/改参数）。

**测试**
- 单测（现有 mock executor）：断言**同一 run 内所有 exec 的 command 里出现的临时路径完全一致**，且不含 `$$`、`$RANDOM`、`mktemp`。
- 新增断言：`uploadAndVerifyScript` 的 `sha256sum` 路径 == `uploadAndRun` 里 `sh` 的路径。

**风险**：低。行为唯一变化是路径字符串（不再依赖远端 PID）。

---

## F-02 密码 sudo 探测从不喂密码【audit #2】

**现状**：`ServerProvisioner.kt:203-208` `exec(login, null, "$SUDO_WITH_PASSWORD true")`；`exec()`（`:225-239`）**仅在 `sudoPrefix == SUDO_WITH_PASSWORD` 时**把密码前置进 stdin。传 `null` → 无 stdin → `sudo -S` 读到 EOF → 必失败 → 需要 sudo 密码的主机一律回落 `LocalOnly`。

**根因**：探测调用的 `sudoPrefix` 参数与 `exec()` 的判定条件不一致。

**设计**：探测时传 `SUDO_WITH_PASSWORD` 作为 `sudoPrefix`，让 `exec()` 走密码前置分支：

```kotlin
if (!login.password.isNullOrEmpty()) {
    val sudoPass = exec(login, SUDO_WITH_PASSWORD, "true")   // 原: exec(login, null, "$SUDO_WITH_PASSWORD true")
    if (sudoPass.exitCode == 0) return SUDO_WITH_PASSWORD
}
```

**要点**
- 命令体只剩 `true`（`sudoPrefix` 已含 `sudo -S -p ''`），避免重复 `sudo`。
- `exec()` 的密码前置逻辑不改（已在 `SUDO_WITH_PASSWORD` 分支正确处理）。

**影响面**：`ServerProvisioner.kt:detectSudoPrefix`。

**测试**：单测断言探测命令 == `sudo -S -p '' true` 且 `stdinData` 以 `password` 开头；断言**未**出现 `"sudo -S -p '' sudo ..."` 双 sudo。

**风险**：低。修好后这是「需要 sudo 密码主机」的唯一可用路径（F-01 之后第二优先）。

---

## F-03 「重置服务器指纹」无效【audit #3】

**现状**：`ServerEditViewModel.resetHostKey(host, port)` 只 `knownHostsManager.removeHostKey(...)`；而 `KnownHostsHostKeyRepository.check()`（`JschSshClient.kt:231-234`）在 `config.hostKeyFingerprint`（DB 列）非空时**以 DB 指纹为权威**。`Daos.kt` 只有 `updateHostKeyFingerprint(id, fp)`，**无清空方法** → 首连 TOFU 落库后重置是空操作。

**设计**：三处一起改。

1. DAO 新增清空方法（`ServerDao`）：
```kotlin
@Query("UPDATE servers SET hostKeyFingerprint = NULL WHERE id = :id")
suspend fun clearHostKeyFingerprint(id: Long)
```
2. `ServerRepository` 暴露：
```kotlin
suspend fun resetHostKeyFingerprint(serverId: Long) = withContext(Dispatchers.IO) {
    if (serverId > 0) serverDao.clearHostKeyFingerprint(serverId)
}
```
3. `ServerEditViewModel.resetHostKey` 改签名加 `serverId`，**同时**清 DB 指纹与 known_hosts，并保证 DB 清空是「已重置」的判定依据：
```kotlin
fun resetHostKey(serverId: Long, host: String, port: Int) {
    viewModelScope.launch {
        val removed = knownHostsManager.removeHostKey(host.trim(), port)
        serverRepository.resetHostKeyFingerprint(serverId)
        _message.tryEmit("已重置服务器指纹，下次连接将重新信任该服务器")
    }
}
```
调用点（`ServerEditScreen`）传入 `serverId`。

**要点**
- `check()` 的权威优先级**不改**（DB 有值即强校验是正确设计）；修复点是「重置」要真的清空权威源。
- `id<=0`（新建未落库）无 DB 行，跳过 DB 清空即可。

**影响面**：`Daos.kt`、`ServerRepository.kt`、`ServerEditViewModel.kt`、`ServerEditScreen.kt`。

**测试**：单测（Room in-memory 或 mock repo）断言 `resetHostKey` 后 `serverDao.getByIdBlocking(id).hostKeyFingerprint == null`；并断言随后 `check()` 走 TOFU 分支（OK）。

**风险**：低。需要 DB schema 无变更（只加 DAO 方法，无迁移）。

---

## F-04 硬件密钥签名格式（RSA/EC/Ed25519）【audit #4、#5 + §7 SEC 注】

**现状**（对照 mwiede/jsch 2.28.7 源码）
- `AndroidKeyStoreIdentity.getSignature` 的 `when` 分支：`"EC" -> buildEcdsaSignatureBlob(...)`、`"Ed25519" -> ...`、**`else -> derSig`** —— RSA 返回**裸签名**。JSch 要求该返回值本身就是 `string alg ‖ string sig`（`KeyPairRSA.getSignature` 用 `Buffer.fromBytes([alg, sig])`，`UserAuthPublicKey:321` 再 `putString`）→ 硬件 RSA **必失败**。
- `getSignature` 的 EC 分支用 `privateKey as? ECPublicKey` 取 keySize → 私钥恒不是 `ECPublicKey` → 恒取 `256` → P-384 用 `SHA256withECDSA`；而 `getAlgName()`（`:167-183`，用 `ECKey.params.order.bitLength()`）却声明 `ecdsa-sha2-nistp384` → 声明与签名不一致。
- 未覆写 2 参 `getSignature(data, alg)`（JSch 的 `Identity` 默认实现只是转调 1 参，KDoc 明确要求覆写以支持 `rsa-sha2-256/512`）→ RSA 的 SHA2 协商无法生效。
- Ed25519 用 `"SHA512withEdDSA"`：Ed25519 自带哈希，JCA 标准名是 `"Ed25519"`；用带 digest 的名字很可能 `getInstance` 抛异常 → 静默 null。

**设计（抽纯函数 + 统一 blob 组装）**

```kotlin
override fun getSignature(data: ByteArray?): ByteArray? = getSignature(data, getAlgName())

override fun getSignature(data: ByteArray?, alg: String): ByteArray? {
    if (data == null) return null
    return try {
        val raw = when (privateKey.algorithm) {
            "EC" -> signWith(ecSignAlg(), data)          // SHA256/SHA384withECDSA
            "RSA" -> signWith(rsaSignAlg(alg), data)     // 按 alg: rsa-sha2-512→SHA512withRSA, 256→SHA256withRSA, ssh-rsa→SHA1withRSA
            "Ed25519" -> signWith("Ed25519", data)
            else -> return null
        }
        when (privateKey.algorithm) {
            "EC" -> buildEcdsaSignatureBlob(raw, alg)    // 外层 string 用 alg(而非 getAlgName)
            "RSA" -> buildRawBlob(alg, raw)              // ★ 关键: string alg ‖ string raw(-PKCS#1)
            "Ed25519" -> buildEd25519SignatureBlob(raw, alg)
            else -> null
        }
    } catch (e: Exception) { Log.e(...); null }
}

private fun ecSignAlg(): String =
    if (ecKeySize() > 320) "SHA384withECDSA" else "SHA256withECDSA"   // ecKeySize 复用 ECKey.params.order.bitLength()
```

**要点**
- **外层 `string alg` 用 JSch 传入的 `alg`**（协商后的 `pkmethodsuccess`），RSA 时它可能是 `rsa-sha2-256/512`；`getAlgName()` 仍返回 `ssh-rsa`（那是**公钥类型**，不是签名算法）。
- `buildEcdsaSignatureBlob`/`buildEd25519SignatureBlob` 已有 `string alg` 组装逻辑，改为**接受 alg 形参**（默认 `getAlgName()`），EC 分支传入协商 alg。
- **P-384**：把 `getSignature` 的 keySize 来源与 `getAlgName()` 统一（`ECKey.params.order.bitLength()`）。
- **Ed25519**：JCA 名改 `"Ed25519"`；`AndroidKeyStore` 是否支持 Ed25519 是设备能力问题（`SshKeyManager:67-74` 已强制 Keystore 并在不支持时报错），此处置只保证签名名正确 —— **待真机确认**。
- blob 组装抽成 `internal` 纯函数（不依赖 Keystore），便于单测。

**影响面**：`AndroidKeyStoreIdentity.kt`（`getSignature` 两版 + 三个 build 函数签名 + `getAlgName` 内部一致化）。

**测试**
- 单测（纯函数）：`buildRawBlob("ssh-rsa", sig)` 的字节 == `putString("ssh-rsa")‖putString(sig)`；EC blob 外层为 `string alg`；Ed25519 blob 外层 `ssh-ed25519`。
- 断言 RSA 返回**不是**裸 `sig`（防回归）。
- 手工：真机生成 `RSA 2048` / `P-384` 密钥 → 连接 → 成功鉴权（对照 audit §5 待真机项）。

**风险**：中。JSch 的 `getSignature(data, alg)` 在 KEX 之后、userauth 时传入的 `alg` 需与公钥类型匹配；RSA 时 `ssh-rsa`/`rsa-sha2-*` 都会出现 —— 逻辑按 alg 选摘要，覆盖 `HOST_KEY_ALGORITHMS`/`PUBKEY_ACCEPTED_ALGORITHMS` 里出现的全部 RSA 变体。

---

## F-05 dropbear 路径把隧道密钥写进 root【§7 SEC-1】

**现状**：`assets/ssh_setup_script.sh:31,187-197` —— `configure_dropbear()` 除写 `${ACCT_HOME}/.ssh/authorized_keys`（正确的 per-user 位置）外，还 `mkdir -p /root/.ssh` 并把**同一把隧道公钥** `cat >> /root/.ssh/authorized_keys`。OpenWrt 上若 root 允许公钥登录，手机私钥即可以 **root** 登录（越权），OpenSSH 路径的「专用账号 + chroot」硬化在 dropbear 路径失效。

**设计**：删除 root 授权，只保留账号级授权。

```bash
configure_dropbear() {
    ensure_account
    # 仅写隧道账号的 per-user authorized_keys (dropbear 默认按登录用户读 ~/.ssh/authorized_keys)
    mkdir -p "${ACCT_HOME}/.ssh"
    chown -R "${ACCT}:${ACCT}" "${ACCT_HOME}/.ssh"
    chmod 700 "${ACCT_HOME}/.ssh"
    AUTHKEYS=${ACCT_HOME}/.ssh/authorized_keys
    touch "${AUTHKEYS}"
    grep -qFf "${PUBKEY_FILE}" "${AUTHKEYS}" 2>/dev/null || cat "${PUBKEY_FILE}" >> "${AUTHKEYS}"
    chown "${ACCT}:${ACCT}" "${AUTHKEYS}"
    chmod 600 "${AUTHKEYS}"
}
```
并删除 `DROPBEAR_AUTHKEYS` 常量与 `/root/.ssh` 的 mkdir/chown/chmod 段。

**要点**
- **必须保留 dropbear 兼容性验证**：`ServerProvisioner` 的 VERIFY 步骤已检查账号存在；**新增**一条 dropbear 分支的验证：`test -s ${ACCT_HOME}/.ssh/authorized_keys && stat -c '%U' ${ACCT_HOME}/.ssh/authorized_keys == sshproxy`。
- 若个别 dropbear 构建用全局 `-A`/`AuthKeys` 指向固定文件，则把该文件指向账号文件，**仍不写 root**（该项在真机 OpenWrt 上确认；若确认必须写全局文件，写入对象也应是专用文件而非 root 的）。
- 更新 `SetupScriptConsistencyTest`（脚本 SHA-256 硬编码）与 `ServerProvisioning.SETUP_SCRIPT_SHA256`。

**影响面**：`assets/ssh_setup_script.sh`、`ServerProvisioning.SETUP_SCRIPT_SHA256`、(`ServerProvisioner` 的 VERIFY 命令)。

**测试**
- 脚本静态断言（单测）：脚本内**不出现** `/root/.ssh/authorized_keys`。
- 真机：OpenWrt/dropbear 上跑向导 → 用 app 私钥连 `sshproxy` 成功；`ssh root@host` 用同一把 key **失败**（被拒）。

**风险**：中。删 root 授权可能暴露「某些 dropbear 只认全局文件」的兼容问题 —— 用 VERIFY 步兜住并允许回退到「专用全局文件」而非 root。

---

# P1 详细设计

## F-06 forceDisconnect 永久持锁风险【audit #6】

**现状**：`SshVpnService.kt:862-867`
```kotlin
val lockAcquired = withTimeoutOrNull(FORCE_DISCONNECT_LOCK_WAIT_MS) { connectMutex.lock(); true } ?: false
if (!lockAcquired) { teardownSession(); return }   // 无锁分支
try { teardownSession() } finally { connectMutex.unlock() }
```
若 `lock()` 已成功而超时在其边界触发，`withTimeoutOrNull` 可能返回 `null` 走无锁分支，但锁**已被持有且不 unlock** → 后续所有 `connect/disconnect/rebuild` 永久死锁。

**根因**：用「有超时的挂起 `lock()` + 布尔返回值」表达「是否持锁」，无法区分「未获得」与「已获得但被取消」。

**设计**：改用 **`tryLock()` 轮询**，使「获得」与「释放」严格配对，消除「获得但被放弃」的状态：

```kotlin
private suspend fun forceDisconnect() {
    if (acquireLockBounded(FORCE_DISCONNECT_LOCK_WAIT_MS)) {
        try { teardownSession() } finally { connectMutex.unlock() }
    } else {
        Log.w(...); VpnController.appLogThrottled("强制断开 · 会话锁被占用, 直接强拆 (最后手段)", ...)
        teardownSession()   // 不持锁, 不释放
    }
}

/** 在窗口内轮询 tryLock; 成功即持有(调用方负责 unlock), 失败返回 false 且**不持有**。 */
private suspend fun acquireLockBounded(windowMs: Long): Boolean {
    val deadline = System.nanoTime() + windowMs * 1_000_000
    while (true) {
        if (connectMutex.tryLock()) return true
        if (System.nanoTime() >= deadline) return false
        delay(20)
    }
}
```

**要点**
- `Mutex.tryLock()` 不挂起、不会「获得后被取消」；`return true` 与 `finally unlock()` 一一配对。
- 保留原有语义：等不到锁 → 最后手段强拆（与旧行为一致）。
- 该函数被 UI 断开卡超时兜底调用，改动不影响正常 disconnect 路径。

**影响面**：`SshVpnService.kt:forceDisconnect`（新增私有 `acquireLockBounded`）。

**测试**：单测（fake mutex 或真实 `Mutex` + 并发持锁方）断言 `forceDisconnect` 在超时后**不**留下 held 状态（后续 `tryLock()` 能拿到）；断言正常路径 `unlock` 恰好一次。

**风险**：低。

---

## F-07 Failed 观察者 TOCTOU 拆新会话【audit #7】

**现状**：`SshVpnService.kt` 的 `shouldCleanupOnFailure()`（`:917-920`）在**锁外**判定，`disconnect(userInitiated=false)`（`:932`）才在 `:792` 进 `connectMutex`。窗口内 `autoReconnect`（同样持锁）可能完成重建 → 观察者随后拿锁把**新会话**拆掉。

**设计**：把「重查 + 断开」并入**同一持锁临界区**：

```kotlin
// 由观察者调用; 内部在锁内重查, 避免与 autoReconnect 竞态
private suspend fun disconnectIfIdleOnFailure() {
    connectMutex.withLock {
        if (poolFailReconnectPending || isReconnecting) return
        if (!(vpnController.isVpnRunning() || vpnInterface != null)) return
        disconnectInternal(userInitiated = false)
    }
}
```
观察者改为 `if (state.status == Failed) disconnectIfIdleOnFailure()`；`shouldCleanupOnFailure()` 保留为**仅用于日志**（或在锁内调用）。

**要点**
- 依赖「`autoReconnect` 也持 `connectMutex`」这一既有纪律（`AGENTS.md` 明示会话变更全部串行于 `connectMutex`）—— 锁内重查即杜绝窗口。
- 不改变 `poolFailReconnectPending`/`isReconnecting` 的置位时机（仍在触发重连前置位）。

**影响面**：`SshVpnService.kt`（`observeVpnControllerState` + 新增 `disconnectIfIdleOnFailure`）。

**测试**：并发单测 —— 持锁的 `autoReconnect` 在跑时调用 `disconnectIfIdleOnFailure`，断言其 `return`（不清会话）；重连结束后再调用则执行清理。

**风险**：中（触及会话串行不变量，需回归「池失败重连」与「用户断开」两条既有用例）。

---

## F-08 假 IP 池耗尽不自愈【audit #8】

**现状**：`DnsInterceptor.clampFakeIpCounter`（`:266-269`）= `maxOf(cur, maxSeen, base)`，**永不回拨**；`resetFakeIpCounters`（`:229-251`）被 30s trim 调用，但结果恒 ≥ 当前值 → 计数器**只升**。IPv4 段 131072 个地址（`FAKE_IP_BASE=198.18.0.0`，`FAKE_IP_MAX=198.19.255.255`）；计数是**进程级 companion 静态**（`:399`），跨会话累积。用尽后 `:832-839` 恒 `rawIp > FAKE_IP_MAX` → REMOTE 模式 A/AAAA 解析永久失败，注释「未分配区间可复用」与实现不符。

**根因**：只有「防回拨」（正确性保护），没有「安全回收」。

**设计（不破坏正确性不变量的前提下自愈）**

> **不变量（必须保留）**：假 IP 计数器**绝不下探到已分配值以下并重新发放**。原因：客户端 DNS 缓存里的假 IP 在 TTL 内仍会被用于 CONNECT，若该 IP 被回收给**另一个域名**，`Socks5ProxyServer` 按 `lookupDomain(ip)` 会连到**错误的目的地**（跨域名串号）。这正是「永不回拨」的原始动机 —— 任何「复用」方案都要先证明不引入串号。

1. **修正注释**：`resetFakeIpCounters` 的「未分配区间可复用」删除/改写为「仅对齐上界，不回收」。
2. **耗尽自愈（last-resort，原子）**：当 `rawIp > FAKE_IP_MAX` 时，在**同一把 `fakeIpAllocLock`** 下执行 `resetAllFakeIps()`：清空 `domainToIp`/`ipToDomain` 的假 IP 条目、清 `pendingFakeIpRelease`、计数器复位到 `FAKE_IP_BASE`，打 `LogLevel.ERROR`「假 IP 池耗尽 · 已重建映射，将重新分配」。随后**重试一次**分配。
   - 该路径只在「已经彻底不可用」的状态触发，**净收益 > 风险**（代价是被清掉的旧映射在客户端缓存 TTL 内可能串号 —— 属已知残余风险，记录在案）。
   - 分配与「清空+复位」共用一把锁，避免并发分配拿到被清空的区间。
3. **新增分配锁**：`private val fakeIpAllocLock = Any()`；`domainToIp.get → 命中返回；未命中 → synchronized(fakeIpAllocLock){ 再查一次(双检) → 取/分配 → put }`。DNS 非超热路径，锁可接受。
4. **（可选）会话级预清理**：在 `VpnController.connect` 起始（新 TUN 建立、尚无映射）调用 `dnsInterceptor.onSessionStart()` → `resetAllFakeIps()`，把累积从「进程级」降为「会话级」。**但**需确认「重连时客户端旧缓存串号」风险：若接受该风险（且同 `onSessionStart` 会打 INFO），可显著降低耗尽概率。**建议先上 1-3，4 作为可选开关评估**。

**要点**
- 复用 `sweepPendingFakeIpReleaseForTest`/`seedFakeIpMappingForTest` 风格，提供 `resetAllFakeIpsForTest`。
- 不改 `releaseFakeIp` 的 60s 宽限语义。

**影响面**：`DnsInterceptor.kt`（分配路径 `:822-869`、`clampFakeIpCounter` 注释、新增 `fakeIpAllocLock`/`resetAllFakeIps`）。

**测试**
- 保留 `DnsInterceptorTest.resetFakeIpCounterNeverFallsBelowBase`（防回拨不变量）。
- 新增 `exhaustionTriggersSelfHealingReset`：把计数器置到 `FAKE_IP_MAX` → 触发一次分配 → 断言复位到 base 且新域名成功拿到 IP。
- 新增并发用例：多线程同时触发分配+耗尽，断言无重复 IP 发放。

**风险**：中（触及假 IP 生命周期不变量，需回归 `TcpWindowGateTest` 的假 IP 用例与 audit 记录的三条纪律）。

---

## F-09 SSH 会话池不排空【§8 LEAK-1】

**现状**：`JschSshClient.disconnect()`（`:940-953`）首行 `if (!isConnectedFlag.getAndSet(false)) return true`；`checkPoolHealth()`（`:928-938`）在池失效时 `isConnectedFlag.set(false)` 但**不排空 pool** → 池失败重连触发的 `disconnect()` 恒早退，排空循环（`session.disconnect()`/`keepAliveJob.cancel()`/`scope.cancel()`）**永不可达**；随后 `connect()` 在 `:489 pool.add(pooled)` 叠加到滞留会话上 → 每次池失败重连累积 ≤3 条 Session/Scope。

**设计**：把「是否曾连接」的判定与「排空」解耦 —— 排空**无条件执行**，且 `connect()` 起始保证池干净。

```kotlin
override suspend fun disconnect(): Boolean {
    val wasConnected = isConnectedFlag.getAndSet(false)
    connectionState.value = ConnectionState.Disconnecting
    drainPool()                       // 无条件: 内部 while(pool.isNotEmpty()){ ... }
    currentConfig = null
    connectionState.value = ConnectionState.Disconnected
    return true
}

private fun drainPool() {
    while (pool.isNotEmpty()) {
        val pooled = pool.poll() ?: break
        pooled.keepAliveJob?.cancel()
        try { pooled.session.disconnect() } catch (_: Exception) {}
        pooled.scope.cancel()
    }
}

override suspend fun connect(config): ConnectionResult {
    if (isConnectedFlag.get()) return ConnectionResult(false, error = "Already connected")
    drainPool()                       // 兜底: 任何残留先清掉, 防叠加
    ...
}
```
`checkPoolHealth()` 可保留 `isConnectedFlag.set(false)`（但排空交由 `disconnect()`/`connect()`）。

**要点**
- `disconnect()` 返回值语义（原为「已连接才 true」）调用方只是忽略或当成幂等成功，改为恒 `true` 安全；若需保留语义可返回 `wasConnected`，但**排空必须无条件**。
- 触及不变量：无（`pool` 的并发访问方式不变）。

**影响面**：`JschSshClient.kt`（`disconnect`/`checkPoolHealth`/`connect` + 新增 `drainPool`）。

**测试**：单测（fake session 记录 `disconnect()` 调用）断言「`checkPoolHealth` 置 false 后调用 `disconnect()` 仍会 drain」「`connect()` 前池为空」。

**风险**：低-中（`connect()` 起始 drain 若与并行建连竞态需确认；当前 `connect` 由 `connectMutex` 串行，安全）。

---

## F-10 往返探活通道/线程泄漏【§8 LEAK-2】

**现状**：`verifySessionRoundTrip`（`JschSshClient.kt:775-800`）：成功路径 `ch.disconnect()`；**`catch` 不 disconnect**，且 `withTimeoutOrNull(...)` 超时后 `pooled.scope.async(Dispatchers.IO)` 仍阻塞在 socket（注释自认「泄漏的 IO 线程自会结束」）。

**设计**：用 `try/finally` 保证通道释放，并在超时后取消 async：

```kotlin
val probe = pooled.scope.async(Dispatchers.IO) {
    var ch: com.jcraft.jsch.ChannelExec? = null
    try {
        ch = session.openChannel("exec") as ...
        ...
        ch.connect(HEARTBEAT_EXEC_TIMEOUT_MS)
        ROUND_TRIP_OK
    } catch (e: Exception) { ...classify... }
    finally { try { ch?.disconnect() } catch (_: Exception) {} }
}
return try {
    withTimeoutOrNull(HEARTBEAT_EXEC_TIMEOUT_MS * 2L) { probe.await() } ?: ROUND_TRIP_FAILED
} finally {
    if (!probe.isCompleted) { probe.cancel(); /* 通道 disconnect 由 body 的 finally 兜住 */ }
}
```

**要点**
- 取消 async 不会中断阻塞的 JSch socket，但 body 的 `finally` 会 `ch.disconnect()` → socket 关闭 → 阻塞返回 → 线程回收。
- 不改变三态语义（OK/UNAVAILABLE/FAILED）。

**影响面**：`JschSshClient.kt:verifySessionRoundTrip`。

**测试**：单测用假 session/channel 断言「异常与超时两条路径都调用 `ch.disconnect()`」。

**风险**：低。

---

## F-11 首连主机密钥静默 TOFU 可见化【§7 SEC-3】

**现状**：`JschSshClient.kt:236-242` 首连无条件 `saveTofu` 并 `return OK`，无带外确认；presenting 的指纹仅进 logcat。配置向导连接（`config.id<=0`）连 DB 指纹都不落库（`persistFingerprint:627-631`）。

**设计**（务实版：不阻断连接，但让「首次信任」对用户可见、可核对）

1. **首连打应用内 `INFO` 日志**（用户可见）：`主机密钥首次信任 · <host>:<port> — SHA256:<fp>（请与服务器 ssh-keygen -lf 核对）`。
2. **服务器详情/编辑页**展示已信任指纹（读 `getStoredFingerprint`），提供「核对指纹」说明文案 + 「重置指纹」（走 F-03）。
3. **向导后的首次连接**：向导保存服务器后再连，即走正常 TOFU 并将指纹落库（`id>0`）——无需特殊处理，仅补充日志提示。
4. **不做**「KEX 内弹窗阻断」：`check()` 在 JSch 读线程内同步执行，无法安全地发起 UI 交互（会阻塞 KEX）；阻断式确认需另设「预取指纹」流程，成本高、破坏面大，列为**后续可选增强**（W-3）。

**要点**
- 不改 `check()` 的接受逻辑（不引入连接失败回归）。
- 目的是把「静默」变成「可见 + 可核对 + 可重置」。

**影响面**：`JschSshClient.known_hosts` 相关（日志）、服务器详情 UI、i18n 四份 strings。

**测试**：单测断言首连触发一次 `主机密钥首次信任` app 日志（可用 sink 注入）。

**风险**：低。

---

## F-12 开机自启与门禁健壮性【audit #14、#15、§7 SEC-2】

**现状**
- `BootReceiver.kt:24-41` 只有 `try/finally`，无 `catch`：`settings.autoConnect.first()` 或 `startForegroundService` 异常 → 逃逸到裸协程 → 开机崩溃；且绕过生物识别门禁。
- 门禁调用点（`DashboardScreen.kt:617-627`、`ServerListScreen.kt:199-207`）`else { onGranted() }` = **fail-open**；`BiometricAuth` 的 AES key 与 SSH 签名 key 不同源。

**设计**

1. **BootReceiver 加兜底**：
```kotlin
CoroutineScope(Dispatchers.IO).launch {
    try {
        val autoConnect = settings.autoConnect.first()
        if (autoConnect) { val id = settings.lastServerId.first(); if (id > 0) startService(...) }
    } catch (e: Exception) {
        Log.w("BootReceiver", "auto-connect failed: ${e.message}", e)   // 不让开机崩溃
    } finally { pendingResult.finish() }
}
```
   - `startForegroundService` 用 `try/catch (ForegroundServiceStartNotAllowedException)` 吞掉并记 app 日志。
   - 处理 `ACTION_LOCKED_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED`（可选，避免漏触发/异常）。
2. **门禁 fail-closed**：`needsBiometric(keyAlias)` 为 true 时，**取不到 `FragmentActivity`/`BiometricAuth` 也不放行**（改为提示「无法进行生物识别校验」并中止），仅当 `needsBiometric==false` 才直接 `onGranted()`：
```kotlin
onClickConnect = {
    val proceed = { viewModel.connect(server.id) }
    if (biometricAuth?.needsBiometric(server.keyAlias) == true) {
        val fa = fragmentActivity
        if (fa == null) { toast(无法校验生物识别); return@onClickConnect }
        biometricAuth.connectIfAllowed(fa, server.keyAlias, proceed)
    } else proceed()
}
```
   - 两个调用点同步改。
3. **（可选，SEC-2 加固）**：后台自动重连路径（`ACTION_USER_PRESENT`/网络切换/池失败）本就不走门禁，无可修；把门禁 AES key 与 SSH 签名 key 的**绑定**列为后续增强（W-4）。

**影响面**：`BootReceiver.kt`、`DashboardScreen.kt`、`ServerListScreen.kt`、i18n。

**测试**：门禁三分支单测（needsBiometric=false 直接放行 / true+无 Activity 中止 / true+有 Activity 走弹框）；BootReceiver 注入抛异常的 settings fake 断言不崩溃。

**风险**：中（门禁改 fail-closed 是**行为变更**，需明确声明；老用户设备无生物识别硬件时，若 key 标了 biometric 会被拒 —— 需在 `needsBiometric` 里对「设备无生物识别」单独处理为可放行或提示重设密钥）。

---

# P2 详细设计（要点式）

> P2 多为 UX 一致性、性能与低危硬化；逐项给出最小改动方案与测试点，不展开全部代码。

| 编号 | 映射 | 方案要点 | 测试 | 风险 |
|------|------|----------|------|------|
| F-13 | #9 | 白名单「全选」落地：`selectAll` 改为**由 `enabledPackages` 派生**（`allSelected = filtered.all{it in enabled}`）；点击时对**当前过滤集**批量 `viewModel.setEnabled(pkg, !allSelected)`，并批量落库 + 触发重建（复用现有单包路径的批量版）。 | UI 单测：过滤集全选/反选后 `enabledPackages` 正确 | 低 |
| F-14 | #10 | `ServerListViewModel`：新增 `clearConnecting()`，在连接失败/超时/异常分支（`launch` 的 `catch`、以及 `isActive` 变 true 两种成功/失败出口）都清 `_connectingServerId`；或在 `ServerListScreen` 的 `LaunchedEffect` 里对「连接已结束且未激活」清空。 | ViewModel 单测：连接失败后 `connectingServerId==null` | 低 |
| F-15 | #11 | 全局设置滑块与校验器对齐：MTU `576f..1500f`、KeepAlive `0f..3600f`（0=关闭）；新增「跟随服务器设置（取消全局覆盖）」按钮 → `settingsDataStore.clearMtu()/clearKeepAlive()`（`DataStore.edit{ it.remove(KEY_MTU/KEY_KEEP_ALIVE) }`）+ `SettingsViewModel.clearMtu()/clearKeepAlive()`。 | 单测：设置后清除 → flow 回 `null` | 低 |
| F-16 | #12 | `DomainListSettingsScreen`：加 `LaunchedEffect(domainListUrl) { urlInput = domainListUrl }`（对齐 `AdBlockSettingsScreen.kt:65-67`）。 | 组合测试 | 低 |
| F-17 | #13 | 编辑页 `firstError` 纳入 `keyAlias` 非空校验（无密码时密钥必填）；`ServerFormError` 增 `KEY_REQUIRED` + `server_error_key_required`（四语言）。 | `ServerFormValidatorTest` 补例 | 低 |
| F-18 | #17、#13 | 向导 `submitServerInfo` 的 `when` 补 `NAME_TOO_LONG`；保存时对 `host/name` 用 `trim()` 后的值（校验与落库一致）。 | 向导 ViewModel 单测 | 低 |
| F-19 | #16 | 白名单「系统应用」筛选：`hideSystemApps` 与 `filterMode==2` 语义去重 —— 选中「系统应用」时自动允许系统项（或把 `hideSystemApps` 默认设 false 并仅在显式勾选时应用）。 | UI 单测 | 低 |
| F-20 | §9 PERF-1 | `writeToTun` 串行度：先**实测**（方案 A）确认 `FileOutputStream(fd).write(byte[])` 单次系统调用即整包原子（TUN 每次 write = 一包），若成立则去掉 `@Synchronized`，仅保留计数；否则方案 B：专用写线程 + 有界队列，生产者不阻塞。 | 压测：多连接并发回程 vs 去锁后完整性（`tcpdump`/计数对账） | 中（需实测） |
| F-21 | §9 PERF-2/3 | 每包分配治理：① `TcpConnection` 建连时缓存 `srcIpBytes/dstIpBytes`，回程组包复用（消除 `.address` 克隆）；② 旁路判定改按 `excludedRoutes` 预编译为 `Int/ByteArray` 前缀比较，`extractDstIp` 不再每包建 `InetAddress`；③ `connectionKey` 接受原始字节。 | 单测：组包字节与旧实现一致；微基准分配次数下降 | 中 |
| F-22 | §9 PERF-4 | `forwardSynToTunnel` 移出 `Dispatchers.IO` → `sshIoDispatcher.dispatcher`（派发前 `isSaturated()` 守卫，饱和则快速失败回退），并把 `Thread.sleep(2)` 轮询改为现有 deadline 驱动的 `delay`。 | 单测：饱和时快速失败；既有 SOCKS 握手超时用例不回退 | 中 |
| F-23 | §7 SEC-6/7/8、#3-9/3-11 | 低危硬化批量：算法白名单去 `ssh-rsa`（同步 `JschAlgorithmWhitelistTest`）；移除 `READ_PHONE_STATE` 改 `ConnectivityManager` 粗粒度标签；SSH key 补 `setInvalidatedByBiometricEnrollment(true)`（与门禁 key 一致，需在变更说明里提示「录新指纹会失效旧密钥」）；`DnsInterceptor.protectSocket`/`UdpRelay.dnsInterceptor` 补 `@Volatile`。 | 白名单单测、权限 lint、假 IP/无引用测试 | 低-中 |
| F-24 | §9 PERF-5/6/7/9、#3-24 | 低危性能/一致性批量：Dashboard 用 `derivedStateOf`/字段级 `select` 降低 100ms 重组；per-SYN 日志加 `IS_DEBUG`；`LruStringMap` 热路径访问时间改 `nanoTime` 或按需刷新；`PacketWriter` IPv4 id 改计数/`nanoTime`；`KeyAddScreen` 订阅 `error` 再 `onNavigateBack`。 | 各自单测 | 低 |

---

# 横切关注点

## 测试策略
- **纯 JVM 单测优先**：F-01/F-02（mock executor 断言命令/路径）、F-03（DAO/Repository）、F-04（抽出的 blob 纯函数）、F-06（Mutex 配对）、F-08（假 IP 自愈）、F-09/F-10（fake session 记录释放）、F-12（门禁分支）。
- **保留并强化防回归断言**：`DnsInterceptorTest.resetFakeIpCounterNeverFallsBelowBase`、`TcpWindowGateTest` 假 IP 用例、`TcpCloseBannerTest`、`SetupScriptConsistencyTest`、`JschAlgorithmWhitelistTest`（F-23 同步）。
- **真机清单**（对应 audit §5）：F-01 向导正常路径、F-02 密码 sudo 主机、F-04 RSA/P-384 密钥连接、F-05 OpenWrt root 越权、F-12 开机自启。

## 文档同步（实现落地时）
- `AGENTS.md`：删除「已知缺陷」区中已修条目；对**新增不变量**补 Key Gotchas（F-08 的分配锁与耗尽自愈、F-09 的排空纪律、F-04 的 RSA blob 格式）。
- `docs/specs/2026-10-18-defect-audit.md`：在对应条目标注「已修（commit）」。
- `docs/diagrams/*`：若 F-06/F-07/F-09 改动会话状态机/锁流程，更新对应序列图/状态图。
- `handoff.md`：批次收尾记录。

## 建议实现顺序
P0（F-01→F-02→F-03→F-04→F-05）→ P1（F-06→F-07→F-09→F-10→F-08→F-11→F-12）→ P2。
每个 P0/P1 条目独立提交（Conventional Commits），便于回滚与逐项真机验证。

---

# Waived（明确不修 / 已证伪）

- **W-1 进程级单例 scope/executor 不 cancel/shutdown**（§8 LEAK-5）：Android 进程存活期复用、daemon 线程、无 per-session 累积，属设计；不引入 `onTerminate` 清理。
- **W-2 首连阻断式指纹确认**（§7 SEC-3 的强方案）：`check()` 在 JSch 读线程内同步执行，无法安全弹 UI；改为 F-11 的「可见 + 可核对 + 可重置」。
- **W-3 假 IP 复用（free-list / 会话级复位）**：默认不启用，因存在「客户端缓存假 IP 被回收给另一域名 → 串号」的正确性风险；F-08 仅做「耗尽时全量重建」的 last-resort 自愈，复用方案需先证明无串号（列为可选研究项）。
- **W-4 门禁与 SSH 签名 key 密码学绑定**（§7 SEC-2）：需改造签名流程（`CryptoObject` 注入 Keystore 签名），破坏面大；F-12 先做 fail-closed，绑定列后续。
- **W-5 已证伪项**（§4）：JSch 通道 `connect()` 失败自清（非泄漏）；`UdpRelay` per-packet `Log.d` 已 `IS_DEBUG` 门控且 release 由 R8 剥离（非发布版瓶颈）—— 均**不改**。
