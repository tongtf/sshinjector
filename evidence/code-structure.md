# 代码结构证据

## 核心包结构

```
com.sshinjector
├── data/
│   ├── local/
│   │   ├── dao/Daos.kt           # Room DAOs
│   │   ├── database/AppDatabase.kt
│   │   ├── entity/Entities.kt    # Room 实体
│   │   ├── converter/Converters.kt
│   │   └── preferences/SettingsDataStore.kt
│   └── remote/ssh/
│       ├── JschSshClient.kt      # SshChannelFactory 实现
│       ├── JschTunnelChannel.kt  # TunnelChannel 实现
│       ├── SshKeyManager.kt      # 密钥管理 (Keystore+JSch)
│       └── AndroidKeyStoreIdentity.kt # JSch Identity 适配器
├── domain/
│   ├── model/DomainModels.kt     # ServerConfig, WhitelistApp, VpnState, etc.
│   ├── usecase/
│   │   ├── ServerRepository.kt   # Server CRUD + 白名单
│   │   └── VpnController.kt      # VPN 生命周期协调器
│   └── vpn/
│       ├── PacketProcessor.kt    # IP/TCP/UDP 解析 + TCP 状态机
│       ├── Socks5ProxyServer.kt  # SOCKS5 服务器 + 零拷贝管道
│       ├── DnsInterceptor.kt     # DNS 拦截 + 远端解析 + DoH
│       ├── TunnelChannel.kt      # SSH 直连通道接口
│       └── SshChannelFactory.kt  # SSH 通道工厂接口
├── ui/
│   ├── viewmodel/
│   │   ├── MainViewModel.kt
│   │   ├── ServerListViewModel.kt
│   │   ├── ServerEditViewModel.kt
│   │   ├── WhitelistViewModel.kt
│   │   ├── SettingsViewModel.kt
│   │   └── KeyManagerViewModel.kt
│   ├── screen/
│   │   ├── dashboard/DashboardScreen.kt
│   │   ├── server/ServerListScreen.kt + ServerEditScreen.kt
│   │   ├── whitelist/WhitelistScreen.kt
│   │   ├── settings/SettingsScreen.kt
│   │   └── keymanager/KeyManagerScreen.kt
│   ├── navigation/NavGraph.kt
│   └── theme/Theme.kt + Typography.kt
├── vpn/
│   ├── SshVpnService.kt          # VpnService 前台服务
│   └── BootReceiver.kt           # 开机自启
├── di/Modules.kt                 # Hilt 模块
└── SshInjectorApplication.kt     # Application 入口
```

## 关键接口定义

### SshChannelFactory (domain/vpn/TunnelChannel.kt:21)
```kotlin
interface SshChannelFactory {
    fun createDirectChannel(host: String, port: Int): TunnelChannel?
}
```

### TunnelChannel (domain/vpn/TunnelChannel.kt:10)
```kotlin
interface TunnelChannel {
    fun connect(timeoutMs: Int): Boolean
    val inputStream: InputStream?
    val outputStream: OutputStream?
    val isConnected: Boolean
    fun disconnect()
}
```

### SshChannelFactory.ConnectionResult (domain/vpn/TunnelChannel.kt:35)
```kotlin
data class ConnectionResult(
    val success: Boolean,
    val localSocksPort: Int = 0,
    val error: String? = null
)
```

---

## 热点文件

| 文件 | 行数 | 复杂度 | 说明 |
|------|------|--------|------|
| `PacketProcessor.kt` | ~760 | 高 | IP/TCP/UDP 解析、TCP 状态机、IPv6 扩展头部 |
| `Socks5ProxyServer.kt` | ~565 | 高 | 完整 SOCKS5 协议、零拷贝管道、UDP ASSOCIATE |
| `JschSshClient.kt` | ~190 | 中 | SSH 连接、密钥认证、端口转发、心跳 |
| `SshKeyManager.kt` | ~360 | 高 | Keystore 生成/导入、多算法、JSch Identity 适配 |
| `DnsInterceptor.kt` | ~325 | 高 | DNS 解析、缓存、SOCKS5 TCP/DoH 双模式 |
| `VpnController.kt` | ~270 | 中 | VPN 协调器、数据包循环、TUN 读写 |
| `SshVpnService.kt` | ~280 | 低 | VpnService 生命周期、TUN 建立、通知栏 |
| `SshKeyManager.kt` | ~360 | 高 | 硬件加密存储、生物识别、Ed25519/ECDSA/RSA |

---

