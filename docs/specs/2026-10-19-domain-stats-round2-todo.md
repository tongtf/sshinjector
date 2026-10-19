# 域名访问统计 · 第二轮 (③ 远端解析开关 / ④ 按应用 uid)

> 上游文档: 设计 `2026-10-19-domain-access-stats-design.md` (§7.2 / §7.3 / 附录 A)、
> P1 实施结果 `2026-10-19-domain-stats-p1-todo.md`、回归矩阵 `2026-10-18-device-verification-checklist.md`。
>
> **用户已定 (2026-10-19 晚)**: ① 时间窗、② Room v5 分块落盘 **不做** (不做持久化就没有跨会话时间窗,
> 两者一起废弃); 本轮按顺序做 **③ 远端解析开关 → ④ 按应用 uid**。

---

## 1. ③ 远端解析开关 (本轮完整落地)

### 1.1 冻结契约 (直接来自设计 §7.2 / §7.3, 不再议)

| # | 契约 | 落地位置 |
|---|---|---|
| 1 | 统计页顶部开关 `使用远端解析 (经 SSH)`, **默认关**, 状态记忆到 DataStore | `SettingsDataStore.KEY_USE_REMOTE_RESOLVE` |
| 2 | 远端视角 = **池内已有会话**的 exec 通道跑 `getent ahosts <domain>` | `JschSshClient.resolveIps` |
| 3 | **绝不用** `RemoteCommandExecutor.execSingleShot` (它每次新建一整条 SSH 会话, 0.4~4s + 服务器侧会话堆积) | 同上 |
| 4 | exec 跑 `Dispatchers.IO`, **不进 `SshIoDispatcher`** (与 `verifySessionRoundTrip` 一致; 专用池只服务数据面阻塞读写) | 同上 |
| 5 | 三态: OK / **Unavailable** (exec 被禁/无可用会话/池饱和/无记录) / Failed (黑洞)。Unavailable 与 Failed **都不报错**, 静默回退本地视角 | `RemoteResolveOutcome` |
| 6 | 超时 **5s** + 启动前 `isSaturated()` 预检 + **单飞** (同时只跑一个解析) | `DomainIpResolver` + VM |
| 7 | 结果缓存进条目 (`realIp`/`realIp2`), 不重复解析 | `DomainStatsStore.attachRealIp` |
| 8 | **绝不后台批量解析**; 每次解析都是用户显式触发 | 仅 `resolveRealIp` 调用点 |
| 9 | 回退时 UI 标注 `本地视角 (远端不可用)` | `DomainStatsUiState.remoteUnavailable` |
| 10 | 展示技巧: 多个 IP 显示第一个 + `+N`; 无 IP 显示 `—`(既有) | `DomainStatsScreen.ipText` |
| 11 | 自证点: `远端解析 · <域名> — Nms · N 个 IP` (INFO, 故障归因只数 WARNING/ERROR) | `DomainIpResolver` |

### 1.2 新增纪律 (实现层, 违反即回归)

- **命令注入防护**: 域名来自 DNS 应答 (攻击者可控), 进 shell 前必须**字符白名单**校验
  (`RemoteLookup.isValidHost`: 只允许 `[A-Za-z0-9.-]`, 长度 ≤253, 不以 `-`/`.` 开头);
  校验不过 → 直接 Unavailable (回退本地), 绝不拼进命令。
- **通道必须 `finally { disconnect() }`**: exec 通道残留在 JSch 静态 `Channel.pool` 里 = 泄漏 +
  持续灌 1MB 管道 (见 AGENTS「JSch 一条 Session 只有一个读线程」)。
- **输出读取用 `setOutputStream(ByteArrayOutputStream())`** (JSch 读线程直接写进我们的流),
  **不要** `setOutputStream(null)` + 手工读 —— 后者会走通道默认 `PipedOutputStream` (32KB 上限),
  正是冻住整条会话的那个坑。输出上限 64KB (服务器可能灌爆内存)。
- **首候选失败后的换人语义**: 快失败 (秒级被拒, <2.5s) = exec 不支持 → 试下一个会话, 全快失败才
  Unavailable; 慢失败 (撑满超时) = 黑洞 → 立刻 Failed, 不再拿别的会话陪跑 (与往返探活同一判据
  `ROUND_TRIP_SLOW_FAIL_MS`)。
- **`getent` 顺序即答案**: 不重排 v4/v6 —— `getent ahosts` 的输出顺序就是服务器 `getaddrinfo` 的偏好
  顺序, 首个 IP 正是 SSH 通道实际会拨的那个 (gai.conf 修复后双栈域名 v6 优先), 重排会让展示的
  "远端视角 IP" 与真实出口不一致。

### 1.3 不做 (本轮明确排除)

- 单会话批量 (`getent ahosts a; echo ---; getent ahosts b`) —— 一次只解析用户点的那个域名;
- 解析结果写回 `DnsInterceptor.realIpToDomain` / 缓存 —— **纯旁路**, 只进统计条目;
- 远端解析用于 DNS 转发决策 —— 它只是展示, 不参与隧道行为。

---

## 2. ④ 按应用 uid (本轮只出**可行性探针**)

设计附录 A 的结论: `ConnectivityManager.getConnectionOwnerUid()` 是 `@SystemApi`
(`NETWORK_STACK` 权限), 普通应用调不到; `NetworkStatsManager.queryDetailsForUid` 只能给
"每应用总流量" (还要 `PACKAGE_USAGE_STATS` 特殊权限); `/proc/net/*` 需要 root/Shizuku。

**所以本轮不写任何 `appUid` 进数据模型**, 只落一个探针:
`①` 反射调 `getConnectionOwnerUid` 看是否可调用 (`NoSuchMethodException` / `SecurityException`);
`②` 查 `PACKAGE_USAGE_STATS` 的 app-op 授权状态; `③` 结果进应用内日志 (INFO)。
真机回报后**再决定**数据模型与 UI (能到 uid 才做"应用 × 域名", 只能到总流量就必须另起一个正交页面)。

---

## 3. 验证

- 单测: `RemoteLookupTest` (注入防护 / getent 解析 / 截断) + `DomainIpResolverTest`
  (远端成功 / Unavailable / Failed / 空结果 / 关闭开关 / 池饱和 六条路径 → path 语义) +
  `DomainStatsStoreTest` 新签名 (两槽 + `realIpTotal` 截断可见 / 空表 no-op / 缺条目 no-op)。
- 真机 (清单 S14): 开关默认关 → 解析走本地 (日志 `本地解析…`); 打开开关且**已连接** →
  日志 `远端解析 · <域名> — Nms · N 个 IP`, 行内显示服务器侧 IP; exec 不可用的服务器 (OpenWrt/dropbear)
  → `远端解析不可用 · <域名> — 已回退本地视角`, 页面出现 `本地视角 (远端不可用)`, **不产生 WARNING**;
  未连接时打开开关 → 解析仍走本地 (无 `远端解析` 行)。
