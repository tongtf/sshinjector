# 域名访问统计 · 第二轮 (③ 远端解析开关 / ④ 按应用 uid)

> 上游文档: 设计 `2026-10-19-domain-access-stats-design.md` (§7.2 / §7.3 / 附录 A)、
> P1 实施结果 `2026-10-19-domain-stats-p1-todo.md`、回归矩阵 `2026-10-18-device-verification-checklist.md`。
>
> **用户已定 (2026-10-19 晚)**: ① 时间窗、② Room v5 分块落盘 **不做** (不做持久化就没有跨会话时间窗,
> 两者一起废弃); 本轮按顺序做 **③ 远端解析开关 → ④ 按应用 uid**。

---

## 1. ③ 远端解析开关 (本轮完整落地)

> ⚠️ **机制段已更新 (v1.2.7, 2026-10-19 真机定案)**: §1.1 契约 #2 与 §1.2 里"exec `getent ahosts`"
> 的描述**已被真机推翻** —— 加固过的服务器对 exec 恒给空输出 (`ForceCommand /bin/false` / chroot +
> `nologin`), 实测服务器就是这样。现在远端腿 = 池内会话 `direct-tcpip` 到公共 DNS (53/TCP) 自己问。
> **完整取证、改动清单与判据见 §4**。其余契约 (#1、#3~#11) 不变。

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

## 2. ④ 按应用 uid (前提更正 + 探针 + 数据面, 均已落地)

### 2.1 前提更正 (2026-10-19 本地 SDK 取证, 推翻设计附录 A 的初判)

设计附录 A 写的是: `ConnectivityManager.getConnectionOwnerUid()` 是 `@SystemApi`
(`NETWORK_STACK`), 普通应用调不到。**这是错的**, 三条证据 (都可在构建机上复现):

1. 它出现在公开 stub 里: `javap -classpath platforms/android-37.0/android.jar android.net.ConnectivityManager`
   → `public int getConnectionOwnerUid(int, java.net.InetSocketAddress, java.net.InetSocketAddress)`;
   同 SDK 里 `@SystemApi` 的 `startTethering` / `getTetheringSupported` **不在** stub 中
   (`android.os.ServiceManager` 这类 @hide 类同样查不到) → 该 jar 是严格的公开 API 面。
2. `platforms/android-37.0/data/api-versions.xml` 记 `getConnectionOwnerUid(...)` **`since="29"`**
   —— 自 Android 10 起就是公开 API。
3. stub javadoc 明确写给 **VPN 应用**: "return uid if the connection is found and the app has
   permission to observe it (**e.g., if it is associated with the calling VPN app's VpnService
   tunnel**) or `Process.INVALID_UID` if the connection is not found. @throws SecurityException if
   the caller is **not the active VpnService** for the current user";
   `annotations.zip` 里该方法**没有** `@RequiresPermission` → 不需要清单权限。

**含义**: ④ 的可达上限比原判断高得多 —— 不需要 `PACKAGE_USAGE_STATS`, 不需要 root/Shizuku,
本应用作为**活动 VpnService** 就是该 API 的目标调用者。剩下的未知只有**设备侧行为**
(真机是否真的给隧道连接返回归属、平台实现差异), 所以本轮仍然**不写 `appUid` 进数据模型**,
只把这条未知变成一条可回报的证据 (纪律: 验证通过前不动数据模型)。

### 2.2 探针 (只读诊断, 用户点一下)

位置: 设置 → 域名访问统计 → 顶栏 `i`。实现 `domain/vpn/AppUidProbe.kt`。

- **未连接 VPN 直接不跑** → `VPN_NOT_ACTIVE` (该 API 只对活动 VpnService 开放, 此刻调用只会得到
  `SecurityException`; 把那种"被拒"当结论就是误判);
- **真查一次**: 在自己进程内建一条**真实回环 TCP 连接** (双方都属于本进程), 双向各查一次归属 ——
  返回本进程 uid = `AVAILABLE` (该 API 对我们真的能用); 返回 -1 = `NO_OWNER` (能调但查不到);
  `SecurityException` = `DENIED`; 其它 = `ERROR` (+`detail`);
- **只碰本机回环**: 不进隧道、不出网 (不产生统计行、不占数据面连接)、不改任何状态;
- 辅证 (供判读 P2 的退路): `PACKAGE_USAGE_STATS` app-op 授权状态 + `getPackagesForUid` 可解析;
- 结论同时进**应用内日志** (INFO) 与 Toast → 真机回报只需一行。

### 2.3 真机回报后怎么走

| 探针结论 | ④ 的下一步 |
|---|---|
| `AVAILABLE` | 直接做: `TcpConnection` 已有 `srcIp/port`+`dstIp/port` (回程组包缓存), 在
`closeTcpConnection` 取五元组 → **异步**查 uid (单线程 + 有界队列, 绝不进 packetLoop) → `DomainStatsStore.attachAppUid(domain, uid)` → UI 显示"应用 N 个" |
| `NO_OWNER` | 改用**隧道侧真实五元组**再验一次 (最可能是"非隧道连接不给归属"; 那就是探针的假阴性, 不是 API 不可用) |
| `DENIED` | 该路径不可达 → 退到 `NetworkStatsManager` 的按 uid **总流量** (要 `PACKAGE_USAGE_STATS`), 它能给"哪个应用用了多少"但**给不出域名**, 必须另起一个正交页面而不是塞进本页 |
| `NO_METHOD` / `ERROR` | 把 `detail` 回报, 再定 |

**已定 (不随探针结果变)**: uid 查询一律**异步 + 有界 + 失败静默**; 绝不在 `closeTcpConnection`
(数据面热路径) 里做 binder 调用; `appUid` 只进统计展示, 不参与任何隧道决策 (与 P0/P1 的纪律一致)。

### 2.4 数据面落地 (v1.2.6, 按 `AVAILABLE` 路径实现)

| 环节 | 位置 | 纪律 |
|---|---|---|
| 触发 | `TcpStateMachine.closeTcpConnection` (紧接 `recordClose`) | 只 `lookup(五元组, 回调)`: 原子计数 + `execute` 入队; **无 binder、无阻塞、无分配洪泛**; 地址用 `conn.srcIpBytes/dstIpBytes` (原始字节, 不在数据面线程物化 `InetAddress`); 回调只 `attachAppUid(domainKey, uid)` |
| 查询 | `domain/vpn/ConnectivityAppUidResolver.kt` | 反射调 `getConnectionOwnerUid` (编译期 SDK 有、运行设备可能没有 → 反射让"缺失"退化成静默停用而不是 `NoSuchMethodError`); **单线程** `ThreadPoolExecutor` + **有界** `ArrayBlockingQueue(128)`; 在途上限 64 ≪ 队列容量 (保证不触发拒绝); `-1`/异常一律只计 `misses` |
| 写回 | `DomainStatsStore.attachAppUid` | 与 `attachRealIp` 同纪律: **不新建条目**、`uid < 0` 忽略、去重、最多 `MAX_APP_UID_SLOTS=3` 槽 (惰性 `IntArray`), 溢出只计数 (`appUidOverflow` → 资源节 `归属溢出 N`) |
| 展示 | `ui/screen/settings/AppUidLabels.kt` + `DomainStatsViewModel.resolveAppLabels` | uid → 应用名带缓存 (uid 数量级 = 用户真用过的应用数); 每次刷新最多解析 `MAX_APP_LABELS=64` 个 (超出退化成 `uid N`); 解析跑在 `Dispatchers.Default`, **绝不在组合期** |
| 观测 | 快照资源节 `UID 归属 命中 H/Q 查询 · 无归属 M [· 被拒 D] [· 队列丢弃 X]` | "到底有没有拿到 uid" 的唯一判据 (无 adb 现场) |

**两条不随实现变的红线**:

1. **断开瞬间必然 `SecurityException`** —— `resetTcpState()` 会逐条关连接并触发反查, 而那时本进程已不是
   活动 VpnService。所以"被拒"只能是**有时间窗的暂停** (`DisableWindow`: 30s, 窗口内只打一条 WARNING),
   **绝不能永久停用** (一次正常断开就把功能杀死到下次进程启动, 重连也不恢复 —— 这是实现期差点落下的
   回归, 由 `AppUidResolverTest` 的时钟注入用例钉死)。
2. **`appUid` 只进展示**: 域名/路由/名单判定都不读它; 拿不到 uid 时页面只是少一列, 隧道行为逐字节不变。

仍**不做**: 后台批量解析、把 uid 写进持久化模型 (P1 起仍只落名单三键)、把 uid 塞进导出 CSV
(CSV 列序是冻结契约, 要加列需另起一次显式变更)、按 uid 的隧道决策。

---

## 3. 验证

- 单测: `RemoteLookupTest` (注入防护 / getent 解析 / 截断) + `DomainIpResolverTest`
  (远端成功 / Unavailable / Failed / 空结果 / 关闭开关 / 池饱和 六条路径 → path 语义) +
  `DomainStatsStoreTest` 新签名 (两槽 + `realIpTotal` 截断可见 / 空表 no-op / 缺条目 no-op)。
- 真机 (清单 S14): 开关默认关 → 解析走本地 (日志 `本地解析…`); 打开开关且**已连接** →
  日志 `远端解析 · <域名> — Nms · N 个 IP`, 行内显示服务器侧 IP; exec 不可用的服务器 (OpenWrt/dropbear)
  → `远端解析不可用 · <域名> — 已回退本地视角`, 页面出现 `本地视角 (远端不可用)`, **不产生 WARNING**;
  未连接时打开开关 → 解析仍走本地 (无 `远端解析` 行)。
- 单测: `AppUidProbeTest` (3 例: 公开 SDK 前提锁 / 未连接时不跑 / 无服务时安全降级)。
- 真机 (清单 S15): **先连上 VPN** → 统计页顶栏 `i` → 日志与 Toast 各一行
  `UID 归属探针 · getConnectionOwnerUid=<token> · …`; 按 §2.3 的表决定 ④ 的下一步。
- 单测 (④ 数据面): `AppUidResolverTest` (5 例: 无服务静默 null / 查询计数且不抛 / `NONE` 空实现 /
  平台 `SecurityException` 计一次"被拒"且进入暂停窗口 / 暂停窗口到期自动恢复 —— 时钟注入, 不 sleep)、
  `TcpDomainStatsTest` (+2 例: 反查到的 uid 必须回写且五元组与连接一致 / 负数 uid 不得进槽)、
  `DomainStatsStoreTest` (+3 例: `attachAppUid` 不新建条目 / 三槽去重与溢出计数 + 资源节可见 /
  `clear` 归零溢出)、`AppUidLabelsTest` (3 例: 解析不到退化 `uid N` / 缓存命中只问一次 / 无包退化)。
- 真机 (清单 S16): 连上 VPN → 浏览几个 App (浏览器/应用商店) → 统计页出现「应用」列且是**真实应用名**;
  快照资源节出现 `UID 归属 命中 H/Q 查询` 且 `H > 0` (H==0 且 Q>0 = 该设备拿不到归属, 回报那一节);
  断开后**不得**出现 `UID 归属 不可用` / 反复 `UID 归属反查被平台拒绝` (窗口化应只出一次)。

---

## 4. v1.2.6 真机回灌 → v1.2.7 修复 (2026-10-19)

> 用户回报的 v1.2.6 日志暴露四处问题, 本轮全部处理;**每一条都有"日志指纹 → 根因 → 改法"**。

### 4.1 ③ 远端解析: exec 机制在加固服务器上永远拿不到输出 (改机制)

- **日志指纹**: `远端解析不可用 · <域名> — 已回退本地视角 (exec 不可用或会话池无活口)` 多次出现,
  而**同期**快照是 `SSH 会话 3/3 (健康 3, 可疑 0)`, 且耗时 <1s (秒级、非黑洞)。
- **根因 (服务器侧取证)**: 实测服务器 `/etc/ssh/sshd_config.d/socks-proxy.conf`:
  ```
  Match User sproxy
      ChrootDirectory /home/sproxy/chroot
      AllowTcpForwarding yes
      ForceCommand /bin/false     ← exec 请求被替换成 /bin/false: 无输出、exit 1
      PermitTTY no
  ```
  本应用向导生成的 Match 块同理 (chroot + `nologin` 账号 = chroot 里没有 shell, exec 起不来)。
  也就是说: **exec 不可用不是"某些服务器"的例外, 而是"加固过的服务器"的常态**, 而
  `AllowTcpForwarding yes` 恰好说明 **direct-tcpip 才是被允许的那条路** (整个数据面本来就靠它)。
- **改法**: 远端腿改为池内会话开 `direct-tcpip` 到公共 DNS (`1.1.1.1` → `8.8.8.8` →
  `2606:4700:4700::1111`), 自己组 DNS over TCP 报文 (RFC 1035 §4.2.2: 2 字节长度前缀 + 报文),
  解析 A/AAAA 应答。总预算 5s + 单候选 1.5s + `isSaturated()` 预检 + 单飞不变。
  - 通道姿势**照抄数据面 `createDirectChannel`** (`setInput/OutputStream(null)` + `getInputStream()/getOutputStream()`),
    **不能**用 exec 那套 `setOutputStream(BAOS)`; 通道 `finally { disconnect() }` 且 `activeChannels` 配对增减;
  - 顺序即答案 (不重排 v4/v6), 上限 8; 结果形态与本地腿一致 (`InetAddress.hostAddress`);
  - 三态不变, 但 `Unavailable`/`Failed` 现在**带人话成因** (`detail`), 直接进 INFO 文案 ——
    无 adb 时这是唯一能判断"哪一侧不干活"的通道 (v1.2.6 的文案没有任何信息量)。
- **顺带修掉的真 bug (本地腿)**: 真机日志
  `本地解析查询失败 · connectivitycheck.gstatic.com (type=1) — RelativeNameException: '…' is not an
  absolute name` —— `resolveLocally` 用 `Name.fromString(name)` 构造 QUESTION, 而域名已 `trimEnd('.')`
  过 = 相对名 → dnsjava 直接抛异常, **一个包都没发出去** → 表现是"点解析永远解析不到 IP"。
  改为 `absoluteName()` (`Name.fromString(host, Name.root)`), 单测锁死 (绝对名 + 尾点 + 空白/尾点收敛)。
  这条同时影响**非 A/AAAA 查询的受保护直查** (MX/TXT)。

### 4.2 ④ uid 命中率: 反查时机从"关闭时"提到"SYN 到达时"

- **日志指纹**: 快照 `UID 归属 命中 5/28 → 7/79 → 7/88 → 7/105` (命中**不再增长**);
  探针 `getConnectionOwnerUid=NO_OWNER (自测回环 uid=-1/-1, 期望 10500)`。
- **根因**: `getConnectionOwnerUid` 查的是**内核里这条 socket 的归属**, 而连接关闭那一刻应用常已把
  socket 销毁 —— 大量 `存活 0s` 的短连接 (HTTP/2 预热、探测请求) 一个都查不到。
- **改法**: 反查在 `createTcpConnection` (SYN 到达) 发起, 结果缓存在 `TcpConnection.appUid`
  (body 属性, 不是构造参数), 关闭时回写统计; 关闭**先**发生时由回调里的补写路径兜住
  (`state == Closed` → 用同一口径的 `statsKey` 直接 `attachAppUid`)。每个客户端 socket **只查一次**。
- **探针语义更正**: `NO_OWNER` 是**环回连接的预期假阴性** (环回不属于任何隧道, 平台不给归属),
  **不是**"能力不可用" —— 判定 ④ 是否可用看**数据面命中率**。所以探针结果现在同时带上
  `VpnController.appUidDiagnostics()`, 并把结论从 Toast 改成**可复制的弹窗** (真机反馈"且无法复制")。

### 4.3 本轮新增/改动单测

| 用例 | 锁什么 |
|---|---|
| `DnsInterceptorTest.resolveLocally builds an absolute question name` | 缺尾点的域名必须补成绝对名 (v1.2.6 的 `RelativeNameException` 回归锁) |
| `RemoteLookupTest` (重写 11 例) | 线格式 2 字节前缀往返 / 绝对名 + `RD` / 类型透传 / 截断·零长·超长帧判掉 / 答案保序去重与上限 / 垃圾应答 → 空表 |
| `DomainIpResolverTest` (2 例改名带 detail) | 三态回退路径语义不变 |
| `TcpDomainStatsTest.a late uid callback arriving after close is still recorded` | 关闭后才回来的 uid 必须补写 (短命连接全靠这条) |
| `AppUidProbeTest.probe surfaces the data plane hit rate` | 探针必须把数据面命中率带出来 (NO_OWNER 不是结论) |

### 4.4 本轮明确不做

- 不做远端解析结果缓存到 `DnsInterceptor` / 不用于隧道决策 (纯展示, 与 P0/P1 一致);
- ~~不把 uid 塞进 CSV (列序是冻结契约)~~ → **已于 §5.2 修订** (追加在**末尾**两列, 老列号不变),
  不做按 uid 的隧道决策;
- 不加"记录应用归属"开关 (拿不到就少一列, 无需开关)。

---

## 5. v1.2.7 真机回灌 (② 轮) → 降噪 + 收尾 (2026-10-19, **不升版本号**)

> 前提: v1.2.7 首版真机日志**四条全过** —— ③ `远端解析 · m.youtube.com — 446ms · 2 个 IP (S… · 1.1.1.1)`;
> ④ `UID 归属 命中 29/29 查询 · 无归属 0` (v1.2.6 是 `7/105`); 探针弹窗可复制 (用户直接贴出来了);
> 全篇无 `RelativeNameException`。剩下的只有**噪声**与两个收尾项, 所以这一轮不动数据面:
> 版本号保持 `1.2.7` / `versionCode 20`, 只改日志定级与展示层。

### 5.1 `远端不解析该目标` (A: 秒拒降噪)

**真机指纹**: `update.kiwibrowser.com.unused` (Kiwi 浏览器的更新探测用的假 TLD) 产生三条:

```
[ERROR] SSH 通道打开失败 · update.kiwibrowser.com.unused:443 — channel is not opened. · 试了 3/3 个候选 · 末次耗时 124ms · 在途通道 5
[ERROR] SSH 通道建立失败 · … — 通道工厂返回 null
[WARNING] SOCKS 连接被拒 · 198.18.0.14:443 — rep=5
```

**根因 (hk6 取证)**: 该名与 `update.kiwibrowser.com` 在 1.1.1.1 的 **53/TCP** 上都是 **NXDOMAIN**
(`rcode=3, ancount=0`; 对照 `m.youtube.com` = NOERROR/1)。真实 DNS 会直接回 NXDOMAIN、客户端根本不会建连;
假 IP 模式把任何 A 查询都回成 `198.18.x.x` → 客户端照常 CONNECT → sshd 侧 `getaddrinfo` 失败 →
**124ms 秒回 `CHANNEL_OPEN_FAILURE`** → `createDirectChannel` 返回 null → `rep=5`。
**这不是本栈故障**, 却在 `故障归因` 里凭空多出两类"故障"。

**改法** (只碰失败路径, 数据面逐字节不变):

- `ChannelOpenBudget.isFastRejectOnAllCandidates(attempted, candidateCount, stalledAny)`: 试满全部候选
  **且**没有任何一次拖到 `OPEN_STALL_MIN_MS` (=1s) 才算"全候选秒拒" (慢失败/预算 `break` 都不算);
- 命中该判据 → 一条 **INFO** `远端不解析该目标 (域名可能不存在) · <host:port> — <message> · 试了 N/N 个候选 ·
  末次耗时 Xms · 在途通道 K (pool: …)`, **不再打 ERROR**; 其余 (慢失败) 保持原 ERROR 文案不变;
- `Socks5ProxyServer` 里那条**重复**的 `SSH 通道建立失败 · … 通道工厂返回 null` 降 **DEBUG** ——
  `createDirectChannel` 已是唯一权威出口 (它的行里本来就带 `试了 N/M · 末次耗时 · 在途通道 · pool`),
  同一个失败不该算两类故障;
- `createDirectChannel` 删掉 `if (size == 0) return null` 这条**静默**出口 (同一刻池被清空时保证仍有日志);
- `SOCKS 连接被拒` 现在打**域名** (`target(conn)`, 不再是裸假 IP) 且 `rep=5` 带上人话成因;
- 安全网 (为什么降级不会掩盖真故障): **系统性**拒绝会让 `SSH 通道统计 · 目标层失败` 与快照的同类计数一起涨,
  且"所有域名一起失败"在 INFO 行里肉眼可见。

### 5.2 CSV 追加「应用」两列 (C)

- 末尾追加 `app_uids` (机器事实, 多值以 `,` 连接 → RFC4180 会加引号) 与 `app` (应用名, 认不出退化成 `uid N`);
  **老列号 0..10 不变** (契约只增不改); 空槽 (`-1`) 被滤掉 → 两列留空;
- 纯对象不碰 Android: 应用名由 `DomainStatsViewModel.exportCsv` 经 `resolveAppLabels` (带 `AppUidLabels` 缓存
  与 `MAX_APP_LABELS=64` 预算) 解析后传入, 与页面「应用」列**同一口径**;
- `DomainStatsCsvTest` 表头逐字符更新 + 3 条新用例 (单值不加引号 / 多值与退化成 `uid N` / 空槽过滤)。

### 5.3 探针去掉 `用量访问` (B)

`Result.usageStatsGranted`、`AppOpsManager` 那段读取、四份 `strings.xml` 的 `usage=%2$s` 占位、日志里的
`用量访问=…` 全部删除 —— 它是 `PACKAGE_USAGE_STATS`, 只服务**已被放弃**的 `NetworkStatsManager` 回退,
留在弹窗里会被误读成 ④ 的判据 (真机 `usage=denied` 而数据面 `命中 29/29` 就是这么被误读的)。

### 5.4 单测与门禁

- 新增 6 例: `ChannelOpenBudgetTest` 3 例 (全候选秒拒 / 有 stall 不算 / 未试满或零候选不算) +
  `DomainStatsCsvTest` 3 例; `AppUidProbeTest` 删掉 `usageStatsGranted` 断言。
- 单元测试 **398 → 404 例 0 失败**; `ktlintCheck` / `detekt` / `lint` / `assembleDebug` / `assembleRelease` 全绿。

### 5.5 本轮明确不做

- 不给假 IP 模式加 NXDOMAIN 透传 (要先真解析一次, 等于放弃假 IP 的机制收益); 只把**已发生**的秒拒归类;
- 不动探测失败 / `隧道零回程` 那条 (v1.2.7 日志里 3 次探测失败 + `零回程 2(秒断 2)`, 形态自洽且同域名很快
  恢复: `accounts.google.com` 16:40:44 下行 5836B、16:41:39 远端解析成功) —— 按既有判据属**瞬时抖动**,
  同一域名反复才动 app。

