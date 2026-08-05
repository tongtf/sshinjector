# SSHInjector 修复 TODO 计划

基于审计报告 `docs/audit-report-2026-08-05.md` 生成

> **状态更新 (2026-08-05)**: TODO-001 至 TODO-010 (P0/P1 全部) 已完成，`lintDebug` + `testDebugUnitTest` 通过。

---

## P0 - 立即修复 (阻塞发布)

### TODO-001: 启用 SSH 主机密钥验证 (C-02)
- **文件**: `app/src/main/java/cn/srv0/sshinjector/data/remote/ssh/JschSshClient.kt:136-137`
- **问题**: `s.setConfig("StrictHostKeyChecking", "no")` 完全禁用 MITM 防护
- **验收**: 
  - 移除 `StrictHostKeyChecking=no`
  - 实现 known_hosts 文件管理 (首次连接信任 TOFU)
  - 利用已有 `ServerEntity.hostKeyFingerprint` 字段存储/验证指纹
  - 连接时验证主机密钥，不匹配则拒绝并报错

### TODO-002: 移除全局明文 HTTP 允许 (C-03)
- **文件**: `app/src/main/AndroidManifest.xml:38`
- **问题**: `android:usesCleartextTraffic="true"`
- **验收**:
  - 移除该属性
  - 创建 `app/src/main/res/xml/network_security_config.xml`
  - 配置仅允许特定域名明文 (如域名列表下载源)，其余强制 HTTPS
  - Manifest 添加 `android:networkSecurityConfig="@xml/network_security_config"`

### TODO-003: 加密存储 SSH 密码 (C-01)
- **文件**: `app/src/main/java/cn/srv0/sshinjector/data/local/entity/Entities.kt:18`
- **问题**: `ServerEntity.password` 明文存储 Room 数据库
- **验收**:
  - 密码字段改为加密存储 (AES-GCM，复用 `SshKeyManager` 的 `importWrapperKey` 方案)
  - Repository 层读写时自动加解密
  - 数据库迁移脚本处理现有明文数据

### TODO-004: 修复 Lint MissingPermission 错误
- **文件**: `app/src/main/java/cn/srv0/sshinjector/ui/viewmodel/MainViewModel.kt:210`
- **问题**: 访问 `TelephonyManager.dataNetworkType` 无权限检查
- **验收**:
  - 添加 `ContextCompat.checkSelfPermission(READ_PHONE_STATE)` 检查
  - 或捕获 `SecurityException` 优雅降级

---

## P1 - 本周内修复

### TODO-005: 移除敏感信息日志输出 (H-01)
- **文件**: `SshKeyManager.kt:51,330,618-619`, `AndroidKeyStoreIdentity.kt:25,51`
- **验收**: 生产构建 (`BuildConfig.DEBUG == false`) 时不输出密钥别名/算法/生物识别状态

### TODO-006: SOCKS5 代理添加访问控制 (H-02)
- **文件**: `Socks5ProxyServer.kt:61-65,364-382`
- **验收**: 仅允许自身 UID 连接，或添加 Token 认证机制

### TODO-007: 禁用 Ed25519 软件密钥降级 (H-03)
- **文件**: `SshKeyManager.kt:59-70`
- **验收**: Android 12+ 必须使用 Keystore；旧设备降级 ECDSA P-256，抛出异常而非软密钥

### TODO-008: JSch 密码使用 char[] 并清零 (H-04)
- **文件**: `JschSshClient.kt:132-133`, `VpnController.kt:796`
- **验收**: 使用 `setPassword(byte[])`，用后 `Arrays.fill(charArray, 0)`

### TODO-009: 拆分 PacketProcessor (架构债务)
- **文件**: `PacketProcessor.kt` (1759行)
- **验收**: 提取独立模块：
  - `IpPacketParser` - IP/TCP/UDP 头解析
  - `TcpStateMachine` - TCP 状态机
  - `ChecksumCalculator` - 校验和计算
  - `UdpRelay` - UDP ASSOCIATE 转发
  - `PacketProcessor` 退化为协调器 (<300行)

### TODO-010: 修复 CI 死配置
- **文件**: `.github/workflows/ci.yml`
- **验收**: 
  - 方案A: 添加 `id("org.owasp.dependencycheck")` 和 `id("jacoco")` 插件
  - 方案B: 移除 dependency-check job 和 jacoco 上传步骤
  - 推荐方案B (轻量)，或方案A 完整配置

