# 多出口 SOCKS5 路由 (Multi-Exit Routing) — 设计文档

> 状态: **设计稿 v2 (2026-10-29 评审修订)**, 未改任何代码。
> v1 = P0–P2, **包含按应用 (包名) 分流**; §16 的 15 项决策已于 2026-10-28 / 10-29 定稿。
> 行号基于本工作区当日 `main`。
> 结论: **可行**, 核心传输层已经具备"每连接一个插件 + 每插件一个本地 SOCKS 端口"的形状,
> 真正的阻塞点是**上层的单例假设** (`TunnelManager` / `Socks5TunnelPlugin` / `JschSshClient`)、
> **全局唯一的 DNS 分流判定**、以及**出口维度的日志/健康口径**, 不是数据面。
> 一句话: 一条 VPN 会话里同时挂 N 个 SSH-SOCKS5 出口, 按"**应用 (包名)** / 域名 / IP-CIDR / 端口"
> 规则把**新连接**分到不同出口; 出口是会话级的, 连接一旦建立就不再迁移。
>
> **v2 相对 v1 的四条主要修订 + 十条实现级修正** (评审结论, 完整清单见 §16.3):
> ① **按 app 分流从 P3 提前到 v1** —— 阻碍只是 `AppUidResolver` 缺一个"会完成"的等待原语,
>    不是平台能力 (反查本来就在 SYN 发起, 见 `TcpStateMachine.kt:1847`);
> ② 规则**按包名表达** (`app:com.example -> hk`), 运行时做 `uid → 包名` 映射, **不把 uid 写进规则**
>    (重装 / 多用户 / 共享 uid 都会让 uid 变, 包名不会);
> ③ 规则模型**从 P1 就含 app target**, 与域名/CIDR/端口同处一张有序表, P3 不再改 DSL 与存储;
> ④ 重试只覆盖 **CONNECT 之前**的失败 (`rep` 码分不出出口级/目标级), 且 `JschSshClient` **必须带出口标签**
>    (否则附加出口池失败会打"整条会话即将断开"的 ERROR, 验收场景 S21 直接失败)。
>
> 两条与性能/正确性强相关的细则: **uid 查询用"一次请求、多人等"的句柄模型** (SYN 时入队,
> 路由与域名统计共用, 不重复调 binder); **bypass 判定排在 uid 等待之前** (直连流量不该为
> 应用规则付 50ms)。

**阅读路线**: 只想判断可行性 → §3; 评审方案与语义 → §4–§6; 已定的取舍与被否掉的选项 → §16;
实施 → §7–§10 与 §14; 验收 (无 adb 现场) → §12 与附录 D; 调研期发现的既有缺陷 → 附录 C。

### 0.1 评审结论速览 (TL;DR)

| 问题 | 结论 |
|---|---|
| 这套设计**是否合理**? | **合理**, 且是当前代码结构下的最小改动路径: 每连接传插件/端口的能力已经存在 (`forwardThroughLocalSocks`), 真正要拆的只是上层的单例假设 |
| **能否按 app 把流量分到不同 SOCKS5 代理**? | **能, 且列入 v1**。唯一阻碍是 `AppUidResolver` 没有"查询完成"信号 —— 补一个**有界等待的请求句柄**即可 (≤50ms, 超时回退 default), 不需要改数据面 |
| app 规则写什么? | **包名** (`app:com.example.app[:port] -> hk`), 不写 uid (重装/多用户/共享 uid 都会变) |
| 有没有"按 app 强制走隧道"? | **没有, 也做不到**: 进不进 TUN 在 SYN 之前就定了 (§6.4 约束 4)。应用规则只决定"已进 TUN 的连接走哪个出口" |
| 最大的实现风险? | ① 出口维度的日志/健康口径 (附加出口池失败会打全局 ERROR → 假故障); ② 失败分类只能按**阶段**切, 不能按 `rep` 码切; ③ 128 线程墙下单出口可能饿死其它出口 |
| 关掉开关是否零影响? | **是**, 验收判据是 `数据面快照` 文本**逐字**一致 (S17), 不是"看起来一样" |

### 0.2 配套文档 (三份, 各管一件事)

| 文档 | 回答什么 | 谁看 |
|---|---|---|
| **本文** (设计) | **为什么这样设计** / 语义边界 / 被否掉的选项 / 不变量 | 评审、后续复审 |
| `2026-10-31-multi-socks5-routing-dev-guide.md` (开发) | **按什么顺序写、每步改哪个文件的哪一行、怎么自检** | 实施者 |
| `2026-10-31-multi-socks5-routing-acceptance.md` (验收) | **什么算做完** (单测/门禁/真机 S17–S26/判读阈值) | 实施者 + 回归 |

> 三份文档的分工是硬的: 设计文档**不写施工步骤**, 开发文档**不改语义**, 验收文档**不解释实现**。
> 任何一处需要改语义时, 先改设计文档 (并写 §16 决策记录), 再同步另两份。

---

## 0. 为什么值得做

现在的模型是"一台服务器 = 一条隧道 = 全部流量"。实际使用里这个假设经常不成立:

| 场景 | 现状 | 期望 |
|---|---|---|
| 流媒体在 A 区, 工作服务在 B 区 | 只能二选一, 或者手动切服务器(断流 + 重连) | 一条会话里各走各的 |
| 某个域名只有香港节点能过, 其余要走日本 | 全局换出口 | 单域名定向 |
| **某个 app 必须走另一个出口**(如海外 App vs 国内 App) | 只能整体切服务器 | **单应用定向, 其余照旧** |
| 主出口抖动时不想整条 VPN 掉线 | 池失败 → 全局 `autoReconnect` | 只有该出口的连接失败, 新的走主出口 |
| 大流量下载不想占用工作出口的并发 | 无解 | 按 app / 域名 / IP 把下载站分到另一个出口 |

这不是"多开几个 VPN"(Android 只允许一个活动 VpnService), 而是**一个 TUN 内做出口选择**。
TUN、回程直通、窗口闸门、假 IP、统计、健康归因全部原样复用。

---

## 1. 需求解读 / 目标 / 非目标

### 1.1 三种可能的"多出口" (先对齐语义)

| 编号 | 解读 | 是否本设计 |
|---|---|---|
| **R-A** | **同时使用多个 SSH-SOCKS5 上游出口, 按"应用 / 目标"分流** | ✅ 本文的"多出口" |
| R-B | 对接**外部** SOCKS5 / HTTP 代理 (不是本机 SSH 隧道) | ❌ P3 (§14), 需要新增 `TunnelPlugin` 实现 |
| R-C | 多种 DNS 模式/多种隧道协议并存 | ❌ 与"出口"正交, 不在本文范围 |

> 后文不特别说明时, "出口 (exit)" 一律指 R-A: 一个 `JschSshClient`(3 会话池) +
> 一个本地 `Socks5ProxyServer` 监听, 对应一台已保存的服务器。
> "目标" 指域名 / IP / 端口; "应用" 指发起连接的那个 uid (规则里写包名)。两者共同决定出口。

### 1.2 目标

| 编号 | 目标 |
|---|---|
| G1 | 一条 VPN 会话内可同时启动 **1 个主出口 + N 个附加出口** (v1: N ≤ 2), 各自独立 SSH 会话池 |
| G2 | 新增连接按 **应用 (app) / 域名 / IPv4-IPv6 CIDR / 精确 IP / 端口** 规则选出口; 规则改动**无需重连** |
| G3 | 未命中规则 → 主出口 (`default`), 与今天行为一致 |
| G4 | 附加出口**会话级**故障自动降级 + 新连接回退主出口, **不影响整条 VPN** |
| G5 | 出口配置/规则/状态可见, 无 adb 现场能从导出的日志判读 (§12) |
| G6 | **关闭开关时, 行为与日志逐字节不变** (§13.3 验收) |
| G7 | **按应用分流**: 规则写**包名**, 同一 app 的所有连接走同一个出口 (确定性, 不轮询); uid 拿不到时回退 `default` 并计数 |
| G8 | **按应用是 v1 能力, 不是 P3**: 每条新连接在 SYN 之后、CONNECT 之前有界等待一次 uid (≤50ms), 超时回退 `default` |
| G9 | 规则模型**一次成型**: app / 域名 / CIDR / 端口都是同一张有序表里的 target 类型, P3 不再改 DSL / 存储 / UI 骨架 |

### 1.3 非目标

| 编号 | 非目标 | 理由 |
|---|---|---|
| N1 | 连接建立后**迁移**到另一个出口 | 迁移需要重放 TCP 状态/序号/窗口, 本栈无重传缓存; 代价与风险完全不成比例 |
| N2 | 按连接做负载均衡 (轮询/最小连接) | 会破坏同一站点的会话一致性 (HTTP/2 多路复用会分裂), v1 只做**确定性规则** |
| N3 | 每个出口独立 DNS 模式 | 假 IP 池与映射表是全局的, 分模式会让 `lookupDomain` 语义分裂; v1 全局一个模式 |
| N4 | 主出口故障时静默把所有流量切到附加出口 | 会掩盖"主出口已死"这一事实, 且状态卡/出口 IP/探测全部以主出口为准 (§9.4) |
| N5 | 把**一个 app 的连接分散**到多个出口 (权重/轮询) | 同一 app 的会话会被打散到不同 IP (登录态/风控/HTTP2 复用全崩); app 规则只做**一对一确定映射** |
| N6 | 目标级失败 (NXDOMAIN 秒拒) 触发出口故障转移 | 不是本栈故障 (见 AGENTS "远端不解析该目标"), 换出口只会把一次失败变成两次 |
| N7 | 用 app 规则把**未进 TUN** 的流量拉进隧道 | 一个 TUN、一次 `addAllowedApplication` 决策, SYN 时刻已定; 见 §6.4 约束 4 |
| N8 | **按 app 分流 DNS** (不同 app 用不同 DNS 策略) | DNS 报文由 netd/系统解析器代发, 其 uid **不是**发起应用; 假 IP 池与映射表也是全局的 (N3) |
| N9 | 用 uid 决定"是否进 TUN" | 进不进 TUN 由 `addAllowedApplication` + 路由表在 SYN 之前决定, 与 N7 同因 |

---

## 2. 现状盘点

### 2.1 可复用资产 (几乎不用改)

| 组件 | 位置 | 为什么可复用 |
|---|---|---|
| 每连接传插件 | `TcpStateMachine.forwardThroughLocalSocks(conn, plugin, socksPort)` (`TcpStateMachine.kt:839`) | **签名已经接受插件与端口**, 不是全局字段 —— 这是整个方案的可行性基石 |
| 本地代理实例 | `Socks5ProxyServer` (`Socks5ProxyServer.kt:55`) | 可变状态 (serverChannel/selector/scope/connections/callbacks/counters) **全是实例字段**; 文件级只有常量与 `IS_DEBUG`; 多实例天然隔离 |
| 端口 | `Socks5ProxyServer.start(port, bind)` (`:102-141`) | 返回实际绑定端口 (`boundPort`), 传 `0` 即系统分配 |
| SSH 池 | `JschSshClient` (`:407`) | 每个实例自带 3 会话池、keepAlive、健康检查、`createDirectChannel`、指纹 TOFU |
| 回程直通 | `registerTunCallback` / `registerTargetEofCallback` | 回调按 `clientPort` 分键, 挂在**各代理实例**上, 多实例互不干扰 |
| 假 IP → 域名 | `DnsInterceptor.lookupDomain` | 全局唯一映射即可: 出口选择发生在 CONNECT 前, 域名由手机侧还原 |
| 连接上限 / 线程池 | `SshIoDispatcher` | 每连接 1 条回程线程, 多出口不增加"单连接成本", 只提高总并发 (§10) |
| 诊断文案 | `DataPlaneSnapshot` | 纯字符串 + 可空节 (§12: 关闭时空串, 不改变现有日志) |

### 2.2 七个单例假设 (必须逐个拆)

| # | 位置 | 现状 | 多出口下会怎样 |
|---|---|---|---|
| B1 | `TunnelManager.kt:22` | `activePlugin: MutableStateFlow<TunnelPlugin?>` | 后启动的出口覆盖前一个, 只有最后一个可用 |
| B2 | `TunnelManager.kt:24,39-42` | `activePlugins[pluginId] = plugin`, 键是**协议名** | 第二个 socks5 插件覆盖第一个且不 `disconnect()` → 端口/会话泄漏 |
| B3 | `TunnelManager.kt:60-63` | `getActiveOrFallback()` 只有一个 | 无法按目标选择 |
| B4 | `Socks5TunnelPlugin.kt:12,21` | `@Singleton` + 单 `socksServer` 字段 | 一个实例只能持有一个监听 |
| B5 | `JschSshClient.kt:407` | `@Singleton` + 单 `pool` | 所有出口共用 3 条会话, 出口之间没有任何隔离 |
| B6 | `TcpStateMachine.kt:177` | `@Volatile tunCallbackPlugin: TunnelPlugin?` **机器级单字段** | 连接 A 关闭时可能拿 B 的插件去摘回调 (§附录 C-2, 既有缺陷) |
| B7 | `SshVpnService.kt:215` | 探活用 `config.socksPort` | 有多出口/端口回退时探错端口 (§附录 C-3) |

另外三处**判定**是全局的, 必须改成"按目标"; 还有一处**异步输入**必须变成"决策前可得":

| # | 位置 | 现状 | 多出口下的问题 |
|---|---|---|---|
| D1 | `DnsInterceptor.cachePolicyTag` (`:190-200`) | 只看模式 + 域名名单 | 域名命中出口规则但仍被判为"直连" → 缓存复用直连答案 |
| D2 | `DnsInterceptor.useSystemDns` (`:669-679`) | 同上, 独立实现 | 两处判定必须**同一个谓词**, 否则缓存标签与实际路径分叉 |
| D3 | `VpnController.computeShouldBypassTcp` (`:392-399`) | 只看排除路由 + 模式 | 域名有出口规则却走了直连 → 规则静默失效 |
| D4 | `AppUidResolver.lookup` (`AppUidResolver.kt:29-38`) | **只异步且没有完成信号**: 只有拿到 ≥0 才回调, 拿不到永不回调 | 路由需要在**选出口之前**知道 uid; 没有"查询已结束"的信号就没法有界等待, 只能靠事后回写 `conn.appUid` → 同一 app 随机走不同出口。**平台能力本身没问题** (反查已在 SYN 发起, `TcpStateMachine.kt:1847`) |

### 2.3 一句话结论

> 数据面**已经支持**"一条连接选一个出口", 缺的是"注册表按出口分键 + 路由决策 + 健康隔离"。
> 工作量集中在上层编排, 不需要动 TUN / 组包 / 窗口 / 假 IP 生命周期。
> **按 app 分流同理**: 决策输入 (uid) 在 SYN 时刻可得, 缺的是一个**会完成的请求句柄**
> (`UidRequest.await`) 和一张 `uid → 包名` 映射表 —— 两者都是纯上层, 与数据面无关。

---

## 3. 可行性结论

### 3.1 关键证据链

```text
packetLoop
  └─ TcpStateMachine.processTcpPacket               // 热路径, 逐包
       └─ (SYN) forwardSynToTunnel(conn)            // 每连接一次, 协程 (Dispatchers.IO), 不在 packetLoop 上
            ├─ domain = dnsInterceptor.lookupDomain(ip)      // 假 IP 反查, 可能 null
            ├─ bypass = bypassPredicate(ip, port)            // §6.5 顺序: 排除路由 > 出口规则 > split 直连
            │    └─ true → forwardThroughBypass, return      // **直连不等 uid** (省 50ms)
            ├─ uid    = conn.uidRequest.await(50)            // **一定完成**: ≥0 或 NO_UID(-1)
            ├─ pkg    = appIdentity.packageOf(uid)           // uid → 包名 (会话级缓存), 查不到 = null
            ├─ target = RouteTarget(uid, pkg, domain, ip, port)
            └─ exit = tunnelManager.resolveExit(target)      ← 唯一需要改的一行
               forwardThroughLocalSocks(conn, exit.plugin, exit.socksPort)  ← 已经是参数化
```

- `forwardThroughLocalSocks` 内部**没有任何全局插件引用**: 回调注册、凭据、CONNECT 全部走 `plugin`。
- `Socks5ProxyServer` 的 `pendingTunCallbacks` / `pendingTargetEofCallbacks`
  是**实例字段** (`:221,239`), 键是 `clientPort`; 不同实例的键空间天然隔离。
- `JschSshClient` 的所有可变状态都在实例内 (`pool:575`, `sessionIndex:576`,
  `isConnectedFlag:579`, `connectionState:582`); 静态的只有 `JSch.setLogger` 的一次性守卫
  与算法白名单, 多实例共享无害。**唯一的例外是日志文案** (见附录 C-5): 池失败那行必须按出口标签分流。
- **uid 获取时机这件事本身就是可行的**: `forwardSynToTunnel` 已经是协程 (不在 `packetLoop` 上),
  所以"在选出口前等一次 uid"只推迟**这一条连接**的 SYN-ACK, 不影响其它连接, 也不占热路径线程。
  真机 7/105 那个命中率是**关闭时**查的; SYN 到达时 socket 必然存在, 预期接近 100% (§9.6 门槛)。

### 3.2 结论分级

| 能力 | 结论 | 依据 |
|---|---|---|
| 一条会话同时监听 N 个本地 SOCKS5 | ✅ 直接可行 | 每实例独立 bind, 附加出口 bind `0` |
| 每条新连接选不同出口 | ✅ 直接可行 | `forwardThroughLocalSocks` 已参数化 |
| 同一域名不同出口解析到不同 IP | ✅ 天然正确 | CONNECT 发的是**域名**, 由各出口的 sshd 自己解析 (geo-DNS 按出口生效) |
| 出口独立健康/故障隔离 | ✅ 可行 | 每出口独立 `JschSshClient` + 独立 3 会话池 |
| 连接中途换出口 | ❌ 不做 | 需要 TCP 状态迁移 (N1) |
| 在 SYSTEM / 空白名单模式下按域名或应用分流 | ❌ 不可能 | 这两个模式**不加任何 TUN 路由** (`SshVpnService.kt:637-647`), 流量根本不进 TUN (§6.4) |
| **按 uid 在 SYN 时路由** | ✅ **v1 落地** | 反查是公开 API 且 SYN 时 socket 必然存在; 只要 `awaitUid` 有界 (≤50ms) 且**一定完成**, 未知则回退 `default` (§8.5.1) |
| 按 app 分 DNS | ❌ 不做 | DNS 由 netd 代发, uid 不是发起应用 (N8) |

### 3.3 必须承认的代价

1. **SSH 连接数 ×(1+N)**: 3 条/出口。3 个出口 = 9 条并发 SSH 连接, 会撞服务端
   `MaxStartups` (OpenSSH 默认 `10:30:100`)。缓解: 错峰 300ms + v1 上限 4 个附加出口。
2. **回程线程数**: 每个活跃连接 1 条 `SshIoDispatcher` 线程 (上限 128)。多出口**不增加单连接成本**,
   但让 128 这个天花板更容易被摸到 → 必须有全局限额 (§10 I-8)。**512 的预算挡不住 128 的线程墙**,
   见 §10 的资源表。
3. **内存**: 每条连接 32KB 直接缓冲; 512 连接/出口 × N 的组合不可接受 → 全局预算仍按 512 总计。
4. **观测面复杂度**: 故障归因/快照/连接生命周期日志必须带出口标签, 否则"哪条出口在失败"无法判读。
5. **每条走隧道的新连接多一次 uid 等待 (预算 ≤50ms, 正常 <2ms)**: 只在首包 (SYN→SYN-ACK) 生效,
   不影响已建立连接的吞吐; **直连 (bypass) 连接不等** (§5.2 顺序); 拿不到就回退 `default`
   (**不是**失败)。真实延迟与命中率必须真机实测 (§9.6)。

---

## 4. 方案选型

### 4.1 候选

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. 单监听 + 内部转发** | 1 个 `Socks5ProxyServer`, 在 `connectToTarget` 里选 `SshChannelFactory` | 只占 1 个端口; 规则只在服务端一处 | `Socks5ProxyServer` 要持有 N 个 factory + N 套健康; CONNECT 之前无法知道域名 (SOCKS5 域名在 CONNECT 里, 这里反而更早, 但 TUN 侧规则要在 SYN 时定, 两处决策会分叉); 单点故障域 | ❌ |
| **B. 每出口一个本地 SOCKS5 + 独立 SSH 池** | N × (`Socks5ProxyServer` + `JschSshClient` + `Socks5TunnelPlugin`) | 完全复用现有链路; 故障域天然隔离; 路由只需选 `(plugin, port)`; 其余代码零改动 | N 个 loopback 端口; 3N 条 SSH 会话 | ✅ **推荐** |
| C. 共享 SSH 池 + 每出口一个通道选择器 | 1 个 `JschSshClient`, 通道按出口打标签 | SSH 连接数少 | 需要改 `createDirectChannel` 的会话选择语义 (池内会话 = 同一台服务器, 无法跨服务器), 实际上不成立 | ❌ 逻辑上不成立 |
| D. 外部 SOCKS5 出口 | 新增插件实现, 不经 SSH | 灵活 | 完全不同的一组凭据/健康/加密语义 | ⏭ P3 |

