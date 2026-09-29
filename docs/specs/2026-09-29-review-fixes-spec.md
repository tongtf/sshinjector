# 代码审查修复方案 spec

- 日期：2026-09-29
- 输入：全量代码审查报告（编号 S/F/R/LOW，安全/功能/资源泄露/设计四类）
- 范围：**只出方案，不含实现**；实现按批次走 implement
- 状态：二次审计完成（2026-09-29，逐条读码核验；修正 11 处，含 2 处方案级缺陷 F12-g 死锁 / S1 JSch 契约）

## 批次划分

| 批次 | 条目 | 原则 |
|------|------|------|
| **P0** | S1 S2 S3 F1 F2 F3 F4 F5 | 安全核心 + 高危功能 |
| **P1** | S4(并入S1) S5 S6 S7 F6 F7 F8 F12-a~f | 中高危（F9/R6 审计后移入 P3 waived） |
| **P2** | F10 F11 F12-g~k R2~R5 LOW 组 | 中低危（R6 审计后移入 P3 waived） |
| **P3** | waived 项 | 明确不修 + 理由 |

---

# P0 详细方案

## S1【安全·高】SSH 主机密钥在认证后才校验 + Session 泄漏 + 指纹死代码

**现状**：`JschSshClient.kt:323` 设 `StrictHostKeyChecking=no` → `:345 s.connect()`（KEX+认证全完成）→ `:347 verifyHostKey()`。校验失败 `catch(:354)` 不 `disconnect()`（原 S4/R1）。`config.hostKeyFingerprint` 强校验分支（`:366-371`）因 UI 从不写入该字段而是死代码。

**根因**：校验放在 connect 之后"手动验证"，凭据已发给未验证对端；TOFU 保存进内存 `currentConfig`（`:380`）不落库，指纹永不生效。

**方案**（三件事一次做）：

1. **校验提前到 KEX**：删除 `StrictHostKeyChecking=no`，改用 JSch 原生拦截：
   - 实现 `HostKeyRepository` 适配器（包装现有 `KnownHostsManager`），`session.setHostKeyRepository(adapter)` + `StrictHostKeyChecking=yes`。
   - **JSch 契约（已对照 mwiede/jsch 源码核实）**：`int check(String host, byte[] key)`——**无 port 参数**；返回码 `OK=0 / NOT_INCLUDED=1 / CHANGED=2`。JSch 在 `Session.checkHost` 中对非 22 端口把 host 编码为 `"[host]:port"` 字符串传入（22 端口为裸 host），adapter 需自行解析还原 port 以匹配 KnownHostsManager 的 `host,port` 键。
   - `adapter.check(host, key)` 语义：
     - `config.hostKeyFingerprint` 非空 → 比对 SHA-256 指纹，不匹配返回 `CHANGED`（JSch 在 KEX 内抛 `JSchChangedHostKeyException` 中止，**userauth 不会执行**）；
     - 指纹空且 known_hosts 无记录 → **TOFU**：adapter 自行保存 + 返回 `OK`。**注意：`StrictHostKeyChecking=yes` 下 JSch 对 NOT_INCLUDED 直接抛 `JSchUnknownHostKeyException`，不会自动调 `add()`**——TOFU 保存必须发生在 adapter 内部，不能依赖 JSch；
     - known_hosts 有记录且不匹配 → `CHANGED`。
   - 其余方法按 JSch 契约实现：`add(hostkey, ui)`（TOFU 保存点）、`remove` x2、`getKnownHostsRepositoryID()`、`getHostKey()` x2（`i==OK` 时 JSch 会调 `getHostKey(chost, type)` 检查 `@revoked` 标记，必须正确返回）。**接口无 `find`/`isIgnored`**。
   - 删除 `verifyHostKey()` 调用与后置 TOFU 逻辑（`:347`、`:374-381`）。校验移入 JSch connect 内部后，JSch 自己的 catch 会 `disconnect()`——原"校验失败 Session 泄漏"随之消除（其他失败路径的兜底见第 2 点）。
2. **修 Session 泄漏（原 S4/R1）**：`createSession` 的 `catch` 增加 `runCatching { s.disconnect() }`（`val s` 提到 try 外或 try-finally）；`execSingleShot` 把 `session = s` 移到 `connect()` 之后立即赋值，保证 `finally(:723)` 兜底。
3. **指纹落库（复活强校验）**：TOFU 首次保存后回调 `ServerRepository.updateHostKeyFingerprint(serverId, fp)`（新增 DAO update），下次连接即走指纹强校验分支。**实现前置（审计补充）**：`JschSshClient` 现构造器只有 `keyManager`/`knownHostsManager`，需注入 `ServerRepository`（DI 变更）；`ServerDao` 新增 `updateHostKeyFingerprint(id, fp)`。`ServerConfig.id` 在 `connect()` 时可用（`SshVpnService` 传入的 config 含 id）✓。

**验证**：单测 adapter.check 返回值矩阵（指纹匹配/不匹配/未知主机/已有记录变更）；手动改 known_hosts 指纹 → 连接必须在认证前失败且抓包无 userauth 包；`ServerEditScreen` 重置指纹按钮回归。

**风险**：check() 返回码用错会拒绝一切连接——实现时逐值对照 JSch `HostKeyRepository` 契约测试。TOFU 首连 MITM 仍在（P3 UI 闭环）。

---

## S2【安全·高】SOCKS5 无认证，任意本地 App 可搭隧道

**现状**：`Socks5ProxyServer.kt:430-431` 无条件回 `0x00`；`:189` 注释的"Android loopback 隔离"前提是错的。合法客户端只有本 app（`TcpStateMachine.forwardThroughLocalSocks`）。

**方案**：loopback TCP + **每次 VPN 会话随机凭据（RFC 1929）**——不可知即不可达（挡搭便车/SSRF/S3 的 1 字节 DoS 入口）。

