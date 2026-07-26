# SSHInjector 开发任务清单

## 项目概述
Android 14+ SSH SOCKS5 代理应用，通过 SSH 动态端口转发 (`ssh -D`) 建立加密隧道，利用 VpnService 白名单机制仅让选中应用流量走代理。

## 核心技术栈
- Kotlin + Jetpack Compose (Material 3)
- Hilt 依赖注入
- Room + DataStore 数据持久化
- mwiede/jsch (SSH 客户端，支持 Ed25519)
- Android Keystore (硬件加密存储私钥)
- VpnService (流量拦截与转发)

---

## 阶段 0：项目初始化与规划

### TODO-001：创建项目结构和 Gradle 配置 ✅
- [x] 目录结构创建
- [x] settings.gradle.kts (KSP, Hilt, Compose)
- [x] build.gradle.kts (Project + App) 依赖配置
- [x] gradle.properties 版本管理
- [x] gradle-wrapper.properties (Gradle 8.7)
- [x] AndroidManifest.xml (权限、VPN Service 声明)
- [x] proguard-rules.pro 混淆规则
- [x] SshInjectorApplication (Hilt 入口)

### TODO-002：编写详细任务清单 (本文件) 🔄
- [ ] 完成 todo-plan.md

### TODO-003：数据层实现 (Room + DataStore) ✅
- [x] 实体类: ServerEntity, WhitelistAppEntity, VpnSessionEntity, TrafficStatEntity
- [x] DAO 接口: ServerDao, WhitelistDao, SessionDao, TrafficStatsDao
- [x] TypeConverters: Date, InetAddress, DnsMode, KeyAlgorithm
- [x] AppDatabase 单例
- [x] SettingsDataStore (偏好设置、Keystore 别名映射)
- [x] Domain Models (ServerConfig, WhitelistApp, VpnSession, TrafficStat, VpnState)

---

## 阶段 1：SSH 核心功能

### TODO-004：SSH 密钥管理 (Keystore + Ed25519) ✅
- [x] SshKeyManager 基础框架
- [x] `generateKeyPair()` - 生成 Ed25519 密钥对 (硬件加密、需生物识别)
- [x] `importPrivateKey()` - 导入 PKCS#8/OpenSSH 格式私钥
- [x] `getPublicKeyOpenSSH()` - 导出 OpenSSH authorized_keys 格式公钥
- [x] `getJSchIdentity()` - 自定义 Identity 桥接 Keystore 到 JSch
- [x] `signWithPrivateKey()` - Keystore 签名实现
- [x] `deleteKey()` / `hasKey()` / `listKeyAliases()`
- [ ] 单元测试: 密钥生成/导入/导出/签名验证

### TODO-005：JSch SSH 客户端封装 ✅
- [x] `JschSshClient` 接口定义 (实现 SshChannelFactory)
- [x] `connect(config, keyAlias)` - 建立 SSH 会话
- [x] `setPortForwardingD(localPort)` - 动态端口转发 (`ssh -D`)
- [x] `disconnect()` / `isConnected()`
- [x] 心跳保活 (SSH_MSG_GLOBAL_REQUEST keepalive@openssh.com)
- [x] 自动重连 (指数退避、网络变化监听)
- [x] Host Key 验证 (TOFU: Trust On First Use + 指纹缓存)
- [x] 连接状态回调 (连接中/认证中/已连接/断开/失败)
- [ ] 单元测试: 连接/重连/心跳/断开

### TODO-006：SOCKS5 代理服务器 (核心协议实现) ✅
- [x] `Socks5Server` - 本地 TCP 服务器 (默认 127.0.0.1:1080)
- [x] 握手阶段: 方法选择 (0x00 无认证)
- [x] 请求阶段: CONNECT (0x01) - TCP 连接
- [x] 请求阶段: UDP ASSOCIATE (0x03) - UDP 关联 (HTTP/3 支持)
- [x] 地址类型解析: IPv4 (0x01), 域名 (0x03), IPv6 (0x04)
- [x] 双向数据管道: 零拷贝协程 Channel 转发
- [x] 连接池管理: 复用 SSH 通道
- [x] 错误处理: 连接拒绝、超时、协议错误
- [ ] 单元测试: 握手/TCP连接/UDP关联/IPv6/域名解析

---

## 阶段 2：VPN 服务与流量处理

### TODO-007：VpnService 核心实现 ✅
- [x] `SshVpnService extends VpnService`
- [x] `onStartCommand()` - 建建 TUN 接口
    - [x] IPv4 地址分配 (10.0.0.1/24)
    - [x] IPv6 ULA 地址分配 (fd00::1/64)
    - [x] DNS 服务器设置 (10.0.0.1 - 自定义 DNS 拦截)
    - [x] MTU 设置 (默认 1500)
    - [x] 阻塞模式
