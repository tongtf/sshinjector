# 多出口 SOCKS5 路由 — 验收标准

> 配套: 设计文档 `2026-10-27-multi-socks5-routing-design.md` (语义)、
> 开发指南 `2026-10-31-multi-socks5-routing-dev-guide.md` (施工顺序)。
> **本文只回答"什么算做完"**: 不解释为什么这样设计, 也不写改哪个文件。
>
> 验收对象: 分支 `feat/multi-exit-routing` 的 P0 / P1 / P2 三个交付点。
> **P0 的验收标准与 P1/P2 不同**: P0 是"零行为变化", 判据是**逐字节一致**, 不是"看起来一样"。

---

## 0. 分层验收 (L0–L4)

| 层 | 内容 | 什么时候必须全绿 | 不通过的含义 |
|---|---|---|---|
| **L0** | 编译 + DI 图完整 | 每个 commit | 结构改造没接完 (Dagger 会先报) |
| **L1** | 单元测试 (JVM) | 每个 commit | 逻辑语义错; 并发用例失败 = 有真 bug |
| **L2** | ktlint / detekt / lint | 每个 commit | 代码规范门禁 (CI 同款) |
| **L3** | **零行为变化** (逐字节) | **P0 每个 commit + P1/P2 关功能时** | 功能关闭仍有差异 = 不能合并 |
| **L4** | 真机 S17–S26 | P2 完成时 | 运行时语义错 (单元测试覆盖不到) |

> L3 是本次功能**最容易被跳过**的一层: 单测全绿、编译通过, 但快照多了一个字段
> 就等于"关闭功能仍有行为变化"。P0 阶段必须每次都做。

---

