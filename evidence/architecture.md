# 架构现状 (Design 词汇描述)

## 模块划分

### 1. SSH 核心模块 (Domain Layer)
- **Module**: `SshChannelFactory` (Interface) / `JschSshClient` (Implementation)
- **Interface**: `createDirectChannel(host: String, port: Int): TunnelChannel?`
- **Depth**: 中等 - 隐藏 JSch 细节，暴露 SSH 直连通道创建
- **Seam**: `SshChannelFactory` 接口，用于 SOCKS5 代理创建 TCP 连接
- **Adapter**: `JschSshClient` 实现，内部使用 `ChannelDirectTCPIP`

### 2. SSH 密钥管理模块 (Data Layer)
- **Module**: `SshKeyManager` (Interface + Implementation 合并)
- **Interface**: `generateKeyPair()`, `importPrivateKey()`, `getPublicKeyOpenSSH()`, `getJSchIdentity()`, `signWithPrivateKey()`, `deleteKey()`
- **Depth**: 高 - 隐藏 Android Keystore 硬件加密复杂性、JSch Identity 适配、Ed25519/ECDSA/RSA 多算法支持
- **Seam**: 无外部 seam，内部使用 Android Keystore + BiometricPrompt

### 3. SOCKS5 代理模块 (Domain Layer)
- **Module**: `Socks5ProxyServer` (Class)
- **Interface**: `start(port: Int, bindAddress: String): Result<Int>`, `stop()`, `getStats(): ProxyStats`
- **Depth**: 高 - 完整 SOCKS5 协议栈（握手/认证/CONNECT/UDP ASSOCIATE）、零拷贝双向管道、IPv4/IPv6/域名支持
- **Seam**: 内部依赖 `SshChannelFactory` 创建隧道连接

### 4. 数据包处理模块 (Domain Layer)
- **Module**: `PacketProcessor` (Class)
- **Interface**: `processIpv4Packet()`, `processIpv6Packet()`, `setDnsInterceptor()`, `setTunWriter()`, `cleanupStaleConnections()`
- **Depth**: 高 - IP/TCP/UDP 头部解析、TCP 状态机、IPv6 扩展头部处理、校验和计算
- **Seam**: `setDnsInterceptor()` 注入 DNS 处理；`setTunWriter()` 注入 TUN 写回回调

### 5. DNS 拦截模块 (Domain Layer)
- **Module**: `DnsInterceptor` (Class)
- **Interface**: `processDnsQuery()`, `pollResponse()`, `setTransportMode()`
- **Depth**: 高 - DNS 报文解析、缓存、SOCKS5 TCP 远端解析、DoH 支持
- **Seam**: 传输模式可切换（SOCKS5_TCP / DOH）

### 6. VPN 控制模块 (Domain Layer)
- **Module**: `VpnController` (Class)
- **Interface**: `connect(server, password?)`, `disconnect()`, `setVpnInterface()`, `writeToTun()`
- **Depth**: 中等 - 协调 SSH 连接、SOCKS5 启动、数据包循环、DNS 拦截
- **Seam**: 依赖 `SshChannelFactory`、`Socks5ProxyServer`、`PacketProcessor`、`DnsInterceptor`

### 7. 数据持久化模块 (Data Layer)
- **Module**: `AppDatabase` (Room) + `ServerRepository` (UseCase)
- **Interface**: CRUD Flow、server/whitelist 查询、JSON 序列化 CIDR 路由
- **Depth**: 低 - 标准 Room 封装

### 8. VPN 前台服务 (Android Framework Layer)
- **Module**: `SshVpnService` (extends VpnService)
- **Interface**: 生命周期回调 + 通知栏交互
- **Depth**: 低 - 委托给 `VpnController` 处理业务逻辑

## 依赖关系 (自上而下)

```
UI Layer (Compose)
    ↓
ViewModels (Hilt)
    ↓
Domain Layer (UseCases)
    ├── VpnController ──────→ SshChannelFactory (Seam)
    ├── Socks5ProxyServer ──→ SshChannelFactory (Seam)
    ├── PacketProcessor ────→ DnsInterceptor (Seam), TunWriter callback (Seam)
    └── DnsInterceptor ────→ Transport Mode (Seam: SOCKS5_TCP/DOH)
    ↓
Data Layer (Repository)
    ↓
Room / DataStore / Keystore
```

## 关键 Seam 位置

1. **SshChannelFactory** - SSH 实现隔离，允许测试时替换为 Mock
2. **TunWriter callback** - `PacketProcessor.setTunWriter()` 解耦 TUN 写入
3. **DnsInterceptor transport** - `setTransportMode()` 切换 SOCKS5/DoH
4. **VpnController 依赖注入** - Hilt 提供所有 domain 接口实现

## Leverage 分析

| Module | Interface 复杂度 | Implementation 复杂度 | Leverage |
|--------|------------------|----------------------|----------|
| SshKeyManager | 低 (6 方法) | 高 (Keystore+JSch+多算法) | 高 |
| Socks5ProxyServer | 低 (3 方法) | 高 (完整协议栈) | 高 |
| PacketProcessor | 中 (5 方法) | 高 (协议解析+状态机) | 高 |
| DnsInterceptor | 低 (3 方法) | 高 (DNS+缓存+DoH) | 高 |
| VpnController | 中 (4 方法) | 中 (协调器) | 中 |

## Locality 分析

- **SshKeyManager**: 密钥生成/导入/签名/导出局部化在单类
- **Socks5ProxyServer**: SOCKS5 协议逻辑内聚，隧道创建委托 seam
- **PacketProcessor**: IP/TCP/UDP 解析、TCP 状态机、校验和计算高度局部化
- **VpnController**: 仅协调，无核心协议逻辑

---

