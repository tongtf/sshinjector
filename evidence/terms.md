# 领域术语表

## 核心术语

| 术语 | 定义 | 对应代码 |
|------|------|----------|
| **ServerConfig** | 服务器配置聚合根：主机、端口、用户名、密钥别名、认证算法、密码、MTU、保活、DNS模式、IPv6、白名单包名、排除路由 | `domain/model/DomainModels.kt:6` |
| **WhitelistApp** | 白名单应用：包名、应用名、图标哈希、启用状态、添加时间 | `domain/model/DomainModels.kt:31` |
| **VpnState** | VPN 连接状态：状态枚举、关联服务器、统计、错误、IP | `domain/model/DomainModels.kt:49` |
| **ConnectionStats** | 连接统计：上行/下行字节、包数、开始时间、最后更新 | `domain/model/DomainModels.kt:40` |
| **TunnelChannel** | SSH 直连通道抽象：连接、输入/输出流、连接状态、断开 | `domain/vpn/TunnelChannel.kt:10` |
| **SshChannelFactory** | SSH 通道工厂：创建直连通道 | `domain/vpn/TunnelChannel.kt:21` |
| **PacketProcessor** | 数据包处理器：IP/TCP/UDP 解析、五元组提取、TCP 状态机、SOCKS5 转发、DNS 拦截 | `domain/vpn/PacketProcessor.kt:29` |
| **Socks5ProxyServer** | 本地 SOCKS5 代理服务器：接受连接、握手、CONNECT/UDP ASSOCIATE、零拷贝转发 | `domain/vpn/Socks5ProxyServer.kt:32` |
| **DnsInterceptor** | DNS 拦截器：拦截 UDP:53、远端解析、缓存、SOCKS5 TCP/DoH 双模式 | `domain/vpn/DnsInterceptor.kt:30` |
| **VpnController** | VPN 控制器：协调 SSH/SOCKS5/DNS/数据包处理、生命周期、TUN 读写 | `domain/usecase/VpnController.kt:30` |
| **SshVpnService** | VPN 前台服务：建立 TUN、管理生命周期、通知栏、白名单应用 | `vpn/SshVpnService.kt:1` |
| **SshKeyManager** | SSH 密钥管理：Keystore 生成/导入 Ed25519/ECDSA/RSA、生物识别、JSch Identity 适配 | `data/remote/ssh/SshKeyManager.kt:1` |
| **AndroidKeyStoreIdentity** | JSch Identity 适配器：Keystore 签名、公钥导出、多算法支持 | `data/remote/ssh/AndroidKeyStoreIdentity.kt:1` |

## 协议术语

| 术语 | 定义 |
|------|------|
| **SOCKS5 CONNECT (0x01)** | TCP 连接请求：建立到目标的 TCP 隧道 |
| **SOCKS5 UDP ASSOCIATE (0x03)** | UDP 关联请求：建立 UDP relay 端口 |
| **ChannelDirectTCPIP** | JSch `direct-tcpip` 通道：SSH 动态端口转发核心 |
| **TUN/TAP** | 虚拟网络接口：VPN 流量拦截入口 |
| **IPv6 ULA** | Unique Local Address (fd00::/8)：VPN 内部 IPv6 地址 |
| **DoH** | DNS over HTTPS：DNS 查询加密传输 |

## 架构术语

| 术语 | 定义 |
|------|------|
| **Module** | 单一职责的代码单元：PacketProcessor/Socks5ProxyServer/DnsInterceptor |
| **Interface/Seam** | TunnelChannel/SshChannelFactory/Socks5ProxyServer 等接口 |
| **Depth** | PacketProcessor 深度封装 IP/TCP/UDP 解析、校验和、TCP 状态机 |
| **Adapter** | AndroidKeyStoreIdentity 适配 JSch Identity；JschTunnelChannel 适配 TunnelChannel |
| **Leverage** | SshChannelFactory 抽象允许替换 SSH 实现；DnsTransport 模式切换 |
| **Locality** | VpnController 协调所有核心组件，数据流集中 |

---