## 1. L0 编译与 DI

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
```

| # | 判据 | 说明 |
|---|---|---|
| L0-1 | `assembleDebug` 成功, 无新增 warning (对比基线) | 新增 Kotlin warning 需在本 commit 修掉 |
| L0-2 | Hilt/KSP 通过 | `Socks5TunnelPlugin` 去 `@Singleton` 后若仍有 `@Inject` 注入点, 这里会失败 —— **这是预期的守卫** |
| L0-3 | `grep -r "Socks5TunnelPlugin" app/src/main` 只剩工厂 + DI 模块 | 证明没有旁路构造 |
| L0-4 | `grep -rn "tunCallbackPlugin" app/src/main` 为 0 | C-2 已清除 |
| L0-5 | `grep -rn "getActiveOrFallback()" app/src/main` 的每一处都有判空 | 三处调用点 |

---

## 2. L1 单元测试清单

### 2.1 P0 (结构, 必须现在就有)

| 测试类 | 断言 (必须锁死的点) | 反例 (改坏了会怎样) |
|---|---|---|
| `TunnelConfigSocks5Test` | `forSocks5` 透传 `id` / `hostKeyFingerprint`; `id == 0` 时 `persistFingerprint` 不落库 | C-1 复发: 指纹强校验再次失效 |
| `ConnectionBudgetTest` | ① 3 个出口共享同一 `total`; ② `tryAcquire` 失败后 `inUse()` 不增加; ③ `release` 恰好一次 (含异常路径); ④ **构造 `Socks5Connection` 抛异常时不泄漏 permit**; ⑤ **acquire 之后、`start()` 之前连接被关闭仍 release** | 预算只减不增 → 重连后所有新连接被拒 (S26 的核心) |
| `Socks5ProxyServerLabelTest` | `exitId == ""` 时 `diagnostics()` 无前缀 (与今天逐字一致); 非空时前缀 `[hk] ` | 单出口日志被改动 → L3 失败 |
| `MultiExitSnapshotTest` (基线部分) | `exitDiagnostics == ""` 时 `render()` 输出与改动前**逐字相等** | 快照多出字段 → 违反 G6 |

> P0 的单测**不需要**任何假插件/假 DNS —— 它们只验证"结构改造没有改变默认行为"。

### 2.2 P1 (纯逻辑, 全部 JVM)

| 测试类 | 断言 | 反例 |
|---|---|---|
| `ExitIdTest` | 小写规范化 / 去空格 / 长度 1..32 / `[a-z0-9_-]` / `default` 保留字拒绝 | 出口名与 `default` 冲突 |
| `ExitRuleParserTest` | 注释、空行、箭头两侧空白; 域名 / `*.` / CIDR / IPv4 / `[IPv6]` / 端口; **`app:<pkg>` 与 `app:<pkg>:<port>`**; 非法包名 (`com` / `com..a` / `app:`); 非法 CIDR; **未知出口保留且 `unresolved=true`**; `default` 非末行报错; 超上限; 行号正确 | 丢弃未知出口规则 = 用户配置永久丢失 |
| `RoutingTableTest` | 有序 first-match; 端口限定; `domain == null` 时跳过域名规则; **`NO_UID` 时跳过应用规则**; 应用规则与域名规则按**行序**决定优先级; `default ->` 兜底; 空表返回 null | 应用规则抢占域名规则 / 反过来 |
| `AppRuleMatchTest` | 包名**精确**匹配 (无前缀/通配); 带端口只命中该端口; 共享 uid 组同包名命中; `pkg == null` 不命中; 大小写敏感 | `com.example` 误命中 `com.example2` |
| `AppIdentityCacheTest` | 注入假查表: 命中只查一次; `null` **不写负缓存**; `clear()` 后重查; `uid < 0` 直接 null | 负缓存 = 应用刚启动时永远拿不到包名 |
| `RoutingTableSelectPerfTest` | 512 条规则 × 10k 次 `select` < 500ms | 规则匹配退化成 O(n²) |
| `DnsTunnelDecisionTest` | `(mode × inList × hasExitRule)` 矩阵: `cachePolicyTag == TUNNEL` **当且仅当** `shouldTunnel == true`; 谓词为 null 时两条路径都退化为今天的判定; **`hasDomainRule` 忽略端口、忽略 app 规则** | DNS 认为直连、TCP 却要隧道 (缓存复用直连答案) |
| `VpnControllerBypassTest` (扩展) | 排除路由 > 出口规则 > split 直连; 域名未知时行为不变; **应用规则不改变直连/隧道判定** | 给 app 加规则导致本应直连的流量被拉进隧道 |
| `ExitFailoverPolicyTest` | 全断 ✅ / 1~2 条不健康 ❌ / 通道秒拒 ❌ / 池饱和 ❌ / **CONNECT 之前连续 3 次 ✅、1~2 次 ❌** / CONNECT 阶段失败 ❌; 隔离 30s 内不重入; 连续 2 次成功恢复 | 一次瞬时失败把出口判死 |

### 2.3 P2 (数据面 + 并发)

| 测试类 | 断言 | 反例 |
|---|---|---|
| `AppUidAwaitTest` | `await` 超时返回 `NO_UID` 且不抛; 队列满 (第 65 个) **立即**完成 `NO_UID` (不挂起); 正常命中 < 50ms; **同一句柄被路由与统计两个消费者共用, binder 只调用一次**; 平台拒绝 → 30s `DisableWindow` 后恢复 | 无界等待 → 客户端 SYN 超时; 重复查询 → 预算被吃两次 |
| `MultiExitRoutingTest` | 两个假插件 + 固定 DNS 映射: 域名规则命中只打到 B; 未命中打 A; **决策只发生一次** (不重复 `resolveExit`) | 同一连接两条出口各建一次 |
| `MultiExitAppRoutingTest` | `app:` 规则命中只打到 B; 同一 uid 连续 10 条全部同出口; `NO_UID` → default 且**不改直连判定**; **bypass 连接一次都不 `await` uid**; **规则表无 `app:` 行时也不 `await`** | 直连多 50ms; 应用规则随机分裂 |
| `MultiExitCallbackTest` | 并发两条连接 (A/B 出口) 各自注册后关闭, **只移除自己**的回调; 重复关闭幂等 | C-2: 关 A 把 B 的回调摘掉 → B 回程断 |
| `MultiExitRetryTest` | B 协商秒失败 → 重试 default 成功; **CONNECT 阶段失败 (`rep=5`) 不重试**; `default` 自身失败不重试; `elapsed > 3s` 不重试; 迟到 SYN-ACK 不重复发 | NXDOMAIN 每次多一次 SSH 建连 |
| `MultiExitShutdownTest` | `stopAll()` 与 300ms 错峰启动竞争: 断开后 `exits` 为空、无新端口、无新会话 | 断开后出口"复活" |
| `MultiExitIsolationTest` | B 池失败 → 全局 `connectionState`/health 不变; 新连接走 default; 现有 A 连接不受影响; **附加出口日志为 WARNING 且不含"连接即将断开"** | 假 ERROR 污染 `故障归因` |
| `MultiExitSnapshotTest` (开启部分) | 含每个出口 + `应用` 行; 计数器口径与 §12 一致 | 快照判读被带偏 |

**并发用例的硬性要求** (CI 曾被无界等待挂 26 分钟):

- 所有注入线程 `isDaemon = true`
- 所有等待 **有界**: `CountDownLatch.await(5, SECONDS)` + 超时 `fail("...")`
- **禁止** `while (...) yield()` / `Thread.sleep` 赌时序
- 被测的挂起调用不得跑在测试主线程上

---

## 3. L2 门禁

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest
./gradlew ktlintCheck
./gradlew detekt
./gradlew lint
```