1. `Socks5TunnelPlugin.connect()` 时生成 `authUser`/`authPass`（SecureRandom 16 字节 URL-safe），仅存内存，随插件重启轮换。
2. `processHandshake`：METHODS 含 `0x02` → 回 `0x02`；否则回 `0xFF` + close。
3. `processAuthMethods`（现为空壳 `:435-438`）：实现 RFC 1929 单包解析（VER=1, ULEN, USER, PLEN, PASS），常量时间比较，成功回 `0x00`，失败回 `0x01` + close；数据不足走 S3 续读机制。
4. `TcpStateMachine.forwardThroughLocalSocks:249` 握手改 `0x05,0x01,0x02` 并发认证包；凭据经 `TunnelPlugin` 接口新增 `val socksAuth: Pair<String,String>? get() = null` 获取，`Socks5TunnelPlugin` 实现。**服务端取凭据路径（审计补充）**：`Socks5ProxyServer` 构造无 `TunnelPlugin` 引用，需由 `Socks5TunnelPlugin.connect()` 在 `proxy.start()` 前后把凭据传给 server（构造参数或 `setExpectedAuth(user, pass)` setter）——server 侧验证必须拿到期望凭据，不能只靠接口暴露。
5. **不加** `connectToTarget` 目标白名单（token 已挡，YAGNI）。不留 `0x00` 无认证降级口子。

**验证**：单测 `Socks5AuthTest`（无认证/错密码/正确凭据三路）；手动 `nc 127.0.0.1 1080` 发 `05 01 00` 必须回 `05 FF`；回归 VPN 连接后浏览器正常上网。

**风险**：接口变更牵动测试 mock——默认 `null` 分支仅测试用，生产路径必有凭据。

---

## S3【功能·高】SOCKS 半包导致 eventLoop 死循环冻结全代理

**现状**：`Socks5ProxyServer.kt:351-363` 外层 `while (hasRemaining && state != Closed)`；`processHandshake:416`/`processRequest:442` 数据不足时 `return` 不消费不改状态 → 空转。且 `handleRead:336 buffer.clear()` 丢弃未消费残留，协议无法跨段重组。

**根因**：非阻塞解析缺"保存半包 + 挂起等下次读"机制。

**方案**（与 S2 同函数一起改）：
1. 三个 process 方法返回 `Boolean`（true=可继续），数据不足返回 false → 外层 `keepProcessing = false` 退出循环。**注意当前 bug 比"半包丢数据"更重**：Handshake 态剩 1B、Request 态剩 1-3B 时 processXxx 不消费直接 return，`:351 while(hasRemaining)` 对同一批字节空转死循环（已读码确认 :416/:442）。
2. **消费纪律**：头部字节必须在整条记录齐备前不得消耗——`processHandshake` 用 `buffer.get(pos)`/position-based peek 读取 VER/NMETHODS，仅当 `remaining >= nMethods` 才整体推进 position（现 :418-428 先 get ver/nmeth 再 minOf skip，部分到达时会丢失已消费的方法字节，且 S2 需要完整方法列表才能选认证方式）；`processRequest` 同理：peek ATYP → 按 atyp 算出最小总长（IPv4=10 / IPv6=22 / Domain=5+len），不足则不消耗任何字节返回 false。
3. 半包保留：**仅 Handshake/AuthMethods/Request 态**在循环任意出口后 `buffer.compact()`；`handleRead:336` 的 `clear()` 改为按 compact 后续读（position() 处开始 read）。Relaying 态保持现状——`enqueueToSsh(:821-835)` 每次全量 drain+copy，无需改。
4. S2 新增的认证解析复用同一机制与消费纪律。

**验证**：单测分片握手（1B 逐字节、请求拆 2/3 段）最终完成 CONNECT 且 30s 内不超时；Handshake 态只发 1B → eventLoop 不自旋（当前代码此处即死循环，修复后应安静等待续包）；1 字节连接与正常连接并发互不阻塞；`Socks5BackpressureTest` 必须仍过。

**风险**：`buffer` 是 eventLoop-only 红线字段——本方案只改 clear→compact，不改线程归属，红线不破。

---

## F1【功能·高】排除路由/域名分流直连是回注黑洞（含审查遗漏的同模式问题）

**现状**：
- `VpnController.kt:521-527`：命中 `excludedRoutes` → `writeToTun(原包)`；
- `VpnController.kt:542-551`：DOMAIN_SPLIT 未命中域名的 TCP → 同样 `writeToTun`（**同类根因，审查时遗漏，本方案一并覆盖**）；
- `writeToTun:699-707` = 写 TUN fd = 内核 RX。dst 非本机：`ip_forward=0` 静默丢；`=1` 转发回 tun0（0/0 路由）→ 再次读出 → 再次回注 → **读写死循环**。两条路径都到不了物理网卡。

**根因**：用户态无法把已进 TUN 的包塞回物理网卡；`protect()` 只对 socket 有效，对 raw TUN 包无效。

**方案**：「回注」改「**用户态直通代理**」，复用 TcpStateMachine 现有 SocketChannel 架构：

1. `VpnController` 暴露 `shouldBypassTcp(dstIp, dstPort): Boolean`（收编现有 `shouldBypassVpn` + DOMAIN_SPLIT `direct` 判定 `:533-541`），注入 `TcpStateMachine`。
2. `forwardSynToTunnel`（`TcpStateMachine.kt:189`）入口加分支：
   - `SocketChannel.open()` → `protect(socket)` **在 connect 之前** → `connect(dst)`；
   - `conn.socksChannel = sc`（复用上行 `forwardToSocks` 写路径）→ `state=Established` → `buildSynAckPacket`（顺序遵守 F8）→ `startRelayFromSocks`（复用下行 relay → `writeTcpPayloadToTun`）；
   - 建连失败 → `buildRstPacket` + `closeTcpConnection`（走 F6 统一关闭）。
    - 与现有 `forwardThroughDirectChannel(:426)` 同构，只是 socket 本地直连、不经 Socks5ProxyServer/SSH。
    - **实现前置（审计发现）**：`VpnController.setProtectFunction` 现只收 `(java.net.DatagramSocket) -> Boolean`（`:96`），TCP bypass 需**新增 Socket 重载** `setProtectTcpFunction((java.net.Socket) -> Boolean)`，由 `SshVpnService.kt:182/:634` 处一并注入（`this.protect(Socket)` API 已存在）。
