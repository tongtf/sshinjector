# TODO-PLAN: 白名单模式修复

> 日期: 2026-08-02
> 分支: feature/whitelist-mode-fix
> 状态: 基本完成（真机验证通过）

## 当前批次

### TODO 1：VPN 层 / 修复空白名单时全部流量进隧道

- 契约范围：`vpn/SshVpnService.kt` `establishVpnInterface()`
- 状态: `[ ]`
- 任务：
  - [ ] 当 `dnsMode==2 && allowedPackages.isEmpty()` 时，不添加 `0.0.0.0/0` + `::/0` 路由（VpnService 未设置 allowed list 时默认放行全部应用进 TUN）
  - [ ] 修正日志文案：空白名单时明确提示"未选择白名单应用，VPN 将透传全部流量（等价 SYSTEM）"
- 验收标准：空白名单下建立 VPN 接口，非白名单应用的流量不进入 TUN（逻辑上等价直连模式）

### TODO 2：VPN 层 / 自身应用加入白名单崩溃

- 契约范围：`vpn/SshVpnService.kt` `establishVpnInterface()`
- 状态: `[x]` （真机验证中发现并修复）
- 任务：
  - [x] `addAllowedApplication` 前过滤自身包名（`addDisallowedApplication(packageName)` 已排除自身）
  - [x] 捕获 `IllegalArgumentException` 并记录日志而非上抛导致连接失败
  - [x] **真机发现新增 bug**：异常类型实为 `UnsupportedOperationException`（非 `IllegalArgumentException`），且 `addDisallowedApplication` 与 `addAllowedApplication` 互斥——白名单模式下两者不能共存。已修复为：白名单模式（dnsMode==2 且白名单非空）**不调用 addDisallowedApplication**，仅用 addAllowedApplication 限定允许应用；catch 类型改为 `UnsupportedOperationException`
- 验收标准：自身应用加入白名单后连接仍成功，且自身流量不走 TUN

### TODO 3：VPN 层 / 白名单改动热更新

- 契约范围：`vpn/SshVpnService.kt`、`ui/screen/whitelist/WhitelistViewModel.kt`
- 状态: `[x]` （真机验证通过）
- 任务：
  - [x] WhitelistViewModel 暴露 `enabledPackages` 变化事件（或 SshVpnService 观察 Room 白名单表）
  - [x] 白名单变更时重建 VPN 接口（`Builder.establish()`），保留 SSH 隧道连接不中断
  - [x] 热更新失败时回退日志提示重连
- 验收标准：VPN 运行中修改白名单，10 秒内生效且隧道不断连
- **真机验证**：用户连续增删白名单（最终"已选 8"个应用），logcat 出现 14 次 VpnJni tun 接口重建，VPN 接口的 Uids 集合与白名单实时一致，SSH 隧道保持不断

### TODO 4：模式切换 / 运行中切到白名单模式生效

- 契约范围：`ui/viewmodel/MainViewModel.kt` `switchDnsMode()`、`domain/usecase/VpnController.kt` `updateDnsMode()`、`vpn/SshVpnService.kt`
- 状态: `[x]` （编码完成）
- 任务：
  - [x] `switchDnsMode` 支持循环 0→1→2→3（当前 `(current + 1) % 3` 无法到模式 3）
  - [x] 运行中切换模式时，除更新 DNS 层外，重建 VPN 接口使路由/白名单生效
  - [x] 或：模式切换需重连（明确提示用户）
- 验收标准：运行中切换到白名单模式，内核路由/白名单实际生效

### TODO 5：UI / 修复假的权限检查

- 契约范围：`ui/screen/whitelist/WhitelistScreen.kt` `checkPermission()`
- 状态: `[x]`
- 任务：
  - [x] 移除不存在的 `Settings.canManageAllPackages` 反射调用
  - [x] 用 `PackageManager` + `QUERY_ALL_PACKAGES`（已声明）判断能力，或直接允许加载
  - [x] 修正 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 错误 Intent（实际需要的是应用详情设置）
- 验收标准：应用列表可靠加载，无误导性权限引导

### TODO 6：测试 / 单元测试覆盖

- 契约范围：`app/src/test/`
- 状态: `[x]` （部分完成）
- 任务：
  - [x] 覆盖模式切换循环逻辑（0→1→2→3→0）— `DnsModeSwitchTest.kt`
  - [ ] 覆盖空白名单路由决策逻辑（若抽成纯函数）
- 验收标准：新增测试通过

## 完成后检查

- [x] `./gradlew :app:testDebugUnitTest :app:lintDebug` 通过
- [x] 真机验证白名单模式：白名单 app 走隧道，非白名单 app 直连（VPN Uids 与白名单实时一致，热更新正常）