| # | 判据 |
|---|---|
| L2-1 | 四项**分别**通过 (detekt + lint 合并跑会 OOM) |
| L2-2 | 无新增 `@Suppress`; 若确需, 在 PR 描述里写明原因 |
| L2-3 | 行宽 ≤ 120, 缩进 4 / 续行 8; 新增文件 import 顺序符合 ktlint |
| L2-4 | 新增文案全部走 `strings.xml` × 4 语言 (`values`, `-en`, `-ru`, `-zh-rCN`) |

---

## 4. L3 零行为变化 (P0 的核心判据)

### 4.1 判定方法

在 **`multi_exit_enabled` 不存在 / `false`** 的前提下:

1. 用同一台服务器、同一 `dnsMode`、同一组站点, 在 **改动前** 跑一次会话, 导出应用内日志;
2. 在**改动后** 用完全相同的操作跑一次, 导出日志;
3. 对 `数据面快照` 那几行做 **逐字符 diff**。

```bash
# 提取快照行 (导出日志里 "数据面快照" 之后到下一节之前的文本)
grep -A 12 "数据面快照" before.txt > before.snapshot
grep -A 12 "数据面快照" after.txt  > after.snapshot
diff -u before.snapshot after.snapshot
```

### 4.2 允许的差异 (白名单, 只有这些)

| 差异 | 为什么允许 |
|---|---|
| 时间戳 / 速率 (`↑1.2MB/s`) | 每次会话必然不同 |
| `存活 12s` / 建连耗时 | 同上 |
| 域名/端口字面量 | 访问内容不同 |

### 4.3 不允许的差异 (任一出现 = L3 失败)

| 差异 | 说明 |
|---|---|
| 新增 `出口 …` 行 / `应用 …` 行 | `exitDiagnostics` 必须在关闭时是空串 |
| 新增任何字段名 (`在途通道` 之外的新词) | 快照是判读契约 |
| 日志出现 `出口就绪` / `出口降级` / `出口规则已重载` | 关闭时不应有任何出口生命周期日志 |
| `SSH 会话` 数量不是 3 | 关闭时只有一个池 |
| loopback 端口 > 1 个 | 关闭时只有一个 `Socks5ProxyServer` |
| `诊断` 前缀出现 `[hk]` | `exitId` 为空串时不得有前缀 |
| 任何既有日志文案的**标点/措辞**变化 | C-5 改造只允许在 `exitLabel != ""` 分支新增文案 |

### 4.4 对象图判据 (静态)

| # | 判据 |
|---|---|
| L3-1 | 关闭时 `TunnelManager.exits.keys == {"default"}` |
| L3-2 | 关闭时 `RoutingTableStore` 不被读取 (`resolveExit` 恒 default, 不打规则日志) |
| L3-3 | 关闭时 `AppUidResolver` 的查询次数与今天**相同** (仍由域名统计发起, 不新增第二次) |
| L3-4 | 关闭时 `ConnectionBudget` 生效但语义等价: 拒绝条件仍是 `connections.size >= 512` (不是 128) |

> L3-4 是 P0-7 最容易写错的一条: 软上限 128 **只对非 default 出口**, P0 阶段
> 唯一出口是 default → 软上限**必须完全不生效**。

---

## 5. L4 真机验收 (S17–S30)

> 追加到 `docs/specs/2026-10-18-device-verification-checklist.md`。
> 每条都写成"你做什么 → 日志里必须 / 不得出现什么"。
> **S17 是前置门禁: 不通过就不测后面的。**

