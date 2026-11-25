# 门禁核验 (2026-11-25)

> 范围：机器门禁（CI / ktlint / detekt / Android Lint / 单测 / 构建）与验收门禁（S17–S35 / L0–L4）。
> 方法：读 workflow 与实际配置，对照本分支 42 个提交的改动面，并核对 GitHub Actions 运行记录。

## 0. 结论

**门禁方向正确，但存在 6 个实质缺口**：其中 3 个会让"门禁通过"不等于"代码可用"
（PR 不跑 release、androidTest 从不运行、无覆盖率下限），2 个是流程性缺口
（主分支可 force-push 抹掉红状态、feature 分支从不触发 CI），1 个是规则未机器化
（L2-4 四语言一致性）。本轮已修其中 4 个可代码化的项。

## 1. 机器门禁现状

| 门禁 | 配置 | 评价 |
|---|---|---|
| ktlint | `ktlintCheck`（main+test，android=true） | ✅ 有效；本分支被拦过多次 |
| detekt | `maxIssues: 0`（warnings 也计入） | ✅ 严格 |
| Android Lint | `./gradlew lint`，默认只对 error 失败 | ⚠️ warning 不拦（可接受） |
| 单测 | `testDebugUnitTest`，逐用例打印 + 失败 annotation + 报告 artifact | ✅ 设计良好 |
| Debug 构建 | `assembleDebug` | ✅ |
| Release/R8 | 仅 tag 触发 `build-release` | ❌ PR 不验证 |
| androidTest | **CI 从不运行**（4 个文件） | ❌ Room 迁移/Keystore/DB 零自动化 |
| 覆盖率 | 无 JaCoCo/Kover | ❌ 568 个用例≠覆盖率 |
| 依赖漏洞 | 仅 Dependabot 提示 | ⚠️ 无 PR 门禁 |
| SDK 供应 | 原为 `platforms;android-34`，项目 `compileSdk = 37` | ✅ 已修为 37/37（此前靠 AGP 自动下载） |
| 触发范围 | main/develop、tag、PR；**feature 分支不跑** | ⚠️ 已加 `workflow_dispatch`；建议开 Draft PR |

## 2. 主要发现

| # | 严重度 | 发现 | 证据 | 处理 |
|---|---|---|---|---|
| F1 | **高** | PR 不跑 release：R8/ProGuard 问题只会在打 tag 时暴露 | `build-release` 仅 `refs/tags/v*` | ✅ `build-debug` 增加 `assembleRelease`（debug-key 回退，仅编译验证） |
| F2 | **高** | androidTest 从不运行；`DatabaseIntegrationTest` 可能已腐烂 | workflow 只跑 `testDebugUnitTest` | ⚠️ 建议 nightly/手动 emulator job |
| F3 | **高** | 无覆盖率下限；`VpnController`/`SshVpnService` 编排无直接测试 | 无 jacoco/kover 配置 | ⚠️ 建议加 Kover + 分模块阈值 |
| F4 | **高** | 主分支可 force-push：2026-11-24 的红色 CI 属于孤儿提交 `7a3309a`，已不在仓库；当前 main tip 自己的上次绿色运行是 2026-10-19 | GitHub API 运行记录 | ⚠️ 仓库设置：禁 force-push、要求 status checks、禁直推 |
| F5 | 中 | feature 分支推送不触发 CI，本分支 42 个提交只有本地门禁 | `on.push.branches` | ✅ 加 `workflow_dispatch`；建议开 Draft PR |
| F6 | 中 | SDK 供应与 `compileSdk 37` 不一致 | workflow `android-34` vs `compileSdk = 37` | ✅ 修为 37/37（此前 10-26 的绿色说明 AGP 自动下载可用，但慢且不确定） |
| F7 | 中 | L2-4「四语言」无机器校验；人工核对把 `translatable="false"` 误判成漏翻译 | B6 报告 `settings_license_content` 缺失（实为 `translatable=false`） | ✅ 新增 `StringResourceParityTest`（2 例，忽略 `translatable=false`） |
| F8 | 中 | 无依赖漏洞 PR 门禁；默认分支现存 1 个 high | Dependabot 提示 | ⚠️ 建议 `dependency-review-action` |
| F9 | 低 | L4 标题仍写 S17–S30，实际已扩到 S35 | acceptance 文档 | ✅ 改为 S17–S35 |
| F10 | 低 | 验收缺今天修复对应的场景（向导/默认开关/迁移/Keystore/上行滞留/启动中禁用/看门狗/R8/删最后服务器） | acceptance §5 | ✅ 新增 S36–S44 |
| F11 | 低 | CI 无 `concurrency` 取消（重复推送浪费 runner） | workflow | ✅ 已有（在文件末尾，统一移到顶部） |
| F12 | 低 | `unitTests.isReturnDefaultValues = true` 会掩盖 Android stub 误用 | build.gradle | ⚠️ 记录 |