3. `VpnController.processPacket` 两处回注分支（`:525`、`:549`）删除；`:523` SYSTEM 模式 `forwardDnsBypassPacket` 保留（protect socket 转发有效）。
4. **UDP bypass 不做**（YAGNI）：UDP 已降级，非 53 本就丢弃计数；DNS 已有 bypass。文档标注「排除规则当前仅作用于 TCP+DNS」。
5. IPv6 排除同分支 v6 直连即可。

**验证**：excludedRoutes 加网关 IP → VPN 下 ping 网关/访问局域网 NAS（修复前黑洞）；logcat 无 `shouldBypassVpn: TRUE` 高频刷屏；DOMAIN_SPLIT list 未命中域名 HTTPS 可访问；单测 `shouldBypassTcp` 矩阵 + 断言 bypass 不调 `openTcpChannel`。

**风险**：中高。① protect 必须在 connect 前，否则自环（实现加注释+测试）；② 直连复用 `socksChannel` 字段后 `closeTcpConnection`/`cleanupStaleConnections` 天然回收 ✓；③ 直连流量日志标注 bypass 便于排查。

---

## F2【功能·高】回程直通不更新 lastActivity，活跃下载 5 分钟被杀

**现状**：`Socks5ProxyServer.kt:699-748` `relayFromTarget` 只更新统计；`lastActivity` 仅 `:346/:377/:674/:861` 四处；直通路径绕过 `handleWrite`，纯下行连接 `checkTimeout:942`（`idleTimeoutMs=300000`）恒超时。

**方案**：`relayFromTarget` 的 `if (read > 0)` 块（`:736-737`）加 `lastActivity = System.currentTimeMillis()` 一行。

**验证**：单测——mock tunnel 持续吐数据（注小 `idleTimeoutMs`）断言不被关闭；停吐 5 分钟断言关闭（保留空闲超时语义）。

**风险**：无。`lastActivity` 为 `@Volatile`（`:307`），跨线程可见性 OK。

---

## F3【功能·高】连接失败复用 disconnect() → 清空 lastServerId/默认服务器

**现状**：`SshVpnService.kt:197-205`（connect catch）、`:652`（autoReconnect catch）调 `disconnect()`；`disconnect:446-456` 无条件 `deactivateAllServers()` + `setLastServerId(0)`——本属"用户主动断开"语义。

**方案**：
```kotlin
private suspend fun disconnect(userInitiated: Boolean = true) {
    ...
    if (userInitiated) {
        serverRepository.deactivateAllServers()
        settingsDataStore.setLastServerId(0)
    }
    // TUN/SSH/scope 清理不变；Failed 状态保留
}
```
- `:197-205`、`:652` → `disconnect(userInitiated = false)`。
- 调用点逐一确认：`ACTION_DISCONNECT(:106)` → true；`onRevoke(:117)` → false（系统回收非用户意图）；`onTaskRemoved(:122)` → true（用户划掉任务，同主动断开）；`connect` catch(`:204`)/`autoReconnect` catch(`:652`) → false。**注意（审计修正）**：`:108` 的 `ACTION_REBUILD` 调 `rebuildVpnInterface()`，不涉及 `disconnect()`；`onDestroy` 走 `cleanupScope` 调 `vpnController.disconnect()`（非本方法）——原方案调用点清单有误。

**验证**：单测——connect 抛异常 → 断言 `setLastServerId(0)`/`deactivateAllServers` **未调用**且 state=Failed；ACTION_DISCONNECT → 断言二者调用。场景回归：连上→断网重连失败→重启→BootReceiver 仍拿到 lastServerId。

**风险**：低。漏改某调用点仅行为保守，不崩溃。

---

## F4【功能·高】forwardToSocks 部分写返回 false → 重传字节重复

**现状**：`TcpStateMachine.kt:854-863` 写一半 `write()==0` → `return false`；调用方 `:144-151` 不推进 `forwardedBytes`。重传到达走 `:142 ==expected` 分支**整段重发**（已读码确认），前缀字节重复进流。

**方案**：`forwardToSocks` 返回值 Boolean → **已写字节数 Int**：
- SOCKS 分支：循环累计，`write()==0` break 返回已写量；IOException → 0（连接已 close）。
- tunnelChannel 分支：阻塞写全成 → `payloadLength`；异常 → 0。
- 调用方：
```kotlin
val written = forwardToSocks(conn, buffer, payloadStart + dataOffset, payloadLen)
if (written > 0) {
    conn.forwardedBytes += written
    sendAckToBrowser(conn, connKey)   // ACK 只推进到已写处
}
// written == 0 → 不推进不 ACK，浏览器整段重传（背压语义保留）
```
- 部分写后重传：`expectedBrowserSeq` 已前移，重传段 `seq == expected`（剩余部分）→ 正常转发；已写过的旧前缀 `seq < expected` → 走 `:151` dup 分支丢弃+ACK ✓。

**验证**：单测——mock SocketChannel 每次只写 N 字节（N < payload），断言：`forwardedBytes` 只加 N、ACK 推进 N、重传剩余段被转发且总字节 == payloadLen（无重复）。

**风险**：低。tunnel 分支是阻塞全写，返回值语义变化只影响调用方一处。

---

## F5【功能·高】ICMPv6 NA Hop Limit=64 内核丢弃

**现状**：`Icmpv6Responder.kt:113` 写 64；同文件 RA（`:155`）正确写 255。RFC 4861 要求 ND 消息必须 255，Linux `ndisc_rcv` 硬校验非 255 即丢。

**方案**：`packet.put(64.toByte())` → `packet.put(255.toByte())`，一行；注释同步。

**同类排查**：grep 全部 ND 构包点的 Hop Limit 字段（RA 已对、NS 是否存在构造点需确认）。

**验证**：单测——构造 NA 包断言 byte[7]==0xFF；真机 enableIPv6 连接后 `ping6 ff02::1` 有响应 / TCP over IPv6 通。

**风险**：无。

---

# P1 详细方案

## S5【安全·中】关闭 IPv6 时 IPv6 明文绕过隧道

**现状**：`SshVpnService.kt:331-334/344-348/352-355`：`enableIPv6=false` 时不加 `::/0` 路由 → IPv6 走物理网卡，真实地址+流量暴露。