| 场景 | 你做什么 | 必须出现 | 不得出现 |
|---|---|---|---|
| **S17** 功能关闭基线 | 关闭多出口, 连接 + 浏览 + 下载 + Play 各一次 | 设置页 `未启用`; 1 个 loopback 端口; `SSH 会话 3/3`; 快照与上一版**逐字一致** | 任何 `出口` 前缀行; `出口就绪/降级/规则重载`; 快照多字段; 端口 > 1 |
| **S18** 空规则 + 启用 (等价性) | 打开多出口, 加 1 个附加出口, 规则留空, 重连 | `出口就绪 · hk — SSH 3/3 会话 · 本地 :NNNNN · 建连 Xms`; 快照两段出口; 全部流量走 default; 主出口耗时与 S17 同量级 | 附加出口出现连接; `故障归因` 多条目; `SSH 会话` 总数 ≠ 6 |
| **S19** 域名规则分流 | `ipify.org -> hk`, default 指向另一台 VPS; 各访问 3 次 | 命中站点出口 IP = hk; 未命中 = default; `hk: N 连` 增长; `隧道通道就绪` 正常 | 两站点同出口; 重试后仍停错出口; `SOCKS 0x03` |
| **S20** IP/CIDR + 端口限定 | 字面量 IP 规则 → jp; `example.com:443 -> jp`, 再测 :80 | 字面量走 jp; :443 走 jp; :80 走 default | 端口规则误伤其它端口; 字面量被域名规则覆盖 |
| **S21** 附加出口故障转移 | 服务器侧杀 hk 的 sshd, 再访问 5 个命中 hk 的新域名 | `出口降级 · hk — SSH 会话全部丢失, 新连接改走 default` (WARNING); `出口 SSH 会话全部丢失 · hk — 新连接改走 default` (WARNING); 新连接全部成功走 default; **无** `auto reconnecting` | 整条 VPN 重连; 主出口连接被 RST; `故障归因` 出现全局 `SSH 会话全部丢失` 或"网络探测失败"; 30s 内抖动 |
| **S22** 主出口故障语义不变 | 让 default 的 3 条会话全断 | `SSH 会话全部丢失 · 连接即将断开并触发重连` + `auto reconnecting`; UI 走 Connecting → Connected | 静默把流量切到 hk 并保持"已连接"; 附加出口被误降级 |
| **S23** 资源与泄漏 (两套都测: 2 个附加出口=3 出口 / 4 个附加出口=5 出口) | 各自浏览 5 分钟, 断开再连 | 断开前 `在途通道` 回 0; 端口全释放; 新会话 `SSH 会话 3/3`×N; `饱和回执 0`; 连接数 ≤ 512 | `在途通道` 单调上升; 断开后 `出口就绪` 复活; 内存/线程上涨 |
| **S24** DOMAIN_SPLIT + 规则一致性 | 名单内 A、名单外 B; `B -> hk` | B 被分配假 IP 并走 hk; 改规则后立即生效 | B 先被直连解析回真实 IP; 规则改了仍走 default; `RelativeNameException` |
| **S25** 按应用分流 | `app:<浏览器> -> hk`; 冷启动 10 次 + 另一应用对照 | 浏览器出口 IP = hk, 另一应用 = default; `应用 · 命中 H/Q` 增长且 `超时` 不增; `hk: N 连` 只随浏览器增长 | 同一应用分裂到两个出口 (除超时同步增长); `SOCKS 0x03`; `应用规则未生效` 出现在默认视图 |
| **S26** 预算与关闭竞态 | 3 出口 + 200 条 `curl --max-time 1` 快开快关; 再在 connect 中强行断开 | `连接数` 回落; 无残留端口; 重连后新连接正常; `饱和回执` 不持续上升 | 预算只减不增 (重连后全被拒); `在途通道` 单调上升; 断开后又打 `出口就绪` |
| **S27** 建连前快速失败重试 | 命中 hk 的域名, 在 hk 上 `kill` 本地监听 (或断开其 SSH 会话) 后立刻访问 | `出口快速失败重试 · <域名> — hk → default (Nms 即失败)` (INFO, `N` < 3000); 该次访问成功; 快照 `快速重试` +1 | 重试超过 1 次; 客户端看到 RST; 慢失败 (> 3s) 也重试; 目标不存在 (NXDOMAIN) 触发换出口 |
| **S28** 出口改名/删除联动 | 给 `hk` 改名 `jp` 且勾选"同时更新规则"; 再删除 `jp` 且勾选"同时删除规则" | 规则页里旧名全部变成新名 / 对应行消失; 快照 `未解析规则 0`; 保存后无需重连即生效 | 规则文本里残留旧名; 快照出现 `未解析规则 > 0`; 改名后流量仍走 default |
| **S29** 出口健康恢复节拍 | S21 之后把 hk 的 SSH 恢复, 等 30s | 第一次巡检后仍 `Degraded`; 第二次巡检 `出口 hk 已恢复 · 新连接重新按规则派发` (SUCCESS); 快照 `Degraded → Ready` | 一次成功就恢复 (抖动); 恢复后已有连接被 RST; 恢复日志重复刷屏 |
| **S30** 出口软上限 | 命中 hk 的站点开 150 条并发长连接 | 第 129 条起 `软上限回退` 增长, 连接改走 default 且**不失败** | 软上限触发时 RST; `SshIoDispatcher 饱和回执` 持续上涨; default 也被软上限拒绝 |