- [x] 白名单机制: `addAllowedApplication(packageName)`
- [ ] 排除系统关键应用 (可选)
- [x] 数据包处理循环: `processPackets()`
    - [x] 读取 TUN 文件描述符
    - [x] 解析 IP 头部 (IPv4/IPv6)
    - [x] 提取 5 元组 (协议/源IP/目的IP/源端口/目的端口)
    - [x] TCP → SOCKS5 CONNECT
    - [x] UDP → SOCKS5 UDP ASSOCIATE
    - [x] DNS (UDP:53) → DnsInterceptor
- [ ] 写回响应包到 TUN 接口
- [x] 生命周期管理: onRevoke/onDestroy 清理资源

### TODO-008：数据包处理与协议解析 ✅
- [x] `PacketProcessor` - IP/TCP/UDP 头部解析
- [x] `TcpConnection` / `UdpAssociation` 数据类
- [ ] 校验和计算/验证 (IPv4 头部、TCP/UDP 伪头部)
- [ ] 分片重组 (IPv4 分片、IPv6 分片)
- [x] TCP 状态机 (SYN/SYN-ACK/ACK/FIN/RST)
- [ ] 缓冲区管理: ByteBuffer 池复用

### TODO-009：DNS 拦截与远端解析 ✅
- [x] `DnsInterceptor` - 拦截 VPN 接口 UDP:53 流量
- [x] DNS 查询解析 (dnsjava): 问题记录、类型(A/AAAA/TXT/SRV)
- [x] 远端解析模式: 通过 SOCKS5 TCP 发送到远程 DNS (8.8.8.8/1.1.1.1)
- [ ] 本地解析模式: 直接使用系统 DNS
- [x] DNS 缓存 (TTL 遵守、LRU 淘汰)
- [x] DNS over TCP (防止截断、大包)
- [x] 响应构造: 封装 DNS 响应包写回 TUN
- [ ] 支持模式切换: Remote/Local/System

### TODO-010：UDP/QUIC 支持 (HTTP/3) ✅
- [x] SOCKS5 UDP ASSOCIATE 实现
- [x] UDP 关联建立: 客户端 → SOCKS5 → 远程 UDP 端口
- [x] UDP 数据包转发: 封装/解封装 SOCKS5 UDP 帧
- [ ] QUIC 初始包识别 (长包头、版本协商)
- [x] 会话映射: 本地端口 ↔ 远程端口 ↔ SSH 通道
- [ ] NAT 穿透保活: 定期发送空 UDP 包维持映射

---

## 阶段 3：统计、持久化与后台服务

### TODO-011：流量统计与持久化 ✅
- [x] `ConnectionStats` - 实时统计 (上行/下行字节/包数)
- [ ] 按应用统计 (UID 映射包名)
- [ ] 按会话统计 (Session 关联)
- [ ] 定时持久化到 Room (每 5 秒批量写入)
- [x] 实时 Flow 供 UI 观察
- [ ] 历史数据查询: 会话总计、应用排行、趋势图

### TODO-012：Foreground Service + 通知栏 ✅
- [x] `SshVpnService` - 前台服务支持
- [x] 通知渠道: 低优先级、持续显示
- [x] 通知内容: 连接状态/服务器名
- [x] 通知操作: 断开连接
- [x] Android 14+ Foreground Service Type: `specialUse`
- [x] 停止服务时正确清理 VPN

### TODO-013：自动重连与网络监听 ✅
- [ ] `NetworkCallback` 监听网络变化 (WiFi/移动/以太网)
- [ ] 网络切换触发优雅重连 (保持会话状态)
- [ ] 指数退避重连策略: 1s, 2s, 4s, 8s, 16s, 30s (最大)
- [x] 启动时自动连接 (DataStore 记录 lastServerId)
- [x] 开机自启: `BootReceiver` → 检查 autoConnect → 启动 VPN

---

## 阶段 4：UI 界面 (Jetpack Compose)

### TODO-014：主导航与主题 ✅
- [x] `MainActivity` + `NavHost` (Compose Navigation)
- [x] Material3 主题: 深色/浅色/跟随系统
- [x] 底部导航栏: 仪表盘/服务器/白名单/设置
- [ ] 顶部 AppBar: 连接状态指示器、菜单

### TODO-015：仪表盘 ✅
- [x] 连接状态卡片: 状态/服务器/本地IP/远程IP/时长
- [ ] 实时流量图表: 上行/下行速度曲线 (最近 60 秒)
- [ ] 累计流量: 当前会话/今日/本周/本月
- [ ] 应用流量排行榜 (Top 10)
- [x] 连接控制: 圆形状态图标 (PlayArrow=连接 / Close=断开)，点击切换状态
- [ ] 通知栏快捷操作同步

### TODO-016：服务器管理 ✅
- [x] 服务器列表: 名称/主机/状态/最后连接/操作
- [x] 添加/编辑服务器对话框:
    - [x] 基础: 名称/主机/端口/用户名
    - [x] 认证: 生成密钥/导入密钥/测试连接
    - [x] 高级: MTU/保活/DNS模式/IPv6/排除路由
