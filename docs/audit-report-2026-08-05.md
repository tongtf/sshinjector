# SSHInjector 代码审计与漏洞检测报告

**审计日期**: 2026-08-05  
**审计范围**: 全项目代码质量、安全漏洞、架构设计、CI配置  
**审计人员**: opencode 自动化审计流程

---

## 执行摘要

| 维度 | 状态 | 关键发现 |
|------|------|----------|
| **代码质量 (lint)** | ❌ 1 Error, 20 Warnings | MissingPermission 错误需修复 |
| **安全漏洞** | 🔴 3 Critical, 4 High, 5 Medium, 5 Low | SSH主机验证禁用、明文密码存储、全局明文HTTP |
| **架构设计** | ✅ 整体深模块设计合理 | SshChannelFactory seam 有价值，PacketProcessor 过深建议拆分 |
| **CI 配置** | ❌ 2 个死配置任务 | dependency-check、jacoco 任务不存在 |
| **单元测试** | ✅ 全部通过 | 31 tests passed |

---

## 1. 代码质量检查 (Lint)

### 1.1 Error (必须修复)

| 文件 | 行 | 问题 | 说明 |
|------|-----|------|------|
| `MainViewModel.kt` | 210 | `MissingPermission` | 访问 `TelephonyManager.dataNetworkType` 需要 `READ_PHONE_STATE` 权限，需显式检查或处理 SecurityException |

### 1.2 Warnings (建议修复)

| 类别 | 数量 | 代表性问题 |
|------|------|------------|
| GradleDependency | 3 | lifecycle-runtime-compose 2.8.7 → 2.9.4，core-ktx 1.15.0 → 1.16.0，compose-bom 2024.12.01 → 2025.12.00 |
| SwitchIntDef | 1 | Wi-Fi 标准 switch 未覆盖全部 @IntDef 值 |
| DiscouragedApi | 1 | `scheduleAtFixedRate` 建议改用 `scheduleWithFixedDelay` (DnsInterceptor.kt:101) |
| ObsoleteSdkInt | 1 | `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R` 检查多余 (minSdk 34) |
| AutoboxingStateCreation | 1 | `mutableStateOf(0L)` → `mutableLongStateOf()` (FloatingNavIcon.kt:49) |
| IconLauncherShape | 10 | 启动器图标填满正方形、圆形图标非圆形、ic_launcher 与 ic_launcher_round 内容重复 |

---

## 2. 安全漏洞扫描报告

### 🔴 Critical (3 个，立即修复)

| ID | 问题 | 文件 | 行 | 风险 | 修复建议 |
|----|------|------|-----|------|----------|
| **C-01** | SSH 密码明文存储在 Room 数据库 | `Entities.kt` | 18 | Root/ADB 可读取所有 SSH 密码 | 使用 EncryptedSharedPreferences 或 AES-GCM 加密存储（参考 `importedKeysDir` 方案） |
| **C-02** | `StrictHostKeyChecking=no` 完全禁用 SSH 主机验证 | `JschSshClient.kt` | 136-137 | **中间人攻击完全劫持连接**，所有代理流量可被窃听/篡改 | 实现 known_hosts 管理，使用 TOFU 或预配置主机指纹；利用已有 `hostKeyFingerprint` 字段 |
| **C-03** | `usesCleartextTraffic="true"` 全局允许明文 HTTP | `AndroidManifest.xml` | 38 | 任意 HTTP 通信可被中间人劫持 | 使用 `network_security_config.xml` 仅允许特定域名明文，其余强制 HTTPS |

### 🟠 High (4 个)

| ID | 问题 | 文件 | 行 | 风险 | 修复建议 |
|----|------|------|-----|------|----------|
| **H-01** | 日志泄露 SSH 密钥别名、算法、生物识别状态 | `SshKeyManager.kt` | 51,330,618-619 | `READ_LOGS` 应用可枚举密钥信息 | 生产构建用 `BuildConfig.DEBUG` 包裹敏感日志 |
| **H-02** | Socks5ProxyServer 无认证接受任意本地连接 | `Socks5ProxyServer.kt` | 61-65,364-382 | 恶意本地应用可滥用 SSH 隧道 | 添加 UID 白名单或 Token 认证，限制仅自身应用接入 |
| **H-03** | Ed25519 降级到软件密钥生成 (失去硬件保护) | `SshKeyManager.kt` | 59-70 | 私钥可被直接转储读取 | Android 12+ 强制 Keystore；旧设备降级 ECDSA P-256，禁用软密钥 |
| **H-04** | JSch 密码以 String 明文在 JVM 堆驻留 | `JschSshClient.kt` | 132-133, `VpnController.kt` | 堆转储攻击可恢复密码 | 使用 `char[]` + `setPassword(byte[])` 并用后清零 |

