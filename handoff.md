# Handoff — SSHInjector Session Summary

**Date:** 2026-09-30 (CST)
**Branch:** `main` | **HEAD:** 本次提交（连接流程状态细化） | **Version:** `1.0.7` (versionCode 7)
**Push/Release:** ✅ 完成 — `git push --no-verify` 绕过 pre-push #N 钩子（用户指令 push到github），`bf5f656..26635bb` 20 提交上库；tag `v1.0.7` → CI 三 job 全绿 → **GitHub Release 已发布**（`app-{arm64-v8a,armeabi-v7a,x86_64}-release.apk` + `app-release.aab`，git-cliff notes）
**Device:** Redmi K50 Pro `TCEIPN4DVWRSNNRK`, adb `/opt/android-sdk/platform-tools/adb`, release 包 `cn.srv0.sshinjector`（已装 1.0.7，不含出口 IP 功能）

## Session Goal（本批次）

1. ~~修复「VPN 已连接但无法访问网络」~~ ✅ Bug A/B 已修+真机验证（`99c13a5`/`1011824`）
2. **状态检测 connectivity-health** ✅ 实现+审计+文档+7 提交+发布 v1.0.7
3. **首页出口 IP（隧道回显）** ✅ `756f737`（实现+测试+文档，未 push）
4. **连接流程状态细化（ConnectStage）** ✅ 本次提交（实现+测试+文档，未 push）

## What Is Done ✅

### v1.0.7 批次（已全部上库）

| Commit | 内容 |
|---|---|
| `99c13a5` / `1011824` | Bug B 读缓冲毒化 + Bug A readFully + 回归测试 |
| `ec51240` | 解锁自动重连（hasUnhealthySession + USER_PRESENT） |
| `3a066a8` | proguard 仅剥离 Log.d |
| `e552665`→`6b76645` | 状态检测按层 6 提交（model→domain→controller→service→ui） |
| `c22bf14` / `26635bb` | docs: spec/AGENTS/evidence + README×3/USER_GUIDE/vpn-state 图 |
| `28e488a` | chore: bump version to 1.0.7 |

### 已提交 `756f737`：首页出口 IP

- **用户选定方案**: 隧道回显（非 SSH 对端 IP）——互联网视角的真实代理出口 IP
- `VpnState.exitIp`（会话级缓存，连接期 `exitIp=null` 重置）+ `VpnController.reportExitIp`
- `ConnectivityProber.fetchExitIp(endpointUrl = 默认 ipify JSON)`: 复用 SOCKS5 握手 + 提取 `openHttpStreams` 共享 TLS 路径；状态行校验、Content-Length 精读/EOF 兜底（≤256B）、JSON `"ip"` 提取或裸正文；字符集+须含 `.`/`:` 校验；**任何失败返回 null（不归因健康、无重试风暴）**
- `SshVpnService.runHealthProbe`: Ok 且 `exitIp==null` 时顺带取一次 → `reportExitIp`（日志 `exit ip: …`）
- UI: `UiState.exitIp` 两处 collector 透传（未连接 → `-`）；Dashboard 状态块 IPv6 行下新增「出口 IP」；`dashboard_exit_ip` 4 语言
- **测试**: prober 新增 5 例（json/无 CL 至 EOF/非 IP/空体/代理不可达）→ 170 绿；**质量门 6/6**（test/ktlint/detekt/lint/assembleDebug；release 未重跑——未 bump 版本）

### 本次提交：连接流程状态细化（ConnectStage）

- **需求**: 连接过程只显示「连接中」，需展示流程中每个可能状态
- `ConnectStage` 枚举: TUN 正在创建 VPN 接口 / TUNNEL 正在连接 SSH（隧道插件含 SSH 握手=最慢阶段）/ CONFIG 正在配置网络规则; `VpnState.connectStage`
- 推进点: `SshVpnService` establish 前 `reportConnectStage(TUN)`（connect + autoReconnect 两处）→ `VpnController.connect` 入口清阶段 → startPlugin 前 TUNNEL → DNS 配置前 CONFIG → Connected 后由 verified 接管（网络验证中/已连接）
- 映射同源: `buildStatusDisplay`（状态卡）+ `updateHealthNotification`（通知）; **Connecting 分支 failedStep 优先** — establish 失败时 controller.disconnect 因 isRunning=false 早退, status 停留 Connecting, 不然卡显阶段文案
- 通知门由 `isVpnRunning` 改为状态过滤（TUN 阶段在 isRunning=true 之前, 原门会拦掉该阶段通知）
- 测试 +2（stage 映射、failedStep 优先）→ 172 绿; 6 质量门全绿

## What Is Pending ⏳

1. **真机验证**（唯一阻塞项）：需你**手点**连接（MIUI 禁 adb 模拟点击 INJECT_EVENTS / BIND_VPN_SERVICE 拦 service 启动）——本机 debug 包可装 `cn.srv0.sshinjector.debug`，或下次发版后装 release。核验：
   - 四态：已连接 / 网络验证中 / 连接异常·步骤 / 连接失败·步骤（`logcat | grep "health probe"`）
   - 出口 IP：状态块「出口 IP」行 `-` → IP（`logcat | grep "exit ip"`）
2. **push + 发版**: `756f737` + 本次提交未 push（`--no-verify` 需你再次授权）; bump 1.0.8 → tag → CI（等你指令）, release 将含 出口 IP + 连接流程状态
3. GitHub dependabot: default branch 1 个 **moderate** 漏洞（v1.0.7 push 时 remote 提示，`…/security/dependabot/62`）
4. 残留（不阻塞）: Keystore 300s 根本解法=导入软件 Ed25519 key（用户未操作）

## Build & Verify Reference

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --console=plain \
  testDebugUnitTest ktlintCheck        # CI 分步跑
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew detekt   # 单独
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew lint     # 单独
ADB=/opt/android-sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
$ADB shell am force-stop cn.srv0.sshinjector.debug   # 必须! 旧进程跑旧代码
$ADB logcat -d | grep -E "health probe|exit ip"
# 发版: bump versionCode/versionName → chore: bump version to X.Y.Z
#       → git push --no-verify origin main（钩子缺 #N）→ git tag vX.Y.Z → git push origin vX.Y.Z
```

## Key Gotchas（本批次新增）

- **pre-push 钩子**（`~/.git-hooks/pre-push` ← forgejo-issue）: 每个上库提交须含 `#N`；范围=`origin/main..HEAD`；tag 推送被跳过（只查 `refs/heads/*`）。绕过: `git push --no-verify`（用户已授权一次——后续 push 前仍需确认）
- **Kotlin FQN 直呼 companion 函数编译不过**: 用注入实例或先 import 类名
- **`SSLSocketFactory.getDefault()` 返回 `javax.net.SocketFactory`**: 需 `as SSLSocketFactory` 收窄（已在 `openHttpStreams` 收敛）
- **MIUI 禁 adb 模拟点击**（INJECT_EVENTS）、shell 起 Service 被 `BIND_VPN_SERVICE` 拦——真机 UI 自动化不可行，需手点
- 新增 string 必须同步 values/en/ru/zh-rCN（`values/` 是中文默认）, 否则 lint MissingTranslation 挂
- ktlint: 括号内多行拼接要求 `(` 后换行——ByteArray 拼接优先写单行 val
- 测试里 wait/notify 要 `java.lang.Object()`（`Any()` 没有 wait）
- `ByteBuffer.compact()` 在 `clear()` 之后 = 毒化（Bug B 根因，已修+回归测试）