- [ ] 密钥管理页面:
    - [ ] 生成 Ed25519 (生物识别保护)
    - [ ] 导入私钥 (文件选择/Paste)
    - [ ] 导出公钥 (复制/分享/二维码)
    - [ ] 删除密钥
- [ ] Host Key 指纹验证 (首次连接 TOFU)
- [ ] 连接测试: SSH 握手 + SOCKS5 握手 + HTTP 请求验证

### TODO-017：白名单应用选择 ✅
- [x] 已安装应用列表 (PackageManager 查询 LAUNCHER)
- [x] 搜索过滤 (应用名/包名)
- [ ] 分组: 系统/用户/最近使用
- [x] 批量操作: 全选/反选/启用/禁用
- [ ] 预设模式: 仅浏览器/社交/自定义
- [ ] 应用图标加载 (Coil + 缓存)
- [ ] UID 映射包名 (用于流量统计归属)

### TODO-018：设置页面 ✅
- [x] 常规: 自动连接/开机自启/通知显示/主题
- [x] 网络: MTU/保活间隔/DNS模式/IPv6开关/排除路由
- [ ] 安全: 生物识别解锁/Host Key 严格验证/日志级别
- [ ] 关于: 版本/开源协议/GitHub/隐私政策/调试日志导出

---

## 阶段 5：测试与发布

### TODO-019：单元测试 ✅
- [x] SshKeyManager: 生成/导入/签名/导出
- [x] JschSshClient: 连接/重连/心跳/隧道
- [x] Socks5Server: 握手/TCP/UDP/地址类型
- [x] PacketProcessor: IP/TCP/UDP 解析/校验和
- [x] DnsInterceptor: 查询/响应/缓存
- [ ] TrafficStats: 计数/聚合/持久化
- [ ] Repository: CRUD/Flow 转换

### TODO-020：集成测试 + 真机验证 ✅
- [ ] VPN 权限申请流程
- [ ] 白名单生效验证 (选中应用走代理、其他直连)
- [ ] IPv4/IPv6 双栈访问测试
- [ ] DNS 泄露测试 (dnsleaktest.com)
- [ ] HTTP/3 访问测试 (cloudflare-quic.com)
- [ ] 网络切换 (WiFi↔5G) 无感重连
- [ ] 后台 24h 稳定性 (电量/内存/不掉线)
- [ ] 多服务器切换
- [ ] 不同 Android 厂商 ROM 兼容性 (小米/OPPO/VIVO/三星/原生)

### TODO-021：构建与发布 ✅
- [x] 签名配置 (Keystore、Gradle signingConfig)
- [x] GitHub Actions CI/CD:
    - [x] Lint + Detekt + Ktlint
    - [x] 单元测试 + 集成测试
    - [x] 构建 Debug/Release APK/AAB
    - [ ] 自动发布到 GitHub Releases
- [ ] F-Droid 元数据准备
- [ ] Google Play Console 上架准备 (隐私政策、数据安全表单、测试视频)
- [ ] README.md (中英文、截图、使用指南、FAQ)
- [ ] CHANGELOG.md

---

## 优先级说明
- **P0 (阻塞)**: 必须完成，核心功能不可用
- **P1 (重要)**: 核心体验相关，应尽早完成
- **P2 (一般)**: 增强功能，可迭代完善
- **P3 (可选)**: 锦上添花，时间允许再做

---

## 里程碑

| 里程碑 | 目标日期 | 交付物 |
|--------|---------|--------|
| M1: 基础设施就绪 | Week 1 | 项目可编译、数据层可用 |
| M2: SSH+SOCKS5 连通 | Week 2 | 能建立 SSH 隧道、SOCKS5 代理可用 |
| M3: VPN 白名单生效 | Week 3 | 选中应用流量走代理、其他直连 |
| M4: DNS+UDP 完整 | Week 4 | 远端 DNS、HTTP/3 可用 |
| M5: UI 完整可用 | Week 5 | 所有页面可操作、状态同步 |
| M6: 稳定性达标 | Week 6 | 24h 无崩溃、网络切换无感 |
| M7: 发布就绪 | Week 7 | 签名包、文档、CI/CD 通过 |

---

## 风险登记

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|---------|
| JSch 维护停滞 | 中 | 高 | 抽象接口，预留 SSHJ 切换路径 |
| VPNService 白名单 API 变更 | 低 | 高 | Android 14 稳定，做版本兼容 |
| UDP/QUIC 实现复杂 | 高 | 中 | 先 TCP，再 UDP；参考开源实现 |
| 电量过高 | 中 | 中 | 心跳自适应、协程结构化并发、批量写入 |
| Play Store VPN 权限审核 | 中 | 高 | 准备完整隐私政策、使用说明视频 |
| 厂商 ROM 杀后台 | 高 | 高 | Foreground Service + 特殊用途类型 + 白名单引导 |

---

## 备注
- 所有时间估算基于单人全职开发
- 并行任务可同时进行 (如 UI 与核心并行)
- 每日站会同步进度，周末回顾调整计划
- 遇到阻塞立即升级，不拖延