# Handoff — SSHInjector Session Summary

**Date:** 2026-09-30 (CST)
**Branch:** `main` | **HEAD:** `3a066a8`（origin/main 落后 8+ commits, push 被 pre-push 钩子阻塞）
**Working tree:** ⚠️ **dirty** — 状态检测功能已实现且 6 门禁全绿, **未提交**（见下）
**Device:** Redmi K50 Pro `TCEIPN4DVWRSNNRK`, adb `/opt/android-sdk/platform-tools/adb`, release 包 `cn.srv0.sshinjector`

## Session Goal

1. ~~修复「VPN 已连接但无法访问网络」~~ ✅ 已修+真机验证（用户: "可以联网了"）
2. **状态检测功能**（dev-workflow: explore → brainstorm → spec → implement → **audit 待做**）：
   UI 仅在真正可用时显示 Connected；失败时显示断在哪一步（SSH/认证/本地代理/隧道/DNS/远端/虚拟网卡）
3. 门禁全绿后提交、真机验证、（forgejo 恢复后）push

## What Is Done ✅（已提交, 4 commits）

- `99c13a5` fix(vpn): Bug B — read buffer 毒化（`handleRead` 尾部 compact-after-clear）+ 回归测试
- `1011824` fix(vpn): Bug A — SOCKS5 应答 readFully 半包修复 + `Socks5ClientReadTest`
- `ec51240` feat(vpn): 解锁自动重连（`hasUnhealthySession()` + `ACTION_USER_PRESENT`）
- `3a066a8` chore(release): proguard 仅剥离 `Log.d`
- 真机日志核验: `remaining=32768` / `channel is broken` 归零, 23× TCP established, 7× 正常 EOF

## 状态检测功能（工作区, 未提交, 6 门禁全绿）

**规格**: `docs/specs/2026-09-30-connectivity-health-spec.md`（D1 端点可配置 / D2 15s+连败2才降级 / D3 仅展示不杀连接 / D4 连接期失败同归因）

| 文件 | 内容 |
|---|---|
| `domain/model/DomainModels.kt` | 新 `HealthStep` 枚举(label); `VpnState` +`verified` +`failedStep` |
| `domain/vpn/ConnectivityProber.kt`（新） | 手写 SOCKS5 0x02 握手 + CONNECT 域名类型 0x03 + http/https; 阶段归因: 握手→PROXY, CONNECT 等待超时→TUNNEL, rep≠0/HTTP 失败→REMOTE; 默认端点 `connect.rom.miui.com/generate_204` |
| `domain/vpn/HealthTracker.kt`（新） | 连败 2 次才降级, 1 成即恢复, `immediate`(SSH 池不健康)首败即降级 |
| `domain/usecase/VpnController.kt` | `reportHealth()`; connect 重置; packetLoop TUN 失败归因 `HealthStep.TUN` |
| `vpn/SshVpnService.kt` | 15s 探测循环(`startHealthMonitor`/`runHealthProbe`), 解锁/网络切换触发即测, `mapConnectFailureToStep`, 通知栏文案跟随(已连接/网络验证中/连接异常·步骤) |
| `ui/viewmodel/MainViewModel.kt` | `UiState.statusDisplay`（两处收集器）; serverConnectionStatus `Degraded` |
| `ui/screen/dashboard/DashboardScreen.kt` | 状态文字 → `state.statusDisplay` |
| `ui/screen/settings/*` | 探测地址 OutlinedTextField + `probeUrl`（DataStore 可配置, 空=默认） |
| `data/local/preferences/SettingsDataStore.kt` | `KEY_PROBE_URL` / `probeUrl` / `setProbeUrl` |
| strings ×4（values/en/ru/zh-rCN） | `settings_probe_url(_hint)` 翻译齐 |
| 测试（新 15 个, 全过） | `HealthTrackerTest`(7) 降级节奏; `ConnectivityProberTest`(8) 真实 Socks5ProxyServer+假隧道: Ok/凭据错/代理拒连/无通道 rep≠0/静默 CONNECT→TUNNEL/垃圾响应/非法 URL |

**门禁**: `testDebugUnitTest`(165 全过) + `ktlintCheck` + `detekt` + `lint` + `assembleDebug` + `assembleRelease` ✅（audit 后复跑）

**audit 已完成**（`docs/audit-report.md`）: 0 严重 / 4 中等已修（HealthTracker @Synchronized、buildStatusDisplay 迁 companion+4 测试、Degraded 消费方、spec 5 处对齐）/ 5 低影响已处理（EOF 用例、CancellationException rethrow、todo-plan 勾销、AGENTS.md、文件长度留债）。判定: **通过, 可进 grill**。

**关键实现注意**:
- 探测走 loopback→SSH（不经 TUN, 任何分流模式路径一致）; CONNECT 用域名交远端解析, 不依赖本地 DNS
- `runHealthProbe` 里 JschSshClient 用**注入实例** `jschSshClient.xxx()`（FQN 直呼 companion 成员编译不过）
- `disconnect(userInitiated=true)` 清健康归因; 失败路径 `disconnect(false)` 后由调用方补写归因（顺序不能反）
- 探测失败**只降级显示**, 不杀连接（D3）; 自动重连仍走解锁/网络切换/keepAlive 既有钩子

## What Is Pending ⏳（按序）

1. ~~**dev-workflow audit**~~ ✅ `docs/audit-report.md` 判定通过
2. **grill**（人工复核, ≤30 问题只沟通重大问题——本次审计无严重问题, 预期快过）→ 或用户直接放行
3. **真机验证**: 装 release（`adb install -r` + **force-stop**）, 验证四种显示: 已连接 / 网络验证中 / 连接异常·步骤 / 连接失败·步骤（故障注入: 杀远端 sshd、断网、改错 socksPort）
4. **提交**: 建议 `feat(vpn): end-to-end connectivity health probe with step attribution`（含 docs/ 规格文档要不要一并提交由用户定）
5. **Push 阻塞（用户: "forgejo不可用, 先不管这个"）**: pre-push 钩子要求 `#N`, forgejo `code.srv0.cn` 不可用
6. 残留（不阻塞）: Keystore 300s 窗口根本解法=导入软件 Ed25519 key, 用户尚未操作; 探测端点真机首次验证(默认端点可达性)

## Build & Verify Reference

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --console=plain \
  testDebugUnitTest ktlintCheck        # CI 分步跑
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew detekt
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew lint
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug assembleRelease
ADB=/opt/android-sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/release/sshinjector-<ver>-arm64-v8a.apk
$ADB shell am force-stop cn.srv0.sshinjector   # 必须! 旧进程跑旧代码
# 探测日志: logcat tag SshVpnService → "health probe ok (verified)" / "health probe failed: step=..."
```

## Key Gotchas（本次新增）

- **Kotlin FQN 直呼 companion 函数编译不过**（`pkg.Obj.func()` → Unresolved）: 用注入实例或先 import 类名
- **`SSLSocketFactory.getDefault()` 返回 `javax.net.SocketFactory`**（无 4 参 createSocket）: 需 `as SSLSocketFactory` 收窄
- ktlint: 构造参数/多行表达式必须换行; `else if` 嵌套表达式改 `when`; 声明前注释需空行
- 新增 string 必须同步 en/ru/zh-rCN, 否则 `lint` MissingTranslation 挂
- 测试里 wait/notify 要 `java.lang.Object()`（`Any()` 没有 wait, 编译警告可忽略）