---

## P2 - 下个迭代

### TODO-011: Debug 日志 Release 构建禁用 (M-01)
- **文件**: `PacketProcessor.kt:878,1329`, `Socks5ProxyServer.kt:531`
- **验收**: 统一用 `Log.isLoggable(TAG, Log.DEBUG)` 守卫

### TODO-012: Ed25519 公钥/元数据加密 (M-02)
- **文件**: `SshKeyManager.kt:112-113,258-260`
- **验收**: `.meta` 和 `.pub` 文件同私钥一样 AES-GCM 加密

### TODO-013: DNS 缓冲区增大 (M-03)
- **文件**: `DnsInterceptor.kt:343`
- **验收**: 512 → 4096 字节，或动态按 EDNS0 BUFSIZE

### TODO-014: 域名列表下载签名验证 (M-04)
- **文件**: `SettingsDataStore.kt:40`, `MainViewModel.kt:462`
- **验收**: 实现 HTTP 证书固定或内容 SHA-256/签名验证

### TODO-015: BootReceiver 权限保护 (M-05)
- **文件**: `AndroidManifest.xml:67-73`
- **验收**: 添加 `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"`

### TODO-016: 依赖版本升级
- **文件**: `app/build.gradle.kts`
- **验收**: lifecycle-runtime-compose 2.9.4, core-ktx 1.16.0, compose-bom 2025.12.00

### TODO-017: 启动器图标规范化
- **文件**: `app/src/main/res/mipmap-*/ic_launcher*.png`
- **验收**: 符合 Material Design 规范，圆形图标为圆形，去重

---

## P3 - 技术债务

### TODO-018: ThreadLocal ByteBuffer 改局部变量 (L-01)
- 完成: `TcpStateMachine.kt` 移除 64KB/线程 ThreadLocal `responseBuffer`，4 处构建方法改为 `ByteBuffer.allocate(totalLen)` 局部分配（返回值均经 `copyOfRange` 拷贝），并删除死常量 `MAX_PACKET_SIZE`

### TODO-019: ICMPv6 NA 使用真实 MAC (L-02)
- 完成: `Icmpv6Responder.kt` 全零 MAC (保留非法地址) 替换为稳定本地管理 MAC `02:00:00:00:00:01`（`gatewayMac`）。真实 TUN MAC 需 native ioctl 不现实；省略 option 违反 RFC 4861 §7.2.4（组播 NS 的 NA 必须含 Target Link-layer option）

### TODO-020: UDP relay 验证发送者地址 (L-03)
- 完成: `UdpRelay.kt` 接收线程校验 `sender.address.isLoopbackAddress`，非回环来源直接丢弃（SOCKS5 UDP relay 仅绑定 127.0.0.1）

### TODO-021: Ed25519 解密限制大小上限 (L-04)
- 完成: `SshKeyManager.kt` `loadImportedPem` 解密后 payload 上限 `MAX_IMPORTED_KEY_SIZE = 4096`，并校验 passLen 越界

### TODO-022: 域名列表内容 CRC/签名校验 (L-05)
- 完成: `DomainListManager.kt` 持久化时写 SHA-256 摘要（`.sha256` 文件），`loadFromDisk` 校验磁盘缓存完整性，不匹配则删除并回退内置列表。列表源为用户自定义 URL，无签名密钥，故采用完整性校验

### TODO-023: 清理 gradle.properties 过时版本声明
- 完成: 删除 compose.compiler.version 至 turbine.version 全部死属性（无任何 build 文件引用；build.gradle.kts 为唯一事实源）

### TODO-024: Socks5ProxyServer 内部类私有化
- 完成: `Socks5Connection` 改为 `private class`，`SocksState` 改为 `private enum class`（仅本文件使用）

---

## 执行顺序

```
P0: TODO-001 → TODO-002 → TODO-003 → TODO-004
P1: TODO-005 → TODO-006 → TODO-007 → TODO-008 → TODO-009 → TODO-010
P2: TODO-011 → TODO-017 (并行可行)
P3: TODO-018 → TODO-024 (零碎时间)
```

每完成一个 TODO：
1. 运行 `./gradlew lint` 确保无回归
2. 运行 `./gradlew testDebugUnitTest` 确保测试通过
3. 更新 TODO 状态为 completed