> 方案 C 的致命点: 一个 `JschSshClient` 的池里 3 条会话**连的是同一台服务器**,
> "出口"在其内部没有意义; 要跨服务器就必须有多个 client, 那就回到 B。

### 4.2 为什么 B 是"最小改动"

- `TcpStateMachine` 只需要把 "取唯一插件" 改成 "按目标取插件", 其余 2000 行不动。
- `Socks5ProxyServer` 逻辑零改动: 只加两个**可选**构造参数 (诊断标签 `exitId`、共享
  `ConnectionBudget`), 默认值下行为与今天完全一致 (§8.9)。
- `JschSshClient` **数据面逻辑**一行不改 (新增的是**构造更多实例**的工厂); 但**必须**加一个
  `@Volatile var exitLabel` (body 属性), 只用于两处日志分流 (附录 C-5) —— "一行不改"是错的,
  附加出口池失败会打出"整条会话即将断开"的 ERROR, 与 §9.4 / S21 直接冲突。
- 关闭开关时不创建任何附加实例 → 运行时对象图与今天完全一致。
- 按 app 分流**不增加任何数据面对象**: 只多一次 `awaitUid` (协程挂起) 与一次包名查表。

### 4.3 关键取舍: 出口选择发生在 SYN, 不在 CONNECT

SOCKS5 的 CONNECT 里确实带域名, 但那已经是"连接本地代理之后"。本栈的
`forwardThroughLocalSocks` 在连接前就要知道**连哪个 loopback 端口**, 所以决策点只能是 SYN。
这与假 IP 机制正好匹配: 域名在 DNS 阶段就已经还原 (`lookupDomain`), SYN 时可用。

---

## 5. 总体设计

### 5.1 组件图

```text
                         ┌──────────────────────────────┐
   TUN ──► PacketProcessor│ TcpStateMachine              │
                         │  ├─ dnsInterceptor.lookupDomain(ip) ─────► domain?
                         │  ├─ bypassPredicate(ip, port) ───────────► 直连? → forwardThroughBypass (不等 uid)
                         │  ├─ conn.uidRequest.await(50ms) ─────────► uid
                         │  ├─ appIdentity.packageOf(uid) ──────────► pkg
                         │  ├─ tunnelManager.resolveExit(RouteTarget(uid, pkg, domain, ip, port))
                         │  │      └─ RoutingTableStore (immutable snapshot)
                         │  └─ forwardThroughLocalSocks(conn, plugin, port)  [不改]
                         └──────────────┬───────────────┘
                                        │ 选中的 ActiveExit
             ┌──────────────────────────┼──────────────────────────┐
             ▼                          ▼                          ▼
   ┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐
   │ exit "default"   │      │ exit "hk"        │      │ exit "jp"        │
   │ Socks5TunnelPlugin│     │ Socks5TunnelPlugin│     │ Socks5TunnelPlugin│
   │  ├ JschSshClient │      │  ├ JschSshClient │      │  ├ JschSshClient │
   │  │   (3 sessions)│      │  │   (3 sessions)│      │  │   (3 sessions)│
   │  └ Socks5Proxy   │      │  └ Socks5Proxy   │      │  └ Socks5Proxy   │
   │    127.0.0.1:1080│      │    127.0.0.1:0   │      │    127.0.0.1:0   │
   └──────────────────┘      └──────────────────┘      └──────────────────┘
             ▲                          ▲                          ▲
             └────────── TunnelManager: exitId → ActiveExit ───────┘
```

### 5.2 一次 SYN 的完整时序

```text
1. processTcpPacket: SYN, 无 conn → createTcpConnection
2. conn.clientWindowRaw/wscale/browserSeq 就位
   └─ 同一时刻: conn.uidRequest = appUidResolver.request(...)   // 只入队, 非阻塞
3. forwardSynToTunnel(conn)  [协程, Dispatchers.IO]
   3.1 domain = dnsInterceptor.lookupDomain(dstIp)  // 可能为 null (非假 IP)
   3.2 bypass 判定 (顺序见 §6.5): 排除路由 > 出口规则 > split 直连
        └─ true → forwardThroughBypass, 结束 (**不等 uid, 不选出口**)
   3.3 uid = conn.uidRequest.await(50)              // 一定完成; 超时/无归属 = NO_UID
   3.4 pkg = appIdentity.packageOf(uid)             // uid → 包名, 缓存; 查不到 = null
   3.5 target = RouteTarget(uid, pkg, domain, dstIp, dstPort)
       decision = tunnelManager.resolveExit(target)
        ├─ 多出口关闭 / 规则空 / 出口不 Ready / 出口满 128 → default
        └─ 命中规则 → exitId; 该出口 Degraded → default + 计数
   3.6 conn.exitId = decision.id  (body 字段, 只写一次)
   3.7 forwardThroughLocalSocks(conn, decision.plugin, decision.socksPort)
       ├─ 注册 tunCallback / targetEofCallback (键 = 本连接 localPort)
       ├─ SOCKS5 握手 + CONNECT (域名)
       ├─ 成功 → markEstablished → SYN-ACK → flush 缓存 → 转发
       └─ 失败 → 分类:
            ├─ **CONNECT 之前**的失败 + 未发 SYN-ACK + 耗时 < 3s + 不是 default
            │   → 改投 default **重试一次** (§9.5)
            └─ 其它 → closeTcpConnection (RST)
4. 后续所有上行/回程/关闭都使用 conn.exitId 解析插件 (不再有机器级字段)
```

> **为什么 bypass 必须排在 uid 之前**: 直连流量根本不选出口, 应用规则对它没有任何意义。
> 把 50ms 的 uid 等待放在 bypass 之前, 等于给分流模式下**每一条直连连接**都加 50ms 首包延迟
> (分流模式里直连是多数) —— 这是最容易犯且最难察觉的性能错 (现象只有"网页整体变慢")。
> 反过来, bypass 判定只需要域名 (已有) 与"是否有出口规则" (纯文本查表), 不需要 uid。
>
> 3.3 的**入队**其实发生在 SYN 到达时 (步骤 2, `packetLoop`, 只入队, 非阻塞), **等待**在协程里
> 完成 —— 所以这 50ms 不是"从零开始查", 而是"等一个已经在跑的查询"。50ms 是上限不是常态
> (binder 单次查询实测亚毫秒, 见 §9.6); 等待期间客户端只会重传 SYN, 与现有"等 SSH 通道就绪
> 才回 SYN-ACK"是同一类等待。

### 5.3 出口身份

| 概念 | 值 | 说明 |
|---|---|---|
| `exitId` | `"default"` / 用户命名 (`hk`, `jp`) | 小写、1..32 字符、`[a-z0-9_-]`; `default` 保留 |
| `default` | **当前连接的服务器** | 不落库、不可删、不可禁用; 恒指向 `currentServer` |
| 附加出口 | `proxy_exits` 表一行 | 引用 `servers.id`; 删除服务器时级联删除 |
| 规则里的出口引用 | 出口名 | 名字唯一; 重命名会即时改写规则文本 (UI 层) |
| **应用身份** | **包名** (`com.example.app`) | 规则里写包名; `uid` 只是运行时句柄, **绝不写进规则** (重装/多用户/共享 uid 会变)。运行时 `uid → 包名` 由 `AppIdentity` 解析 (会话级缓存) |

> 命名空间注意: `TunnelPlugin.id` 仍是**协议名** (`"socks5"`), 新加的 `exitId` 是**出口维度**,
> 两者正交 —— 不要复用 `id` 存出口名 (会让"按协议查插件"与"按出口查实例"互相污染)。

> 为什么主出口不落库: 它是"用户点连接的那台服务器", 已经由 `last_server_id` /
> `currentServer` 表达。再存一份会出现"连接 A 但主出口是 B"的二义性。

> 为什么应用规则用包名: `uid` 在重装后**必然变**, 工作资料里的同一应用是**另一个 uid**,
> 共享 uid 的应用组共用一个数字。写 uid 等于把规则绑死在一次安装上; 写包名则由
> `PackageManager.getNameForUid(uid)` 在运行时解析 (共享 uid 的组会一起命中, UI 需提示)。

### 5.4 生命周期

```text
connect():
  1. appIdentity.clear()                                   // uid → 包名 缓存按会话重置
  2. 主出口: tunnelManager.startExit(DEFAULT, "socks5", label = "", serverHost = server.host, forSocks5(server, password))
     └─ 失败 = 整体失败 (现状); label 为空串 = 日志不加出口前缀 (G6)
  3. 装载规则快照 (DataStore) + dnsInterceptor.setExitRulePredicate { store.hasDomainRule(it) }
     └─ 失败/为空 = 空表 (全部走 default), WARNING; 谓词仍 set (空表恒 false)
  4. 读取配置: multi_exit_enabled? → 附加出口列表 (enabled, 按 priority)
  5. 主出口 READY 后, 逐个启动附加出口 (间隔 300ms), best-effort:
       - 失败 → WARNING + 该出口 Failed, 会话继续
       - 本次会话不再自动重试 (由用户重连或后台恢复)

disconnect()/autoReconnect():
  tunnelManager.stopAll()
    ├─ 先 cancel 所有在途 start Job (sessionGeneration 守卫)
    ├─ 再 stop 每个 proxy (释放端口/连接)
    └─ 再 disconnect 每个 client (排空会话池)
```

**错峰启动**: 附加出口的启动协程 `delay(300L * index)`, 避免 3×N 条 SSH 同时握手
把服务端 `MaxStartups` 打满 (表现是随机 "connection reset by peer")。
附加出口的启动**不阻塞 `connect()` 返回**: 主出口 READY 即让 UI 进入 Connected,
附加出口在后台陆续就绪 (未就绪期间 `resolveExit` 一律回 default, 不会产生失败连接)。

---

## 6. 路由语义

### 6.1 决策点唯一

- **只在 SYN 决策一次** (uid / 包名 / 域名 / IP / 端口 全部在这一刻取齐), 写进 `conn.exitId`, 后续不再重算。
- 唯一例外: §9.5 的"建立前快速失败重试一次" (仍在 SYN-ACK 之前, 客户端只看到 SYN 重传)。
- 域名规则依赖假 IP: 没有 `lookupDomain` 结果时, 域名规则**不参与**匹配 (IP/CIDR/应用规则照常)。
- 应用规则依赖 uid: `awaitUid` 超时或无归属时, 应用规则**不参与**匹配 (继续匹配后续行 → default),
  **绝不**因为拿不到 uid 就回退直连 —— 那会让同一 app 的流量在隧道内外跳变 (§6.5 末段)。

### 6.2 规则语法 (v1)

一行一条, `#` 注释, 空行忽略; 语法 `<target> -> <exit>`:

```text
# ---- 应用分流 (行序即优先级, 放最上面 = app 优先) ----
app:com.example.bank      -> jp
app:com.example.video:443 -> hk

# ---- 香港出口 ----
youtube.com            -> hk
*.googlevideo.com      -> hk
203.0.113.0/24         -> hk
198.51.100.7           -> hk

# ---- 日本出口 ----
example.co.jp:443      -> jp
[2001:db8::]/32        -> jp

# ---- 兜底 (可选, 必须最后一行) ----
default                -> hk
```

| target 形式 | 语义 | 示例 |
|---|---|---|
| `app:com.example.app` | **包名精确匹配**, 该应用的所有新连接 | 命中 `com.example.app` 的任意目标 |
| `app:com.example.app:443` | 包名 + 端口精确 | 只命中该应用的 443 |
| `example.com` | **后缀**匹配 (apex + 子域) | 命中 `example.com` / `a.b.example.com` |
| `*.example.com` | 同上 (等价写法, 便于从别处粘贴) | 同上 |
| `example.com:443` | 后缀 + 端口精确 | 只命中 443 |
| `203.0.113.0/24` | CIDR | IPv4/IPv6 均可 |
| `198.51.100.7` | 精确 IP (等价 `/32`) | |
| `[...]:443` | IPv6 + 端口 | 方括号必填 |
| `default` | **未命中兜底**, 只允许一行且必须是最后一条 | 换默认出口 |

细则:

1. **端口只对 TCP 目的端口生效** (UDP 已降级, 不参与)。
2. **域名规则只在能还原域名时生效**; 无法还原时该行被跳过, 继续匹配后续行。
3. **应用规则只在 uid 可得时生效**; 拿不到时该行被跳过 (不回退直连), 并按原因计
   `uidTimeoutTotal` / `uidMissTotal` (前者调预算, 后者是事实)。
4. `default -> x` 之后的规则不可达 → UI 报错并拒绝保存。`example.com -> default` 合法,
   表示"这一条显式走主出口" (可覆盖后面的 `default -> hk`)。
5. 重复 target: UI 标黄, 运行时**先出现者优先** (不删除, 避免静默改用户数据)。
6. 出口名不存在 / 已禁用 / 已删除 → **保留原文并标记 unresolved** (匹配时跳过), 汇总一条 WARNING
   (`出口规则无效 · N 行 — 第 X 行: 出口 "jp" 不存在`), 其余行照常生效。
   **绝不删行**: 规则在 DataStore、出口在 Room, 两者没有事务 —— 删行等于在改名/迁移/导入时序
   不一致时永久丢失用户配置 (与 `StatsRuleText` 的"精确删行"哲学一致)。
7. 上限 512 行 (超过拒绝保存); 128 行以上 UI 提示"每行都会在新建连接时匹配一次"。
8. `app:` 只接受**包名** (`[a-zA-Z0-9_.]`, 至少一个 `.`), 不接受 uid; 应用选择器写入包名。

### 6.3 匹配算法

**有序 first-match-wins** (与 sing-box / Clash 的 route 规则同构), 输入是**一个** `RouteTarget`:

```kotlin
data class RouteTarget(
    val uid: Int,          // AppUidResolver.NO_UID = -1
    val pkg: String?,      // uid → 包名; null = 未解析/无归属
    val domain: String?,   // 假 IP 反查; null = 未知
    val ip: InetAddress,
    val port: Int,
)

fun select(t: RouteTarget): String? {
    for (rule in rules) {                 // 顺序即优先级
        if (rule.matches(t)) return rule.exitId
    }
    return null                            // → default
}
```

| 规则类型 | `matches` 判据 |
|---|---|
| `app:` | `t.pkg != null && t.pkg == rule.pkg` (+ 可选端口) |
| 域名 | `t.domain != null && suffixMatch(t.domain, rule.suffix)` (+ 可选端口) |
| CIDR / 精确 IP | `CidrRoute.matchesRaw` / 精确比较 (+ 可选端口) |

> **`RouteTarget.ip` 必须取 `conn.dstIp`**, 不能取 `ParsedPacket.dstIp`: `select` 跑在
> `forwardSynToTunnel` 协程里, 此时 TUN 读缓冲可能已被下一帧覆盖 (AGENTS 的"惰性地址同帧"
> 不变量), 越帧读取会让快照里的 `地址惰性失效` 增长。`conn.dstIp` 是连接自己持有的
> `InetAddress`, 生命周期与连接一致, 不参与该不变量。

`select` 里还有一条**必须先判**的分支: `if (rule.exitUnresolved) continue` —— 引用不存在出口的
规则**保留在表里但不参与匹配** (D-14)。它不能算作"命中 default", 因为用户的本意是走别的出口;
跳过它意味着"这条规则暂时不存在", 后面的规则继续有机会命中。

为什么不做"最长后缀 / 最长前缀"打分:

- 两套尺度混在一起 (域名标签数 vs 前缀长度) 无法给出一致的优先级直觉;
- 用户能看见行号, "上移/下移"是可解释的; 打分是隐式的;
- 规则量级是几十条, 顺序扫描的成本远低于一次 SYN 的其他开销 (§6.6 预算)。

为什么应用规则**不单列一张表**: 单表只有一根优先级轴, 用户拖动行就能表达"app 优先"还是
"域名优先"; 两张表必须再定义"谁先", 而任何默认都会让另一半用户意外 (D-2 的同一理由)。

### 6.4 域名与应用可见性 (最重要的一条约束)

先明确一件事: **规则只能作用于"真的进了 TUN 的连接"**。而各 DNS 模式加的路由完全不同
(`SshVpnService.buildVpnBuilder` `:630-660`):

| 模式 | 进 TUN 的目标 | 域名可得? | 域名规则 | IP/CIDR 规则 | 应用规则 | 多出口可用? |
|---|---|---|---|---|---|---|
| REMOTE (默认, 假 IP) | 全部 (`0.0.0.0/0`, `::/0`) | ✅ 假 IP | ✅ | ✅ | ⚠️ **只对字面量 IP 生效** | ✅ |
| WHITELIST (非空名单) | 白名单应用的全部 | ✅ 假 IP | ✅ | ✅ | ✅ | ✅ |
| WHITELIST (空名单 → SYSTEM) | **无路由, 不进 TUN** | — | ❌ | ❌ | ❌ | ❌ 功能不可用 |
| DOMAIN_SPLIT (名单命中) | 假 IP 段 (`FAKE_V4_ROUTE`) | ✅ 假 IP | ✅ | ⚠️ 只能匹配到假 IP 字面, 无实际意义 | ✅ | ✅ |
| DOMAIN_SPLIT (名单未命中) | **不进 TUN** (真实 IP 直连物理网卡) | ❌ | 仅当规则强制分配假 IP (§6.5 D-5) | ❌ | ⚠️ 仅对"本就进 TUN 的连接"生效 | ⚠️ 需域名规则显式提升 |
| SYSTEM | **无路由, 不进 TUN** | ❌ | ❌ | ❌ | ❌ | ❌ 功能不可用 |

由此得到五条硬约束:

0. **REMOTE（假 IP）模式下 IP/CIDR 规则只对"直接连接字面量 IP"的请求生效**: 域名连接的
   `conn.dstIp` 是 `198.18.x.x` 假 IP, IP 规则按它匹配永远不命中 (真实 IP 只有远端解析时才知道)。
   这不是实现 bug 而是假 IP 架构的必然结果 —— UI 必须给出提示 (规则页已加)。
1. **SYSTEM 模式与"白名单空名单"下没有任何 TUN 流量** (这不是"DNS 不劫持", 是连 TCP 都不进 TUN),
   多出口在这种模式下**整体不可用** —— UI 必须显示"当前 DNS 模式不支持多出口", 而不是让用户
   配了半天规则却毫无反应。
2. **DOMAIN_SPLIT 下 IP 字面量地址 (如 `curl https://1.2.3.4/`) 根本不进 TUN**,
   IP/CIDR 规则对它无效; 该模式只有"域名 → 假 IP"这一条路能进 TUN。
3. **DOMAIN_SPLIT 下未命中名单的域名默认不进 TUN**, 出口规则要生效就必须先把它变成假 IP
   (即 D-5 的"显式规则强制走隧道")。**只有域名规则能触发这个提升** —— 纯 IP/CIDR 规则
   在 DNS 阶段不可见, 提升不了。
4. **应用规则不能把流量拉进 TUN** (N7/N9): 进不进 TUN 由 `addAllowedApplication` + 路由表
   在 SYN 之前决定。应用规则只决定"**已经进 TUN 的连接**走哪个出口"。所以在 SYSTEM / 空白名单
   下应用规则完全无效; 在 DOMAIN_SPLIT 下只对"本来就是假 IP 目标"的连接有效。
   UI 必须在应用规则区给出与模式相关的提示, 否则用户会以为"配了没反应"。
   **WHITELIST 模式下还有一层**: 不在白名单里的应用流量根本不进 TUN, 给它们写 `app:` 规则
   同样无效 —— 应用选择器里应把非白名单应用标灰并提示"需先加入白名单"
   (这是"两个名单"最容易混淆的一处: 白名单决定**进不进** TUN, 出口规则决定**进哪个**出口)。
5. **DNS 没有应用维度** (N8): DNS 报文由 netd/系统解析器代发, 报文的 uid 不是发起应用。
   因此应用规则**不参与** `shouldTunnel` / `cachePolicyTag` —— 那两处只看域名 (§8.8)。

### 6.5 与直连/分流判定的优先级 (D-5 决策)

**排除路由 > 模式是否进 TUN > 出口规则 > 分流直连 > 默认隧道**

| 顺序 | 判定 | 结果 |
|---|---|---|
| 1 | 命中 `excludedRoutes` (用户/系统显式排除) | **直连**, 出口规则不生效 (安全阀: 局域网/系统 DNS 不允许被隧道劫走; 应用规则也不覆盖它) |
| 2 | 当前模式不加 TUN 路由 (SYSTEM / 白名单空名单) | **不适用** —— 没有连接可路由 (§6.4) |
| 3 | **域名**有出口规则 (且域名已知) | **隧道**, 即使 `DOMAIN_SPLIT` 本会直连; 该域名必须分配假 IP |
| 4 | `DOMAIN_SPLIT` 且非假 IP | 直连 |
| 5 | 其它 | 隧道 (由 `resolveExit` 选出口; **应用规则在这一步生效**) |