**方案**：关闭时仍 `builder.addRoute("::", 0)`（**不** addAddress fd00::1），把 IPv6 引入 TUN 后丢弃——即「关闭 = 不用 IPv6」而非「IPv6 逃逸」：
- 丢弃点（审计修正）：`processIpv6Packet` 在 `PacketProcessor.kt:95`（非 VpnController），改 `PacketProcessor.processIpv6Packet` 入口或 `VpnController.processPacket` v6 分支（`:561-566`）按配置丢弃 + 计数（新增 `droppedIpv6Count` 或复用 `addError`）。
- DNS AAAA 假 IP 路径在 `enableIPv6=false` 时不启用：`DnsInterceptor` 现无该开关，需新增 `setEnableIPv6(Boolean)`（`@Volatile`），`VpnController.connect`/`updateDnsMode` 处注入（与 `mergeGlobalSettings` 的 `enableIPv6` 联动）。
- IPv6 ND：TUN 无 fd00::1 地址时不会收到针对它的 NS，`Icmpv6Responder` 分支自然不触发。

**验证**：`enableIPv6=false` 连接后真机访问双栈站点——抓包确认无 IPv6 明文出网（或 `ifconfig` 无 v6 默认路由外泄）；开开关后 IPv6 正常走隧道。

**风险**：中。① `addRoute("::",0)` 但无 v6 地址——包仍会进 TUN，需确保丢弃分支在**任何** v6 包先于处理（否则 fd00 假 IP/DNS 路径做无用功）；② 系统 connectivity check 若走 IPv6 会失败——Android 会回落 IPv4，可接受；③ UI 文案同步为「关闭后设备不使用 IPv6」。

## S6【安全·中】密码加密静默降级明文落盘

**现状**：`AesGcmCipher.kt:27-31` catch 返回 `plain`；`ServerRepository.kt:221` 直接入库。

**方案**：
- 写端：`encrypt` 失败**不降级**——向上抛出（`saveServerEdit` 捕获 → UI 报错"加密失败，请重试"），绝不写明文。
- 读端：保留 `:36` 明文兼容读（服务存量历史数据）；`mergeForEdit` 保存时若 `!isEncrypted(stored)` 且本次未提供新密码 → 用现有明文重加密（惰性迁移）。
- 删掉降级日志分支；**同步改类注释**（`:12-13` 现称降级是"有意为之的兼容策略"，改后应说明写端已不降级、仅读端兼容）。

**验证**：单测——mock Keystore 使 encrypt 抛异常 → `saveServerEdit` 返回失败且 DB 无明文；存量明文记录读→保存后变 `enc:v1:` 前缀。

**风险**：低。Keystore 短暂不可用时保存失败是**预期行为**（安全优先），UI 需给可重试提示。

## S7【安全·中】SSH 算法白名单缺 rsa-sha2，Cipher/MAC 未限制

**现状**：`JschSshClient.kt:336,340` 无 `rsa-sha2-256/512`；`Cipher`/`MAC` 走 JSch 默认（含 3des-cbc、hmac-sha1）。

**方案**（`createSession` 与 `execSingleShot` 两处同步改）：
```kotlin
HostKeyAlgorithms / PubkeyAcceptedAlgorithms += "rsa-sha2-256,rsa-sha2-512"   // 保留 ssh-rsa 兼容旧服务器
Cipher = "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes256-ctr"
MAC    = "hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com,hmac-sha2-512,hmac-sha2-256"
```

**验证**：实现前对照 mwiede jsch 0.2.17 支持的算法名字符串（JSch `Config` 内置表），名字写错会导致 kex 失败——**先写单测用 `JSch.getConfig("Cipher")` 断言可解析**；连 OpenSSH 8.8+ 默认配置服务器（RSA 密钥）回归。

**风险**：中。算法名不被 JSch 识别 → `session.connect` 抛 unknown algorithm。GCM 与 ETM MAC 为 AEAD/新式，对端不支持时 JSch 协商回退（我们设了白名单则不回退——若老服务器只有 aes256-cbc 会被拒，故白名单**含 ctr 不含 cbc**，兼容与安全折中；连接失败场景文档标注）。

## F6【功能·中】TCP 关闭无 FIN/RST，失败 SYN 卡死同五元组 5 分钟

**现状**：`closeTcpConnection:900-917` 只 remove+close 不发包；`:254-292` SOCKS 失败不 RST 不移除条目；`:116-127` SYN 重传遇 Closed 条目只更新 browserSeq 永不重试；`tcpConnections` 无 `clear()`。

**方案**：
1. `closeTcpConnection(key, conn, notifyBrowser: Boolean = true)`：内部在 remove 前，若 `notifyBrowser && conn.state != Closed` → `buildRstPacket(conn, connKey)` → `tunWriterProvider()`（RST+ACK 已实现 `:755+`，seq=serverSeq / ack 按现有计算）。
2. 调用点语义：
   - 远端 EOF/异常（`:417-419`/`:477-479` finally）、cleanupStale、IO 异常 → 默认 true（浏览器立即 abort 而非挂死）；
   - 浏览器主动 FIN（`:175-177`）→ `notifyBrowser = false`（对端已关，无需回包）；
    - SOCKS 握手/CONNECT 失败（`:254-292` 四处）→ **改调 `closeTcpConnection(key, conn, true)`**（补 RST + 移除条目，修掉"不移除"）。
    - **forwardSynToTunnel 内还有 2 处直改点（审计补充）**：`:205`（plugin 既无 SOCKS 端口又无 direct channel——现手动 `buildRstPacket` + 直改 state，改为直接调 `closeTcpConnection(key, conn, true)` 并删掉手动 RST，避免双 RST）与 `:211`（catch）同样收敛——共 6 处直改点，全部收敛。
3. 移除条目后，浏览器重传 SYN → `:117 conn == null` → 重新 `forwardSynToTunnel` 自动重试 ✓（`:116-127` 不用改）。
4. `TcpStateMachine.reset()`（`tcpConnections.clear()` + `nextTcpSeq/nextTcpAck.clear()`——后者即 R2 删除的收尾），`VpnController.disconnect` 调用，跨会话残留清零。

