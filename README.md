# SSHInjector

<div align="center">
  <img src="https://img.shields.io/badge/Android-14%2B-green.svg?style=flat-square&logo=android" alt="Android 14+">
  <img src="https://img.shields.io/badge/Kotlin-1.9.24-blue.svg?style=flat-square&logo=kotlin" alt="Kotlin 1.9.24">
  <img src="https://img.shields.io/badge/Compose-Material3-orange.svg?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/SSH-Ed25519-red.svg?style=flat-square" alt="SSH Ed25519">
</div>

<div align="center">
  <h3>🔐 Android 14+ SSH SOCKS5 代理应用</h3>
  <p>通过 SSH 动态端口转发 (<code>ssh -D</code>) 建立加密隧道，利用 VpnService 白名单机制，仅让选定应用流量走代理</p>
</div>

---

## ✨ 核心功能

| 功能 | 说明 |
|------|------|
| **SSH 密钥认证** | Ed25519 硬件加密存储，生物识别解锁，支持应用内生成/导入/导出 |
| **SOCKS5 代理** | 完整 RFC 1928 实现：TCP CONNECT + UDP ASSOCIATE，支持 IPv4/IPv6/域名 |
| **白名单模式** | `VpnService.addAllowedApplication()` - 仅选中应用走代理，其他直连 |
| **双栈网络** | 同时支持 IPv4 和 IPv6，TUN 接口分配双栈地址 |
| **远端 DNS 解析** | 拦截 UDP:53，通过 SSH 隧道 TCP 发送到远程 DNS (8.8.8.8/1.1.1.1)，防泄露 |
| **HTTP/3 支持** | SOCKS5 UDP ASSOCIATE 转发 QUIC 流量 |
| **实时统计** | 上传/下载速度、累计流量、连接时长、应用级流量排行 |
| **自动重连** | 网络切换 (WiFi↔5G) 无感重连，指数退避策略，开机自启 |
| **Material 3 UI** | 服务器管理、白名单选择、仪表盘、设置、密钥管理 |

---

## 🏗 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                      Android 14+                            │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Compose)                                         │
│  ├── DashboardScreen    ← 实时流量/连接状态/快捷操作         │
│  ├── ServerListScreen   ← 服务器增删改查/测试连接/密钥管理   │
│  ├── WhitelistScreen    ← 应用列表搜索/分组/全选/预设模式    │
│  ├── SettingsScreen     ← MTU/保活/DNS/IPv6/主题/生物识别   │
│  └── KeyManagerScreen   ← Ed25519 生成/导入/导出/二维码分享  │
├─────────────────────────────────────────────────────────────┤
│  Domain Layer (UseCases)                                    │
│  ├── VpnController       ← VPN 生命周期/状态机/统计聚合      │
│  ├── ServerRepository    ← 服务器/白名单/会话/流量 CRUD      │
│  └── KeyManager          ← 密钥生成/导入/签名/公钥导出       │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  ├── Room Database       ← Server/Whitelist/Session/Traffic │
│  ├── DataStore           ← 偏好设置/Keystore 别名映射        │
│  ├── SshKeyManager       ← Android Keystore (硬件加密)       │
│  ├── JschSshClient       ← SSH 连接/隧道/心跳/重连           │
│  ├── SshTunnelManager    ← 本地 SOCKS5 ↔ SSH 通道桥接        │
│  ├── Socks5ProxyServer   ← RFC 1928 服务端 (NIO Selector)    │
│  ├── PacketProcessor     ← IP/TCP/UDP 解析/五元组/转发       │
│  └── DnsInterceptor      ← DNS 查询拦截/远端解析/缓存        │
├─────────────────────────────────────────────────────────────┤
│  System Layer                                               │
│  ├── SshVpnService       ← VpnService (TUN/白名单/数据包循环)│
│  └── BootReceiver        ← 开机自启/网络变化监听             │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 核心技术栈

| 分类 | 技术 |
|------|------|
| 语言/UI | Kotlin 1.9.24 + Jetpack Compose (Material 3) |
| 架构 | MVVM + Clean Architecture + Repository 模式 |
| DI | Hilt (KSP) |
| 数据库 | Room 2.6.1 (KSP) |
| 偏好设置 | DataStore Preferences (RxJava3 Flow) |
| SSH 客户端 | mwiede/jsch:0.2.14 (维护活跃，支持 Ed25519) |
| DNS 解析 | dnsjava 3.5.7 |
| 网络 I/O | Java NIO Selector + Kotlinx Coroutines |
| 密钥存储 | Android Keystore (硬件加密) + BiometricPrompt |
| 协程 | Kotlinx Coroutines 1.8.1 + Flow |
| 导航 | Navigation Compose 2.7.7 |
| 图片加载 | Coil 2.6.0 |

---

## 🚀 快速开始

### 前置要求

- Android 14 (API 34) 或更高
- 一台支持 SSH 密钥认证的 VPS (OpenSSH 服务端)
- 服务端配置：`AllowTcpForwarding yes` (默认开启)

### 服务端准备

```bash
# 1. 生成 Ed25519 密钥对 (在手机应用内生成更安全)
ssh-keygen -t ed25519 -C "your-phone@android"

# 2. 将公钥添加到服务器
ssh-copy-id -i ~/.ssh/id_ed25519.pub user@your-vps

# 3. 验证 SSH 配置
grep -E '^(AllowTcpForwarding|GatewayPorts|PermitTunnel)' /etc/ssh/sshd_config
# 确保 AllowTcpForwarding yes
```

### 编译安装

