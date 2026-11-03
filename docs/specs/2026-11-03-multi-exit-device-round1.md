# 多出口路由 · 真机第一轮 (2026-11-03)

> 对象: `feat/multi-exit-routing` @ `1.3.0` (versionCode 21)。
> 现场条件: 无 adb, 证据只有导出的应用内日志; 白名单模式 (9 个应用), 主出口 `hk.srv0.cn`,
> 附加出口 `jz(a.srv0.cn)` / `bj(bj.srv0.cn)` / `hk(hk.srv0.cn)`。
> 结论: **多出口运行时本身正常** (三个附加出口可同时就绪), 本轮暴露的两条缺陷都与出口无关,
> 但都会让用户误判成"多出口坏了"。

## 1. 时间线 (两段日志)

| 时刻 | 事件 | 判读 |
|---|---|---|
| 10:20:43 | `SSH 会话创建失败 · pool-0 — Algorithm negotiation fail: algorithmName="kex"` | 出口 jz 建池失败 |
| 10:20:43 | `隧道插件启动失败 · socks5 — All 3 SSH sessions failed to connect` | jz 的插件启动失败 (名字也叫 socks5, **不是**主出口) |
| 10:20:45 / 10:20:49 | `出口 bj 已就绪` / `出口 hk 已就绪` | 另外两个出口正常 |
| 10:20:49 | `VPN 连接已建立` | 主出口 + 2 个附加出口 = 3 套 SOCKS 服务并存 |
| 10:21:57 | `隧道侧零回程 · www.gstatic.com:80 — SSH 写出 104B / 读回 0B · 存活 5126ms` | 探测连接远端未回字节 (已知偶发) |
| 10:38:51 | `出口 jz 已就绪 (a.srv0.cn) · 建连耗时 259ms` | kex 修复后 jz 正常 |
| 10:38:53 | `网络探测失败 · 隧道通道 — tunnel closed with 0B before status line (请求后 99ms)` | 远端 99ms 主动关通道 (非超时) |
| 10:39:53 | 快照 `出口 jz / 主出口 / 出口 hk / 出口 bj` 全 `Ready` | 四套出口并存, 无流量 (无规则) |

## 2. 缺陷 1: 出口 jz 永久建连失败 (kex)

### 证据

```
jschProposal   = diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256,
                 ext-info-c,kex-strict-c-v00@openssh.com
serverProposal = mlkem768x25519-sha256,sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com,
                 curve25519-sha256,curve25519-sha256@libssh.org,
                 ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,
                 ext-info-s,kex-strict-s-v00@openssh.com
```

白名单里**写了** `curve25519-sha256`, 客户端提案里却没有 → 被 JSch **静默过滤**。

### 根因

JSch 的 X25519 实现 (`DHXEC`) 通过反射加载 XDH provider; Android 上没有可用实现, 于是
`curve25519-sha256*` 在建提案时被丢掉。白名单原本只剩 `dh-group-exchange-sha256` /
`group14-sha256`, 而 jz 的 sshd (较新 OpenSSH) 只开 PQ / curve25519 / ecdh → 交集为空。

**与网络、与"能不能开多个 SOCKS"都无关** —— 同一时刻 bj/hk 用 group14 正常建连。

### 修复 (`0e149a4`)

- `KEX_ALGORITHMS` 补 `ecdh-sha2-nistp256/384/521` + `diffie-hellman-group16-sha512`;
  ECDH 在 Android 的 JCE 上可用 (`KeyAgreement("ECDH")`), 是 curve25519 缺失时的主力。
- 回归用例 `JschAlgorithmWhitelistTest.kex whitelist still negotiates a modern OpenSSH server
  without curve25519`: **按"curve25519 全部不可用"** 的真实条件与该服务器提案算交集, 空则失败。
- AGENTS「Key Gotchas」已记录 (JSch 升级/白名单调整前必读)。

## 3. 缺陷 2: 已连接时点另一台服务器 = 静默 no-op

### 证据

用户操作: 已连着 hk, 在服务器列表点第二台 → "点了没反应 / 连不上"; 日志里**没有任何新会话记录**。

### 根因

```kotlin
// 旧 SshVpnService.connect()
if (vpnController.isVpnRunning()) {
    Log.w("SshVpnService", "VPN already running")
    return          // 只有 logcat
}
```

同时 `MainViewModel.connect()` 先把该服务器乐观置成 `Connecting`, 服务端直接返回后
**不会再有 vpnState 事件**把它改回来 → 那一行永久转圈。两个问题叠加, 现场表现就是"连不上"。

### 修复 (`fbd581b`)

| 情形 | 行为 |
|---|---|
| 未连接 | 正常建连 |
| 已连同一台 | INFO `已连接该服务器 · 无需重复连接`; UI 不动 (ViewModel 侧提前 return) |
| 已连另一台 | `切换服务器 · A → B` → `autoReconnect(新配置)` (拆接口/重建/健康监控/通知), 先写 `lastServerId` + `setActiveServer` |

判定抽成纯函数 `connectIntent(running, currentId, targetId)`, 单测 `ConnectIntentTest`(4)。

## 4. 观察 (不是缺陷)

| 现象 | 判读 |
|---|---|
| `出口 jz: 0 连 · ↑0B ↓0B · Ready` | 出口就绪 ≠ 有流量; 没有规则指向它时全部走 default |
| `主出口(hk.srv0.cn)` 与 `出口 hk(hk.srv0.cn)` 同机 | 两套 SSH 池打同一 sshd (6 条会话); 建议主出口换机或删掉该出口 |
| `tunnel closed with 0B before status line (请求后 99ms)` | 远端秒关通道 (非本栈超时); 单次按抖动处理, 同域名反复才动 app |
| 快照 `应用分流 等待 0 · 拿到 uid 0 · 应用映射 0 条` | 未配置 `app:` 规则, 数据面不为 uid 等待 (符合设计) |
| 快照无 `未解析规则` | 当前没有规则引用未启动的出口 |

## 5. 本轮**未**覆盖 (合并 main 前的门禁)

S17–S30 只完成了"连接 + 出口就绪 + 快照观察"这一小段, 以下全部未验:

- **S17 关闭态基线** (必须与上一版快照逐字节 diff) —— 前置门禁, 未做;
- S19/S20 域名与 IP 规则分流 (需要有规则 + 两个不同出口 IP 的站点);
- S21/S22 出口/主出口故障语义, S23 资源泄漏, S24 DOMAIN_SPLIT + D-5;
- S25 按应用分流 (共享 uid 场景), S26 预算竞态;
- S27 快速失败重试, S28 改名/删除联动, S29 健康恢复节拍, S30 软上限。

判据见 `2026-10-31-multi-socks5-routing-acceptance.md` §5 与
`2026-10-18-device-verification-checklist.md` 的 S17–S30 段。
