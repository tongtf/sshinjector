# 图集复核结论（2026-11-24）

复核范围：`docs/diagrams/*.html`（索引 + 各状态机 / 流程 / 时序 / 甘特图）
权威事实：仓库根 `AGENTS.md`；图集最后同步时间：**2026-10-08**（当前代码 v1.2.8）。

## 结论（修订）

- [x] 原结论「图与代码事实一致，无遗留漂移」**不成立，予以作废**。旧清单只在 2026-08-06 / 08-18 做过回读，之后数据面、DNS、SSH、可观测性连续重构，图未跟进。
- [x] 六步编排（**探索 → 设计 → 绘图 → 审查 → 修复 → 回读**）作为工作流编排**仍然准确**，可继续使用；失效的是「回读通过 ⇒ 图无漂移」这个前提 —— 代码持续演进，回读必须针对当前代码重做。
- [x] 每张图顶部均已加「最后同步 2026-10-08，细节可能滞后，现状见 AGENTS.md」提示条；索引页同步标注容量常量与新增功能的滞后风险。
- [x] 本清单自身必须随图一并更新；否则它会再次变成「审查通过」的假证据。后续每次改图，先改本文件的漂移表与结论日期。

## 本次审计发现的主要漂移

| 主题 | 图集/旧清单说法 | 当前事实（AGENTS.md / 代码） |
| --- | --- | --- |
| SOCKS5 认证 | 「握手无认证路径（processAuthMethods 直通）」 | **fail-closed**：握手强制用户名密码 `[0x05,0x02]`，无凭据直接 `[0x05,0xFF]` 拒绝；生物识别门禁同样 fail-closed |
| UDP ASSOCIATE | 「空回复 0.0.0.0:0，连接保持打开」 | 立即回 **rep=0x07** 并关闭；非 DNS UDP（QUIC/游戏）在 TUN 侧明确丢弃并**按目的端口计数**，DNS 回程仍由 `UdpRelay` 组包 |
| 出向背压容量 | `Channel(256)` | `SSH_SEND_QUEUE_CAPACITY = 128`（`Socks5ProxyServer.kt`），单槽 `pendingToSshBlock` + `backpressureLock` + 暂停 OP_READ 不变 |
| DNS 假 IP 生命周期 | 只提 LRU 映射 | 应答 **TTL=5s**，`ipToDomain`/`domainToIp` 上限 **16384** 独立 LRU，真实 IP 独立表 8192；引用归零后 **60s 释放宽限**再删映射 |
| 白名单空名单 | 图未区分 | `DnsTransportPolicy`：空名单退化为 **SYSTEM**（保护 socket 本地解析），非空才 WHITELIST；白名单模式下 DNS 上游**排除 VPN 网络**（过滤 `TRANSPORT_VPN` + 滤掉 `TUN_IP`） |
| TUN 写入 | 旧清单提 `writeToTun` monitor | 已改为 **TunWriter 单写者**：`write`（控制包，满则丢且不计 TUN 写失败）/`writeBlocking`（数据，等待有界）语义分离；`@Synchronized` 因前提消失而移除 |
| 会话变更串行化 | 图只画状态转移，未画锁 | `connect`/`disconnect`/`autoReconnect`/`rebuild` 全部串行于 **connectMutex**（Mutex 不可重入，已持锁路径只能调 `disconnectInternal` 等） |
| 域名统计 | 完全缺失 | `DomainStatsStore` 会话级**内存**聚合（上限 20000，不落盘）；P1 建议→应用三键分存 + 撤销快照；远端解析走池内会话 direct-tcpip；按应用 uid 仅做展示列 |
| 健康探测 | 只有端到端探测 | 新增**分阶段健康**（`StageCounters` + `StageHealthEvaluator`：TUN/DNS/FORWARD 三段，连续 2 失败窗降级、1 成功窗恢复），与端到端探测经 `reportMergedHealth` 合并，只降级显示不杀连接 |
| SSH 会话健康 | 只有 keepAlive 往返 | **三态探活** `ROUND_TRIP_OK / UNAVAILABLE / FAILED`（UNAVAILABLE 不判死）；开通道**两层预算**（总 9s + 单候选 3s），开超时的会话后置 30s；全候选秒拒归为 INFO「远端不解析该目标」而非 ERROR |
| SSH 容量与拒绝 | 只提池饱和 | `SshIoDispatcher` 饱和时 `connectToTarget` 提前回 `0x05`；JSch 通道 8MB 窗口 / 64KB 包；Android 上 Ed25519 MR-JAR 无效，kex 白名单必须含 ECDH |
| 关闭语义 | 索引卡曾写「图中有 FIN+ACK」 | 图未展开该细节；实际远端 clean EOF → FIN+ACK，其余路径 RST+ACK，客户端 FIN 必须被 ACK 覆盖 |
| 可观测性 | 只有应用日志 | 60s **数据面快照** + 断开前强制一条；故障归因按静态前缀聚合 WARNING/ERROR；`数据面快照` 是唯一诊断出口（无 adb） |
| 隧道插件 | 单一路径未标注 | 现有插件**只有 socks5**（`TunnelPlugin` + `TunnelModule` 绑定）；新增隧道需同时补 descriptor 与绑定 |

## 幽灵引用清理

- [x] 删除旧清单中的 `udp-flow.html` 引用 —— 该文件从未存在，UDP 内容实际在 `socks5-state.html` 与 `packet-flow.html` 中体现。

## 保留仍准确的结论

- [x] 六步工作流编排本身准确（见上）。
- [x] 架构图的三层划分与主数据流方向（TUN → PacketProcessor → SOCKS5 → JSch → 远端）在宏观上仍成立，只是缺少域名统计栈 / TunWriter / SshIoDispatcher / 隧道插件等节点（已在图内提示条标注）。
- [x] 配置服务器一键配置的六步与 `ServerProvisioning.Step` 枚举一致（与本次漂移无关，仍有效）。

## 仍未绘图

域名统计 / 健康探测 / 诊断快照尚无对应 HTML；见仓库根 `AGENTS.md`。