理由: 如果出口规则在"本会直连"的域名上不生效, 用户在分流模式下写了规则却毫无反应 ——
这是最坏的失败形态 (静默无操作)。所以**显式域名规则 = 显式要求走隧道**。
配套要求: `DnsInterceptor` 必须把"有出口规则的域名"也算作隧道域 (§8.8), 否则会先直连解析、
拿到真实 IP, 而真实 IP 在 `DOMAIN_SPLIT` 下根本不进 TUN, 规则永远匹配不上。
谓词必须**忽略端口** (`example.com:443 -> hk` 也要能把 `example.com` 提升进隧道)。

`default -> x` **不改变**"直连/隧道"判定, 只改变"隧道走哪个出口" —— 分流语义保持不变。
它也不能让"本来不进 TUN 的流量"进 TUN (不存在"把直连流量捞回隧道"的路径)。
应用规则同理: 它只改出口, 不改"直连/隧道"。

**规则命中但出口不可用 (Starting/Degraded/Failed) → 走 default 出口, 仍然走隧道**,
绝不回落成直连: 否则同一域名在出口抖动时会在线路完全不同的两条路径间跳变,
而用户看到的是"偶尔打不开 / 偶尔 IP 不对"。这也是 §16 D-5 的边界, 与 §9.1 的回退语义一致。

**应用规则命中但 uid 拿不到 → 视作未命中** (继续匹配后续行), 最终走 default 出口。
不缓存"上次这个 app 走哪"来猜测 —— 猜错比走 default 更糟 (N5 的同一理由)。

### 6.6 性能预算

| 项 | 预算 | 说明 |
|---|---|---|
| 规则解析 | 每次文本变更一次 | `@Volatile` 快照交换, 不阻塞数据面 |
| 单次 `select` | ≤ 200 行 × ~0.1µs ≈ 20µs (上限 512 行 ≈ 50µs) | 只在 SYN 调用 (新建连接数 ≪ 包数); 应用规则是字符串等值比较, 比域名后缀更快 |
| **uid 等待** | **≤ 50ms (硬上限), 正常 < 2ms** | 只在 `forwardSynToTunnel` 协程里; 超时 = `NO_UID` + 计数, 不重试不阻塞 |
| **uid → 包名** | 会话级缓存, 首次一次 binder (`getNameForUid`) | 缓存命中是哈希查表; 查不到 = null (应用规则跳过) |
| 域名还原 | 已有 `lookupDomain` (哈希查表) | 不新增映射, 不复用 `target()` 的分配 |
| 内存 | 规则表常驻 < 50KB | 不可变对象 + 解析后丢弃原文 |

---

## 7. 数据模型

### 7.1 Room v5: `proxy_exits`

```kotlin
@Entity(
    tableName = "proxy_exits",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["name"], unique = true), Index("serverId")],
)
data class ProxyExitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,            // 规则引用名, 小写唯一
    @ColumnInfo(name = "serverId") val serverId: Long,      // servers.id
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "priority") val priority: Int = 100, // 故障转移顺序, 小者优先
    @ColumnInfo(name = "createdAt") val createdAt: Date = Date(),
    @ColumnInfo(name = "updatedAt") val updatedAt: Date = Date(),
)
```

迁移 (AppDatabase version 5):

```sql
CREATE TABLE IF NOT EXISTS `proxy_exits` (
    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    `name` TEXT NOT NULL,
    `serverId` INTEGER NOT NULL,
    `enabled` INTEGER NOT NULL DEFAULT 1,
    `priority` INTEGER NOT NULL DEFAULT 100,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_proxy_exits_name` ON `proxy_exits`(`name`);
CREATE INDEX IF NOT EXISTS `index_proxy_exits_serverId` ON `proxy_exits`(`serverId`);
```

> **不要**把 `host/port/username` 复制进这张表: 服务器详情是 `servers` 的真相源,
> 复制会出现"改了服务器地址, 出口还用旧地址"的分叉。

### 7.2 DataStore 键

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `multi_exit_enabled` | Boolean | **false** | 总开关; false = 零行为变化。**变更在下次连接生效** (不打断已有连接) |
| `multi_exit_rules` | String | `""` | 规则文本 (§6.2); 应用规则存**包名**, 不存 uid |
| `multi_exit_failover_enabled` | Boolean | true | 附加出口故障时回退 default (false = 命中降级出口的连接直接 RST, 由客户端重试) |
| `multi_exit_prio_<exitId>` | — | — | **不设**: 优先级在 DB 表里 |

> 规则文本与 `ad_block_rules` / 域名名单同构 (纯文本 + 分键存放), 便于导入导出、
> 也避免把"每次改一行"写成 DB 事务。

> **不落库 `uid → 包名` 映射**: 它是设备事实 (重装/多用户会变), 每次会话由
> `AppIdentity` 现解析并缓存; 落库只会得到一份过期的对照表。

### 7.3 为什么不复用 `servers` 表当出口

| 方案 | 问题 |
|---|---|
| 用 `servers` + DataStore 存"启用集合" | 规则要引用服务器名 —— 名字不唯一 (用户可存两台同名), 规则会串号; 没有 per-exit 优先级/启停 |
| 用 `servers.id` 直接引用 | 规则变成 `-> 3`, 用户不可读、不可迁移 |
| 新增 `proxy_exits` (选定) | 名字唯一 + 可启停 + 可排序, 且删服务器自动级联 |

### 7.4 迁移与回滚

- 升级: 4 → 5 只加表, 现有数据零改动。
- **降级 (装回 v4 APK) 会在打开 DB 时抛版本不匹配** —— 这是 Room 的既有语义。
  发布说明必须写"降级需清除应用数据"; 内测阶段可接受。
- 回滚本功能 (关开关) 不需要降级 DB: 表留着不用即可。

---

## 8. 逐文件改造

> 分两批: **P0 = 只拆接缝, 行为零变化**; **P2 = 真正启用多出口**。
> P0 单独可合并、可回归, 不必等 UI 就绪。

### 8.1 `domain/vpn/tunnel/TunnelConfig.kt`

```kotlin
data class Socks5(
    override val common: CommonConfig = CommonConfig(),
    val sshHost: String,
    val sshPort: Int = 22,
    val sshUsername: String,
    val sshKeyAlias: String,
    val sshPassword: String? = null,
    val sshKeyAlgorithm: String = "ECDSA_P256",
    val socksPort: Int = 1080,                 // 主出口 = server.socksPort; 附加出口由调用方显式传 0
    // 新增 (P0):
    val serverId: Long = 0,                    // 0 = 临时配置, 跳过指纹落库
    val hostKeyFingerprint: String? = null,    // 既有 DB 指纹, 运行时强校验
)
```

- `forSocks5(server, password)` **签名不变**, 内部把 `server.id` 与 `server.hostKeyFingerprint`
  一起带过去 (修附录 C-1)。
- **`exitId` 不进 `TunnelConfig`**: 出口身份只在插件实例上 (`Socks5TunnelPlugin.exitId`),
  两份会分叉 (配置写 `default`、实例是 `hk`)。
- **端口由调用方显式决定**: 主出口传 `server.socksPort`, 附加出口传 `0` (系统分配)。
  不要藏进 `forSocks5` 的隐式分支 —— 两个出口拿到同一端口时 bind 失败的报错看起来像"出口坏了"。
- **C-1 是安全策略收紧, 不是零行为变化**: 补上指纹后隧道连接会开始**强校验** DB 里的历史指纹。
  指纹过期/不匹配的用户会从"能连"变成"连不上" → 必须单独提交、单独发版说明, 失败文案明确指向
  「设置 → 重置主机指纹」; **不要**把它混进 P0 的"逐字一致"验收 (S17)。

### 8.2 `data/remote/ssh/JschSshClient.kt` + 新工厂

- `JschSshClient` 的**数据面逻辑不改** (仍然是 `@Singleton` 那一套, 供主出口与
  `RemoteCommandExecutor` / `RemoteDomainResolver` / 向导继续使用)。
- **但必须加一个出口标签**, 否则附加出口池失败会打出"整条会话即将断开"的 ERROR (附录 C-5):

```kotlin
// body 属性, 不是构造参数: @Inject 构造函数加 String 参数会让 Dagger 去找 String 绑定。
@Volatile var exitLabel: String = ""
```

  由工厂 (附加出口) / `VpnController.connect` (主出口) 在 `client.connect()` **之前**赋值。
  两处日志按标签分流:

  | 位置 | 主出口 (`exitLabel == ""`) | 附加出口 |
  |---|---|---|
  | `checkPoolHealth()` (`:1297`) | 原文案 `SSH 会话全部丢失 · 连接即将断开并触发重连` + **ERROR** | `出口 SSH 会话全部丢失 · hk — 新连接改走 default` + **WARNING**, `throttleKey = "出口降级"` |
  | `createDirectChannel` 无可用会话 (`:1426`) | 原文案 + ERROR | `出口无可用会话 · hk — 新连接改走 default` + WARNING |

  > 不改这两行, §9.4 / E-3 的"附加出口故障不影响全局判读"和验收 S21 的"不得出现"清单
  > 会**确定性失败** —— 它们进的是 `故障归因` 的 ERROR 计数。

- 新增 `data/remote/tunnel/TunnelPluginFactory.kt`:

```kotlin
interface TunnelPluginFactory {
    val protocolId: String
    fun create(exitId: String, label: String): TunnelPlugin
}

@Singleton
class Socks5TunnelPluginFactory
    @Inject
    constructor(
        private val primaryClient: JschSshClient,      // 主出口复用 Hilt 单例
        private val keyManager: SshKeyManager,
        private val knownHosts: KnownHostsManager,
        private val serverDao: ServerDao,
        private val dnsInterceptor: DnsInterceptor,
        private val sshIoDispatcher: SshIoDispatcher,
        private val budgetHolder: ConnectionBudgetHolder,
    ) : TunnelPluginFactory {
        override val protocolId = "socks5"

        override fun create(exitId: String, label: String): TunnelPlugin =
            Socks5TunnelPlugin(
                client = if (exitId == ExitId.DEFAULT) primaryClient
                         else JschSshClient(keyManager, knownHosts, serverDao)
                             .also { it.exitLabel = exitId },
                dnsInterceptor = dnsInterceptor,
                sshIoDispatcher = sshIoDispatcher,
                budgetHolder = budgetHolder,
                exitId = exitId,
                label = label,
            )
    }
```

> 只有主出口共享 `@Singleton` 客户端, 是为了让**探测/向导/远端解析**继续拿同一条池
> (`AppModule.kt:39-43` 的绑定不变); 附加出口用独立实例, 故障域才真正隔离。
> **附加出口的 `ServerConfig` 必须由 `VpnController.launchAdditionalExits` 从
> `ServerRepository` 取 (解密后的密码)** —— 直接读 `ServerEntity.password` 拿到的是密文,
> 与 AGENTS「Server persistence」的纪律冲突。

### 8.3 `data/remote/tunnel/Socks5TunnelPlugin.kt`

| 改动 | 说明 |
|---|---|
| 去掉 `@Singleton` | 由工厂创建 (主出口也走工厂, 见 §8.10); 不再被任何地方 `@Inject` |
| ctor 增加 `exitId: String`, `label: String` (显式, 无默认值) | Dagger 不能注入带默认值的额外参数 (会尝试解析 `String`) |
| ctor 注入 `ConnectionBudgetHolder` | `connect()` 时取当前会话的 `ConnectionBudget` 传给 `Socks5ProxyServer` (§10 I-8); holder 为空 = 不限流 |
| `connect` 构造 `ServerConfig` 时带上 `id` / `hostKeyFingerprint` | 修 C-1 |
| 端口策略 | **原样使用 `c.socksPort`** (主出口 = 配置端口, 附加出口调用方已传 0); 不再按 exitId 分支 |
| 新增 `override val exitId` / `override fun healthState()` | 见 §9.1 |
| 新增 `override val activeConnections: Int` | 直接委托 `socksServer?.activeConnections ?: 0` (§8.9 暴露, 单一来源); 软上限与快照都读它 (I-19) |
| 新增 `exitScope` | `connect()` 里创建 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, 订阅 `client.connectionState`; 收到 `Failed` 且非主出口 → 置 `Degraded` (主出口不在此处理, 由 `SshVpnService` 走整体重连); `disconnect()` 里 `cancel()`; 协程体内 `CancellationException` 必须 rethrow (既有观测纪律) |
| `tunnelDiagnostics()` 前缀出口名 | `[hk] 代理 活跃 3 连接 · …`; 关闭多出口时前缀为空, 保持现有文案 |
| `exitScope` 的 `Disconnected` | **不**视为故障 (那是 `disconnect()` 自己置的) |

### 8.4 `domain/vpn/tunnel/TunnelManager.kt` → 按出口分键

```kotlin
data class ActiveExit(
    val id: String,
    /** 展示名 (日志/UI 用), 通常就是出口名; 空串 = 主出口 (日志里不前缀) */
    val label: String,
    /** 服务器地址, 只用于快照/列表展示 (`出口 hk(hk2.example.com)`) —— 不要塞进 label */
    val serverHost: String,
    val plugin: TunnelPlugin,
    val startedAt: Long,
    @Volatile var health: ExitHealth,
    @Volatile var degradedSince: Long,
    @Volatile var lastProbeOkAt: Long,
    // 会话级计数: startExit 时清零 (I-9)
    @Volatile var connTotal: Long = 0,
    @Volatile var failTotal: Long = 0,
    @Volatile var degradeTotal: Long = 0,
    @Volatile var lastError: String = "",
    // 活跃连接数**不在这里记账**: 单一来源是 `plugin.activeConnections`
    // (= `Socks5ProxyServer.connections.size`), 软上限直接读它 (I-19)。
    // 另记一份 = 两处增减不同步 → 软上限要么永不触发要么永久触发。
)

private val exits = ConcurrentHashMap<String, ActiveExit>()
private val startJobs = ConcurrentHashMap<String, Job>()
private val exitMutex = Mutex()                 // 注册/摘除串行
private val perExitMutex = ConcurrentHashMap<String, Mutex>()
@Volatile private var sessionGeneration = 0L    // stopAll 自增
```

| 方法 | 语义 |
|---|---|
| `startExit(id, protocolId, label, serverHost, config): Result<Unit>` | 幂等; 同 id 已在运行直接返回 success; 失败**不**移除旧可用实例; **先清零该出口的会话计数**。`label` 只进日志前缀, `serverHost` 只进快照 —— 两者别混用 (混了日志会变成 `出口 hk2.example.com — …`, 用户对不上出口名) |
| `exit(id): ActiveExit?` | 只返回已注册实例 (可能 Degraded) |
| `resolveExit(target: RouteTarget): ActiveExit` | 规则 + 健康 → 一定返回一个 (最终兜底 default); 若 default 都不可用则返回它并让下层按现有语义失败。**软上限只约束"非 default"出口**: 目标出口 `plugin.activeConnections >= EXIT_CONN_SOFT_CAP` 时改回 default; **default 自己满员时不在这里拒绝** (交给既有的 `SshIoDispatcher.isSaturated()` → `0x05` 路径, 语义与今天一致; 在这里拒会没有 SOCKS 应答通道)。P0 (只有 default) **不启用软上限** —— 否则等于把 `MAX_CONNECTIONS` 从 512 悄悄降到 128 |
| `stopExit(id)` | 停单个出口 (主开关运行时关闭 / 用户禁用); **不**打断已有连接 → v1 只在下一次连接生效 |
| `exitsSnapshot(): List<ActiveExit>` | 快照, 供热路径只读 |
| `exitDiagnostics(): String` | `§12.1` 的 `出口 …` 节文本 (每出口一行); **未启用/只有主出口时返回空串** (G6: 关闭时日志逐字节不变) |
| `stopAll()` | `sessionGeneration++` → cancel 所有 startJobs → 逐个 stop |
| `getActiveOrFallback(): TunnelPlugin?` | **改为可空**: 工厂化后未 `startExit` 时没有实例。**三个调用点**都要判空: `SshVpnService:216` (探活凭据, 空则跳过本轮探活) / `VpnController:1333` (快照, 空则空串) / `TcpStateMachine:569` (P0 期间仍在用, 空则按现有"无本地端口"语义关闭连接; P2 起该行被 `resolveExit` 取代) |

`stopAll()` 顺序**不能反**: 先 cancel 在途 start, 再停实例 —— 否则一个 300ms 后启动的
附加出口会在断开之后把 SSH 池和端口重新拉起来 (泄漏到下个会话)。

**分阶段**: P0 只做"按出口分键 + `startExit` + 单出口", 此时 `resolveExit` 恒返回
`ExitId.DEFAULT` (不注入 store); P1 才注入 `RoutingTableStore` 并把规则匹配接上 —— 这样
P0 可以独立合并、单独回归, 行为零变化。

**规则快照只有一个来源**: `RoutingTableStore` (`@Singleton`, 持有 `@Volatile` 不可变快照) 注入
`TunnelManager` (供 `resolveExit`); `DnsInterceptor` 不持有副本, 只拿一个委托谓词
(`exitRulePredicate`, 见 §8.8) 现取同一个 store —— 不允许任何一处自行解析规则文本
(否则会出现"DNS 认为是隧道域、路由表却没这条规则")。
`resolveExit` 是**非挂起**且无锁的: 它只做一次引用读 + 规则匹配 + 一次 map 查。
`resolveExit` 命中但出口不是 `Ready` → 返回 default 并 `exitFailoverTotal++` (不抛异常)。
命中出口 `Ready` 但 `activeConns >= EXIT_CONN_SOFT_CAP (128)` → 同样返回 default 并
`exitSoftCapTotal++` (I-19) —— 软上限必须在这里判, 不能放进全局 `ConnectionBudget`
(那是所有出口共用的, 判断不了"谁吃掉了容量")。

### 8.5 `domain/vpn/TcpStateMachine.kt`

| 位置 | 改动 |
|---|---|
| `:33-44` 构造参数 | 不新增 `TunnelManager` 依赖 (路由结果由 `forwardSynToTunnel` 里取); 新增 `appIdentity: AppIdentity? = null` —— 默认值**只服务测试**, 生产由 `PacketProcessor` 注入并**显式传入** (与 `appUidResolver` 同款风格, 后者已在手 `:1858`)。**不传 = 应用规则永远不命中**, 这是最容易漏的一处接线 |
| `:177` `tunCallbackPlugin` | **删除**, 改为 `conn.tunnelPlugin: TunnelPlugin?` (body 字段) |
| `:192-224` `TcpConnection` | 新增 `@Volatile var exitId: String = ExitId.DEFAULT`、`var tunnelPlugin: TunnelPlugin? = null`、`var appPkg: String? = null`、`var uidRequest: UidRequest? = null` (**body 属性, 不进构造参数** —— 否则破坏 data class 的 equals/copy 语义, 与 `lastOpenStallAtMs` 同类坑) |
| `:555-600` `forwardSynToTunnel` | ⓪ **bypass 判定先行** (`:560`, 现有代码位置不变) —— 直连路径**不**等 uid; ① `uid = conn.uidRequest.await(50)` (句柄在 SYN 时已入队, **不再发第二次查询**); ② `pkg = appIdentity?.packageOf(uid)`; ③ `target = RouteTarget(...)`; ④ `decision = tunnelManager.resolveExit(target)`; ⑤ `conn.exitId = decision.id`; ⑥ 失败分类 → 可选重试一次 (§9.5) |
| `:839` `forwardThroughLocalSocks(conn, plugin, socksPort)` | 返回值从 `Unit` 改为 `ForwardOutcome`; 成功路径不变; **三条早退 (`:858/:916/:940`) 改为 `return ForwardOutcome.ExitUnavailable(...)` 而不是就地 `closeTcpConnection`** —— 是否关闭由调用方按"能否重试"决定 |
| `:887` | `tunCallbackPlugin = plugin` → `conn.tunnelPlugin = plugin` |
| `:1847` `createTcpConnection` 里的 `enqueueAppUidLookup` | **改为发起一次 `request(...)` 并把句柄存到 `conn.uidRequest`**: 路由路径 `await` 同一个句柄 (不再发第二次 binder 调用); 统计侧用 `onComplete { conn.appUid = it }` 回写, 与 `closeTcpConnection` 共用同一份缓存值 |
| `:1973-1977` | 用 `conn.tunnelPlugin?.remove…`; 先取局部变量再判空, 移除后置 null |
| `:1393` `startRelayFromSocks` | 不变 (fallback 路径本来就传 conn) |

`ForwardOutcome` (v2):

```kotlin
sealed interface ForwardOutcome {
    data object Established : ForwardOutcome