**验证**：单测——mock tunWriter，远端 EOF → 断言收到 RST 包且 `tcpConnections` 空；浏览器 FIN → 无包；SOCKS 失败 → RST + map 空 + 同五元组新 SYN 能重建。真机：SSH 断开瞬间浏览器标签页立刻报连接错误（而非转圈 2 分钟）。

**风险**：RST seq/ack 若算错浏览器会忽略 RST（seq 不在窗口）→ 挂死依旧。实现时单测断言 RST 的 seq/ack 与浏览器期望窗口一致（seq == serverSeq，ack == browserSeq+1+forwardedBytes）。

## F7【功能·中】forwardToSocks 的 `socksChannel!!` NPE

**现状**：`TcpStateMachine.kt:805-815` 守卫允许 `socksChannel==null && tunnelChannel!=null`，`:815` 即 `!!`。

**方案**：把 `val socksChannel = conn.socksChannel` 的判空移到 SOCKS 分支内：
```kotlin
val tunnelChannel = conn.tunnelChannel
if (tunnelChannel != null) { ...return }        // tunnel 分支先行
val socksChannel = conn.socksChannel ?: return false   // 判空后置
```
守卫条件简化为 `state != Established → false`。

**验证**：单测构造 tunnel-only conn 走 forwardToSocks → 不抛 NPE（当前必抛）。F1 的直连分支复用 `socksChannel`，本修复是它的前置依赖。

**风险**：无。

## F8【功能·中】SYN-ACK 晚于 CONNECT → banner 先于握手到达被丢（serverSeq RMW 随之消失）

**现状**：`forwardThroughLocalSocks:315` 在 SOCKS CONNECT **成功后**才发 SYN-ACK；而 `Socks5ProxyServer.onTargetConnected(:677)` 收到 CONNECT 应答即启动回程 relay——SSH banner（如 "SSH-2.0…"）可经 tunCallback 先于 SYN-ACK 写进 TUN，浏览器尚处 SYN_SENT 直接丢弃该数据且 seq 已推进 → **永不重传**（banner 首字节永久丢失）。

**审计修正后的方案**：把 `buildSynAckPacket` + 写出从 `:315-318` **移到 SOCKS5 握手发送之前**（`:247` 附近，socket connect 成功后即可）——不依赖隧道结果。正确性依据（已读码验证）：
- TUN 写按序投递给浏览器内核栈；banner 字节物理上不可能早于 CONNECT 应答产生 → SYN-ACK 先写出即保证 banner 后到 ✓ 竞态彻底消除，无需"提前但仍在 launch 内"的折中。
- **转发路径不检查 state**（`:139` 仅要求 ack+hasPayload）：客户端数据在 SOCKS 未就绪时到达 → `forwardToSocks` 因两 channel 均 null 被守卫挡下返回 0（F4/F7 落地后无 NPE）→ 不 ACK，浏览器重传直至隧道就绪 ✓。
- **direct-channel 路径无需改**：`forwardThroughDirectChannel:426` relay 启动（`:451`）在 SYN-ACK 写出（`:447`）**之后**，本就无竞态。
- `serverSeq` RMW 竞态（原 :501/:587 双写者）随之消失：SYN-ACK 提前后其 seq/ack 只依赖 browserSeq；回程 serverSeq 推进仅剩 relay 单线程一个写者。**不加 synchronized**（避免过度工程）。
- **前置依赖 F6**：SOCKS 握手/CONNECT 失败路径必须发 RST，否则浏览器在已收到 SYN-ACK 后挂死。

顺带清理（本轮修，见 L10）：`:134 syn&&ack→SynReceived`、`:137 SynReceived→Established` 两分支是死代码——本端被动监听从不主动发 SYN，客户端不会同时置 SYN+ACK；SOCKS 成功直接置 Established `:306`，不存在经 SynReceived 的路径。

**验证**：单测——mock SOCKS connect 延迟 + banner 立即返回，断言 SYN-ACK 先于任何 `writeTcpPayloadToTun`；真机连 SSH/SMTP/MariaDB（主动发 banner）首字节完整无缺字、TLS ClientHello 不丢。

**风险**：中低。唯一行为变化是浏览器在隧道就绪前可能多几次重传（亚秒级，现有背压语义覆盖）。

## F9【审计后 waived】OP_WRITE RMW 竞态——已验证不存在跨线程路径

**审计结论（读码核实）**：原审查称 `:400/:797` 无锁 RMW，实际当前代码已在锁内：
- `handleWrite:403-406` — `synchronized(backpressureLock)` 包住 interestOps 清除；
- `sendReply:801-803`（sshIoDispatcher 线程调用）— 同样在 `backpressureLock` 内置 OP_WRITE，并有注释说明与 handleWrite 的并发意图。

唯一未加锁的是 `sendTunResponse:512-525`，但其**全仓仅一个调用点**（`:490` UDP ASSOCIATE，eventLoop 线程内）——与 handleWrite/sendReply 同线程或无并发交叉；且 F12-c 会把该唯一调用改为 `sendErrorReply+close`。**无需修改**。

保留一条实现时注意：F6/F8 若新增 sshIoDispatcher 侧的回复路径，必须走已加锁的 `sendReply`（勿新造入队点）。

## F12-a~f【功能·中】其余中危功能项

