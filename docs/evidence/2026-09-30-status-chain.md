# Evidence — 状态链路现状（explore 2026-09-30）

## 「已连接」是怎么来的（误报根源）

1. **设置即 Connected**：`SshVpnService.startVpn` 在 `vpnController.connect()` 成功（SSH 池 3/3 + TUN 建立 + SOCKS 代理启动）后直接 `serviceVpnState = Connected`（`SshVpnService.kt:251`，reconnect 路径 `:744` 同）——**没有任何端到端可用性验证**。通知栏同一时刻写死「已连接」（`:471`）。
2. **半开 socket 检测不到**：`JschSshClient.isConnectedFlag` 在死 socket 上仍为 true（`SshVpnService.kt:95` 注释明说此问题）；`checkPoolHealth`（`JschSshClient.kt:594`）仅当 **全部** session `!healthy && !connected` 才置 Failed。单条/部分 session 死不触发。
3. **隧道层故障 UI 无感知**：Bug B 事件中 SSH 池全绿、TUN 正常，但远端通道 ~1s 内 EOF——`relayFromTarget: EOF from tunnel` 只有 W 日志，不回传到任何状态。UI 全程显示 Connected。
4. **状态枚举**：`VpnState.VpnStatus` 5 态 Disconnected/Connecting/Connected/Disconnecting/Failed（`DomainModels.kt:59`）+ `error: String?`；`JschSshClient.ConnectionState` 独立 6 态（含 Authenticating），经 `observeJschConnectionState` 汇入。

## UI 消费点

- `MainViewModel.kt:324`：`state.status == Connected` → `isConnected=true`、`connectionStatus="Connected"`、代理地址展示；`:375`、`:804` 同模式（双份收集逻辑，疑似可合并）
- 主界面状态区 + 服务器列表项（`serverId -> status` 映射）；通知栏由 Service 自己 build
- 中文文案「已连接」在 `SshVpnService.kt:471` 硬编码（strings.xml 未覆盖服务通知）

## 可复用的既有设施

- **探测出口已存在**：本地 SOCKS5 `127.0.0.1:socksPort`（`TcpStateMachine` 同款出站路径），探测器可用普通 `Socket`/`HttpURLConnection` 设 `SOCKS` 代理直连——复用生产路径，不新开特权通道
- **触发钩子已存在**：网络切换回调（`registerNetworkCallback`）、解锁广播（本批次新加 `ACTION_USER_PRESENT`）、keepAlive 周期、`autoReconnect` 退避
- **步骤素材**：`connectionState`（Authenticating/Connected/Failed）+ `lastError.value`（Auth fail / All SSH sessions lost 等）；`Socks5ProxyServer` 有 `Status.Stopped..Running` 枚举
- **测试基建**：`Socks5AuthTest` 模式（真起本地 proxy + mock TunnelChannel）可仿写探测器测试

## 需要新做的（缺口）

- 端到端探测本身（经隧道拿任意 HTTP 响应 = 可用；被墙端点会误报，端点需可达性友好）
- 失败→步骤的归因模型（哪一步：SSH / SOCKS / 隧道通道 / 远端出口）
- 连败阈值/恢复/降级的状态转换（防闪断误报）
- UI/通知的步骤级文案 + 状态降级显示