**S17 的判定细节**: 必须做 §4 的 `diff`, **不允许**"我看了一下差不多"。

**S25 补充**: 应用选择器里"共享 uid"应用会带 `共享 uid` 角标 —— 这类包名规则会命中**同组全部应用**,
验收时用同组两个应用各测一次, 确认行为符合预期 (这不是 bug, 是 Android 的 uid 语义)。

**UI 文案边界 (不是故障)**: 会话中途打开总开关会提示「附加出口在下次连接时启动」; 这是刻意行为
(不在 UI 操作里建 SSH 池), 别当成"没生效"上报。

**真机进展 (2026-11-03, 第一轮)**: 已完成"连接 + 全部出口就绪 + 快照出口段"这一小段
(3 个附加出口同时 `Ready`, 见 `2026-11-03-multi-exit-device-round1.md`); **S17 关闭态基线 diff 与
S19–S30 全部未跑**。本轮顺带修掉两条与出口无关但会伪装成"多出口坏了"的缺陷:
出口 kex 白名单缺 ECDH (`0e149a4`)、已连接时点另一台服务器静默 no-op (`fbd581b`)。

---

## 6. 阈值速查 (不达标即不通过)

| 指标 | 门槛 | 不达标先看什么 |
|---|---|---|
| `awaitUid` 命中率 (新建连接) | **≥ 95%** | `uidTimeoutTotal` vs `uidMissTotal`: 前者调预算, 后者是内核真的没归属 |
| uid 等待 P95 | **≤ 15ms** (预算硬上限 50ms) | 反查线程数 / 是否有重复查询 |
| 附加出口建连耗时 | 与主出口同量级 (**< 1.5×**) | `MaxStartups` / 错峰是否生效 |
| `SshIoDispatcher 饱和回执` | 稳定期 **0**, 峰值不持续上升 | 单出口软上限 / 连接预算 |
| 出口降级抖动 | 30s 内 ≤ 1 次 | 隔离窗口 / 降级判据是否过宽 |
| 内存增量 (3 出口 vs 1 出口) | **< 30%** | 每出口 3 条会话 + 1 个监听是固定成本; 超出说明有泄漏 |
| 内存增量 (5 出口 vs 1 出口) | **< 50%** | 2026-11-25 裁决: 3 出口与 5 出口**两套都测**; v1 上限为 4 个附加出口 (15 条常驻 SSH 会话) |
| `MaxStartups` 冲突 (5 出口同时启动) | 0 次随机 `connection reset` | 300ms 错峰是否生效; sshd `MaxStartups` 默认值 |
| 功能关闭时快照差异 | **0 处** | L3 失败, 直接阻塞合并 |

---

## 7. 既有缺陷的验收 (C-1…C-5)

这五条是调研期发现的**现有代码缺陷**, P0 顺手修掉, 各自需要独立判据:

