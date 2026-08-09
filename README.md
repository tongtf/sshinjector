# SSHInjector

<div align="center">

**🌐 语言 / Languages：** [中文](README.md) · [English](README.en.md) · [Русский](README.ru.md)

</div>

<p align="center">
  <img src="docs/images/icon.png" width="128" height="128" alt="SSHInjector">
</p>

<div align="center">
  <img src="https://img.shields.io/badge/Android-14%2B-green.svg?style=flat-square&logo=android" alt="Android 14+">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-blue.svg?style=flat-square&logo=kotlin" alt="Kotlin 2.0.21">
  <img src="https://img.shields.io/badge/Compose-Material3-orange.svg?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/SSH-ECDSA-red.svg?style=flat-square" alt="SSH ECDSA">
</div>

<div align="center">
  <h3>🔐 Android 14+ SSH SOCKS5 代理应用</h3>
  <p>利用 VpnService 捕获流量，应用内 SOCKS5 服务端经 SSH 直连隧道 (<code>direct-tcpip</code>) 转发，仅让选定应用流量走代理</p>
</div>

---

## ✨ 核心功能

| 功能 | 说明 |
|------|------|
| **SSH 密钥认证** | ECDSA P-256/P-384 密钥存储于 Android Keystore，生物识别解锁，支持应用内生成/导入/复制公钥 |
| **SOCKS5 代理** | TCP CONNECT 代理，支持 IPv4/IPv6/域名（UDP ASSOCIATE 尚未支持） |
| **白名单模式** | `VpnService.addAllowedApplication()` - 仅选中应用走代理，其他直连 |
| **双栈网络** | 同时支持 IPv4 和 IPv6，TUN 接口分配双栈地址 |
| **远端 DNS 解析** | 拦截 UDP:53，通过 SSH 隧道 TCP 发送到远程 DNS (8.8.8.8/1.1.1.1)，防泄露 |
| **连接统计** | 连接状态、流量、连接时长（进程内实时统计） |
| **自动重连** | 网络切换 (WiFi↔5G) 自动重连（2s 去抖）；SSH 会话级线性退避重连 |
| **Material 3 UI** | 仪表盘、服务器管理、白名单、设置、密钥管理 |

---

## 🏗 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                      Android 14+                            │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Compose)                                         │
│  ├── DashboardScreen    ← 实时状态/连接控制/服务器快捷操作    │
│  ├── ServerListScreen   ← 服务器增删改查/一键配置向导        │
│  ├── WhitelistScreen    ← 应用列表搜索/全选/勾选              │
│  ├── SettingsScreen     ← DNS 模式/语言/生物识别             │
│  └── KeyManagerScreen   ← ECDSA P-256 生成/导入/复制公钥     │
├─────────────────────────────────────────────────────────────┤
│  Domain Layer (UseCases)                                    │
│  ├── VpnController       ← VPN 生命周期/状态机/统计聚合      │
│  ├── ServerRepository    ← 服务器/白名单 CRUD                │
│  └── KeyManager          ← 密钥生成/导入/签名/公钥导出       │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  ├── Room Database       ← Server/WhitelistApp              │
│  ├── DataStore           ← 偏好设置/Keystore 别名映射        │
│  ├── SshKeyManager       ← Android Keystore (密钥不可导出)   │
│  ├── JschSshClient       ← SSH 连接/隧道/心跳/重连           │
│  ├── TunnelManager       ← 本地 SOCKS5 ↔ SSH 通道桥接        │
│  ├── Socks5ProxyServer   ← SOCKS5 TCP 服务端 (NIO Selector)  │
│  ├── PacketProcessor     ← IP/TCP/UDP 解析/五元组/转发       │
│  └── DnsInterceptor      ← DNS 查询拦截/远端解析/缓存        │
├─────────────────────────────────────────────────────────────┤
│  System Layer                                               │
│  ├── SshVpnService       ← VpnService (TUN/白名单/数据包循环)│
│  └── BootReceiver        ← 开机自启                          │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 核心技术栈

