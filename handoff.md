# Handoff — SSHInjector Session Summary

**Date:** 2026-09-30 (CST)
**Branch:** `main` | **HEAD:** `28e488a` + 本次文档提交 | **Version:** `1.0.7` (versionCode 7)
**Working tree:** 本次文档提交后 clean；**push 被 pre-push 钩子阻塞**（12+ 提交缺 `#N` issue ref，forgejo 不可用，用户已知）
**Device:** Redmi K50 Pro `TCEIPN4DVWRSNNRK`, adb `/opt/android-sdk/platform-tools/adb`, release 包 `cn.srv0.sshinjector`（已装 1.0.7）

## Session Goal（本批次）

1. ~~修复「VPN 已连接但无法访问网络」~~ ✅ Bug A/B 已修+真机验证（`99c13a5`/`1011824`）
2. **状态检测功能 connectivity-health** ✅ 实现+审计+全量文档更新，按层拆分 7 提交
3. 版本升级 1.0.7 ✅（`28e488a`）

## What Is Done ✅

### 已提交（本批次 8 个提交）

| Commit | 内容 |
|---|---|
| `99c13a5` | fix(vpn): Bug B — read buffer 毒化（compact-after-clear）+ 回归测试 |
| `1011824` | fix(vpn): Bug A — SOCKS5 应答 readFully + `Socks5ClientReadTest` |
| `ec51240` | feat(vpn): 解锁自动重连（hasUnhealthySession + ACTION_USER_PRESENT） |
| `3a066a8` | chore(release): proguard 仅剥离 Log.d |
| `e552665` | feat(model): HealthStep 枚举 + VpnState.verified/failedStep |
| `12b7f8b` | feat(domain): ConnectivityProber + HealthTracker + 16 测试 |
| `a7ffa20` | feat(vpn): VpnController.reportHealth + TUN 归因 + connect 重置 |
| `8130d0c` | feat(vpn): SshVpnService 探测循环/事件触发/连接期归因/通知 + probeUrl |
| `6b76645` | feat(ui): statusDisplay/Degraded 配色/设置项/4 语言/VM 映射测试 |
| `c22bf14` | docs: spec + AGENTS + evidence + env-check + handoff |
| `28e488a` | chore: bump version to 1.0.7 |
| (本次) | docs: README×3 / USER_GUIDE / vpn-state 图 / optimization-todo / todo-plan |

拆分原则：依赖顺序 model→domain→controller→service→ui→docs→chore，**每个提交点可独立编译**（已 checkout 验证最重的 `8130d0c`）。

### 质量门（audit 后复跑，全绿）

`testDebugUnitTest`(165) + `ktlintCheck` + `detekt` + `lint` + `assembleDebug` + `assembleRelease`

### audit（`docs/audit-report.md`，本地不入库）

判定通过：0 严重 / 4 中等已修（HealthTracker @Synchronized、buildStatusDisplay 迁 companion 可测、Degraded 无消费方断链、spec 5 处漂移）/ 5 低影响已处理。

## What Is Pending ⏳

1. **Push 解阻**：pre-push 钩子要求每个提交含 `#N`。选项：forgejo 恢复后补 ref / 用户授权 `--no-verify`。解阻后：
   ```bash
   git push origin main
   git tag v1.0.7 && git push origin v1.0.7   # 触发 CI build-release (APK/AAB + GitHub Release)
   ```
2. **真机四态验证**（T3/T4 验收未闭环）：已连接 / 网络验证中 / 连接异常·步骤 / 连接失败·步骤。
   - 1.0.7 已装（`adb install -r` + force-stop 完成）；**adb 模拟点击被 MIUI 禁用（INJECT_EVENTS），`am startservice` 被 BIND_VPN_SERVICE 拦** → 需要**手点**播放按钮发起连接
   - 连接后可协助核验：`logcat | grep "health probe"`（ok / failed: step=…）
   - 故障注入建议：飞行模式（→连接异常·SSH 连接）、改错探测地址（设置页）观察降级与恢复
3. 残留（不阻塞）：Keystore 300s 根本解法=导入软件 Ed25519 key（用户未操作）

## 文档同步记录（本次）

- `README.md` / `README.en.md` / `README.ru.md`：核心功能表 +状态检测行
- `docs/USER_GUIDE.md`：§6 状态检测四态表+探测节奏+D3 说明；§7 探测地址设置行；§8 排障首条=看步骤
- `docs/diagrams/vpn-state.html`：file:line 修正（connect catch :349-357 / autoReconnect :830,:871-878 / observeJsch :712 / forceReset MV:787 / VpnStatus 枚举 :79-85）+ verified/failedStep 叠加注记
- `docs/optimization-todo.md`：追加「v1.0.7 · 状态检测发布」记录
- `AGENTS.md`：Architecture 区状态检测 bullet（前次提交）
- `docs/todo-plan.md`（本地不入库）：T1–T7 全勾销

## Build & Verify Reference

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --console=plain \
  testDebugUnitTest ktlintCheck        # CI 分步跑
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew detekt
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew lint
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug assembleRelease
ADB=/opt/android-sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
$ADB shell am force-stop cn.srv0.sshinjector   # 必须! 旧进程跑旧代码
$ADB logcat -d | grep "health probe"           # ok (verified) / failed: step=...
```

## Key Gotchas（本批次新增）

- **Kotlin FQN 直呼 companion 函数编译不过**（`pkg.Obj.func()` → Unresolved）：用注入实例或先 import 类名
- **`SSLSocketFactory.getDefault()` 返回 `javax.net.SocketFactory`**（无 4 参 createSocket）：需 `as SSLSocketFactory` 收窄
- **MIUI 禁 adb 模拟点击**（INJECT_EVENTS SecurityException）、**shell 起 Service 被 `BIND_VPN_SERVICE` 签名权限拦**——真机 UI 自动化不可行，需手点
- 新增 string 必须同步 en/ru/zh-rCN，否则 `lint` MissingTranslation 挂
- 测试里 wait/notify 要 `java.lang.Object()`（`Any()` 没有 wait）
- `ByteBuffer.compact()` 在 `clear()` 之后 = 毒化（Bug B 根因，已修+回归测试）