| ID | 位置 | 方案 | 验证 |
|----|------|------|------|
| a. fallback 共享缓冲 | `Socks5ProxyServer.kt:736-740` | `sendReply(readBuffer.array(), 0, read)` → `sendReply(readBuffer.array().copyOf(read), 0, read)`（一行复制） | 单测：慢写队列+连续读，断言队列内容不被下轮 read 覆写 |
| b. relay 异常不 close | `:743-747` | catch 内补 `this@Socks5Connection.close()`（与 EOF 分支 `:725` 一致） | 单测：mock tunnel 抛 IOException → state 变 Closed、socket close |
| c. UDP ASSOCIATE 假成功 | `:490` | 改 `sendErrorReply(0x07); close()`（与 else 分支 `:496-498` 一致） | 单测：发 UDP ASSOCIATE → 收 0x07 |
| d. DNS cache 键不一致 | `DnsInterceptor.kt:288` vs `:519/:573` | 抽 `private fun dnsCacheKey(q: Name, type: Int) = "${q.toString(true)}.$type"`，读写三处统一（**以 `toString(true)` 无尾点为规范**） | 单测：假 IP 路径写入后同 key 读命中，`cacheHits > 0` |
| e. processDnsQuery 返回被吞 | `UdpRelay.kt:99-101` | `if (!interceptor.processDnsQuery(...)) return false`；同时把 `VpnController.kt:556-565` 对 UDP 的 `!processed` 分支从 `writeToTun 回注`（F1 同类黑洞）改为**丢弃 + `stats.addError()`**；`interceptor==null` 同理。注释"false=透传"改为"false=丢弃"（回注即黑洞，透传语义删除） | 单测：畸形 DNS → 不回注、不崩、计数 +1 |
| f. null intent START_STICKY | `SshVpnService.kt:86-112` | `when (intent?.action)` 加 `null -> { stopSelf(); return START_NOT_STICKY }`（或 `else` 分支） | 真机：`am kill` 后服务不空转重启 |

---

# P2 详细方案（中低危）

## F10【中】DNS 响应源 IP 硬编码 10.0.0.2

**现状**：`VpnController.kt:667-670, 831-835` 恒用 `VpnNetwork.TUN_IP`；`DnsResponse.srcIp`（`DnsInterceptor.kt:461` 已填 `pending.dstIp`）从未被读。

**方案**：`writeDnsResponse`（`:665-680`）改用 `response.srcIp`（`:667` 的 `vpnIp` 硬编码删除）；`handleRemoteDnsFakery`（`:483`）构造 DnsResponse 时把**查询的目标地址**作为 `srcIp`——**注意（审计修正）**：该函数现签名 `(question, originalQueryId, srcIp, srcPort)` **无 dstIp 入参**，需从 `processDnsQuery`（`:325` 有 `dstIp`）透传进来；REMOTE 模式下 dstIp 即 10.0.0.2（客户端实际查询的 VPN DNS），行为正确。`forwardDnsBypassPacket` 的响应（`:831-835`）srcIp 应为**被查询的 DNS 服务器**（即该函数入参 `dstIp`），非 `TUN_IP`——实现前先确认该路径触发条件（SYSTEM 模式 + dstIp ∈ excludedRoutes）。**从方案移除（审计修正）**：`:827` 的 `10.0.0.1` 是"客户端源 IP 解析失败"的兜底，与 DNS 响应 srcIp 无关，不在本条范围。VPN 自配 DNS（查询目标就是 10.0.0.2）行为不变。

**验证**：单测——构造查询 dst=8.8.8.8 的响应，断言回程 IP 包 src==8.8.8.8；真机硬编码 8.8.8.8 的 app DNS 不超时。

**风险**：低。若 bionic 实际不校验源地址则为纯正确性改进（无回归面）。

## F11【中】网络回调对非默认网络触发全量重连

**现状**：`SshVpnService.kt:534-600` 不与 activeNetwork 比对，去重键含 networkId → WiFi+蜂窝并存时副网络上下线触发 `autoReconnect`。

**方案**：`onAvailable/onLost` 开头比对 `connectivityManager.activeNetwork`，非默认网络事件直接 return；注册时记录初始 networkId 抑制首个 onAvailable 自触发；`(networkId, isLost)` 去重保留。

**验证**：单测——回调传入非 active network → 不调 autoReconnect（mock）；真机：开关飞行模式/插拔 SIM 不产生多余重连日志（主 WiFi 不变时）。

**风险**：低。`onLost(active 自己)` 时 activeNetwork 已切换——用"记录的 lastActiveId"比对更稳，实现时注意。

## F12-g~k【中】其余

| ID | 位置 | 方案 |
|----|------|------|
| g. ACTION_CONNECT 无串行化 | `SshVpnService.kt:103-151` | 加 `private val connectMutex = Mutex()`，`connect()` 整体 `mutex.withLock`；重入时若已在 Connecting → return（保留现 `:151` 检查，锁内执行消除 TOCTOU）。**死锁警告（审计补充）**：`connect()` 的 catch（`:204`）会调 `disconnect()`——若 `disconnect()` 也 `withLock` 则同线程重入死锁（Mutex 不可重入）。`disconnect()` **不加锁**（或 catch 路径调无锁的内部实现），锁只保护 connect 入口 |
| h. VPN 授权 intent 不清除 | `DashboardScreen.kt:90-105`（launcher）+ `MainViewModel.kt:705-708` | launcher 回调（DashboardScreen `:93-99`）补 `else -> viewModel.clearVpnPermissionRequest()`（新增方法置 intent=null + 保留 pendingConnectServerId 供重试，位置在 MainViewModel）；`LaunchedEffect` key 变化即不重弹。**位置修正（审计）**：launcher 在 DashboardScreen 不在 MainViewModel——原方案只写 MainViewModel.kt:697-708 不完整 |
| i. TUN 读错误忙循环 | `VpnController.kt:431-450` + `SshVpnService.kt:229-237` | 两层修：① **根因修在 establishVpnInterface**——`establish()` 返回 null 时，旧接口已在 `:232` 被无条件关闭、`:237` 才抛异常 → VpnController 的 inputStream 指向已关闭 fd，packetLoop 每轮 read 立即抛异常且 `isRunning` 仍 true → 无退避死循环；改**先判 null 再换**（null 直接抛，旧 TUN 不动），失败即回退旧 TUN 正常工作，`rebuildVpnInterfaceInternal` catch 只记日志。② **packetLoop 兜底**：catch 内 `Thread.sleep(100)` 退避 + 连续失败计数 ≥20 → `isRunning=false` 退出 loop + 状态置 Failed（覆盖其他死流场景，如断开竞态） |
| j. protect lambda 泄漏 Service | `VpnController.kt:78,96-99` | `disconnect()`/`forceReset()` 末尾 `setProtectFunction(null)`（函数收 `((DatagramSocket) -> Boolean)?`），`SshVpnService.onDestroy` 同调 |
| k. mainThread 阻塞 | `MainViewModel.kt:172-314`、`ServerRepository.kt:32-40`、`KeyManagerViewModel.kt:47-64` | `getDeviceIpv4/6`、`getNetworkDisplay`、`KeyManagerViewModel.refresh` 包 `withContext(Dispatchers.IO)`；`ServerRepository.allServersFlow` 加 `.flowOn(Dispatchers.IO)`（decrypt 移出 Main） |