| 分类 | 技术 |
|------|------|
| 语言/UI | Kotlin 2.0.21 + Jetpack Compose (Material 3) |
| 架构 | MVVM + Clean Architecture + Repository 模式 |
| DI | Hilt 2.53.1 (KSP) |
| 数据库 | Room 2.6.1 (KSP) |
| 偏好设置 | DataStore Preferences (Flow) |
| SSH 客户端 | mwiede/jsch:0.2.14 (维护活跃，支持 ECDSA P-256) |
| DNS 解析 | dnsjava 3.6.5 |
| 网络 I/O | Java NIO Selector + Kotlinx Coroutines |
| 密钥存储 | Android Keystore + BiometricPrompt（密钥不可导出） |
| 协程 | Kotlinx Coroutines 1.9.0 + Flow |
| 导航 | Navigation Compose 2.8.5 |

---

## 🚀 快速开始

### 前置要求

- Android 14 (API 34) 或更高
- 一台服务器：主流 Linux 或 **OpenWrt**，运行 OpenSSH 或 dropbear（一键配置需 root 或 sudo 权限）
- OpenSSH 需 `AllowTcpForwarding yes`（默认开启；dropbear 默认允许 TCP 转发）

### 服务端准备

**方式 A：App 内一键配置（推荐）**

在「添加服务器」向导中，输入你服务器上具有 **root 或 sudo** 权限的账号，App 会自动完成全部配置：

- 创建专用隧道账号 `sshproxy`：`nologin` + 密码锁定，无法登录 shell
- chroot 隔离，仅暴露最小化文件系统（`ChrootDirectory`）
- 仅公钥认证（`PasswordAuthentication no`），`authorized_keys` 加 immutable 锁（`chattr +i`）
- sshd 追加 `Match User sshproxy` 加固块；修改前备份，`sshd -t` 通过才重载

**方式 B：手动配置**

按 **[docs/server-setup.md](docs/server-setup.md)** 的步骤手动创建 `sshproxy` 账号、chroot 目录与 sshd 加固配置。

> **OpenWrt**：安装 `openssh-server` 获得完整加固（chroot + Match）；默认 dropbear 走基础兼容模式（创建 `sshproxy` 账号 + 仅公钥，无 chroot 隔离），且不修改全局 dropbear 配置。详见 [docs/server-setup.md](docs/server-setup.md)。

### 编译安装

```bash
# 克隆项目
git clone https://github.com/tongtf/sshinjector.git
cd sshinjector

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK (默认 debug keystore 签名)
./gradlew assembleRelease

# 编译 Release AAB (配置环境变量后使用自定义签名)
#   KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
./gradlew bundleRelease
```

---

## 📖 使用指南

> 完整文档见 **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)**（安装、配置、故障排查、FAQ）

### 1. 添加服务器

1. 点击「+」打开添加服务器向导
2. 填写：名称、主机、端口(默认22)、用户名/密码（root 或 sudo 权限）
3. 向导自动生成 ECDSA 密钥对并一键配置服务端（创建 `sshproxy` 专用账号 + sshd 加固）
4. 也可选择「仅生成密钥」：复制公钥 → 粘贴到 VPS `~/.ssh/authorized_keys`

### 2. 配置白名单

1. 进入「白名单」页面
2. 搜索/浏览已安装应用
3. 勾选需要走代理的应用 (仅勾选的应用流量会被拦截)

### 3. 连接 VPN

1. 主界面点击大按钮「连接」
2. 首次会请求 VPN 权限，允许即可
3. 连接成功显示：本地 IP / 远程 IP / 连接状态
4. 通知栏常驻显示状态，可点击断开

### 4. 设置

| 设置项 | 说明 |
|--------|------|
| DNS 模式 | 远端解析（默认，防泄露）/ 本地直连 / 白名单 / 域名分流 |
| 域名列表 | 配置域名分流规则的域名列表 |
| 语言 | 中文 / English / Русский / 跟随系统 |
| 生物识别 | 开启后解锁私钥需指纹/面容 |

