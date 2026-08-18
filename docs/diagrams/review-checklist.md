# 图集审查清单

生成时间：2026-08-06 · 工作流：dev-workflow explore → design → audit

## 审查说明

每张图均对照源码调用链（file:line）逐一核验。下方记录已执行的图逻辑校验项与结论。

## 校验项

### 架构图 `architecture-system.html`
- [x] 层划分与代码包结构一致（UI → domain/usecase → domain/vpn → data/remote）
- [x] 主数据流方向：TUN → PacketProcessor → Socks5ProxyServer → JschSshClient → 远端
- [x] 支撑流（配置/密钥）为虚线，不与数据流混淆
- [x] 交叉线已桥接/错开（VpnController→LocalStore 与 PacketProcessor→Socks5 平行错 8px）

### 状态机图
- `vpn-state.html`
  - [x] 状态枚举核对：Authenticating/EstablishingTunnel/Reconnecting 为死代码已标注
  - [x] 转移事件名与代码一致（connect()/disconnect()/onRevoke/网络切换/forceReset 3s 兜底）
  - [x] Failed 为 VpnService 层设置，单标注 `* → Failed`（非逐状态画线）
- `socks5-state.html`
  - [x] 握手无认证路径（processAuthMethods 直通）已标注；AuthMethods 枚举保留不参与流转
  - [x] 超时参数（连接 10s / 空闲 300s）标注
  - [x] UDP ASSOCIATE 空回复问题已在图中提示
- `ssh-reconnect-state.html`
  - [x] 退避公式 min(1s·n, 10s) 与上限 5 次核对（JschSshClient.kt:380,384）
  - [x] 原子替换池成员语义正确

### 流程图
- `packet-flow.html`
  - [x] 决策分支覆盖：排除路由 → DOMAIN_SPLIT 直连 → 协议分派
  - [x] 透传合并路径已修正（绕行不穿 ICMP 节点）
- `vpn-connect-flow.html`
  - [x] 生物识别 / VPN 授权门控分支汇入点已修正（避免与主链竖线重叠）
- `tcp-flow.html`
  - [x] seq 校验（重传丢弃/乱序快重传）已在脚注说明
  - [x] 交叉线已加 hop 桥接（rep 失败路径）
- `dns-flow.html`
  - [x] useSystemDns 决策三种情形（SYSTEM / SPLIT 未命中 / REMOTE+WHITELIST）
  - [x] 缓存路径已加 hop 跳过系统 DNS 横线
- `provisioning-flow.html`
  - [x] 六步与 ServerProvisioning.Step 枚举一致；四类失败出口标注
- `udp-flow.html`
  - [x] UDP 中继不可用的已知问题以醒目块标注
- `key-flow.html`
  - [x] 三来源 + 硬件/非硬件分支与 createJSchIdentity 逻辑一致

### 时序图
- `vpn-start-sequence.html`
  - [x] 参与者与代码边界一致；消息顺序 = 真实调用链
  - [x] 无向上消息、激活条闭合
- `tcp-data-sequence.html`
  - [x] 注册回向回调在 CONNECT 请求之前（TcpStateMachine.kt:255-262）标注
  - [x] 出向背压（Channel cap=32 → 单槽 → 暂停 OP_READ）与回向直通均呈现
- `dns-sequence.html`
  - [x] REMOTE 假 IP 与 SYSTEM protect-socket 两段消息流正确
  - [x] 恢复查询 ID / 缓存 / ipToDomain 步骤齐全

## 结论

- [x] 流程图无不可达节点 / 死循环；每个决策有出口
- [x] 状态机初态/终态正确、无终态回流、转移有标签
- [x] 时序图消息均有发送/接收方、异步路径为虚线
- [x] 图与图交叉一致（流程图↔时序图↔状态机图互通）
- [x] 无幽灵参与者（均能在代码中找到对应类）

**审查通过**，未发现致命逻辑缺陷。

## 追加复核（2026-08-18 · dev-workflow audit → 修复）

代码层面本次审计修复涉及多图描述的流程，逐图回读确认无漂移：

- `tcp-data-sequence.html` / `tcp-flow.html`
  - [x] 出向背压新增 `backpressureLock`（单槽/full 标志/OP_READ 切换原子化）与 `pendingConnectData`（buffer 不跨线程）——图脚注已同步
- `socks5-state.html`
  - [x] Connecting 阶段新增「池饱和拒绝」路径（`SshIoDispatcher.isSaturated()` → sendErrorReply+close）——节点副文本已同步
- `ssh-reconnect-state.html`
  - [x] 整体 autoReconnect 增加 10s 退避窗（`POOL_FAIL_RECONNECT_BACKOFF_MS`）；重连成功后旧 scope 取消——图注已同步
- `dns-flow.html` / `dns-sequence.html`
  - [x] 断开时 `clearPendingResponses()` 排空残留响应；假 IP 计数器 `updateAndGet` 永不回拨；`ipToDomain/domainToIp` 双向驱逐——脚注已同步
- `index.html` KEY FACTS 已更新（背压锁、池饱和拒绝、开机自启 goAsync、重连退避）

**结论**：图与代码事实一致，无遗留漂移。