### 🟡 Medium (5 个)

| ID | 问题 | 文件 | 行 | 风险 | 修复建议 |
|----|------|------|-----|------|----------|
| **M-01** | Debug 日志暴露访问 IP/域名 | `PacketProcessor.kt` | 878,1329, `Socks5ProxyServer.kt` | 531 | `READ_LOGS` 应用可重建用户浏览轨迹 | Release 构建禁用 debug 日志 (`Log.isLoggable`) |
| **M-02** | Ed25519 公钥/元数据明文存储 `.meta`/`.pub` | `SshKeyManager.kt` | 112-113,258-260 | 公钥指纹可被枚举 | 对 `.meta` 使用相同 AES-GCM 加密 |
| **M-03** | DNS 响应缓冲区硬编码 512 字节 | `DnsInterceptor.kt` | 343 | 大型 DNSSEC/EDNS0 响应截断 | 增至 4096 或动态按 EDNS0 BUFSIZE 分配 |
| **M-04** | 域名列表 URL 硬编码且无证书固定 | `SettingsDataStore.kt` | 40, `MainViewModel.kt` | 462 | 中间人可注入恶意域名列表 | 实现 HTTP 证书固定或签名验证 |
| **M-05** | BootReceiver exported=true 无权限保护 | `AndroidManifest.xml` | 67-73 | 不必要的组件暴露 | 添加 `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"` |

### 🟢 Low (5 个)

| ID | 问题 | 文件 | 行 | 建议 |
|----|------|------|-----|------|
| **L-01** | `ThreadLocal<ByteBuffer>` 并发风险 | `PacketProcessor.kt` | 46-48 | 改为局部变量分配直接缓冲区 |
| **L-02** | ICMPv6 NA 使用全零 MAC | `PacketProcessor.kt` | 543-545 | 使用真实 TUN MAC 或省略 Link-layer Option |
| **L-03** | UDP relay 未验证发送者地址 | `PacketProcessor.kt` | 1630-1688 | 验证 sender 为 `127.0.0.1` |
| **L-04** | Ed25519 私钥解密无大小上限 | `SshKeyManager.kt` | 342 | 限制解密后 payload ≤ 4096 字节 |
| **L-05** | 域名列表下载内容无签名/CRC 验证 | `MainViewModel.kt` | 462 | 增加 SHA-256 校验或签名验证 |

---

## 3. 架构设计评估 (Design Skill 视角)

### 3.1 模块评分汇总

| 模块 | 评分 | Depth | Leverage | Locality | 核心问题 |
|------|------|-------|----------|----------|----------|
| **PacketProcessor** | 🔴 **过深需拆分** | 极高 (1759行) | 高 | 中 | 单类承担 IP/TCP/UDP解析+TCP状态机+SOCKS5转发+UDP relay+DNS拦截+连接管理，职责过重 |
| **Socks5ProxyServer** | 🟡 **合理偏深** | 高 (783行) | 高 | 高 | 零拷贝管道内聚良好，但 `processHandshake`/`processConnect` 可提取为私有类 |
| **TunnelChannel / SshChannelFactory** | ✅ **优秀深模块** | 高 | **极高** | 高 | **Seam 有真实价值**：JschSshClient 是唯一 adapter，但接口设计为未来多隧道协议（如 shadowsocks/VLESS）预留了扩展点 |
| **JschSshClient (Adapter)** | ✅ **合理** | 中 | — | 高 | 会话池+心跳+健康检查封装良好，实现细节未泄露 |
| **VpnController** | 🟡 **合理协调器** | 中 | 中 | **高** | 纯协调无核心协议逻辑，符合 Locality 原则；但 `setProtectFunction` 回调略显突兀 |
| **SshKeyManager** | ✅ **优秀深模块** | 高 | **极高** | **高** | 6方法小接口隐藏 Keystore+多算法+AES-GCM+生物识别+JSch适配器 的巨大复杂度 |
| **DnsInterceptor** | 🟡 **合理偏深** | 高 | 高 | 高 | DNS解析+缓存+SOCKS5-TCP/DoH双模式内聚，`scheduleAtFixedRate` 需修复 |

### 3.2 设计检查清单结果