---

## 🔒 安全模型

```
私钥生成 → Android Keystore → 系统级隔离
     ↓
私钥签名 → BiometricPrompt (指纹/面容) → 用户授权
     ↓
SSH 认证 → JSch Identity 接口桥接 → Keystore 签名
     ↓
公钥导出 → OpenSSH 格式 → 部署到服务器
```

**关键点**：
- 私钥**永不离开** Keystore，不可导出
- 服务器密码与导入的私钥均以 **AES-256-GCM 加密存储**（密钥托管于 Android Keystore），密文不离开设备；导入私钥支持口令保护，口令随密钥一并加密存储
- 服务器表单输入（主机名/IPv4/IPv6、端口、MTU、保活间隔）在保存前统一校验，非法值直接拦截
- 首次连接 TOFU (Trust On First Use) + Host Key 指纹缓存，指纹变化时拒绝连接（防中间人）
- 仅保留安全算法：curve25519 / diffie-hellman-group14+ 密钥交换，ed25519 / ECDSA P-256+ 主机密钥
- 通知栏/前台服务类型 `FOREGROUND_SERVICE_SPECIAL_USE`

---

## 📂 项目结构

```
SSHInjector/
├── app/
│   ├── src/main/
│   │   ├── java/cn/srv0/sshinjector/
│   │   │   ├── data/
│   │   │   │   ├── local/          # Room + DataStore
│   │   │   │   └── remote/         # ssh (JSch+Keystore) / tunnel / config (ServerProvisioner)
│   │   │   ├── domain/
│   │   │   │   ├── model/          # 领域模型
│   │   │   │   ├── usecase/        # VpnController/Repository
│   │   │   │   └── vpn/            # 核心 VPN 组件 (Socks5/TCP/UDP/DNS/隧道)
│   │   │   ├── di/                 # Hilt Modules
│   │   │   ├── ui/
│   │   │   │   ├── screen/         # 页面 (dashboard/server/whitelist/settings/keymanager)
│   │   │   │   ├── component/      # 通用组件
│   │   │   │   └── theme/          # Material3 主题
│   │   │   └── vpn/                # SshVpnService + BootReceiver
│   │   ├── res/                    # 资源文件
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   ├── server-setup.md           # 服务端配置指南
│   ├── USER_GUIDE.md             # 使用指南
│   └── diagrams/                 # 架构/流程/状态机/时序图集
├── .github/workflows/ci.yml       # CI/CD
├── detekt.yml                     # 代码规范
├── cliff.toml                     # Release 变更日志生成配置
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

- [ ] 版本号更新 (`versionCode` / `versionName`)
- [ ] 运行完整 CI 流水线（lint / detekt / ktlint / test）
- [ ] 打 tag 并推送 `v*`，CI 自动构建 Release 产物并生成分组变更日志（git-cliff）
- [ ] 生成 Release AAB 上传 Play Console / GitHub Releases / F-Droid

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

- [mwiede/jsch](https://github.com/mwiede/jsch) - 活跃维护的 JSch 分支，支持 ECDSA P-256
- [dnsjava](https://github.com/dnsjava/dnsjava) - Java DNS 协议实现
- [Android VPN Toy](https://github.com/android/networking-samples) - VPNService 参考实现
- [SocksDroid](https://github.com/6b6b6b/socksdroid) - SOCKS5 代理参考

---

## ⚠️ 免责声明

本项目仅供学习研究和个人隐私保护使用。使用者需遵守当地法律法规，不得用于非法用途。作者不对因使用本软件导致的任何后果负责。

---

<div align="center">
  <sub>Built with ❤️ for Android 14+ | <a href="https://github.com/tongtf/sshinjector">GitHub</a> | <a href="https://github.com/tongtf/sshinjector/issues">Issues</a></sub>
</div>