## R2~R6【资源泄露】

| ID | 位置 | 方案 |
|----|------|------|
| R2 | `TcpStateMachine.kt:44-45,505,591` | **直接删除** `nextTcpSeq`/`nextTcpAck`（全仓只写不读，死代码+无界增长）；写入点 `:505-506`、`:591-592` 一并删 |
| R3 | 见 F12-j | 同方案 |
| R4 | `MainViewModel.kt:621-669` | `DatagramSocket().use { ... }`；`runDiagnostics` 存 `diagnosticsJob`，启动前 `cancel()` 互斥；`HttpsURLConnection` catch 内 `disconnect()` |
| R5 | `DnsInterceptor.kt:65` | 线程工厂设 daemon + `allowCoreThreadTimeOut(true)`（进程级回收兜底，与 SshIoDispatcher 一致）；`VpnController.kt:94` cached 池同理（影响本就小） |

## LOW 组

| ID | 位置 | 方案 |
|----|------|------|
| L1 | 全局 Log.d | `proguard-rules.pro` 加 `-assumenosideeffects android.util.Log { public static int d(...); public static int i(...); }`（**保留 e/w** 便于线上排查）；`MainViewModel.kt:366-395` 1s 刷屏循环间隔 1000→5000ms 且包 `IS_DEBUG` 守卫 |
| L2 | `AndroidManifest.xml:9-10` | 删 `ACCESS_FINE/COARSE_LOCATION` 两行（零引用零申请）；SSID 显示维持 `<unknown ssid>` 兜底；删 `:24-25` typo 权限 `FOREGROUND_SERVICE_TYPE_VPN` |
| L3 | `ssh_setup_script.sh:31,192-196` | dropbear 分支改写 `/root/.ssh/authorized_keys`（`mkdir -p /root/.ssh`），不再写全局 `/etc/dropbear/authorized_keys`；**SHA-256 同步**：`SetupScriptConsistencyTest` 会强制失败，重算 `ServerProvisioning.kt:13` |
| L4 | 出向队列 8MiB/连接 | `SSH_SEND_QUEUE_CAPACITY` 256 → **64**（64×32KB=2MB/连接，BDP 足够，OOM 面缩 4 倍）；`Socks5BackpressureTest` 阈值同步 |
| L5 | `POST_NOTIFICATIONS` 未申请 | Dashboard 连接前 `rememberLauncherForActivityResult(RequestPermission)`，Android 13+ 才请求；拒绝时通知不可见属可接受降级（文档标注） |
| L6 | 备份规则缺 files 域 | `data_extraction_rules.xml` + `backup_rules.xml` 各补 `<exclude domain="file" path="." />`（现被 allowBackup=false 挡住，属纵深防御） |
| L7 | `saveServerEdit` 非事务 | `Daos.kt` 加 `@Transaction suspend fun updateWithActive(...)` 或用 `room.withTransaction { ... }` 包 `mergeForEdit+update+setActive`；顺带删死代码 `insertAndSetActive`（`:42-47`） |
| L8 | 缺 MIGRATION_1_2 | 对比 `schemas/1.json` vs `2.json` 补 `MIGRATION_1_2` SQL；加 `MigrationTestHelper` 测试（当前迁移零覆盖）；**若确认 v1 从未发布可改为 waived**（见 P3，实现前先 `git log --oneline -- app/schemas` 核实发布历史）——**已核实：v1.0.0 tag 的 AppDatabase version=4，schema 1-3 从未随发布版运行 → waived（见 P3/L8）** |
| L9 | 依赖校验缺失 | `./gradlew --write-verification-metadata sha256` 生成 `gradle/verification-metadata.xml` 提交（一次性成本 + 后续加依赖需同步维护）；jitpack 兜底可移除（jsch 0.2.17 在 Maven Central）——**列为可选，非阻塞** |
| L10 | SynReceived 死代码清理 | `TcpStateMachine.kt`：删枚举值 `SynReceived`（`:70`）、删分支 `else if (syn && ack) { conn.state = SynReceived }`（`:133-134`）与内层检查 `if (state == SynReceived)`（`:136-138`）。删除后 syn+ack 包落入下方纯 ACK/载荷处理，语义正确。全仓引用已核实仅此一处文件、测试零引用 |

---

# P3 明确不修（waived）及理由

| ID | 项 | 理由 |
|----|----|------|
| W1 | DNS 明文回落（SYSTEM 模式 / 非 A/AAAA） | 设计取舍：全链路 DoH/DoT 是独立特性，超出本次审查修复范围；`protect()` 已防自环 |
| W2 | 首连 TOFU 无指纹确认 UI | S1 已把校验提前到 KEX（时序安全）；指纹展示+确认交互是产品增强，排入后续迭代，不阻塞 P0 |
| W3 | TCP 空闲 5min 超时（F2 修复后仍保留） | 空闲超时是资源回收的正确语义，F2 只修"活跃传输被误杀" |
| W4 | `runDiagnostics` 竞态之外的 UX 项 | 已含在 R4（job 互斥） |
| W5 | ProGuard keep 范围偏宽 | 有反射/Room 理由，非缺陷；收紧需专门回归，不在本轮 |
| F9（审计后移入） | OP_WRITE RMW "竞态" | **读码核实不存在跨线程路径**：handleWrite/sendReply 均已在 backpressureLock 内（原审查行号过期）；唯一未加锁的 sendTunResponse 仅 eventLoop 单点调用且被 F12-c 移除。详见 P1 节审计记录 |
| R6（审计后移入） | SshIoDispatcher CallerRunsPolicy TOCTOU → AbortPolicy | **AbortPolicy 会破坏协程语义**：拒绝在 kotlinx 的 `dispatch` 内部处理（按版本为取消任务或降级执行），**不会从 `scope.launch` 抛出** → 调用点捕不到 RejectedExecutionException，connect/relay 协程无 close() 死亡，连接挂死至 300s + socket 泄漏，严格劣于现状。现有 `isSaturated()` 预检（`Socks5ProxyServer.kt:601`）+ CallerRunsPolicy 是 AGENTS.md 记录的既有设计取舍；残留风险仅为极端 TOCTOU 窗口内至多一次 ≤5s 的 eventLoop 内联阻塞。按路径处理拒绝需把所有 dispatcher launch 改 raw executor.submit，收益不成比例 |
| L8（已核实后移入） | 缺 MIGRATION_1_2 | `git ls-tree v1.0.0` + `git show v1.0.0:.../AppDatabase.kt` 核实：**v1.0.0 首发时 DB version=4**，schema `1.json`~`3.json` 均为开发期产物、从未随任何发布版以 version 1/2/3 运行；正式渠道不存在需要 1→2 迁移的存量用户 |
| L9（可选项） | Gradle verification-metadata | spec 自标"一次性成本 + 后续加依赖需同步维护，列为可选非阻塞"——决定不生成：jitpack 依赖（jsch）仍需运行时校验策略，半套元数据比没有更误导；加依赖频率低，收益不成比例 |