| 检查项 | 结果 | 说明 |
|--------|------|------|
| **D1 单一职责** | ⚠️ 部分违反 | PacketProcessor 职责过重 (7+子职责) |
| **D2 小接口+大实现** | ✅ 普遍达标 | SshChannelFactory(4法)、SshKeyManager(6法)、TunnelChannel(4法) 均为深模块 |
| **D3 Seam 合理放置** | ✅ SshChannelFactory | 仅 1 个 adapter (JschSshClient)，但 `TunnelModule.kt` 使用 `@IntoMap @StringKey` 为多隧道预留扩展，算**真实 seam** |
| **D4 无浅模块** | ✅ 无 | 无大接口小实现 |
| **D5 跨层调用合理** | ✅ 合理 | Domain 不依赖 UI；Data 实现 Domain 接口；Hilt 在 SingletonComponent 组装 |
| **D6 无循环依赖** | ✅ 无 | 依赖方向单向：UI → ViewModel → Domain(UseCase) → Data/VPN |
| **D7 封装性完整** | ✅ 良好 | 实现细节 (JSch Session、KeyStore、Selector) 未泄露到接口 |
| **D8 碎片模块** | ✅ 无 | 模块粒度适中 |

### 3.3 重构建议

1. **拆分 PacketProcessor** (P0)：提取 `TcpStateMachine`、`UdpRelay`、`IpPacketParser`、`ChecksumCalculator` 为独立深模块，PacketProcessor 退化为协调器
2. **Socks5ProxyServer 内部类私有化** (P1)：`Socks5Connection`、`HandshakeState` 等不应泄露
3. **VpnController 回调接口化** (P2)：`setProtectFunction` → `NetworkProtector` interface，便于测试

---

## 4. CI/CD 配置问题

| 问题 | 文件 | 位置 | 影响 | 修复 |
|------|------|------|------|------|
| **dependency-check 任务不存在** | `.github/workflows/ci.yml` | 第193-216行 | CI job 直接失败 | 添加 OWASP dependency-check Gradle 插件，或移除该 job |
| **jacoco 插件未配置** | `.github/workflows/ci.yml` | 第75-80行 | 覆盖率上传空目录 | 添加 `id("jacoco")` 插件并配置报告输出，或移除上传步骤 |
| **gradle.properties 版本过时** | `gradle.properties` | 全文 | 误导维护者 | 清理无效版本声明 (Kotlin/Hilt/KSP/dnsjava/compose 版本均未被 build 文件引用) |

---

## 5. 假数据/占位数据扫描

**结论**: 无生产代码中的硬编码真实 IP/密码/临时数据。

| 类型 | 文件 | 行 | 说明 |
|------|------|-----|------|
| `127.0.0.1` | Socks5ProxyServer.kt 等 | 多处 | **正常架构设计** - 本地 SOCKS5 代理通信 |
| `example.com` | ServerEditScreen.kt 等 | 多处 | **UI placeholder** 仅作输入框提示文本 |
| `fd00::/8` fake IP | DnsInterceptor.kt | 166-468 | **正常 VPN DNS 拦截实现** - 分配给用户数据面路由标记 |
| TODO/FIXME/HACK | — | — | **无任何** |

---

## 6. 优先级修复路线图

### P0 (立即，阻塞发布)
- [ ] **C-02**: 启用 SSH 主机密钥验证
- [ ] **C-03**: 移除 `usesCleartextTraffic=true`，配置 network_security_config
- [ ] **C-01**: 加密存储 SSH 密码
- [ ] **Lint Error**: 修复 `MissingPermission` (MainViewModel.kt:210)

### P1 (本周内)
- [ ] **H-01/H-02/H-03/H-04**: 高危安全项修复
- [ ] **PacketProcessor 拆分** (架构债务)
- [ ] **CI 死配置修复** (添加 dependency-check/jacoco 插件或移除 job)

### P2 (下个迭代)
- [ ] **M-01 到 M-05**: 中危安全项
- [ ] **依赖版本升级** (lifecycle、core-ktx、compose-bom)
- [ ] **启动器图标规范化**

### P3 (技术债务)
- [ ] **L-01 到 L-05**: 低危项
- [ ] **gradle.properties 清理**
- [ ] **Socks5ProxyServer 内部类私有化**

---

## 7. 验收标准 (Gate-Out)

- [ ] 所有 Critical/High 安全问题已修复并验证
- [ ] `./gradlew lint` 通过 (0 Error)
- [ ] `./gradlew testDebugUnitTest` 全部通过
- [ ] CI workflow 绿色通过 (无死配置 job)
- [ ] PacketProcessor 拆分完成，单元测试覆盖新模块
- [ ] 无生产代码敏感信息日志输出 (Release build 验证)

---

*报告生成时间: 2026-08-05*  
*下次审计建议: 修复 P0 后复查，或重大架构变更时*