    /**
     * **出口级**失败, 只可能来自 CONNECT 之前:
     * LOOPBACK (本地代理不可达) / NEGOTIATE (SOCKS5 协商) / AUTH (凭据) / BUDGET (线程池饱和).
     * 此时尚未发 SYN-ACK, 客户端只看到 SYN 重传 → 可以安全改投 default。
     */
    data class ExitUnavailable(
        val exitId: String,
        val stage: String,
        val elapsedMs: Long,
    ) : ForwardOutcome

    /** **目标级**失败: CONNECT 应答 rep != 0 / 远端秒拒 / 目标超时 —— 换出口只会把一次失败变成两次 */
    data object TargetFailed : ForwardOutcome

    /** 连接已被关闭 (客户端 RST / 已达 Established 后失败) */
    data object Aborted : ForwardOutcome
}
```

> **为什么不能有 `ExitFailed` 这种"出口坏了"的通用分类**: `Socks5ProxyServer.connectToTarget`
> 把所有失败都映射成 `rep = 0x05` (`:1180-1210`), 到 `TcpStateMachine` 这一层**分不出**
> "出口整体不可用"与"远端不解析这个域名"。所以分类必须按**阶段**切 (CONNECT 之前 / 之后),
> 而不是按"谁的错"切 —— 阶段是可以从代码结构上确定的, 归因不是。

`releaseHandshakeResources(conn)` (新增私有函数, 重试与关闭共用):

```kotlin
// 只在"还没发 SYN-ACK"时调用: 摘回调 + 关通道 + 清 tunnelPlugin, 但**不**关连接、不置 Closed。
// 顺序必须是 remove 回调 → disconnect 通道 → 置 null, 否则 close() 会再 invoke 一次 EOF 回调。
// 注意接口只有 removeTunCallback/removeTargetEofCallback(port) —— 没有 "register(port, null)" 这种形态。
private fun releaseHandshakeResources(conn: TcpConnection) {
    conn.tunnelPlugin?.let { plugin ->
        plugin.removeTunCallback(conn.localPort)
        plugin.removeTargetEofCallback(conn.localPort)
        plugin.disconnect()
    }
    conn.tunnelPlugin = null
}
```

重试条件**只有一份, 写在 §9.5** (八条, 缺一不可)。这里不重复列举 —— 两份清单必然漂移,
而 §9.5 是"什么时候允许改投"的唯一权威描述。

动作顺序 (实现期照抄):

```kotlin
if (shouldRetry(outcome, conn, elapsedMs)) {          // §9.5 的八条
    releaseHandshakeResources(conn)                    // 摘回调 → 断通道 → 置 null
    conn.exitId = ExitId.DEFAULT
    conn.exitRetried = true
    exitRetryTotal.incrementAndGet()
    VpnController.appLog("出口快速失败重试 · ${target(conn)} — " +
        "${from} → default (${outcome.stage} ${elapsedMs}ms 即失败)")
    return forwardThroughLocalSocks(conn, defaultPlugin, defaultPort)   // 只重跑一次
}
```

### 8.5.1 `AppUidResolver` 改造 + 新增 `domain/vpn/AppIdentity.kt`

按应用分流唯一的代码阻碍是"查询没有完成信号" (`AppUidResolver.kt:28-34`: 只有拿到 `>= 0`
才回调)。改造**只加一个会完成的请求句柄**, 不动已有的统计回写路径。

**关键设计: 一次查询, 两个消费者**。`createTcpConnection` (packetLoop, SYN 到达) 本来就会
发起反查 —— 路由路径**不重新发起**, 而是等同一个请求:

```kotlin
// domain/vpn/AppUidResolver.kt
interface AppUidResolver {
    /**
     * 在 SYN 到达时发起 (packetLoop, 非阻塞: 只入队)。
     * **一定完成**: 命中给 >= 0, 否则给 [NO_UID] (无归属 / 平台拒绝 / 队列满 / 超时)。
     * 返回的句柄可被多个消费者 await (路由 + 域名统计), 内部只查一次 binder。
     */
    fun request(
        srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int,
    ): UidRequest

    fun diagnostics(): String
}

/** 一定完成的请求句柄。 */
interface UidRequest {
    /** 有界等待: 命中给 uid, 超时给 [NO_UID] (不抛异常)。协程挂起, 不占线程。 */
    suspend fun await(timeoutMs: Long = UID_WAIT_TIMEOUT_MS): Int   // 50

    /** 统计侧用: 不等待, 完成时回调 (在反查线程上); 已完成的立即回调。 */
    fun onComplete(onDone: (Int) -> Unit)
}
```

> **为什么不是 `lookup(..., onDone)` + 独立的 `awaitUid(...)`**: 两个入口各自入队 =
> 每条新连接两次 binder 调用 + 两个 `CompletableDeferred`, 而且第二次必然排在第一次后面,
> 50ms 预算内更容易超时。句柄模型让"发起一次、多人等"在类型上就成立, 也顺带修掉
> `enqueueAppUidLookup` 的重复查询 (§8.5 表最后一行)。
>
> **术语**: 下文出现的 `awaitUid` 一律指 `conn.uidRequest.await(50)` 这一次有界等待
> (不是第二个接口方法) —— 保留这个短名只是为了在表格与验收清单里指代"等 uid 这一步"。

实现要点 (`ConnectivityAppUidResolver`):

| 项 | 现状 | 改为 | 理由 |
|---|---|---|---|
| 工作线程 | 单线程 | **2~4 线程** (有界) | 单线程在"分页一次开 6~10 条连接"时排队, 50ms 预算内可能排不完 → 全落 `NO_UID` → 应用规则集体失效 |
| 在途上限 | `MAX_PENDING = 64` | 保留 64; 超限**立即 `complete(NO_UID)`** | 旧实现是静默丢弃 (永不回调) → 等待方只能吃满超时 |
| 完成语义 | 无 | 每个请求一个 `CompletableDeferred<Int>`, **所有**出口路径 (成功 / `-1` / 异常 / 拒绝 / 超限 / 窗口暂停) 都 `complete` | "一定完成"是 `await` 有界的前提 |
| 统计接线 | `attachAppUid` 用关闭时的值 | `request` 的句柄在 SYN 阶段 `onComplete { conn.appUid = it }`, 关闭时仍用缓存值 | 同时把"关闭时才查"的 7/105 命中率问题一并修掉 (SYN 时 socket 必然存在) |
| 平台拒绝 | 永久停用 | 保持 `DisableWindow` 30s 窗口 | AGENTS 铁律: 断开瞬间的 `SecurityException` 不能杀死功能到下次进程启动 |
| 指标 | `hits/misses` | 追加 `uidWaitTotal` / `uidTimeoutTotal` / `uidMissTotal` / `uidQueueDropTotal` | 进快照 (§12), 否则"应用规则没生效"无从判读 |

```kotlin
// domain/vpn/AppIdentity.kt (新增, 纯查表 + 缓存)
@Singleton
class AppIdentity @Inject constructor(
    @ApplicationContext context: Context,
    /** 可注入的查表函数: 生产用 PackageManager, 单测用假表 (无 Robolectric) */
    private val lookup: (Int) -> String? = { uid ->
        runCatching { context.packageManager.getNameForUid(uid) }.getOrNull()
    },
) {
    private val cache = ConcurrentHashMap<Int, String>()   // uid -> package

    /** uid → 包名; 查不到 (uid 已消失 / 无权限) 返回 null。共享 uid 返回组内首个包名。 */
    fun packageOf(uid: Int): String? {
        if (uid < 0) return null
        cache[uid]?.let { return it }
        // 只缓存命中, **不写负缓存**: 应用可能正在安装/启动, 下一次连接应重试
        return lookup(uid)?.also { cache[uid] = it }
    }

    fun clear() = cache.clear()          // connect() 时清一次 (重装/多用户切换后 uid 会变)
    fun diagnostics(): String = "应用映射 ${cache.size} 条"
}
```

- **共享 uid**: `getNameForUid` 对共享 uid 返回组内第一个包名 → 按该包名写的规则会命中**整组**。
  UI 的应用选择器必须提示这一点 (否则用户以为只影响一个 app)。
- **缓存失效**: 只在 `VpnController.connect` 清空; 会话中途装/卸应用不影响已有规则匹配
  (新装的应用本来也没有规则)。
- **不写负缓存**是有意的: `getNameForUid` 在应用刚安装/刚启动的瞬间可能返回 null,
  写负缓存会让这条规则在整个会话里永久失效 (与 `DnsInterceptor` 的"假 IP 归零宽限期"同类教训)。
- **绝不参与隧道决策**: `appPkg` 只喂 `RouteTarget`, 不参与 `shouldTunnel` / `cachePolicyTag`
  (§6.4 约束 5), 也不参与 `DomainStats` 的失败判定。

**接线检查点** (最容易漏的三处, 每处都要有单测):

| # | 接线 | 漏了的现场 |
|---|---|---|
| 1 | `PacketProcessor` 注入 `AppIdentity` 并**显式**传给 `TcpStateMachine` | 规则解析正常、UI 正常、**应用规则永远不命中** (静默) |
| 2 | `createTcpConnection` 把 `request(...)` 的句柄存到 `conn.uidRequest` | 路由路径重新发一次查询 → 延迟翻倍、50ms 更容易超时 |
| 3 | `forwardSynToTunnel` 用 `conn.uidRequest.await(50)` | 拿不到 uid → 全部走 default (与 #1 的现象相同, 但日志里有 `uidTimeoutTotal` 增长) |

### 8.6 `domain/usecase/VpnController.kt`

| 位置 | 改动 |
|---|---|
| `:392-399` `computeShouldBypassTcp` | 新增 `domain: String?` + `hasExitRule: Boolean` 参数; 判定顺序见 §6.5; 纯函数, 单测覆盖。**应用规则不参与** (没有 DNS 可见性) |
| `:447` | `startPlugin("socks5", cfg)` → `tunnelManager.startExit(ExitId.DEFAULT, "socks5", label = "", serverHost = cfg.sshHost, cfg)`; 失败语义不变。主出口 `label` 必须是**空串** (日志不加前缀, G6 零行为变化) |
| `:453` 之后 | 新增 `launchAdditionalExits(gen: Long)`: 读开关 + `proxyExitDao.getAll()` (enabled, 按 priority), 逐个 `serverRepository.getServer(serverId)` 取**解密后的 `ServerConfig`**, 再 `tunnelManager.startExit(exit.name, "socks5", label = exit.name, serverHost = cfg.sshHost, Socks5.forSocks5(cfg, cfg.password).copy(socksPort = 0))`; 间隔 300ms; 每步先查 `gen == sessionGeneration` (断开后不再启动)。**`VpnController` 不需要注入工厂** —— 工厂解析在 `TunnelManager.startExit` 内部 |
| `:462-463` | `transportMode` 不变; 但需 `dnsInterceptor.setExitRulePredicate { store.snapshot().hasDomainRule(it) }` (见 §8.8) |
| `:522` | 排除路由不变 (出口规则不修改 TUN 路由) |
| `:588-651` `disconnect` | `stopAll()` 已是全出口; 快照取样位置不变 (`:611` 必须在 `:620` 之前) |
| `:1135-1138` `shouldBypassTcp` | **签名不变** (`(InetAddress, Int) -> Boolean`, 不改 `TcpStateMachine.setBypass` 的 lambda 形状); 内部 `lookupDomain(ip)` + `store.snapshot().hasDomainRule(domain)` 后转调新签名。每 SYN 多一次哈希查表, 可忽略。**谓词必须忽略端口** (`example.com:443 -> hk` 也要能提升 `example.com`) |
| `:1322-1340` 快照 | `tunnelManager.exitDiagnostics()` 传入新字段; 关闭时为空串; 追加 `appIdentity.diagnostics()` + uid 计数 (§12) |
| `:746` `forceReset` | **无需改**: 已调 `tunnelManager.stopAll()`, 覆盖全部出口 (勿改成逐个 `stopExit`) |

`launchAdditionalExits` 的三条纪律:

1. **主出口 READY 之后**才启动 (主出口失败 = 整体失败, 不该留下半套附加出口)。
2. **best-effort**: 单个出口失败只 WARNING + 置 `Failed`, 会话继续; 不重试 (由用户重连或后台恢复)。
3. **每次启动前后都检查会话代际**: 300ms 错峰期间用户可能已经断开 → 不带代际检查会把 SSH 池
   和本地端口泄漏到下一个会话 (`TunnelManager.stopAll()` 的 `sessionGeneration` 就是给这个用的)。

**总开关的运行时语义 (v1 明确)**: `multi_exit_enabled` 改变**不打断已有连接**。关闭时
`stopExit(附加出口)` 只影响**新连接** (已有连接继续走原出口直到关闭); UI 必须写
"下次连接生效", 不能让用户以为点了就立刻全切。打开时同样只在下一次 `connect()` 生效。

### 8.7 `vpn/SshVpnService.kt`

| 位置 | 改动 |
|---|---|
| `:209-261` `runHealthProbe` | 主出口探活不变 (全局状态); 追加 `probeAdditionalExits()` —— **搭同一个 15s 节拍**, **隔离窗口内 (降级 < 30s) 直接跳过**, 只更新各出口健康, 不改 `healthTracker`、不改全局 `verified` |
| `:215-216` | `socksPort = config.socksPort` → `tunnelManager.exit(DEFAULT)?.plugin?.localSocksPort ?: config.socksPort` (修 C-3); `socksAuth = tunnelManager.getActiveOrFallback()?.socksAuth` (可空后必须 `?.`; 为 null 时本轮探活记 `PROXY` 失败并跳过, 不要 NPE) |
| `:403-469` `connect` | 不变 (附加出口由 `VpnController.connect` 内部启动) |
| `:966-998` `observeJschConnectionState` | **只看主出口客户端**; 明确注释: 附加出口的 `Failed` 由 `TunnelManager` 内部处理, 绝不触发 `autoReconnect` |
| `:1147` `autoReconnect` | 不变; 重建时 `stopAll` + 重新启动全部出口 |

**附加出口探活必须并发, 不能串行**: 每个出口一次 `ConnectivityProber.probe()` 最坏 5s,
4 个附加出口串行就是 20s, 叠在 15s 节拍上会把主探活挤到下一轮 (表现为"状态卡偶尔 30s 不更新")。
做法: `coroutineScope { exits.map { async { probeExit(it) } }.awaitAll() }`, 总预算仍是 5s
(`withTimeoutOrNull` 包住单个探测), 任何出口超时只记该出口 `Degraded`, 不影响其它出口与主探活。

### 8.8 `domain/vpn/DnsInterceptor.kt`

**唯一改动: 把两处"是否走隧道"合并成同一个纯函数, 并让它认识出口规则。**

不引入新接口/新注入: 抽一个 `internal` 纯函数 (放 companion, 无 Android 依赖, JVM 直接测):

```kotlin
internal fun shouldTunnel(
    mode: DnsTransport,
    inProxyList: Boolean,
    hasExitRule: Boolean,
): Boolean = when (mode) {
    DnsTransport.SYSTEM -> false
    DnsTransport.REMOTE, DnsTransport.WHITELIST -> true
    DnsTransport.DOMAIN_SPLIT -> inProxyList || hasExitRule
}
```

两个调用点各自只负责取输入:

| 位置 | 改动 |
|---|---|
| `:190-200` `cachePolicyTag` | `val q = question.name.toString(true)` → `if (shouldTunnel(transportMode, domainListManager?.matches(q) == true, exitRulePredicate?.invoke(q) == true)) CACHE_TAG_TUNNEL else CACHE_TAG_DIRECT` |
| `:669-679` `useSystemDns` | `!shouldTunnel(transportMode, inList, exitRulePredicate?.invoke(qname) == true)` |

**谓词为 null (= 多出口关闭, `connect` 不 set) → 两条路径的布尔值全为 false →
判定与今天逐字节一致** (这是 G6 在 DNS 侧的落点)。

**`hasDomainRule(domain)` 的语义必须钉死**:

- **只看域名, 忽略端口**: `example.com:443 -> hk` 命中 `example.com` 时返回 true
  (否则 DNS 阶段提升不了, 而 TCP 阶段又永远拿不到域名 → 规则永久失效);
- **只看域名规则**: `app:` 与 CIDR/IP 规则**不参与** (应用/DNS 无 uid 关联, §6.4 约束 5);
- **只看"出口存在且可用"的规则?** 不 —— 谓词只判断"文本里有这条域名规则", 出口是否 Ready
  由 `resolveExit` 在 TCP 阶段处理。否则 DNS 与路由会因健康状态抖动而分叉 (同一域名
  时而假 IP 时而真实 IP, 缓存键也跟着跳)。

**接入阶段**: 谓词与 `TunnelManager.resolveExit` 的规则匹配**同一阶段接上 (P2)** —— 只接谓词
会出现"规则把域名强制拉进隧道, 但出口恒为 default"的中间态 (能跑但规则半生效, 最容易被当成 bug)。
P1 只做解析/匹配/UI, 不改变任何数据面行为。

`routingTable` **不复制文本、不缓存快照**: 走一个 `@Volatile` 谓词
(`setExitRulePredicate { store.snapshot().hasDomainRule(it) }`, 与 `setDomainListManager` 同款),
由 `VpnController.connect` 设置一次 —— 谓词内部每次现取 store 的当前快照, 所以规则重载**不需要**
重新 set, 也不会出现"DNS 侧看到旧表、路由侧看到新表"。

**这两处必须共用同一函数**, 否则缓存标签与实际路径分叉 (历史教训:
标签只影响缓存键, 不会报错, 表现为"改了设置要等 TTL 到期"或"规则加了没反应")。

单测 `DnsTunnelDecisionTest`: 列一张 `(mode × inList × hasExitRule)` 矩阵, 断言
`cachePolicyTag == TUNNEL` 当且仅当 `shouldTunnel(...) == true` —— 不允许出现只有一侧覆盖的组合。
**不**覆盖 `SYSTEM` 下的真机行为 (那里没有 TUN 流量, 见 §6.4), 只锁函数语义。

### 8.9 `domain/vpn/Socks5ProxyServer.kt`

| 改动 | 说明 |
|---|---|
| ctor 增加 `exitId: String = ""` | 只用于诊断前缀, 默认值保持测试兼容 |
| `diagnostics()` | 有 `exitId` 时前缀 `[$exitId] ` |
| **暴露活跃连接数** | 新增 `val activeConnections: Int get() = connections.size` (当前 `connections` 是 private, `:64`) —— 软上限与快照的唯一来源 (I-19); **不要**在 `ActiveExit` 里再记一份 |
| **会话累计** | 新增 `totalUp/totalDown/totalConnections: AtomicLong`; `onClosed` 回调签名改为 `(Long, Long) -> Unit`, 在 `:293-297` 的 lambda 里用**正在关闭那条连接**的 `toTunnelTotal/fromTunnelTotal` 累加 (关闭后对象即丢弃, 只有这里能拿到); `diagnostics()` 追加 `累计 ↑…B ↓…B · N 连` |
| 连接预算 (§10 I-8) | ctor 接受可选 `budget: ConnectionBudget?`; `handleAccept` (`:252-307`) 里 `tryAcquire` 失败 → `sendErrorReplyAndClose(0x01)` (通用失败) 并计 `budgetRejectTotal`; **release 只在 `onClosed` 回调里做一次** |
| 预算的 acquire 时机 | **必须在 `Socks5Connection` 构造完成 (含 `onClosed` lambda 已安装) 之后、`conn.start()` 之前**。早于构造 → 构造抛异常就漏 release; 晚于 `start()` → 连接可能在 acquire 之前关闭, `onClosed` 已跑完 → 永远不 release (预算只减不增, 最终所有新连接被拒) |

`onClosed` 的 release 为什么天然恰好一次: `Socks5Connection.close()` (`:1294`) 开头有
`state == Closed` 早退守卫, 且 `onClosed` 只在那一个 `close()` 的尾部调用 —— 不需要再引入
`releaseOnce` 标志 (引入反而容易与 `close()` 的幂等语义打架)。但**测试必须覆盖**
"连接在 `connect()` 进行中被 `close()`" (附录 D S26), 因为那条路径最容易漏 release。

其余 (eventLoop / 握手 / 回程 / 关闭语义 / 假 IP 拒接) **一行不改**。

### 8.10 `di/TunnelModule.kt` + `di/AppModule.kt`

**原设计里的 `PrimarySocks5Plugin` 不存在** (v1 文档的笔误), 而且也不需要它:
`TunnelManager` 改为注入**工厂表**, 主出口也由工厂创建 (工厂内部对 `default` 复用
`@Singleton JschSshClient`), 于是插件实例不再需要 Dagger 直接提供。

```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class TunnelModule {
    @Binds @IntoMap @StringKey("socks5")
    abstract fun bindSocks5Factory(impl: Socks5TunnelPluginFactory): TunnelPluginFactory
}
```

- `TunnelManager` 构造参数: `Map<String, TunnelPluginFactory>` (**替换** 原来的
  `Map<String, TunnelPlugin>`) + `RoutingTableStore` + `ServerRepository` 不需要
  (配置由调用方传入)。
- `Socks5TunnelPlugin` 去掉 `@Singleton`, 也不再被任何地方 `@Inject` → 不会出现
  "Dagger 不知道 `exitId` 传什么"的问题。
- `AppModule` **不需要改**: `JschSshClient` / `SshKeyManager` / `KnownHostsManager` /
  `DnsInterceptor` / `SshIoDispatcher` 的既有绑定原样复用。
- 新增 `@Binds @Singleton fun bindAppUidResolver(impl: ConnectivityAppUidResolver)` 已存在
  (`AppModule`), 只需把接口换成 `request(...): UidRequest` 形态; `AppIdentity` 用 `@Inject` 构造即可。
- **P0 的可合并性**: 这一版 DI 改动本身不改变行为 (单出口时 `startExit(DEFAULT)` 的产物
  与今天的 `@Singleton` 插件等价), 可以独立提交。

### 8.11 `data/local/database/AppDatabase.kt` / `entity` / `dao` / `SettingsDataStore`

- `version = 5` + `MIGRATION_4_5` (§7.1)。
- `ProxyExitDao`: `observeAll()`, `getAll()`, `getByName(name)`, `upsert`, `delete`,
  `setEnabled`, `setPriority`。
- `SettingsDataStore`: 三个新键 + getter/setter (失败吞掉并保留默认, 与现有风格一致)。
- `ServerDao` 无需改; `ServerRepository` 新增 `getServersByIds()` 或复用 `getAllServers()`。
- **规则文本永不因出口缺失而被改写** (§6.2 细则 6): store 只解析, 不落盘; 只有用户在 UI
  显式删除出口时才提示"有 N 条规则引用它", 由用户决定是否清理。

### 8.12 `domain/vpn/DataPlaneSnapshot.kt`

新增 `val exitDiagnostics: String = ""`; `render()` 里**仅当非空**追加
(与 `failureSummary` 同款: 关闭多出口时整节省略 → 现有日志逐字节不变)。

多出口打开时这一节至少包含 (逐字格式以 §12.1 为准, 这里不重复维护两份):

- `出口 …` 行: 每出口 `id(host): N 连 · ↑…B ↓…B · 状态` (§12.1);
- `应用 · 命中 H/Q · 超时 T · 无归属 M · 映射 C 条 · 软上限回退 S` 行。

- `应用` 行的分母是**新建连接数** (不是 DNS 查询数), 与域名统计的口径区分开;
- `超时` 与 `无归属` 分开计: 前者是 50ms 预算不够 (要调预算), 后者是内核真的没有归属
  (客户端已关 socket / 系统进程), 处理方式不同。

---

## 9. 健康、故障转移与恢复

### 9.1 出口状态机

```text
        start ok
