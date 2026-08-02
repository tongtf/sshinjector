# TODO-PLAN: 仪表盘/默认服务器/隧道插件管理 UI 修复

> 日期: 2026-08-02
> 分支: feature/whitelist-mode-fix
> 状态: 规划中（暂不实施，先立项）

## 背景

白名单模式修复完成后的遗留 UI 问题，共 3 项。均为用户体验/一致性缺陷，非功能阻断。

## TODO 1：仪表盘 DNS/代理模式切换状态补全

- 契约范围：`ui/screen/dashboard/DashboardScreen.kt`、`ui/viewmodel/MainViewModel.kt`
- 状态: `[ ]`
- 问题：
  - `MainViewModel.kt:121-126` 与 `154-159` 的 `dnsMode` 文本映射只有 `0->远程代理, 1->本地直连, 2->自动模式`，模式 3（DOMAIN_SPLIT）落入 else 被错误显示为"远程代理"
  - 模式 2 文案"自动模式"名不副实（实际是白名单模式）
  - `DashboardScreen.kt:116-121` 的徽章颜色 `when` 只匹配 3 种文案，模式 3 走默认灰
- 任务：
  - [ ] 补全 4 模式映射：`0 远程代理 / 1 本地直连 / 2 白名单模式 / 3 域名分流`（改 `MainViewModel` 两处，提取为共享纯函数避免重复）
  - [ ] `DashboardScreen` 徽章颜色为"域名分流"补充配色（与 SshVpnService 日志文案一致）
  - [ ] 确认 `switchDnsMode` 循环 `0→1→2→3` 在仪表盘点击后 4 个状态都能切换显示
- 验收标准：仪表盘 DNS 徽章点击可循环遍历 4 种模式，文案与颜色均正确，模式 3 不显示为"远程代理"

## TODO 2：仪表盘默认服务器关联逻辑与服务器管理兼容性

- 契约范围：`ui/screen/dashboard/DashboardScreen.kt`、`ui/viewmodel/MainViewModel.kt`、`ui/screen/server/ServerEditViewModel.kt`、`ui/screen/server/ServerListViewModel.kt`、`domain/usecase/ServerRepository.kt`
- 状态: `[ ]`
- 问题（已排查到根因）：
  - 仪表盘"连接"按钮用 `hasDefaultServer` + `defaultServerId`（来自 `activeServerFlow`），而 `SshVpnService.connect()` 成功后 `setActiveServer(serverId)` 会改 active——两条路径可能不同步
  - `ServerEditViewModel.save()` 只有 `setAsDefault=true` 才调 `serverDao.setActive(id)`；`setAsDefault=false` 时**不 deactivate 其他服务器**，且编辑现有 active 服务器时若取消勾选，会导致**多个或零个 isActive=1**（`setActive` 依赖单条 update 的 CASCADE 语义，但编辑流程绕过了它）
  - `ServerListViewModel.connect(id)` 直接连任意服务器但不更新 isActive，断开后 `isActive` 状态可能与实际连接不一致
- 任务：
  - [ ] 梳理"默认服务器"唯一性约束：`setActive` 语义（一个且仅一个 active）应在保存/连接/断开三处一致
  - [ ] 修复 `save()`：`setAsDefault=true` 时先 `deactivateAll()` 再 `setActive(id)`（或保证 `setActive` 的 CASE 原子性）；`setAsDefault=false` 时若该服务器当前是 active 需处理降级
  - [ ] 仪表盘 `connectDefaultServer()` 与服务器列表 `connect(id)` 收敛到同一入口，active 状态单一数据源
  - [ ] 校验"断开后"与"删除 active 服务器后"的 active 状态一致性（删除当前 active 后应有 fallback）
- 验收标准：切换默认服务器后数据库恒有且仅有一个 isActive=1；仪表盘"连接"始终指向正确的默认服务器；删除/编辑 active 服务器不产生悬空状态

## TODO 3：隧道插件管理重新设计

- 契约范围：`ui/screen/server/TunnelConfigForm.kt`、`ui/screen/server/ServerEditScreen.kt`、`ui/screen/server/ServerEditViewModel.kt`、`data/remote/tunnel/*.kt`、`di/TunnelModule.kt`
- 状态: `[ ]`
- 问题（已排查到根因）：
  - `TunnelTypeSelector` 用硬编码 `TUNNEL_TYPES` 列表（`TunnelConfigForm.kt:52-59`），与 `TunnelModule.kt` 实际注册的插件可能漂移（新增插件需同步改 UI，违反单一来源）
  - **`configDescriptor` 字段从未在表单渲染**：各插件（https_proxy/v2ray/trojan/shadowsocks）定义了 `ConfigField`（serverHost/port/password/sni 等），但 `ServerEditScreen` 和 `TunnelConfigForm` 完全不消费 `configDescriptor`，也未读写 `tunnelConfigJson`
  - `TunnelConfig`/`tunnelConfigJson` 字段在 UI 层无编辑入口，插件特定配置无法持久化与回显
  - 无"测试隧道/查看能力标签（TCP/UDP/TLS）"等管理能力，体验简陋
- 任务：
  - [ ] 设计隧道管理新交互（独立于服务器编辑或嵌入编辑页）：类型选择 + 按 `configDescriptor` 动态渲染字段 + 配置 JSON 持久化回显
  - [ ] 以 `TunnelPlugin.configDescriptor` 为唯一字段来源，移除硬编码 `TUNNEL_TYPES` 或改为由插件注册驱动
  - [ ] `ServerEditViewModel`/表单支持 `tunnelConfigJson` 读写（新增字段时回显、保存时序列化）
  - [ ] 展示插件能力标签（`TunnelCapability`）与默认值提示
  - [ ] 评估是否抽独立"隧道管理"入口页（结合 RouteSettingsScreen 已有路线配置能力）
- 验收标准：选择 v2ray/trojan/shadowsocks 等隧道时可编辑对应插件字段并持久化；插件列表由注册表驱动，新增插件无需改 UI 硬编码

## 完成后检查

- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug` 通过
- [ ] 真机验证：仪表盘模式切换 4 状态、默认服务器增删改查一致、隧道插件字段可编辑保存