```bash
# 克隆项目
git clone https://github.com/yourusername/SSHInjector.git
cd SSHInjector

# 配置签名 (本地 properties 或环境变量)
echo "KEYSTORE_PATH=keystore.jks
KEYSTORE_PASSWORD=your_store_pass
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_pass" > local.properties

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release AAB (需配置签名)
./gradlew bundleRelease
```

---

## 📖 使用指南

### 1. 添加服务器

1. 点击「+」添加服务器
2. 填写：名称、主机、端口(默认22)、用户名
3. 点击「生成密钥」创建 Ed25519 密钥对 (需生物识别授权)
4. 复制公钥 → 粘贴到 VPS `~/.ssh/authorized_keys`
5. 点击「测试连接」验证

### 2. 配置白名单

1. 进入「白名单」页面
2. 搜索/浏览已安装应用
3. 勾选需要走代理的应用 (仅勾选的应用流量会被拦截)
4. 支持预设：仅浏览器 / 社交应用 / 自定义

### 3. 连接 VPN

1. 主界面点击大按钮「连接」
2. 首次会请求 VPN 权限，允许即可
3. 连接成功显示：本地 IP / 远程 IP / 实时流量图
4. 通知栏常驻显示状态，支持断开/暂停

### 4. 高级设置

| 设置项 | 推荐值 | 说明 |
|--------|--------|------|
| MTU | 1500 | 根据网络调整，低 MTU 可解决断流 |
| 保活间隔 | 30s | SSH 心跳，防服务端断开 |
| DNS 模式 | 远端解析 | 防 DNS 泄露，需服务端可访问 8.8.8.8 |
| IPv6 | 开启 | 双栈网络，访问 IPv6 站点 |
| 生物识别 | 开启 | 保护私钥，解锁需指纹/面容 |

---

## 🔒 安全模型

```
私钥生成 → Android Keystore (TEE/StrongBox) → 硬件级隔离
     ↓
私钥签名 → BiometricPrompt (指纹/面容/密码) → 用户授权
     ↓
SSH 认证 → JSch Identity 接口桥接 → Keystore 签名
     ↓
公钥导出 → OpenSSH 格式 → 手动部署到服务器
```

**关键点**：
- 私钥**永不离开** Keystore，不可导出
- 首次连接 TOFU (Trust On First Use) + Host Key 指纹缓存
- 支持 `StrictHostKeyChecking=ask` 手动验证
- 通知栏/前台服务类型 `FOREGROUND_SERVICE_SPECIAL_USE`

---

## 📂 项目结构

```
SSHInjector/
├── app/
│   ├── src/main/
│   │   ├── java/com/sshinjector/
│   │   │   ├── data/
│   │   │   │   ├── local/          # Room + DataStore
│   │   │   │   ├── remote/ssh/     # JSch 封装 + Keystore
│   │   │   │   └── repository/     # Repository 实现
│   │   │   ├── domain/
│   │   │   │   ├── model/          # 领域模型
│   │   │   │   ├── usecase/        # VpnController/Repository
│   │   │   │   └── vpn/            # 核心 VPN 组件
│   │   │   ├── di/                 # Hilt Modules/EntryPoints
│   │   │   ├── ui/
│   │   │   │   ├── screen/         # 5 大页面
│   │   │   │   ├── component/      # 通用组件
│   │   │   │   └── theme/          # Material3 主题
│   │   │   └── vpn/                # SshVpnService + BootReceiver
│   │   ├── res/                    # 资源文件
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   └── todo-plan.md               # 详细开发计划
├── .github/workflows/ci.yml       # CI/CD
├── detekt.yml                     # 代码规范
├── build.gradle.kts / settings.gradle.kts
└── README.md
```

---

## 🧪 测试

```bash
# 单元测试
./gradlew testDebugUnitTest

# 集成测试 (需连接设备)
./gradlew connectedAndroidTest

# 代码规范检查
./gradlew ktlintCheck detekt lint
```

---

## 📦 发布清单

- [ ] 签名配置 (keystore.jks + 环境变量)
- [ ] 版本号更新 (`versionCode` / `versionName`)
- [ ] 更新 `CHANGELOG.md`
- [ ] 运行完整 CI 流水线
- [ ] 生成 Release AAB 上传 Play Console / GitHub Releases / F-Droid
- [ ] 隐私政策链接 (Play Console 要求)

---

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支: `git checkout -b feature/amazing-feature`
3. 提交变更: `git commit -m 'Add amazing feature'`
4. 推送分支: `git push origin feature/amazing-feature`
5. 发起 Pull Request

### 代码规范

- 遵循 [Kotlin 编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- `ktlint` + `detekt` + `Android Lint` 全通过
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)

---

## 📄 许可证

```
MIT License

Copyright (c) 2024 SSHInjector Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 🙏 致谢

- [mwiede/jsch](https://github.com/mwiede/jsch) - 活跃维护的 JSch 分支，支持 Ed25519
- [dnsjava](https://github.com/dnsjava/dnsjava) - Java DNS 协议实现
- [Android VPN Toy](https://github.com/android/networking-samples) - VPNService 参考实现
- [SocksDroid](https://github.com/6b6b6b/socksdroid) - SOCKS5 代理参考

---

## ⚠️ 免责声明

本项目仅供学习研究和个人隐私保护使用。使用者需遵守当地法律法规，不得用于非法用途。作者不对因使用本软件导致的任何后果负责。

---

<div align="center">
  <sub>Built with ❤️ for Android 14+ | <a href="https://github.com/yourusername/SSHInjector">GitHub</a> | <a href="https://github.com/yourusername/SSHInjector/issues">Issues</a></sub>
</div>