Starting ─────────► Ready ◄──────────────┐
   │                 │  ▲                 │ 连续 2 次探测成功
   │ start fail      │  │ 探测成功 (1/2)   │
   ▼                 ▼  │                 │
 Failed           Degraded ───────────────┘
                     │
                     │ stopAll()/禁用
                     ▼
                  Stopped
```

| 状态 | 新连接 | 说明 |
|---|---|---|
| `Starting` | 不接 (resolve 视为不可用) | 建池中。**出口在 connect 之前就登记**, 所以 `stopAll/stopExit` 能回收"正在建池"的池; 同 id 重复启动返回失败 |
| `Ready` | ✅ | 唯一可服务的状态 |
| `Degraded` | ❌ → 回退 default | 会话级失败; 隔离 30s 后按 15s 探活节拍恢复 |
| `Failed` | ❌ | 本次会话不再自动恢复 (用户重连才重试) |
| `Stopped` | ❌ | 用户禁用/断开 |

**降级只影响"新连接"**: 已经在 `Degraded` 出口上建立的连接继续跑 (I-1 的同一理由),
它们各自的失败由既有的连接级逻辑处理 (RST / 零回程检测), 不会因为出口状态变化被踢掉。
这也意味着**应用规则的确定性只对新连接成立** —— UI 文案不要写"立即切换"。

### 9.2 什么触发降级 (严格)

| 事件 | 降级? | 理由 |
|---|---|---|
| `client.isConnected() == false` (池全空/全断) | ✅ | 出口真的没了 |
| `client.checkPoolHealth()` 置 `Failed` (所有会话 `!healthy && !isConnected`) | ✅ | 同上 |
| 3 条会话中 1~2 条不健康 | ❌ (WARNING + 计数) | 还有活口, 降级会让容量白白损失 |
| 单条通道 `channel is not opened.` | ❌ | 目标级/瞬时; 换出口只会放大延迟 |
| 全候选**秒拒** (`ChannelOpenBudget.isFastRejectOnAllCandidates`) | ❌ | 目标不存在 (NXDOMAIN 被假 IP 掩蔽), 不是出口故障 |
| `SshIoDispatcher.isSaturated()` | ❌ | 全局资源, 与出口无关 |
| **CONNECT 之前的失败** (`ExitUnavailable`: loopback/协商/凭据/预算) | ✅ 连续 `EXIT_CONSECUTIVE_PRE_FAILURES = 3` 次 | 这一阶段**完全在本地代理与 SSH 之间**, 与目标无关 —— 连续 3 次说明出口真的坏了, 单次仍可能是瞬时 |
| CONNECT 阶段的失败 (`rep != 0` / 目标超时) | ❌ | 分不出出口级/目标级 (附录 C-4), 降级会误杀健康出口 |

> 判据必须复用既有分类器, **不要新写一套超时/错误码判断** ——
> `createDirectChannel` 已经是唯一权威出口 (AGENTS "按耗时区分" 那条)。
> 新增的只有一条: **按阶段计数** (CONNECT 之前 vs 之后), 阶段是代码结构确定的, 不需要猜。

### 9.3 隔离与恢复

| 参数 | 值 | 说明 |
|---|---|---|
| `EXIT_ISOLATION_MS` | 30_000 | 降级后 30s 内不重试 (避免重试风暴) |
| `EXIT_CONSECUTIVE_PRE_FAILURES` | 3 | 连续几次 **CONNECT 之前**的失败才降级 (§9.2); 单次可能是瞬时 |
| `EXIT_CONN_SOFT_CAP` | 128 | **非 default** 出口的活跃连接软上限; default 不设。**注意它不保护线程墙**: `SshIoDispatcher` 是全局 128 线程, 两个出口各 100 连接仍会撞 `isSaturated()` → 0x05。软上限只防"一个出口独吞" |
| `EXIT_RECOVERY_PROBE_MS` | 15_000 | 隔离期后**搭既有 15s 探活节拍** (不新增定时器) |
| `EXIT_RECOVERY_SUCCESSES` | 2 | 连续 2 次成功才回 Ready |
| 探测内容 | `ConnectivityProber(exit.plugin.localSocksPort, exit.plugin.socksAuth, probeUrl).probe()` | 与主探活同构, 走 loopback 不经 TUN; `socksAuth` 从插件接口取 (不要自己猜凭据) |
| 探测频率 | **同一节拍内并发** (每个出口一个 `async`, 总预算 5s) | 串行会让 4 个出口吃掉 20s, 挤掉主探活 (§8.7) |
| 探测预算 | 单次 ≤ 5s, 总 ≤ 5s | 复用 `ConnectivityProber` 默认超时; `withTimeoutOrNull` 兜底 |

恢复成功后打 INFO `出口恢复 · hk — 连续 2 次探测通过 (隔离 47s)`;
失败只在状态**变化**时打 WARNING, 不逐次刷屏。

### 9.4 主出口不做静默转移 (N4)

- 主出口的池失败仍然走现有 `observeJschConnectionState` → `autoReconnect` → 整条会话重建。
- 理由: 状态卡/健康归因/出口 IP/分阶段计数全部以主出口为准; 静默改道会让
  "已连接但所有流量其实走了另一个出口" 变成一个不可观测的状态。
- 附加出口故障**只能**影响"本该走它的新连接", 不能影响:
  - 主出口的 `connectionState`
  - `healthTracker` / `StageCounters` / `故障归因`
  - `autoReconnect` 的触发
- **实现前提**: `JschSshClient.exitLabel` 必须生效 (§8.2 / 附录 C-5) —— 否则附加出口池失败会打
  `SSH 会话全部丢失 · 连接即将断开并触发重连` 的 **ERROR**, 既进 `故障归因` 又会误导用户。
  这是 v1 评审发现的**必须修**项, 不是"顺手优化"。

### 9.5 建立前的一次快速失败重试

场景: 选中的出口恰好在握手瞬间死掉 (`提前 EOF` / 本地代理不可达 / 凭据错)。
此时 **SYN-ACK 还没发** (`TcpStateMachine.kt:1018` 才发), 客户端仍在 SYN_SENT,
天然是"重试安全区"。

重试条件 (**全部满足, 缺一不可**):

1. 结果分类为 `ExitUnavailable` 且 `stage in {LOOPBACK, NEGOTIATE, AUTH, BUDGET}`
   (**不是** `TargetFailed`, 也不是 CONNECT 阶段的任何失败);
2. `!conn.synAckWritten` (SYN-ACK 一旦写出, 客户端可能已经发数据 → 改投 = 双发);
3. `conn.state != Closed`;
4. `forwardedBytes == 0` (没有任何上行被写出);
5. `elapsedMs < RETRY_MAX_ELAPSED_MS = 3000` (慢失败不重试: 客户端 SYN 预算有限,
   且慢失败更像目标侧问题);
6. 尚未重试过 (`conn.exitRetried == false`);
7. **`conn.exitId != ExitId.DEFAULT`** (default 失败没有"下一个"可退; 保持现状 RST);
8. 回退目标 `default` 可用 (`tunnelManager.exit(DEFAULT)?.health != Failed` 且 `localSocksPort > 0`)。

动作: `releaseHandshakeResources(conn)` (§8.5) → `conn.exitId = default` →
`conn.tunnelPlugin = null` → `exitRetryTotal++` → 重跑 `forwardThroughLocalSocks` 一次。

日志: INFO `出口快速失败重试 · example.com — hk → default (协商 210ms 即失败)`.
**不重试**时仍按现有语义 `closeTcpConnection` (RST), 客户端应用层重试。

> **为什么不能用 `rep` 码判定"出口坏了"**: `Socks5ProxyServer.connectToTarget` 把所有失败
> 映射成 `0x05` (附录 C-4)。用 `rep` 做重试判据会让"目标不存在"也触发换出口, 结果是
> 每次 NXDOMAIN 都多一次 SSH 建连 —— 与 N6 直接冲突。

### 9.6 按应用路由的验收门槛 (真机)

应用规则是 v1 能力, 但它的正确性**完全取决于 uid 的可得性与延迟**; 没有实测门槛就等于没有验收。

| 指标 | 门槛 | 不达标时的动作 |
|---|---|---|
| `awaitUid` 命中率 (新建连接) | ≥ 95% | < 95% 先看 `uidTimeoutTotal` vs `uidMissTotal`: 前者调大预算 (50 → 80ms), 后者说明内核真的没归属 (系统进程/已关 socket), 属正常 |
| `awaitUid` P95 延迟 | ≤ 15ms | 检查反查线程数 (2~4) 与队列是否被 `MAX_PENDING` 拒 |
| uid 等待对 SYN-ACK 的影响 | 建连耗时中位数 +≤ 20ms | 超过说明 binder 抖动; 不要为此把等待挪回 packetLoop |
| 包名解析命中 | ≥ 99% (对已安装应用) | `getNameForUid` 返回 null 的 uid 记入 `应用映射未命中`, 不是故障 |
| 应用规则确定性 | 同一 app 连续 50 条新连接 100% 同出口 | 出现分裂 = 有连接走了 `NO_UID` 分支, 按第一条排查 |
| 共享 uid | 组内所有包走同一出口 (Android 语义) | 与设计一致, UI 提示即可 |

**真机取证方法** (无 adb): 导出应用内日志 → 找 `数据面快照` 的 `应用 · 命中 H/Q` 行,
与 `出口` 行的每出口连接数对照; 再用一条 `app:com.example -> hk` 规则跑 50 次冷启动,
确认 `出口` 行里 `hk` 的连接数等于 50、`default` 不增长。

> **50ms 是上限, 不是目标**。真机上 binder 单次调用是亚毫秒级; 预算是给"内核表查询 +
> 线程排队"的尾部留的余量。**不要**为了"更准"把它调到 200ms —— 每条新连接都多 200ms
> 首包延迟, 表现为"网页整体变慢", 且客户端 SYN 重传会放大这个延迟。

---

## 10. 并发与资源不变量

> 这一节是实施期的"不许违反"。每条都要有对应单测或结构性保证。

| 编号 | 不变量 | 违反后的现场 |
|---|---|---|
| **I-1** | 出口决策**只在 SYN 做一次**, 写入 `conn.exitId` 后不再改 (除 §9.5 的建立前重试) | 同一条连接上下行打到不同出口 → 回程永远不来 |
| **I-2** | `exitId` / `tunnelPlugin` 是 `TcpConnection` 的 **body 属性**, 不进构造参数 | data class equals/copy 语义被破坏 → 池/`indexOf`/重建定位错位 |
| **I-3** | 摘回调**只**用 `conn.tunnelPlugin` (或按 `conn.exitId` 解析), 不得有机器级插件字段 | 用过别的出口的插件去摘 → 回调泄漏 / 端口复用时误触发 (附录 C-2) |
| **I-4** | 新连接只会拿到 `Ready` 的出口; 任何不 Ready → `default`; `default` 本身不降级 | 静默把流量投到死出口 → 页面卡"连接中" |
| **I-5** | 出口启动/停止串行 (`exitMutex` + per-exit `Mutex`); `stopAll()` 先 cancel 在途 Job | 断开后 300ms 附加出口把 SSH 池拉起来 → 跨会话泄漏 |
| **I-6** | 附加出口启动失败**只 WARNING**, 绝不向主连接路径抛异常 | 一个附加出口配置错 → 整条 VPN 连不上 |
| **I-7** | 所有 `suspend` 之后要复查 `sessionGeneration` (connect 与 stopAll **各**自增一次); 变了立即返回并释放刚建的资源 | 断开瞬间启动完成的出口永远留在 `exits` 里 |
| **I-8** | 全出口共享连接预算 (总 512); `release` **恰好一次** | 3 出口 × 512 = 1536 条 × 32KB = 48MB 直接缓冲 + 同量线程, 必 OOM |
| **I-9** | 出口计数器是**会话级**, `connect` 清零 (与 `resetSessionCounters` 同步) | 新会话快照带着上一会话累计值, 判读被带偏 |
| **I-10** | 规则表是**不可变快照**, 单次 `@Volatile` 引用交换; `select` 无锁 | 半张表可见 → 部分连接用旧规则 (可接受) / 部分看到空表 (不可接受) |
| **I-11** | 附加出口失败**绝不**触发 `SshVpnService.autoReconnect` | 附加出口抖动 → 整条 VPN 重连风暴 |
| **I-12** | 附加出口的 `keepAlive` 沿用服务器配置; `0` 仍然表示关闭 (不启动协程) | `delay(0)` 忙循环 ×3 会话 ×N 出口 |
| **I-13** | 附加出口启动必须**错峰 300ms** | 3N 条 SSH 同时握手 → 撞 `MaxStartups` → 随机 reset |
| **I-14** | `awaitUid` **必须有界且一定完成** (≤50ms, 超时返回 `NO_UID`), 且**只在 `forwardSynToTunnel` 协程**调用; **直连 (bypass) 路径不等 uid** | 无界等待 = 客户端 SYN 超时; 在 packetLoop 上等 = 全连接停顿; bypass 也等 = 分流模式下每条直连多 50ms |
| **I-15** | 应用身份**只用包名**; `uid → 包名` 是会话级缓存, **绝不**写进规则/DB/CSV | 写 uid 的规则在重装/多用户后静默失效 (或更糟: 命中另一个应用) |
| **I-16** | 应用规则**绝不参与** DNS 判定 (`shouldTunnel` / `cachePolicyTag` / `hasDomainRule`) | DNS 没有 uid 语义 → 会出现"DNS 认为直连、TCP 却要隧道"的分叉 |
| **I-17** | 引用不存在出口的规则**保留原文、匹配时跳过**, 不删行 | 删行 = 在改名/迁移/导入时序不一致时永久丢失用户配置 |
| **I-18** | `ConnectionBudget.acquire` 在连接对象构造完成**之后**、`start()` **之前**; `release` **只在 `onClosed`** | 早于构造 → 构造异常漏 release; 晚于 start → 连接可能已关闭, 永远不 release → 预算耗尽后全拒 |
| **I-19** | 单出口活跃连接**软上限 128** (= `SshIoDispatcher` 线程墙) **只作用于非 default 出口**; default 满员仍走既有饱和路径 | 一个附加出口吃掉全部预算 → 其它出口饿死; 但把 default 也限到 128 等于悄悄降低现有上限 (P0 零行为变化会破) |

资源预算表:

| 资源 | 主出口 | 每个附加出口 | v1 上限 |
|---|---|---|---|
| SSH 会话 | 3 | 3 | 9 (1+2 附加) / 15 (1+4 附加, v1 上限) |
| loopback 端口 | `server.socksPort` | 系统分配 (bind 0) | 3 |
| 连接数 (预算) | — | — | **共享 512** (内存口径) |
| 并发活跃连接 (**真实墙**) | — | — | **128** (`SshIoDispatcher` 线程池上限) |
| 单出口软上限 | **不设** (仍受 512 与 128 线程墙约束) | 128 | 防止附加出口饿死其它出口 (I-19; default 不设, 否则等于降低现有上限) |
| 回程线程 | 1/活跃连接 | 1/活跃连接 | 128 (全局, 既有) |
| 内存/连接 | 32KB | 32KB | 512 × 32KB ≈ 16MB |
| 每连接 uid 等待 | — | — | 1 次, ≤50ms, 协程挂起 (不占线程) |

> **512 挡不住 128**: 预算解决的是"内存与连接对象数量", 线程池解决的是"同时能有多少条
> 回程中继"。真正先撞到的是 128。所以 §10 的两个数必须一起看: 预算满 → `0x01` 快速拒;
> 线程池满 → `SshIoDispatcher.isSaturated()` → 现有 `0x05` 路径。**不要**把预算调成 128 来
> "对齐" —— 那会让"连接多但大多空闲"的正常场景被误拒 (空闲连接不占回程线程)。

**连接预算的归属**: `ConnectionBudget(512)` 由 `VpnController.connect` 在会话开始时**新建**,
写入一个 `@Singleton ConnectionBudgetHolder` (只持有"当前会话的那一个", `disconnect` 时置 null);
`Socks5TunnelPlugin.connect()` 从 holder 取并传给 `Socks5ProxyServer` 的构造函数。
**holder 可以是单例, 但它持有的 budget 必须每会话新建** —— 复用同一个 budget 会让
"上一会话泄漏的 permit" 永久吃掉新会话容量, 且无法在断开时兜底归零。
holder 为空 (测试 / 单出口早期路径) = 不做全局限额, 行为与今天一致。

**软上限的实现位置**: 软上限**不放进** `ConnectionBudget` (那是全局的), 而是
`TunnelManager` 在 `resolveExit` 里读 `plugin.activeConnections` (= `Socks5ProxyServer.connections.size`,
**不另记一份**): 命中出口**不是 default** 且 ≥ 128 → 返回 default 并 `exitSoftCapTotal++`;
**命中 default 时不做这个判断** (default 满员交给既有 `isSaturated()` → `0x05`)。
连接数只有一个真相源, 所以不存在"两个计数不同步"。

---

## 11. UI

### 11.1 入口与页面

- 设置 → 「网络」区, 域名名单下方新增一行:
  **多出口路由 (实验)** — subtitle: `未启用` / `已启用 · 3 个出口 · 12 条规则 · 4 个应用`。
- **首页状态卡与通知栏只反映主出口** (§16 D-9): 不出现"2 出口"这类汇总量,
  出口级状态只在设置页可见 —— 状态卡与通知同源 (`StatusDisplay.build`), 加汇总会让
  "其中 1 个降级"无从表达。
- 路由: `proxy_exits` (列表) / `proxy_exit_edit/{id}` (编辑) / `proxy_exit_rules` (规则) /
  `proxy_exit_apps` (应用选择器, 也可从规则页「添加应用规则」直接进入)。
- **模式门禁**: 当前 `transportMode` 为 `SYSTEM` (含"白名单空名单") 时, 入口行副标题显示
  `当前 DNS 模式不支持 (流量不进 VPN)`, 页面内开关置灰并给出原因; 规则仍可编辑 (只是不生效),
  避免用户以为功能坏了 (§6.4 约束 1)。
- **DOMAIN_SPLIT 下的应用规则提示** (§6.4 约束 4): 应用规则只对"本来就是假 IP 目标"的连接生效,
  页面顶部黄条写 `分流模式下, 应用规则只对已走隧道的连接生效; 需要强制某应用走隧道请用域名规则`。
  这不是缺陷, 是 TUN 路由在 SYN 之前就定好的物理约束。

### 11.2 列表页

```text
┌ 多出口路由                                      [开关] ┐
│ 说明: 按应用/域名/IP 把新连接分到不同出口; 改规则即时  │
│ 生效, 增删出口需要重连。                                │
├────────────────────────────────────────────────────────┤
│ 主出口  default · hk.example.com        [就绪] 12 连接 │
│ 附加出口 hk      hk2.example.com        [降级] 0 连接  │
│          规则 34 条 · 上次降级 2 分钟前       [编辑][删]│
│ 附加出口 jp      jp.example.com         [就绪] 3 连接  │
├────────────────────────────────────────────────────────┤
│ 应用规则 4 条 (银行/视频/…)              [管理应用]     │
│ [添加出口]                          [编辑规则 (34 条)] │
└────────────────────────────────────────────────────────┘
```

- 出口卡片显示: 名称 / 服务器地址 / 状态 chip / 活跃连接 / 本会话上下行 (可选)。
- **应用规则单独一行摘要**: 只显示条数与前几个应用名 (包名对人不可读, 用 `AppUidLabels`
  同款应用名解析), 点击进应用选择器; 完整规则仍在规则编辑器里 (两处编辑同一份文本)。
- **「域名访问统计」不区分出口** (§16 E-2): 该页仍按域名聚合, 不加出口列也不改 CSV 列序
  (列顺序是冻结契约); 出口维度的流量只在出口卡片与本页上方展示。
- 未连接时状态区显示 `未启动`, 不显示陈旧计数。
- 删除/禁用出口时若规则引用它 → 弹确认并列出受影响行号 (可一并清理)。
- **删除出口不自动删规则** (§6.2 细则 6): 规则保留为 unresolved, 页面用 ⚠ 标出"1 条规则引用了
  已删除的出口"; 用户显式清理才改写文本。

### 11.3 规则编辑器

- 等宽字体 + 行号 + 语法着色 (匹配 / `->` / 出口名 / `app:` 前缀)。
- 实时校验: 未知出口、`default` 不在最后、重复 target、非法 CIDR、非法包名、超过上限。
- **「添加应用规则」按钮**: 打开应用选择器 (复用白名单页的已装应用列表, 见下), 选中后插入
  `app:<包名> -> <当前选中出口>` 一行, 光标停在出口名上; 支持多选批量插入 (同一出口)。
- 底部「测试」框: 输入 `example.com` / `1.2.3.4:443` / `app:com.example.app` → 输出
  `→ hk  (第 12 行: *.example.com -> hk)` / `→ default (未命中)`。
  该测试**纯本地**, 不建连、不发 DNS; 输入包名时**不需要**该应用正在运行。
- 保存 = 写 DataStore + 交换快照; INFO 日志 `出口规则已重载 · 34 条 (生效无需重连)`。
- 顶部提示: 域名规则依赖"能还原域名"(假 IP 或真实 IP 反查); 应用规则依赖 uid 反查
  (拿不到时该行跳过, 不回退直连); SYSTEM 与空白名单模式整体不可用 (§6.4)。

**应用选择器的实现**: 复用 `WhitelistViewModel` 里现成的 `getInstalledApplications` 路径
(`QUERY_ALL_PACKAGES` 已在 manifest 声明, `:21`), 抽成一个共享的 `InstalledAppsSource`
(`Flow<List<AppEntry>>`, 含包名/应用名/图标/是否系统应用), 白名单页与多出口页共用 ——
不要复制第二份扫描逻辑 (两处会因缓存/过滤差异给出不同列表)。
选择器里**显示包名小字** (`com.example.app`), 因为规则文本里存的是包名, 用户核对时要能对上。
**共享 uid 的应用组**在列表里加注 `与 N 个应用共用 uid`, 提示规则会整组命中。
**WHITELIST 模式下非白名单应用标灰** + 提示 `需先加入白名单` (§6.4 约束 4): 它们的流量
根本不进 TUN, 加 `app:` 规则也不会有任何效果。选择器需要读当前 `dnsMode` 与白名单集合,
所以 `InstalledAppsSource` 的 `AppEntry` 要带 `inWhitelist: Boolean?` (null = 非白名单模式, 不标灰)。

### 11.4 i18n

新增约 37 个 key, 四份 `values{,-en,-ru,-zh-rCN}/strings.xml` 同步:

`settings_multi_exit`, `settings_multi_exit_desc`, `multi_exit_enabled`, `multi_exit_disabled`,
`multi_exit_exit_default`, `multi_exit_exit_additional`, `multi_exit_add`, `multi_exit_edit`,
`multi_exit_delete`, `multi_exit_delete_in_use`, `multi_exit_status_starting/ready/degraded/failed/stopped`,
`multi_exit_rules`, `multi_exit_rules_desc`, `multi_exit_rule_line`, `multi_exit_rule_unknown_exit`,
`multi_exit_rule_default_not_last`, `multi_exit_rule_duplicate`, `multi_exit_rule_invalid_cidr`,
`multi_exit_rule_invalid_package`, `multi_exit_rule_too_many`, `multi_exit_rule_test`,
`multi_exit_rule_test_hint`, `multi_exit_rule_test_result`, `multi_exit_reconnect_required`,
`multi_exit_domain_needs_fake_ip`, `multi_exit_no_exits`, `multi_exit_priority`,
`multi_exit_connections`, `multi_exit_apps`, `multi_exit_apps_manage`, `multi_exit_add_app_rule`,
`multi_exit_shared_uid`, `multi_exit_app_rule_mode_hint`, `multi_exit_uid_unavailable`,
`multi_exit_app_not_whitelisted`.

状态 chip 复用 `StatusDisplay` 的颜色语义, 但**不走** `HealthStep` (那是链路归因, 不是出口状态)。

---

## 12. 观测面 (无 adb 现场的唯一证据)

### 12.1 快照增量

在 `数据面快照` 的 `tunnelDiagnostics` 之后追加一节 (**仅多出口启用时**):

```text
出口 主出口(hk.example.com): 12 连 · ↑1.2MB ↓8.4MB · Ready
 · hk(hk2.example.com): 3 连 · ↑120KB ↓2.1MB · 就绪
 · jp(jp.example.com): 0 连 · ↑0B ↓0B · 降级(隔离 12s, 失败 2 次)