## 3. 单测覆盖分布（568 例）

| 区域 | 测试文件数 | 评价 |
|---|---|---|
| `domain/vpn`（数据面） | 34 | ✅ 主战场覆盖好（窗口闸门/关闭语义/上行 flush/假 IP/解析） |
| `data/remote/ssh` | 10 | ✅ 算法/预算/取消清理/加密 |
| `domain/vpn/routing` | 6 | ✅ 规则/优先级/提升/对账 |
| `domain/usecase` | 5 | ⚠️ 仅纯逻辑（删除计划/策略），**VpnController 编排未测** |
| `data/remote/config` | 4 | ✅ 供给/脚本/公钥 |
| UI | 8 | ⚠️ 多为纯函数/格式化；Compose 交互未测 |
| `vpn`（Service） | 1 | ❌ 仅 `SessionLock`；connect/disconnect/观察者未测 |
| `data/local` | 1 | ⚠️ schema 文本比对，无运行时迁移测试 |

**结论**：数据面（最易出悬挂/竞态）覆盖最好；**状态编排与服务生命周期是盲区**，恰好也是今天问题最集中的地方。

## 4. 验收门禁（L0–L4）

- **L0** 编译/DI：由 `assembleDebug` + Hilt 编译覆盖。
- **L1** 单测清单：与 568 例基本对齐，但未强制"新增类必须有测试"。
- **L2** 四项静态门禁：CI 已独立跑，✅；L2-4 已机器化。
- **L3** 零行为变化：依赖人工 `diff` 快照（S17），无自动化基线。
- **L4** 真机：S17–S35 + 新增 S36–S44；仍缺长期浸泡、进程被杀、IPv6-only、弱网。

## 5. 建议但未实施（需要仓库权限/更大改动）

1. **分支保护**：main 禁 force-push、要求 `Lint & Test` 与 `Build Debug APK` 通过、禁直推。
2. **androidTest 定时任务**：nightly 或手动 `connectedDebugAndroidTest`（至少覆盖 Room 迁移与 Keystore）。
3. **覆盖率门禁**：Kover 报告作为 artifact + 对 `domain/vpn`、`domain/usecase` 设最低阈值（建议先只报告不卡，避免一次性红）。
4. **依赖审查**：PR 加 `dependency-review-action`，高危直接阻断。
5. **L3 自动化**：把"关闭态快照"做成 golden 文件（现有 `DataPlaneSnapshotTest` 已接近，可扩展为整段文本基线）。
6. **验收脚本化**：S17–S44 的日志断言可抽成 `scripts/verify-log.py`，减少人工判读偏差。

## 6. 本轮改动

- `.github/workflows/ci.yml`：SDK 37/37、`workflow_dispatch`、PR 增加 release 编译、concurrency 归位。
- `StringResourceParityTest`：四语言 key 一致性（忽略 `translatable="false"`）。
- `acceptance`：L4 标题扩到 S35；新增 S36–S44。
- 未改：分支保护、Kover、androidTest job、dependency-review（见 §5）。