---

# 同类排查记录（审查→方案的扩展发现）

1. **回注黑洞同模式**（F1 扩展）：`VpnController.kt:549` DOMAIN_SPLIT TCP 直连、`:558/:564` `!processed` 回注、UdpRelay false 语义（F12-e）——三处与 `:525` 同根因，方案统一覆盖：TCP 走 F1 直通代理，其余改丢弃计数。
2. **Session 泄漏同机制**（S1 扩展）：`createSession` 与 `execSingleShot` 两处 catch 均不 disconnect，同一修法。
3. **OP_WRITE 锁链核实**（F9，审计后 waived）：`sendReply`/`handleWrite` 均已在 `backpressureLock` 内；唯一未加锁的 `sendTunResponse` 仅 eventLoop 单点调用（`:490`），且 F12-c 将其移除——无跨线程竞态，不改。
4. **Hop Limit 同字段**（F5 扩展）：NA/RA 两处构造，RA 已正确，改 NA；实现时 grep `Hop Limit` 全量确认无第三处。
5. **RST 缺失同调用链**（F6 扩展）：`forwardThroughLocalSocks` 内 4 处 `conn.state = Closed` 直改点全部收敛到 `closeTcpConnection`。
6. **写死 TUN_IP 同模式**（F10 扩展）：`:667`（writeDnsResponse）、`:831`（bypass 响应）两处硬编码，分别改 `response.srcIp` / 入参 `dstIp`；`:827` 经核实是客户端源 IP 兜底，不相关。

# 二次审计记录（2026-09-29）

逐条读码核验（含 mwiede/jsch 源码对照），修正如下：

| # | 条目 | 问题 | 处理 |
|---|------|------|------|
| 1 | S1 | `adapter.check(host, port, key)` 签名不存在——JSch `check(String host, byte[] key)` 无 port；返回码 `OK/NOT_INCLUDED/CHANGED`；接口无 `find/isIgnored`；`StrictHostKeyChecking=yes` 下 NOT_INCLUDED 直接抛异常（不自动 add），TOFU 必须 adapter 内部保存后返回 OK | 已重写 S1 方案第 1 点 |
| 2 | S1-3 | 指纹落库需注入 `ServerRepository`（DI 变更）+ 新增 DAO 方法，原方案未写 | 已补 |
| 3 | S2 | `Socks5ProxyServer` 无 `TunnelPlugin` 引用，服务端验证凭据的传递路径缺失 | 已补（构造参数/setter） |
| 4 | S5 | 丢弃点类名错（`processIpv6Packet` 在 `PacketProcessor` 非 VpnController）；`DnsInterceptor` 无 enableIPv6 开关需新增 | 已修正 |
| 5 | F3 | 调用点清单错：`:122` 是 onTaskRemoved（→true），`:108` ACTION_REBUILD 不调 disconnect | 已修正 |
| 6 | F6 | 直改点共 6 处（漏 `forwardSynToTunnel` 的 `:205`/`:211`）；`:205` 已手动发 RST，收敛时避免双 RST | 已补 |
| 7 | F10 | `handleRemoteDnsFakery` 无 dstIp 入参需透传；`:827` 是客户端源 IP 兜底与 DNS 响应无关；`:831` 应为被查询 DNS（入参 dstIp） | 已修正 |
| 8 | F12-g | **方案级缺陷**：connect/disconnect 都 withLock → connect catch 调 disconnect 同线程重入死锁 | 已补死锁警告：disconnect 不加锁 |
| 9 | F12-h | launcher 在 `DashboardScreen.kt:90-105` 不在 MainViewModel | 已修正位置 |
| 10 | F12-i | **原方案与二次审计修正各对一半**：establishVpnInterface `:229-237` 在判 null（`:237`）之前已无条件关闭旧 TUN（`:232`）→ establish 失败后 packetLoop 读已关闭 fd 忙循环属实（原方案对）；但旧 TUN 有效是改完才成立的（二次审计当时说错） | 已定稿双层修：establish 先判 null 再换（旧 TUN 保留回退）+ packetLoop sleep/计数兜底 |
| 11 | S6 | 类注释称降级"有意为之"，改后需同步 | 已补 |

**核验通过无需修改**：S3/S7/F1/F2/F4/F5/F7/F8/F11/F12-a~f/j/k、R2~R5、L1~L10、F9/R6 waived 结论。

# 总体验收标准（Gate-Out）

- [ ] P0 全部实现且新增单测通过；`./gradlew testDebugUnitTest ktlintCheck detekt lint` 四门全绿
- [ ] P0 手动验证：局域网 excludedRoutes 可达（F1）、下载 6 分钟不中断（F2）、重启后 BootReceiver 自启（F3）、`nc` 无认证被拒（S2）、改指纹连接在认证前失败（S1）
- [ ] `Socks5BackpressureTest`、`SshIoDispatcherTest`、`SetupScriptConsistencyTest` 全部回归通过
- [ ] 无 TBD/TODO 残留；waived 项均有理由记录（本文件 P3）