| # | 缺陷 | 验收判据 | 回归方式 |
|---|---|---|---|
| **C-1** | 运行时隧道路径丢 `id`/`hostKeyFingerprint` → DB 指纹强校验失效 | 连一台**指纹已落库**的服务器, 服务器换主机密钥 → 必须拒绝连接并提示指纹不匹配 | 单测 `TunnelConfigSocks5Test` + 真机换 key 一次 |
| **C-2** | `tunCallbackPlugin` 是机器级单字段 | 两条连接 (不同出口) 并发注册/关闭, 只摘自己的回调 | 单测 `MultiExitCallbackTest` (旧实现确定性失败) |
| **C-3** | 探活读 `config.socksPort` 而非实际监听端口 | 单出口探活仍成功; 端口回退时探活打**实际**端口 | 单测 + S17 探活成功 |
| **C-4** | `startPlugin` 按协议名覆盖旧实例且不 `disconnect()` | 同协议二次启动不泄漏端口/会话 (幂等或先 stop) | 单测 `MultiExitShutdownTest` |
| **C-5** | 池失败日志是全局结论, 多实例误报 | 附加出口池死 → WARNING 且不含"连接即将断开"; 主出口池死 → 原文案 + ERROR | 单测 `MultiExitIsolationTest` + S21/S22 |

> C-1 与 C-5 改变**可见行为**, 发布说明必须单独写一段 (不是"内部重构")。

---

## 8. 既有功能回归 (S0–S16)

`multi_exit_enabled=false` 下, `docs/specs/2026-10-18-device-verification-checklist.md`
的 S0–S16 **全部重跑一遍**, 判据不变。

| 重点场景 | 为什么必须重跑 |
|---|---|
| S0 基础连接 + 快照 | L3 的载体 |
| S1/S2 浏览 + 下载 | 上行背压 / 窗口闸门 |
| S4 Play 下载 | CDN 零回程 / IPv4 黑洞判据 |
| S6 断开重连 | 预算 / 会话代际 |
| S8 白名单模式 | DNS 退化 / `activeNetwork` 陷阱 |
| S12 域名分流 | `shouldTunnel` 与 `cachePolicyTag` 一致性 |
| S14 长时间空闲 | keepAlive=0 语义 |
| S16 网络切换 | 重连去抖 |

---

## 9. 分阶段签收表

### P0 (结构改造, 零行为变化)

- [ ] L0-1…L0-5 全绿
- [ ] `TunnelConfigSocks5Test` / `ConnectionBudgetTest` / `Socks5ProxyServerLabelTest` / `MultiExitSnapshotTest` 通过
- [ ] L2 四项门禁通过
- [ ] **L3 §4.1 的 diff 为空**
- [ ] C-1 / C-2 / C-3 / C-4 / C-5 各自的判据至少有一条被自动化覆盖
- [ ] 关功能时对象图满足 L3-1…L3-4

### P1 (数据与规则, 不生效)

- [ ] §2.2 全部单测通过 (含 perf 用例)
- [ ] Room v5 迁移在**旧库副本**上执行成功 (用 `DatabaseIntegrationTest` 或手工 sqlite)
- [ ] 规则编辑器: 非法输入全部被拦, 测试框输出正确
- [ ] 应用选择器: 白名单模式下非白名单应用标灰 + 提示
- [ ] 快照仍**无** `出口` 段 (P1 不启用)
- [ ] L3 diff 仍为空

### P2 (运行时)

- [ ] §2.3 全部单测通过
- [ ] S17–S26 全部通过 (S17 必须最先)
- [ ] S0–S16 回归通过
- [ ] §6 阈值全部达标
- [ ] 关功能 (`multi_exit_enabled=false`) 时 L3 diff 仍为空

---

## 10. 失败判定与上报

### 10.1 什么时候算"功能不可发布"

任一成立即阻塞:

1. L3 出现任何非白名单差异 (关闭功能仍有行为变化);
2. 主出口故障语义改变 (S22 失败);
3. 附加出口故障触发整条 VPN 重连 (S21 失败);
4. 预算泄漏 (S26 出现"重连后所有新连接被拒");
5. `故障归因` 出现由附加出口引起的假 ERROR。

### 10.2 上报格式 (无 adb 现场)

导出的应用内日志是唯一证据。上报必须包含:

```text
场景: S21
操作: 连上 3 出口 → 服务器侧 pkill sshd (hk) → 访问 3 个命中 hk 的域名
现象: <用户看到什么>
关键日志: <粘贴 5~10 行, 必须含 数据面快照 的 出口 段>
快照差异: <与基线相比多了/少了什么>
```

> 判读顺序固定: 先看 `数据面快照` 的 `出口` 段 (谁在服务), 再看 `故障归因`
> (有没有假 ERROR), 最后看单连接日志 (`隧道通道就绪` / `零回程` / `出口快速失败重试`)。