应用 · 命中 37/41 · 超时 4 · 无归属 0 · 映射 23 条 · 软上限回退 0
```

- 每出口一行 (用 ` · ` 连接, 不换行, 避免日志解析器按行切)。
- 计数器: `connCount` / `upBytes` / `downBytes` / `degradedCount` / `lastError`。
  `up/downBytes` 来自 `Socks5ProxyServer` 的会话累计 (§8.9), 不是"当前活跃连接的瞬时和"
  —— 否则长连接一关闭就从快照里消失, 看不到"哪个出口干了多少活"。
- **`应用` 行的口径**: `命中 H/Q` 的 `Q` = 本会话**新建连接数** (不是 DNS 查询数),
  `超时` = 50ms 预算内没等到 uid, `无归属` = 内核明确没有归属 (系统进程 / 客户端已关 socket),
  `映射` = `AppIdentity` 缓存条目数, `软上限回退` = I-19 触发的次数。
  **两个失败口径必须分开**: 超时是预算问题 (调预算), 无归属是事实 (不可调)。
- 关闭多出口: 整个字段为空串 → 与现有文本逐字节一致 (G6)。

### 12.2 日志定级

| 文案 | 级别 | throttleKey | 说明 |
|---|---|---|---|
| `出口就绪 · hk — SSH 3/3 会话 · 本地 :41235 · 建连 1832ms` | INFO | 默认 (不节流) | 生命周期, 每个出口每次会话一条 |
| `出口启动失败 · jp — <原因>` | WARNING | `出口启动失败` | 会话继续 |
| `出口降级 · hk — SSH 会话全部丢失, 新连接改走 default` | WARNING | `出口降级` | 状态变化才打 |
| `出口恢复 · hk — 连续 2 次探测通过 (隔离 47s)` | INFO | 默认 | |
| `出口快速失败重试 · example.com — hk → default (协商 210ms 即失败)` | INFO | 默认 | 需能看见"哪个目标被换出口" |
| `出口规则已重载 · 34 条 (生效无需重连)` | INFO | 默认 | |
| `出口规则无效 · 2 行 — 第 7 行: 出口 "jp" 不存在` | WARNING | `出口规则无效` | 汇总一条, 不逐行刷 |
| `多出口已禁用 · 全部流量走 default` | INFO | 默认 | 开关关闭时连接一条 |
| `应用规则未生效 · com.example.app — uid 查询超时 (50ms)` | **DEBUG** | `应用规则未生效` | 每次连接都可能出现 (系统进程), 按 DEBUG 打; 计数进快照, 不进归因 |
| `TCP 连接结束 · example.com — 正常关闭 · 上行 1.2KB/下行 8.4KB · 存活 3s · 出口 hk` | INFO | 默认 | **仅多出口启用时**追加 ` · 出口 <id>`; 关闭时逐字节保持现状 |

> `故障归因` 只数 WARNING/ERROR: `出口降级` / `出口启动失败` 会进归因 (真故障),
> 规则重载/恢复/重试都是 INFO (不该污染归因)。throttleKey **必须是静态前缀**,
> 否则带出口名/域名的动态文案会逐条成为新键, 节流失效。
> **`应用规则未生效` 必须是 DEBUG**: 系统进程 / 已关 socket 的连接拿不到 uid 是常态,
> 按 WARNING 打会让 `故障归因` 多出一类高频噪声 (与 AGENTS「DNS 手动解析失败打 INFO」同一理由)。

### 12.3 计数器

| 计数器 | 位置 | 用途 |
|---|---|---|
| `exitConnTotal` | **`Socks5ProxyServer.totalConnections`** (累计, 每出口一个实例) | 出口是否在被使用 (与 `activeConnections` 的瞬时值分开看) |
| `exitFailTotal` / `exitDegradeTotal` | `ActiveExit` (TunnelManager 记) | 出口稳定性趋势 |
| `exitFailoverTotal` | 全局 | 规则命中但出口不可用的次数 |
| `exitSoftCapTotal` | 全局 | I-19 软上限回退次数 (出口被单一大流量打满) |
| `exitRetryTotal` / `exitRetrySuccessTotal` | 全局 | §9.5 重试有效性 |
| `inFlightChannels` | 每出口 (复用 `poolDiagnostics`) | 只增不减 = 通道泄漏 |
| `uidWaitTotal` / `uidTimeoutTotal` / `uidMissTotal` | 全局 | 应用规则可用性 (§9.6 门槛) |
| `uidQueueDropTotal` | 全局 | `MAX_PENDING` 拒绝 (反查线程数不够) |
| `appRuleHitTotal` | 全局 | 应用规则实际命中次数 (0 = 规则没生效) |

> **每出口的"活跃连接数"不设计数器**: 它是 `plugin.activeConnections` 的瞬时读
> (= `Socks5ProxyServer.connections.size`), 复制成计数器就会出现两处不同步 (I-19)。
> 需要"累计"时才用 `totalConnections`。

---

## 13. 测试计划

### 13.1 纯逻辑 (JVM, 优先)

| 测试类 | 覆盖 |
|---|---|
| `ExitIdTest` | 规范化 (小写/去空格/长度/字符集), `default` 保留字拒绝 |
| `ExitRuleParserTest` | 注释/空行/箭头两侧空白; 域名/`*.`/CIDR/IPv4/IPv6 方括号/端口; **`app:<包名>` 与 `app:<包名>:<端口>`**; 非法包名 (`com`, `com..a`, `app:`); 非法 CIDR; 未知出口**保留不丢弃**; `default` 非末行; 超过上限; 行号正确 |
| `RoutingTableTest` | 有序 first-match; 端口限定; 域名未知时跳过域名规则; **`NO_UID` 时跳过应用规则**; 应用规则与域名规则的**行序**决定优先级; `default ->` 兜底; 空表 |
| `AppRuleMatchTest` | 包名精确匹配 (不做前缀/通配); 带端口只命中该端口; 共享 uid 组 (同包名) 命中; `pkg == null` 不命中; 大小写敏感 (包名本身小写) |
| `AppIdentityCacheTest` | 注入 `(Int) -> String?` 假查表: 命中缓存只查一次; `null` 不写负缓存 (下次重试); `clear()` 后重新查; `uid < 0` 直接 null |
| `RoutingTableSelectPerfTest` | 512 条规则 × 10k 次 select < 500ms (防回归成 O(n²)) |
| `ExitFailoverPolicyTest` | 降级条件矩阵: 全断 ✅ / 部分不健康 ❌ / 通道秒拒 ❌ / 池饱和 ❌ / **CONNECT 之前连续 3 次 ✅ / 1~2 次 ❌** / CONNECT 阶段失败 ❌; 隔离 30s 内不重入; 连续 2 成功恢复 |
| `DnsTunnelDecisionTest` | (mode × inList × hasExitRule) 矩阵: `cachePolicyTag` 与 `shouldTunnel` 严格一致; 谓词为 null (多出口关闭) 时两条路径都退化为今天的判定 (§8.8); **`hasDomainRule` 忽略端口** |
| `VpnControllerBypassTest` (扩展) | 排除路由 > 出口规则 > split 直连的顺序; 域名未知时行为不变; **应用规则不改变直连/隧道判定** |

### 13.2 需要假件的数据面测试

| 测试类 | 覆盖 |
|---|---|
| `MultiExitRoutingTest` | 两个假插件 (记录被谁接到) + 固定 DNS 映射: 域名规则命中 → 只打到 B; 未命中 → A; 决策只发生一次 |
| `MultiExitAppRoutingTest` | 假 `AppUidResolver` (固定 uid) + 假 `AppIdentity`: `app:` 规则命中 → 只打到 B; 同一 uid 连续 10 条全部同出口; `NO_UID` → 走 default 且**不**改直连判定; **bypass (直连) 连接一次都不 `await` uid**; **规则表无 `app:` 行时也不 `await`** (回滚退路) |
| `AppUidAwaitTest` | 句柄语义: `await` 超时返回 `NO_UID` 且不抛; 队列满 (第 65 个) **立即**完成 `NO_UID` (不挂起); 正常命中 < 50ms; **同一句柄被路由与统计两个消费者 `await`/`onComplete`, binder 只调用一次**; 平台拒绝走 30s `DisableWindow` 后恢复 |
| `MultiExitCallbackTest` | 并发两条连接 (A/B 出口) 各自注册后关闭, 断言**只移除自己**的回调 (C-2 回归; 旧实现确定性失败) |
| `MultiExitRetryTest` | B 出口协商秒失败 → 重试到 default 并成功; **CONNECT 阶段失败 (rep=5) 不重试**; `default` 自身失败不重试; `elapsed > 3s` 不重试; 迟到 SYN-ACK 不重复发 |
| `MultiExitShutdownTest` | `stopAll()` 与 300ms 延迟启动竞争: 断开后 `exits` 为空、无新端口、无新会话 (I-5/I-7) |
| `MultiExitIsolationTest` | B 出口池失败 → 全局 `connectionState`/health 不变; 新连接走 default; 现有 A 连接不受影响 (I-11); **附加出口日志为 WARNING 且不含"连接即将断开"** (C-5) |
| `ConnectionBudgetTest` | 3 出口共享 512; 拒绝不泄漏; close 恰好 release 一次 (含异常路径); **构造期异常不泄漏 permit**; **acquire 前连接被关闭仍 release** (I-18) |
| `TunnelConfigSocks5Test` | `serverId`/`hostKeyFingerprint` 透传; `id == 0` 仍跳过落库 (C-1 回归) |
| `MultiExitSnapshotTest` | 关闭时字段为空串且 `render()` 文本与基线逐字相等; 开启时含每个出口 + `应用` 行 |
| `Socks5ProxyServerLabelTest` | 诊断前缀; 默认构造不带前缀 (测试兼容) |

所有并发用例: **daemon 线程 + 有界 `CountDownLatch.await(5s)`**, 禁止 `while(...) yield()`
(CI 曾被无界等待挂 26 分钟)。

### 13.3 门禁

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew ktlintCheck detekt lint
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

**功能关闭的验收 (G6)**: 在 `multi_exit_enabled=false` 下跑完整的
`docs/specs/2026-10-18-device-verification-checklist.md` S0–S16, 并逐字 diff
S0 的 `数据面快照` 文本 —— 不得出现任何新字段。这是"零行为变化"的硬判据,
不是"看起来一样"。

---

## 14. 分阶段落地

| 阶段 | 内容 | 用户可见变化 | 风险 |
|---|---|---|---|
| **P0 接缝** | `TunnelConfig` 补 `serverId/hostKeyFingerprint` (C-1); `TunnelPluginFactory`; 插件去单例 + `exitId`; `JschSshClient.exitLabel` (C-5); `TunnelManager` 按出口分键 (只 1 个); `conn.tunnelPlugin` (C-2); 探活端口用 `localSocksPort` (C-3); **全局连接预算** (512, 等价于现有 `MAX_CONNECTIONS` 语义) | **无** | 低 (纯结构) |
| **P1 数据与规则** | Room v5 + DAO + 设置键; 规则解析/匹配 (含 `app:`) + 单测; 规则编辑器 + 测试框 + **应用选择器**; 快照出口节 (未启用时为空) | 可配置, 但只有主出口; 规则不生效 | 中 (数据面零改动) |
| **P2 运行时** | `AppUidResolver` 请求句柄 (`request`/`UidRequest.await`) + `AppIdentity`; 附加出口错峰启动/停止; 路由接线 + 单出口软上限; `shouldTunnel` 谓词接入 (两点同时生效); 故障隔离与恢复; 快速失败重试; UI 状态实时; S17–S26 | ✅ 完整功能 (**含按应用分流**) | 中高 (新的并发面) |
| **P3 扩展** | 外部 SOCKS5/HTTP 出口; 加权/最小连接; 每出口 DNS 模式; 主出口故障转移开关; 应用规则的**时间窗/网络条件** | — | 待评估 |

> P1 与 P2 建议**同一个 release** 出货 (P1 单独出货会出现"能配置但不生效"的困惑)。
> P0 可以独立合并, 它顺手修掉五个既有缺陷 (C-1…C-5)。
> **按应用分流不是 P3**: 它在 P1 完成解析/UI, 在 P2 与路由同时接上 —— 因为 `awaitUid`
> 只是给已有的反查加一个"会完成"的等待原语, 与多出口共用同一次 SYN 决策, 拆到 P3 反而
> 要改两次 `forwardSynToTunnel`。

### 14.1 实施顺序 (有依赖, 不要并行改同一文件)

```text
P0-1  TunnelConfig + forSocks5 (C-1)                    [TunnelConfig.kt]
P0-2  JschSshClient.exitLabel + 两处日志分流 (C-5)       [JschSshClient.kt]
P0-3  TunnelPluginFactory + 插件去单例 + DI              [tunnel/*, di/*]
P0-4  TunnelManager 按出口分键 + getActiveOrFallback 判空 [TunnelManager.kt, 三个调用点]
P0-5  conn.tunnelPlugin 替换机器级字段 (C-2)             [TcpStateMachine.kt]
P0-6  探活端口用 localSocksPort (C-3)                    [SshVpnService.kt]
P0-7  ConnectionBudget + 软上限                          [Socks5ProxyServer.kt, TunnelManager.kt]

P1-1  AppIdentity + 包名解析 (纯查表)                     [AppIdentity.kt]
P1-2  ExitRuleParser (`app:` 语法) + RoutingTable        [domain/vpn/routing/*]
P1-3  Room v5 + ProxyExitDao + 设置键                    [data/local/*]
P1-4  规则编辑器 + 应用选择器 + i18n                      [ui/screen/*]

P2-1  AppUidResolver 请求句柄 (request/await) + 计数器   [AppUidResolver.kt, ConnectivityAppUidResolver.kt]
P2-2  forwardSynToTunnel 接 uid/pkg/target/decision      [TcpStateMachine.kt]
P2-3  ForwardOutcome + releaseHandshakeResources + 重试   [TcpStateMachine.kt]
P2-4  附加出口启动/停止 + 探活并发                        [VpnController.kt, SshVpnService.kt]
P2-5  shouldTunnel + exitRulePredicate                   [DnsInterceptor.kt, VpnController.kt]
P2-6  快照/日志/计数器 + 验收 S17–S26                     [DataPlaneSnapshot.kt, *.kt]
```

**文件冲突矩阵** (同一阶段内不要两个人同时改同一文件):

| 文件 | P0 | P1 | P2 |
|---|---|---|---|
| `TcpStateMachine.kt` | P0-5 | — | P2-2, P2-3 |
| `TunnelManager.kt` | P0-4, P0-7 | — | P2-4 |
| `Socks5ProxyServer.kt` | P0-7 | — | — |
| `DnsInterceptor.kt` | — | — | P2-5 |
| `VpnController.kt` | P0-3 | — | P2-4, P2-5 |
| `SshVpnService.kt` | P0-6 | — | P2-4 |

### 14.2 应用分流为什么能在 P2 一次接上

| 需要的输入 | 现状 | 工作量 |
|---|---|---|
| uid | 反查已在 `createTcpConnection` 发起, 只是没有完成信号 | 请求句柄 (`request` + 有界 `await`) + 线程数 1→2~4 + "一定完成" |
| 包名 | `PackageManager.getNameForUid` (公开 API) | 一张 `ConcurrentHashMap` + `connect` 清空 |
| 规则 | P1 已解析 `app:` | 0 (匹配在 `RoutingTable.select`) |
| 决策点 | `forwardSynToTunnel` 本来就是协程 | 一次 `uidRequest.await(50)` 挂起 |
| 数据面 | 不需要改 | 0 |

结论: **没有一项需要动 TUN/组包/窗口/假 IP**, 所以它与多出口是同一批改动, 而不是下一期。

### 14.3 P3 的前置约束 (写在这里, 免得下次重新论证)

1. **外部 SOCKS5 / HTTP 出口**没有 SSH 会话池, 健康/并发/认证语义完全不同 →
   应新增 `ExternalSocks5TunnelPlugin`, 而不是把 `Socks5TunnelPlugin` 参数化到"四不像"。
   届时 `TunnelPluginFactory` 按 `protocolId` 分派即可, 注册表结构不用改。
2. **加权/最小连接**与 N2/N5 冲突 (会话一致性), 要做必须先解决 HTTP/2 多路复用被打散的问题。
3. **每出口 DNS 模式**要先拆全局假 IP 池 (N3), 属于数据面改造, 不是配置项。

---

## 15. 风险与回滚

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 3×N 条 SSH 撞服务端 `MaxStartups` | 随机 `connection reset`, 附加出口启动失败 | 错峰 300ms; v1 上限 4 附加; WARNING 明确提示检查 sshd 配置 |
| R2 | `SshIoDispatcher` 128 线程打满 | 新连接全拒 (`CallerRunsPolicy` → `饱和回执` 上升) | 全局连接预算 512; 快照暴露每出口活跃连接; 不额外扩池 |
| R3 | 回程线程/通道泄漏 (多一层插件) | 会话读线程冻结 → 全部出口变慢 | 每出口 `poolDiagnostics` 进快照; 复用同会话无回程告警 (带 sessionId) |
| R4 | DNS 判定两处分叉 | 规则"加了没反应"或缓存复用直连答案 | 单一纯函数 `shouldTunnel` + 矩阵单测 (§8.8) |
| R5 | 断开时附加出口"复活" | 端口/SSH 跨会话泄漏 | `sessionGeneration` + 先 cancel 再 stop (I-5/I-7) + `MultiExitShutdownTest` |
| R6 | Room 降级不可用 | 回装旧 APK 打不开 DB | 发布说明写明; 内测可接受; 关开关不依赖降级 |
| R7 | 规则写错导致某站全断 | 单站点不可用 | 编辑器实时校验 + 测试框 + "未知出口保留并跳过该行" + 规则重载 INFO |
| R8 | 用户误以为能"连接中途切出口" | 改规则后老连接不变, 被当成 bug | UI 文案明说"只影响新连接" |
| R9 | 附加出口指向同一台服务器 | 无收益, 反而 6 条会话 | UI 允许但提示"同一服务器不会带来新出口 IP" |
| R10 | 观测面膨胀 | 快照过长难读 | 关闭时零增量; 开启时每出口固定一段; 不加逐包日志 |
| R11 | **uid 拿不到导致应用规则静默失效** | 用户配了 `app:X -> hk`, 实际走 default, 看起来"功能没用" | `awaitUid` 有界 + 计数进快照 (`超时` / `无归属` 分开) + §9.6 门槛; 日志 DEBUG 不刷屏 |
| R12 | **应用规则与"进不进 TUN"混淆** | 用户在 SYSTEM/空白名单下配应用规则, 完全无效 | §6.4 约束 4 的 UI 黄条 + 入口门禁 (§11.1) |
| R13 | **附加出口池失败打出全局 ERROR** | `故障归因` 多一类假故障, 用户以为 VPN 要断 | `JschSshClient.exitLabel` 分流 (§8.2 / C-5) + `MultiExitIsolationTest` 断言不含"连接即将断开" |
| R14 | **重试把目标级失败当出口级** | NXDOMAIN 每次都多一次 SSH 建连, 延迟翻倍 | 重试只允许 CONNECT 之前 (`ExitUnavailable`) + `exitId != default` (§9.5) |

**回滚开关**: `multi_exit_enabled=false` → 不启动任何附加出口、不装载规则、快照不加节、
`resolveExit` 恒返回 default、`exitRulePredicate` 保持 null (DNS 判定回到今天的两处语义)。
此时运行路径与 P0 之后完全一致 (P0 的接缝本身不改变行为)。

**应用规则单独关闭** (不需要关整个多出口): 规则文本为空 = 应用规则为空; 也可以只删 `app:` 行。
"没有任何 `app:` 行"时 `forwardSynToTunnel` **跳过 50ms 等待** (句柄照旧为域名统计发起, 只是没人等),
所以不用多出口开关也能零成本关掉 uid 等待 —— 这条是给"uid 实测不达标"留的退路。

---

## 16. 决策记录 (已定稿 2026-10-28, 2026-10-29 评审增补)

> 15 项全部按建议定稿; 每条附"为什么", 便于日后复审时知道当初否掉了什么。
> 实施期若发现某条前提不成立, **改这里并写明原因**, 不要只改代码。
> D-10 之后的**六条**来自 2026-10-29 的代码级评审 (§16.3), 它们**修改了 v1 的设计**。

| # | 问题 | 决议 | 否掉的选项 + 理由 |
|---|---|---|---|
| D-1 | 规则语法 | **`target -> exit` 单行式** | 段落式: 报错只能报到"哪个段", 行号定位失效, 且无法与 `ad_block_rules` 的精确删行同构 |
| D-2 | 匹配优先级 | **有序 first-match-wins** | 最长匹配: 域名标签数与 CIDR 前缀长度是两套尺度, 无法给出一致直觉; 隐式优先级 UI 无法表达 |
| D-3 | 出口配置存哪 | **Room v5 新表 `proxy_exits`** | DataStore JSON: 名字唯一性/删服务器级联/priority 排序全要手写。**已知代价**: 降级装回 v4 APK 会打不开 DB → 发布说明必须写"降级需清除应用数据" |
| D-4 | 主出口能否改 | **不能; 恒为"当前连接的服务器"** | 可指定: 状态卡/探活/出口 IP/`last_server_id` 全部以当前服务器为准, 会出现"连接 A 流量走 B"的二义态。想要的效果用 `default -> hk` 表达 |
| D-5 | 出口规则是否强制走隧道 | **强制** (DOMAIN_SPLIT 下也走隧道, 由 DNS 强制分配假 IP) | 只在已隧道时生效: 分流模式下写规则静默无效 = 最坏失败形态。**边界**: 该出口降级后回退 default 出口 (仍是隧道), 不回落直连。**只对域名规则成立**, 应用规则不参与 (D-11) |
| D-6 | 附加出口故障回退目标 | **default** | 下一个 Ready 出口: 会级联 (b→c→d), 最终走哪台不可解释, 一次失败可能穿透多个出口 |
| D-7 | v1 附加出口上限 | **4** (总 5 出口 / 15 条常驻 SSH 会话) | 2026-11-25 用户裁决保留实现现状 (4)。`MaxStartups` 管的是**同时未认证**数, 3 条/出口 + 300ms 错峰不会撞; 资源按 3 出口与 5 出口**两套场景都测** (见验收 §6) |
| D-8 | 规则改动是否即时生效 | **即时** (出口增删/启停仍需重连) | 需重连: 改一行断网重连, 与域名名单叠加层的既有体验不一致; 且快照交换本来就是即时的 |
| D-9 | 首页状态卡是否显示多出口 | **不显示, 只显示主出口** | 显示 `已连接 · 2 出口`: 状态卡与通知同源 (`StatusDisplay.build`), 要额外表达"其中 1 个降级", 还要同步 4 份 i18n |
| **D-10** | **按应用分流放哪个阶段** | **v1 (P1 解析/UI, P2 接路由)**, 不是 P3 | 放 P3: 唯一阻碍是 `AppUidResolver` 缺完成信号, 而它只是一个接口改动; 放 P3 意味着 `forwardSynToTunnel` 要改两次、规则 DSL 要扩两次、UI 要重做一次 |
| **D-11** | **应用规则能否强制走隧道** | **不能**, 只决定"已进 TUN 的连接走哪个出口" | 能: 进不进 TUN 在 SYN 之前由 `addAllowedApplication` + 路由表决定 (N7/N9), 应用规则无法回溯; 声称能 = 在 SYSTEM/空白名单下必然骗用户 |
| **D-12** | **应用规则写 uid 还是包名** | **包名** (`app:com.example.app`) | uid: 重装/多用户/共享 uid 都会变; 写 uid 的规则在重装后静默失效, 且共享 uid 会误伤整组。运行时 `uid → 包名` 由 `AppIdentity` 解析 |
| **D-13** | **重试的失败分类** | **只重试 CONNECT 之前** (`LOOPBACK/NEGOTIATE/AUTH/BUDGET`), 且 `exitId != default` | 按 `rep` 码分出口级/目标级: `Socks5ProxyServer` 把一切失败映射成 `0x05` (C-4), 用它重试 = NXDOMAIN 每次多一次建连 (与 N6 冲突); 允许 default 重试: 没有"下一个"可退, 只会多一次必然失败的建连 |
| **D-14** | **规则引用不存在出口时怎么办** | **保留原文 + 匹配时跳过 + 汇总 WARNING** | 加载时丢弃 (v1 设计): 规则在 DataStore、出口在 Room, 两者无事务 → 改名/迁移/导入时序不一致时**永久丢失**用户配置; 与 `StatsRuleText` 的精确删行哲学相反 |
| **D-15** | **uid 等待预算** | **50ms 硬上限**, 正常 < 2ms; 超时 = `NO_UID` 回退 default | 无界等待: 客户端 SYN 超时; 200ms: 每条新连接多 200ms 首包延迟, 表现为"整页变慢"; 异步不等待 (v1 的 P3 方案): 同一 app 随机走不同出口 |

### 16.1 随附默认处理 (无异议, 视为已确认)

| # | 处理 | 理由 |
|---|---|---|
| E-1 | 允许附加出口指向同一台服务器, UI 只提示"不会带来新出口 IP" | 禁止要多一层校验, 收益低; 分开会话池本身可能是用户目的 |
| E-2 | 多出口**不进**「域名访问统计」维度 (仍按域名聚合) | CSV 列顺序是冻结契约 (`DomainStatsCsvTest` 逐字符断言), 加列要动 v1.2.7 刚定稿的格式 |
| E-3 | 出口健康**不进** `故障归因` / `StageCounters` / 全局 `verified` | 附加出口抖动不该把整条链路判降级; 只写应用日志 (`出口降级` WARNING) |
| E-4 | keepAlive / MTU 沿用服务器配置, 不做 per-exit 覆盖 | 全局设置已有 `gKeepAlive ?: config.keepAlive` 合并, 再加一层会三方打架 |
| E-5 | **`JschSshClient` 加 `exitLabel`** (body 属性, 非构造参数), 只影响两处日志 | 不加: 附加出口池失败会打全局 ERROR (`SSH 会话全部丢失 · 连接即将断开并触发重连`), 与 E-3 和 S21 直接冲突; 加构造参数: Dagger 会去找 `String` 绑定 |

### 16.2 明确留到 P3 之后再议

| 项 | 触发条件 |
|---|---|
| 级联回退 (default 也不可用时找下一个 Ready 出口) | 有用户真的用满 3 出口且主出口频繁失败 |
| 主出口可指定为某附加出口 | 需要同时重构"连接哪台 / 流量走哪台"的 UI 与诊断口径 |
| 出口上限 > 4 | 有实测数据说明 `SshIoDispatcher` 与内存仍有余量 |
| 外部 SOCKS5/HTTP 出口 | 健康/并发/认证语义不同, 需独立插件 (见 §14.3) |
| 应用规则的时间窗 / 网络条件 / 按 app 分 DNS | 前者是 UI+匹配维度扩张; 后者受 netd 代发 DNS 限制 (N8) |

### 16.3 v2 评审修订记录 (2026-10-29)

评审对象: 本文件 v1 + 当日 `main` 的实际代码。结论: **架构选项 B 成立**, 但有 10 处
"按 v1 实现会确定性出错 / 会静默失效"的点, 已在上文各节就地修正:

| # | v1 的说法 | 代码事实 | 修正落点 |
|---|---|---|---|
| 1 | 按 uid 路由放 P3, 因为"uid 在 SYN 时还没回来" | `AppUidResolver` 是**只异步**, 但反查确实在 `createTcpConnection` 发起; 缺的只是"完成信号" | D-10 / §8.5.1 / §9.6 |
| 2 | `JschSshClient` 一行不改 | `checkPoolHealth` / `createDirectChannel` 的 ERROR 文案是**全局**的, 任何实例都会打 | E-5 / §8.2 / C-5 |
| 3 | 失败分 `ExitFailed` (出口级) / `TargetFailed` | `connectToTarget` 把所有失败映射成 `0x05`, 这一层**分不出** | D-13 / §8.5 / §9.5 / C-4 |
| 4 | 未知出口的规则**加载时丢弃** | 规则与出口分属 DataStore/Room, 无事务; 丢弃 = 静默数据丢失 | D-14 / §6.2 细则 6 |
| 5 | `hasDomainRule(domain, port)` | 带端口的规则会提升不了 DNS, 而 TCP 阶段又拿不到域名 → 规则永久失效 | §8.8 / §6.5 |
| 6 | `TunnelConfig.Socks5.exitId` + 插件 `exitId` | 两份出口身份会分叉 (配置写 default、实例是 hk) | §8.1 / §8.3 |
| 7 | `PrimarySocks5Plugin` + 插件 map | 该类不存在; 且主出口也可以由工厂创建 | §8.10 |
| 8 | 附加出口探活"串行, 4×5s" | 4 个出口串行 = 20s, 会挤掉 15s 节拍里的主探活 | §8.7 / §9.3 |
| 9 | `ConnectionBudget` 在 `handleAccept` 里 acquire | 与 `onClosed` 的 release 之间有构造/启动窗口 | I-18 / §8.9 |
| 10 | 128 线程墙未提 | `SshIoDispatcher` 上限 128, 512 预算挡不住; 单出口可饿死其它出口 | I-19 / §10 资源表 |

**未改动 (评审确认 v1 正确)**: 架构选项 B (每出口一个插件 + 一个本地 SOCKS 端口)、
规则单表 first-match、主出口不可指定、附加出口上限 4、出口健康不进全局归因、
`stopAll()` 覆盖全部出口、`forceReset()` 已调 `stopAll()` 无需改。

### 16.4 施工前复核补丁 (2026-10-31)

v2 定稿后又按"照抄会写出坏代码"的标准逐段复核了一遍, 追加三处修正 —— 都不改架构,
但都直接影响性能或验收判据:

| # | v2 的写法 | 问题 | 修正落点 |
|---|---|---|---|
| P-1 | `AppUidResolver` 同时提供 `lookup(onDone)` 与 `awaitUid(...)` | 两个入口各自入队 = 每条新连接两次 binder 调用, 第二次必然排在第一次后面 → 50ms 预算内更容易超时; 且 `enqueueAppUidLookup` 会重复查询 | §8.5.1 改为**请求句柄** (`request(...): UidRequest`, `await` 有界 + `onComplete` 回写统计), 一次查询多人等 |
| P-2 | 时序把 `awaitUid` 排在 bypass 判定之前 | 直连流量不选出口, 应用规则对它无意义 → 分流模式下**每条直连多 50ms 首包延迟** (现象只有"网页整体变慢", 极难归因) | §5.2 / §5.1 时序: **bypass 判定先行**, 直连路径不 await; I-14 与 `MultiExitAppRoutingTest` 各加一条断言 |
| P-3 | I-19 的"单出口软上限 128"未区分 default | 若对 default 也生效 = 把现有 `MAX_CONNECTIONS`(512) 静默降到 128, P0/P1 的"零行为变化"直接破; 且 resolve 阶段拒绝没有 SOCKS 应答通道 | I-19 / §8.4 `resolveExit`: **只约束非 default 出口**, default 满员仍走既有 `isSaturated()` → `0x05`; **P0 不启用**软上限 |

> 这三条都写进了对应小节, 不是"只记在这里" —— §16 只是索引, 实施以各节为准。

---

## 附录 A · 规则语法 EBNF 与错误码

```ebnf
document   = { line } ;
line       = ws, [ rule | comment ], ws, [ newline ] ;
comment    = "#", { any - newline } ;
rule       = target, ws, "->", ws, exit_ref ;

target     = app_target
           | domain [ ":" port ]
           | ipv4 [ "/" prefix ]
           | ipv6 [ "/" prefix ]
           | "[", ipv6, "]", [ ":", port ]
           | "default" ;

app_target = "app:", package, [ ":", port ] ;
package    = label, ".", label, { ".", label } ;   (* 至少一个点; 不做通配 *)

domain     = [ "*.", ] label, { ".", label } ;
exit_ref   = "default" | exit_name ;
exit_name  = ( letter | digit | "_" | "-" ), { letter | digit | "_" | "-" } ;
port       = digit, { digit } ;          (* 1..65535 *)
prefix     = digit, { digit } ;          (* IPv4 0..32, IPv6 0..128 *)
ws         = { " " | "\t" } ;
```

| 错误 | 处理 | UI |
|---|---|---|
| 未知出口名 | **保留该行, 匹配时跳过**, 汇总 WARNING | 行内黄标 (`unresolved`) + 底部汇总 |
| `default` 非最后一行 | 丢弃 `default` 行 | 行内红标 + 提示"必须最后" |
| 重复 target | 保留首行 | 行内黄标, 允许保存 |
| 非法 CIDR / 端口 | 丢弃该行 | 行内红标 |
| 非法包名 (`com`, `com..a`, `app:`, 含 `*`) | 丢弃该行 | 行内红标 + 提示"只能填包名" |
| 超过 512 行 | 拒绝保存 | 底部红字 + 当前行数 |
| `0.0.0.0/0` 或 `::/0` | 拒绝 | 提示改用 `default -> x` |

> **"未知出口"与其它错误的处理不同**: 其它错误是**语法错** (这一行本来就无法解析),
> 未知出口是**引用错** (语法完全正确, 只是被引用的对象暂时不在)。前者丢弃不会丢信息,
> 后者丢弃会丢用户配置 (§6.2 细则 6 / D-14)。

等价写法示例:

```text
# 这两行语义相同 (都是后缀匹配)
example.com     -> hk
*.example.com   -> hk

# 只要 443
example.com:443 -> jp

# IPv6 + 端口
[2001:db8::1]:443 -> jp

# 按应用 (包名, 不是 uid)
app:com.example.app      -> hk
app:com.example.app:443  -> jp

# 兜底
default -> hk
```

---

## 附录 B · 文件改动地图

> 行号为当前工作区锚点, 实施时以代码为准。

| 文件 | 现锚点 | 改动 | 阶段 |
|---|---|---|---|
| `domain/vpn/tunnel/TunnelConfig.kt` | `:14-23` `Socks5`, `:30-47` `forSocks5` | 加 `serverId/hostKeyFingerprint` (**不加 `exitId`**); 透传 | P0 |
| `domain/vpn/tunnel/TunnelPlugin.kt` | `:3-62` | 加 `exitId` / `healthState()` / `activeConnections` 默认实现 | P0 |
| `data/remote/tunnel/Socks5TunnelPlugin.kt` | `:12-19` 单例/ctor, `:21` `socksServer`, `:40-73` `connect`, `:33-38` 诊断 | 去单例; ctor 加 `exitId/label`; 修 ServerConfig; 端口**原样用 `c.socksPort`**; 诊断前缀; `exitScope` | P0 |
| 新增 `data/remote/tunnel/TunnelPluginFactory.kt` | — | `TunnelPluginFactory` + `Socks5TunnelPluginFactory` | P0 |
| `data/remote/ssh/JschSshClient.kt` | `:407-416` 单例 ctor, `:575-582` 池状态, `:758-773` 指纹, `:1297` 池失败日志, `:1426` 无可用会话 | **数据面逻辑不改**; 加 `@Volatile var exitLabel` (body 属性); 两处日志按标签分流 (C-5) | P0 |
| `domain/vpn/tunnel/TunnelManager.kt` | `:22` `activePlugin`, `:24` `activePlugins`, `:30-52` `startPlugin`, `:54-58` `stopAll`, `:60-63` fallback | 改 `exits: Map<exitId, ActiveExit>`; `startExit/resolveExit(target)/stopExit/exitDiagnostics`; `stopAll` 先 cancel; `getActiveOrFallback` **改可空**; 每出口会话计数; 软上限判断 | P0/P1 |
| `di/TunnelModule.kt` | `:12-18` | 插件 map → **工厂 map** (`@Binds @IntoMap @StringKey("socks5")`) | P0 |
| `domain/vpn/TcpStateMachine.kt` | `:177` `tunCallbackPlugin`, `:192-224` `TcpConnection`, `:555-600`, `:839-1058`, `:1847` uid 入队, `:1973-1977` | `conn.exitId/tunnelPlugin/appPkg/uidRequest`; `uidRequest.await(50)` + `RouteTarget` + `resolveExit`; `ForwardOutcome`; `releaseHandshakeResources`; 重试 (仅 CONNECT 前) | P0/P2 |
| `domain/vpn/Socks5ProxyServer.kt` | `:27` 上限, `:55-65` 实例状态, `:71-76` 诊断, `:102-141` start, `:252-270` accept | `exitId` 标签; ctor 接受共享 `budget`; **构造后 acquire**, `onClosed` 里 release; `onClosed(up, down)` | P0 |
| 新增 `domain/vpn/ConnectionBudget.kt` | — | 全出口共享计数 (512); `tryAcquire`/`release` 幂等语义 | P0 |
| 新增 `domain/vpn/ConnectionBudgetHolder.kt` | — | `@Singleton` 持有"当前会话的那一个", 断开置 null (§10 I-8) | P0 |
| `domain/vpn/AppUidResolver.kt` | `:28-34` 接口, `:41` `NO_UID` | 改为 `request(...): UidRequest` (句柄, 一次查询多人等) + `await(timeoutMs)` 有界一定返回 + 计数 | P2 |
| `domain/vpn/ConnectivityAppUidResolver.kt` | `:66-75` 单线程池, `MAX_PENDING=64` | 线程 1→2~4; 队列满**立即回调 `NO_UID`**; `CompletableDeferred` 完成语义; `DisableWindow` 保留 | P2 |
| 新增 `domain/vpn/AppIdentity.kt` | — | `uid → 包名` (`getNameForUid`) + 会话级缓存 + `clear()` | P1 |
| `domain/usecase/VpnController.kt` | `:392-399` bypass, `:447` startPlugin, `:588-651` disconnect, `:1322-1340` 快照 | 路由接线; 附加出口启动 (**经 `ServerRepository`**); 每会话 `ConnectionBudget`; `setExitRulePredicate`; `appIdentity.clear()`; 快照字段 | P1/P2 |
| `vpn/SshVpnService.kt` | `:209-261` 探活, `:966-998` 池失败观者, `:1147` autoReconnect | 每出口探活 (**并发**); 主出口专属重连 | P1/P2 |
| `domain/vpn/DnsInterceptor.kt` | `:190-200` 标签, `:669-679` 路径 | 两处共用纯函数 `shouldTunnel`; `setExitRulePredicate` (委托 store, 关闭时 null); `hasDomainRule` **忽略端口/忽略应用规则** | P2 |
| 新增 `domain/vpn/exit/ExitId.kt` / `ExitHealth.kt` | — | 常量/规范化/状态枚举 | P0 |
| 新增 `domain/vpn/exit/ExitRule.kt` / `RoutingTable.kt` / `RoutingTableStore.kt` | — | 解析 (`app:`)/匹配/快照存储; unresolved 规则保留 | P1 |
| `domain/vpn/DataPlaneSnapshot.kt` | `:33-60` | 加 `exitDiagnostics` (空串省略) + `应用` 行 | P1/P2 |
| `domain/vpn/PacketProcessor.kt` | `:43-44` 构造 | 注入 `AppIdentity` 并**显式传给 `TcpStateMachine`** (不传 = 应用规则永远不命中, §8.5.1 接线 #1); 快照包装 | P2 |
| `data/local/database/AppDatabase.kt` | `:16-23` version, `:35-50` 迁移 | v5 + `MIGRATION_4_5` | P1 |
| `data/local/entity/Entities.kt` | `:8-31` 现有实体 | 新增 `ProxyExitEntity` | P1 |
| 新增 `data/local/dao/ProxyExitDao.kt` | — | CRUD + observe | P1 |
| `data/local/preferences/SettingsDataStore.kt` | `:31-59` 键区 | 三个新键 | P1 |
| `ui/navigation/NavGraph.kt` | `:65-84` settings 分支 | 四条新路由 (含应用选择器) | P1/P2 |
| `ui/screen/settings/SettingsScreen.kt` | 设置行 (`:286-420` 区) | 新入口行 | P1 |
| 新增 `ui/screen/exit/ProxyExitsScreen.kt` / `ProxyExitEditScreen.kt` / `ExitRulesScreen.kt` / `ExitAppsScreen.kt` + ViewModel | — | UI + 校验 + 测试框 + 应用选择器 | P1/P2 |
| 新增 `ui/screen/apps/InstalledAppsSource.kt` (从 `WhitelistViewModel` 抽出) | `WhitelistViewModel.kt:106`, `WhitelistScreen.kt:97` | 共享已装应用列表 (包名/应用名/图标/系统应用/共享 uid 注记) | P1 |
| `values{,-en,-ru,-zh-rCN}/strings.xml` | — | 约 37 个 key × 4 | P1 |
| `docs/specs/2026-10-18-device-verification-checklist.md` | S16 之后 | 追加 S17–S26 (附录 D) | P2 |
| `docs/diagrams/index.html` | — | 组件图/时序图加出口维度 + uid 决策点 | P2 |

---

## 附录 C · 调研期发现的既有缺陷 (与本功能强相关)

> 五条都是**当前代码**的问题, 不是多出口引入的。建议在 P0 一并修掉
> (C-1 是安全修复, C-3/C-5 是健康判读修复, C-2/C-4 是多出口的结构前提)。
> **C-1 与 C-5 都会改变可见行为**, 必须单独提交 + 单独写发布说明 (见各自小节)。

### C-1 · 运行时隧道路径丢失 `id` / `hostKeyFingerprint` → DB 指纹强校验失效

**证据链**

- `TunnelConfig.Socks5` (`TunnelConfig.kt:14-23`) 没有 `id` / `hostKeyFingerprint`;
- `Socks5TunnelPlugin.connect` (`:44-55`) 用这些字段构造 `ServerConfig`, 于是 `id = 0`,
  `hostKeyFingerprint = null`;
- `JschSshClient.persistFingerprint` (`:758-762`) 对 `id <= 0` 直接 `return`;
- `KnownHostsHostKeyRepository(..., config.hostKeyFingerprint)` 拿到 `null` → 走 TOFU 分支。

**后果**

1. 隧道连接**从不**校验用户在「重置主机指纹」之外已经保存的指纹 —— 服务器换 key
   (或中间人) 在隧道这条路上不会被拦; 只有向导/测试连接那条路会拦。
2. 隧道连接也**从不**把首次见到的指纹落库 (id=0) → 用户永远等不到"强校验已生效"。

**修法 (P0)**: 字段补齐并透传; 补单测断言 `forSocks5` 的产物带 `id`/指纹, 且
`persistFingerprint` 在 `id > 0` 时被调用 (用假 DAO 计数)。

**说明**: 这是安全修复, 与多出口无耦合, 可以单独发版。**但它不是零行为变化**:
补上指纹后, 隧道连接会开始强校验 DB 里的历史指纹 —— 指纹过期/不匹配的用户会从"能连"
变成"连不上" (失败文案必须明确指向「设置 → 重置主机指纹」)。因此:

- **单独提交、单独写发布说明**, 不要混进 P0 的"逐字一致"验收 (S17);
- 失败行必须是 WARNING 且带域名 (`隧道主机指纹不匹配 · host — 已保存 … / 实际 …`);
- 回滚方式 = 回退该提交 (不是关多出口开关)。

### C-2 · `TcpStateMachine.tunCallbackPlugin` 是机器级单字段

**证据**: `:177` 声明, `:887` 赋值, `:1973-1976` 使用。

**单出口时为什么没暴露**: 只有一个插件实例, 全局字段恰好恒等于"当前插件"。

**多出口时的两条问题**

1. **回调泄漏**: 连接 A (出口 X) 注册 → 字段 = X; 连接 B (出口 Y) 注册 → 字段 = Y;
   A 关闭时调用 `Y.removeTunCallback(A.localPort)` —— 键在 Y 的实例映射里不存在,
   `remove` 是 no-op; **X 里的那条回调永远留着**, 闭包持有 `conn`、`TcpStateMachine`、
   32KB 级对象图。长会话 + 高并发 = 稳定泄漏。
2. **端口复用误触发**: loopback 客户端端口会被内核回收再利用; 残留回调若命中新连接
   的同一个端口号, 旧闭包会把数据写进**已关闭连接**的 `writeTcpPayloadToTun`
   (被状态检查挡住, 但会计入丢弃/异常计数, 污染判读)。

**修法 (P0)**: 注册时同时写 `conn.tunnelPlugin = plugin`; 关闭时
`conn.tunnelPlugin?.removeTunCallback(conn.socksLocalPort)` +
`removeTargetEofCallback` + 置 null。删掉机器级字段。
单测 `MultiExitCallbackTest`: 交错注册/关闭, 断言各实例的映射表都为空。

### C-3 · 探活读 `config.socksPort`, 数据面读 `plugin.localSocksPort`

**证据**: `SshVpnService.kt:215` vs `TcpStateMachine.kt:570`。

**今天为什么没事**: 主出口按配置端口 bind, 两者数值相同; bind 失败时插件直接失败,
不会出现"绑到了别的端口还继续跑"。

**为什么现在要修**: 本设计给附加出口用 `bind(0)`, 并且未来可能允许主出口"端口冲突回退"。
一旦端口回退, 探活会打到**没有监听**的旧端口 → 健康永远失败 → 误降级/误重连。
另外"附加出口探活"本来就必须用实际绑定端口。

**修法 (P0)**: 统一 `tunnelManager.exit(DEFAULT)?.plugin?.localSocksPort ?: config.socksPort`。

### C-4 · `TunnelManager.startPlugin` 以协议名为键覆盖旧实例 (latent)

**证据**: `:39-42` `activePlugins[pluginId] = plugin` —— 第二次启动同一协议会把旧实例
从 map 里覆盖掉, **但不会 `disconnect()` 它**。今天因为 `VpnController.connect` 先判
`isRunning` 且 `stopAll()` 会清空, 所以走不到; 一旦有人加"重连插件"或"多出口用同一协议",
就是稳定的端口 + SSH 会话泄漏。

**修法 (P0)**: 按 `exitId` 分键; 同 id 重复启动返回已有实例或先 stop 再 start (二选一, 写入 KDoc)。

### C-5 · `JschSshClient` 的池失败日志是"全局结论", 多实例时误报

**证据**

- `checkPoolHealth()` (`:1297`) 在所有会话都 `!healthy && !isConnected` 时打
  **ERROR** `SSH 会话全部丢失 · 连接即将断开并触发重连`;
- `createDirectChannel` (`:1426`) 无可用会话时打 **ERROR**;
- 两处都**不看实例身份** —— 附加出口的池死了, 文案却断言"整条连接即将断开"。

**单出口时为什么正确**: 池就是主出口的池, 结论成立。

**多出口时的后果**

1. **假故障进归因**: 附加出口抖动会让 `故障归因` 多一类 ERROR, 而主连接完全正常 ——
   与 E-3 (出口健康不进全局归因) 直接冲突;
2. **误导用户**: 日志页出现"连接即将断开", 用户会去重启 VPN;
3. **验收 S21 失败**: 该用例的"不得"清单明确禁止这条文案。

**修法 (P0)**: `@Volatile var exitLabel: String = ""` (body 属性, **不是构造参数** ——
`@Inject` 构造加 `String` 会让 Dagger 去找 `String` 绑定), 由工厂/`VpnController.connect`
在 `client.connect()` 之前赋值; 两处日志按标签分流:

| 条件 | 文案 | 级别 |
|---|---|---|
| `exitLabel == ""` | 原文案 (逐字不变) | ERROR |
| `exitLabel != ""` | `出口 SSH 会话全部丢失 · hk — 新连接改走 default` | WARNING (`throttleKey = "出口降级"`) |

**验证**: `MultiExitIsolationTest` 断言附加出口失败时**不出现**"连接即将断开";
S21 的"不得"清单是同一判据的真机版。

---

## 附录 D · 真机验收清单增量 (S17–S26)

> 追加到 `docs/specs/2026-10-18-device-verification-checklist.md`。
> 每条都写成"你做什么 → 日志里必须出现 / 不得出现什么"。
> **S17 是前置门禁: 不通过就不测后面的。**

### S17 · 功能关闭基线 (零行为变化)

- **做**: 关闭多出口, 正常连接, 浏览 + 下载 + Play 各一次 (等同 S0/S1/S2/S4)。
- **必须**: 设置页显示 `未启用`; 日志里**没有**任何 `出口 ` 前缀的行;
  `数据面快照` 文本与上一版逐字一致 (无 `出口` 段); 只有 1 个 loopback SOCKS 端口;
  `SSH 会话 3/3`。
- **不得**: 出现 `出口就绪/降级/规则重载`; 快照多出字段; 端口数 > 1。

### S18 · 空规则 + 启用 (等价性)

- **做**: 打开多出口, 添加 1 个附加出口, 规则表留空, 重连。
- **必须**: `出口就绪 · hk — SSH 3/3 会话 · 本地 :NNNNN · 建连 Xms`; 快照出现两段出口;
  所有站点仍然只走 default (附加出口 `0 连` 持续); 主出口流量/建连耗时与 S17 同量级。
- **不得**: 附加出口出现任何连接; `故障归因` 多出条目; `SSH 会话` 总数不是 6。

### S19 · 域名规则分流 (核心用例)

- **做**: 规则 `ipify.org -> hk` (或任意能回显出口 IP 的站点), default 指向另一台 VPS;
  重连后访问该站点与一个未命中站点各 3 次。
- **必须**: 命中站点显示的出口 IP = hk 的 IP; 未命中 = default 的 IP;
  快照 `hk: N 连` 随访问增长; 日志 `隧道通道就绪 · <域名>` 正常。
- **不得**: 两个站点走同一出口; 出现 `出口快速失败重试` 后仍停在错误出口;
  出现 `SOCKS 0x03`。

### S20 · IP/CIDR 规则 + 端口限定

- **做**: 用 `curl` 直连一个已知 IP (字面量 URL), 规则 `A.B.C.D -> jp`;
  再测 `example.com:443 -> jp` 与同域名的 :80。
- **必须**: 字面量 IP 走 jp; :443 走 jp, :80 走 default。
- **不得**: 端口规则误伤同域名其它端口; 字面量 IP 被域名规则覆盖 (顺序错误)。

### S21 · 附加出口故障转移 (不重启整条 VPN)

- **做**: 连接稳定后, 在服务器侧 `pkill -u <user> sshd` 或断其网络, 让 hk 的 3 条会话全断;
  然后访问 5 个命中 hk 规则的新域名。
- **必须**: `出口降级 · hk — SSH 会话全部丢失, 新连接改走 default` (WARNING, 一次);
  `出口 SSH 会话全部丢失 · hk — 新连接改走 default` (WARNING, 来自 `JschSshClient.exitLabel`, C-5);
  新连接全部成功且走 default; 日志出现 `出口快速失败重试` 或直接回退;
  **没有** `SSH 会话全部丢失 · 连接即将断开并触发重连` / `auto reconnecting`。
- **不得**: 整条 VPN 重连; 主出口连接被 RST; `故障归因` 出现"网络探测失败"或
  `SSH 会话全部丢失` (全局那条); 30s 内反复降级/恢复抖动 (隔离窗口失效)。

> 这一条是 C-5 的**验收判据**: 只看"VPN 没断"不够, 必须确认日志里没有那条全局 ERROR 文案
> (它进 `故障归因`, 会让后续所有排障判读带上假故障)。

### S22 · 主出口故障语义不变

- **做**: 让 default 的 3 条会话全断。
- **必须**: 与今天完全一致 —— `SSH 会话全部丢失 · 连接即将断开并触发重连` +
  `auto reconnecting`; UI 走 Connecting → Connected。
- **不得**: 静默把全部流量切到 hk 并保持"已连接"; 附加出口被误降级。

### S23 · 资源与泄漏

- **做**: 3 出口连续浏览 5 分钟 + 开 2 个附加出口, 然后断开; 再连一次, 10 秒后看快照。
- **必须**: 断开前快照 `在途通道` 回到 0; 断开后所有 loopback 端口释放
  (`ss -ltn` 无残留); 新会话 `SSH 会话 3/3`×N; `SshIoDispatcher` `饱和回执 0`;
  连接数合计 ≤ 512。
- **不得**: `在途通道` 单调上升; 断开后日志仍出现 `出口就绪` (复活); 内存/线程数上涨。

### S24 · DOMAIN_SPLIT + 规则一致性

- **做**: 切到域名分流, 名单内域名 A、名单外域名 B; 给 B 加出口规则 `B -> hk`。
- **必须**: B 被分配假 IP 并走 hk (`隧道通道就绪 · B`); 日志
  `DNS 拦截器已配置 (模式: DOMAIN_SPLIT)` 之后 B 的查询走隧道 (无 `realIpToDomain` 记录);
  改规则后再访问 B 立即生效 (无需重连)。
- **不得**: B 先被直连解析并回真实 IP (缓存标签与路径分叉);
  规则改了但 B 仍走 default; 出现 `RelativeNameException`。

### S25 · 按应用分流 (v1 核心用例)

- **做**: 规则 `app:<某浏览器包名> -> hk` (默认出口指向另一台 VPS); 冷启动该应用, 访问 3 个
  未命中域名规则的站点; 再用另一个应用访问同一批站点。重复 10 次冷启动。
- **必须**: 浏览器显示的出口 IP = hk 的 IP, 另一个应用 = default 的 IP;
  快照 `应用 · 命中 H/Q` 的 `H/Q` 随新连接增长, `超时` 不增长;
  `出口 hk: N 连` 只随浏览器增长; 日志**没有** `应用规则未生效` 的 WARNING
  (该文案按 DEBUG 打, 默认不显示)。
- **不得**: 同一应用的新连接分裂到两个出口 (除 `超时` 计数同步增长外);
  `SOCKS 0x03`; `应用规则未生效` 出现在默认视图; 主出口流量异常升高。

### S26 · 连接预算与关闭竞态

- **做**: 3 出口 + 连续快速打开/关闭 200 条短连接 (脚本循环 `curl --max-time 1`);
  再在 `connect()` 进行中强行断开 VPN (连接面板点断开)。
- **必须**: 快照 `连接数` 回落; 断开后无残留 loopback 端口; 重连后新连接正常;
  `SshIoDispatcher 饱和回执` 不持续上升。
- **不得**: 预算只减不增 (表现为"重连后所有新连接被拒"); `在途通道` 单调上升;
  断开时 `出口就绪` 在断开之后又打一条。

> S26 专门锁 I-18 的两个窗口: **构造后 acquire** 与 **`onClosed` 恰好 release 一次**。
> 只跑"正常打开/关闭"测不出这两个窗口 —— 必须包含"连接还没建立就被关闭"与"断开瞬间批量关"。

---

## 附录 E · 术语

| 术语 | 含义 |
|---|---|
| 出口 (exit) | 一组 `JschSshClient`(3 会话) + `Socks5ProxyServer`(1 监听), 对应一台服务器 |
| 主出口 / `default` | 用户点击连接的那台服务器; 不可删除、不可禁用 |
| 附加出口 | `proxy_exits` 表里的条目, 引用已保存服务器 |
| 规则 | `<target> -> <exit>` 一行, 有序 first-match |
| 应用规则 | `app:<包名>[:端口] -> <exit>`; **只决定已进 TUN 的连接走哪个出口**, 不改变是否进 TUN |
| 应用身份 | **包名** (`com.example.app`), 不是 uid; 运行时由 `AppIdentity.packageOf(uid)` 解析 |
| uid 等待 | `conn.uidRequest.await(50)` (由 `AppUidResolver.request` 返回的句柄), 超时 = `NO_UID` → 应用规则跳过 |
| 假 IP | `198.18.0.0/15` / `fd00::/8`, 由 `DnsInterceptor` 分配, 用于把域名带到 CONNECT |
| 出口级失败 | **仅 CONNECT 之前** (loopback/协商/凭据/预算); 可故障转移 |
| 目标级失败 | `rep != 0` / 远端 NXDOMAIN 秒拒; **不**故障转移 |
| 隔离窗口 | 降级后 30s 内不重试, 防止重试风暴 |
| 软上限 | **非 default** 出口的活跃连接上限 128 (= `SshIoDispatcher` 线程墙), 超过回退 default (I-19) |
