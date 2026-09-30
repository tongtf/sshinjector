# Spec — 连接状态检测 / 步骤级健康归因 (connectivity-health)

**Date:** 2026-09-30 · **批次:** B1 · **状态:** 定稿（brainstorm 4 项决策全按推荐）

## 需求

1. 只有网络**实际可用**才显示 Connected（已建立 ≠ 已验证）；验证中显示「验证中」，失败显示「连接异常 · <步骤>」。
2. 不可用时显示**具体故障步骤**；连接阶段（Connecting）失败同样归因（统一步骤枚举）。

## 决策（brainstorm 定稿）

| # | 决策 | 结论 |
|---|------|------|
| D1 | 探测端点 | 默认 `http://connect.rom.miui.com/generate_204`，**可配置**（SettingsDataStore 全局 `probeUrl`，null=默认）；收到任意 HTTP 响应状态行（204/200/3xx…）= 端到端可用 |
| D2 | 节奏 | 15s 周期 + 事件触发（连接成功/网络切换/解锁/重连成功）；**连败 2 次降级，1 次成功恢复** |
| D3 | 失败处置 | **仅显示**；自动重连继续走既有钩子（解锁/网络切换/keepAlive），不因探测失败杀连接 |
| D4 | 连接阶段归因 | 要，统一步骤枚举；复用 `lastError`/异常消息映射 |

## 模型

```kotlin
enum class HealthStep { SSH, AUTH, PROXY, TUNNEL, DNS, REMOTE, TUN }
// VpnState 新增:
//   verified: Boolean = false        — Connected 且首次探测成功才 true
//   failedStep: HealthStep? = null   — 当前故障步骤（运行期探测失败 / 连接期失败归因）
```

UI 映射：`Connected && verified && failedStep==null` → 已连接；`Connected && !verified` → 网络验证中；`Connected && failedStep!=null` → 连接异常 · 文案；`Failed && failedStep!=null` → 连接失败 · 文案。

步骤文案（zh-rCN）：SSH=SSH 连接，AUTH=身份认证，PROXY=本地代理，TUNNEL=隧道通道，DNS=DNS 解析，REMOTE=远端网络，TUN=虚拟网卡。

## ConnectivityProber（domain/vpn）

- 阻塞式 `probe(): Result`，跑在 IO/专用 dispatcher；`Result.Ok` / `Result.Failed(step, reason)`
- 路径：裸 `Socket` 连 `127.0.0.1:socksPort` + **手写 SOCKS5 握手**（与 `TcpStateMachine` 同款：`05 01 02` + `plugin.socksAuth` 凭据 + CONNECT **域名类型 0x03**，远端解析，绕开本地假 IP DNS）；随后发 `GET <endpoint> HTTP/1.1 Host:…`，读状态行（不走 `java.net.Proxy`——其自动握手无法按阶段归因）
- 步骤归因：
  - 代理端口拒绝/非 05 版本/认证失败 → `PROXY`（含 greet/auth 阶段超时）
  - CONNECT 回复等待超时（远端通道建立卡住）→ `TUNNEL`
  - CONNECT 回复 rep≠0（0x03/0x04/0x05…）→ `REMOTE`（远端到目标网络不通）
  - 通道建立后 HTTP 读超时/EOF/无状态行 → `REMOTE`
  - 本地 DNS 不参与（域名交远端），DNS 步骤保留给连接期归因
- 白名单/REMOTE/分流模式无差异：探测走 loopback → SSH 出口，与模式无关

## HealthTracker（domain/vpn，纯逻辑可单测）

`onSuccess()` / `onFailure(step, immediate=false)` / `reset()`，内部 consecutive 计数（方法 `@Synchronized`——周期循环与事件触发探测可能并发回调）；ok → verified=true, failed=0；fail×2 → failedStep=step；`immediate=true`（SSH 池已知不健康）首败即降级。调用方（service）读 `verified/failedStep` 后经 `vpnController.reportHealth` 发布。

## SshVpnService 集成

- 连接成功（含 reconnect）→ `verified=false` + 立即探测 + 启动 15s 循环（`healthJob`，disconnect 时 cancel）
- 事件触发重探测：网络切换 callback、`ACTION_USER_PRESENT`、重连成功
- D3：探测失败只经 `vpnController.reportHealth` 更新展示状态（`serviceVpnState` 由既有 observer 镜像），不触发 autoReconnect
- 连接期归因（D4）：`connect()`/`autoReconnect()` catch → `mapConnectFailureToStep(异常消息)`（Auth fail→AUTH、establish/interface/tun→TUN、proxy/socks/in use→PROXY、dns→DNS、其余→SSH）；`observeJschConnectionState` Failed 经 autoReconnect 成败间接归因；`packetLoop` TUN 失败直接归因 `HealthStep.TUN`
- 通知栏文案跟随状态（验证中/已连接/异常·步骤）

## 配置

- `SettingsDataStore.probeUrl: String?`（null=默认常量）；设置界面字段（存在全局设置界面则加，否则本批次仅 DataStore+代码默认，UI 后补）

## 测试（T5）

- `ConnectivityProberTest`：仿 `Socks5AuthTest` 起真 `Socks5ProxyServer`+FakeTunnel——成功（隧道回 HTTP 204）、代理关闭（PROXY）、凭据错（PROXY）、隧道拒连（rep=0x05→REMOTE）、原始 SOCKS 服务端 CONNECT 静默→超时（TUNNEL）、垃圾响应/隧道 EOF（REMOTE）、非法 URL（REMOTE）
- `HealthTrackerTest`：连败 2 降级 / 1 次恢复 / immediate 首败即降级 / 计数清零
- MainViewModel 映射：`MainViewModel.buildStatusDisplay`（companion 纯函数，按现有测试形态落于 `MainViewModelFormatTest`）

## 非目标

- 不因探测失败自动断开/重连（D3）
- 不做每服务器独立探测端点（全局配置即可）
- 不动 `JschSshClient` keepAlive 语义
