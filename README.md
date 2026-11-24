# SSHInjector

<div align="center">

**🌐 语言 / Languages：** [中文](README.md) · [English](README.en.md) · [Русский](README.ru.md)

</div>

<p align="center">
  <img src="docs/images/icon.png" width="128" height="128" alt="SSHInjector">
</p>

<div align="center">
  <img src="https://img.shields.io/badge/Android-14%2B-green.svg?style=flat-square&logo=android" alt="Android 14+">
  <img src="https://img.shields.io/badge/Kotlin-2.4.20-blue.svg?style=flat-square&logo=kotlin" alt="Kotlin 2.4.20">
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
| **一键服务端配置（向导）** | 服务器列表页「+」→「一键服务器配置」：自动创建 `sshproxy` 专用账号、chroot 隔离、部署公钥、加固 sshd（支持 OpenSSH / dropbear / OpenWrt） |
| **SSH 密钥认证** | 向导默认生成 ECDSA P-256；支持 P-384 / RSA 4096 / Ed25519 导入。私钥存于 Android Keystore，生物识别解锁，可导出公钥 |
| **SOCKS5 代理** | TCP CONNECT 代理，支持 IPv4/IPv6/域名。**不含 UDP 中继**（见下方说明） |
| **白名单模式** | `VpnService.addAllowedApplication()` - 仅选中应用走代理，其他直连 |
| **双栈网络** | 同时支持 IPv4 和 IPv6，TUN 接口分配双栈地址 |
| **远端 DNS 解析** | 拦截 UDP:53，REMOTE/WHITELIST 模式回假 IP（`198.18.x.x` / `fd00::x`），域名留到 SSH 远端解析；SYSTEM / 域名分流未命中用受保护 socket 本地解析，防泄露 |
| **域名访问统计** | 本会话域名 / 连接数 / 上下行 / 应用归属只读统计；可把域名送进「待确认建议」再复核应用；支持本地/远端真实 IP 解析与 CSV 导出 |
| **连接统计** | 连接状态、流量、连接时长（进程内实时统计） |
| **自动重连** | 网络切换 (WiFi↔5G) 自动重连（2s 去抖）；SSH 会话级线性退避重连 |
| **状态检测** | 每 15s 端到端探测 + 分阶段健康（TUN/DNS/转发）；失败按步骤归因：连接异常/连接失败 · <步骤> |
| **应用内日志** | 日志页可复制或导出到 `Download/`；含数据面快照与「故障归因」汇总，无 adb 也能排障 |
| **Material 3 UI** | 仪表盘、服务器管理、白名单、域名统计、日志、设置、密钥管理 |

> **UDP 说明**：本应用是 TCP SOCKS5 代理，不实现 UDP ASSOCIATE。DNS（UDP:53）被拦截并由应用自己处理；其余 UDP（QUIC/游戏等）进入 TUN 后会被丢弃并计数（快照里显示「UDP 丢弃 N 个」）。QUIC 不因 ICMP 错误回退 TCP，因此这类应用会自行重试退避，表现为"卡住"。

---

## 🏗 架构设计

```
┌────────────────────────────────────────────────────────────────────┐
│                           Android 14+                             │
├────────────────────────────────────────────────────────────────────┤
│  UI Layer (Compose)                                               │
│  ├── DashboardScreen    ← 实时状态/连接控制/服务器快捷操作         │
│  ├── ServerListScreen   ← 服务器增删改查                          │
│  ├── ServerWizardScreen ← 一键配置向导：密钥生成 + 脚本部署        │
│  ├── WhitelistScreen    ← 应用列表搜索/筛选/勾选                  │
│  ├── DomainStatsScreen  ← 域名统计/名单建议复核/CSV 导出          │
│  ├── LogScreen          ← 应用内日志/快照/导出                    │
│  ├── SettingsScreen     ← DNS 模式/语言/生物识别                  │
│  └── KeyManagerScreen   ← ECDSA 生成/导入/复制公钥                │
├────────────────────────────────────────────────────────────────────┤
│  Domain Layer                                                     │
│  ├── VpnController       ← VPN 生命周期/状态机/统计聚合/日志出口  │
│  ├── ServerRepository    ← 服务器/白名单 CRUD                     │
│  ├── DomainStatsStore    ← 域名统计聚合 (会话级, 上限 20000)      │
│  ├── ConnectivityProber  ← 端到端探测/出口 IP 回显                │
│  └── StageHealthEvaluator← 分阶段健康归因 (TUN/DNS/转发)          │
├────────────────────────────────────────────────────────────────────┤
│  Data Layer                                                       │
│  ├── Room Database       ← Server/WhitelistApp                    │
│  ├── DataStore           ← 偏好设置/Keystore 别名映射             │
│  ├── SshKeyManager       ← Android Keystore (密钥不可导出)        │
│  ├── ServerProvisioner   ← 一键配置编排 (脚本上传 + SHA-256 校验) │
│  ├── JschSshClient       ← SSH 会话池/隧道/心跳/重连              │
│  ├── TunnelPlugin        ← 隧道插件 (当前仅 socks5)               │
│  ├── SshIoDispatcher     ← 阻塞 SSH IO 专用线程池 (上限 128)      │
│  ├── Socks5ProxyServer   ← 本地 SOCKS5 服务端 (NIO Selector)      │
│  ├── PacketProcessor     ← IP/TCP/UDP 解析/五元组/转发            │
│  ├── TunWriter/TunSink   ← TUN 单写者队列 (控制包非阻塞)          │
│  ├── DnsInterceptor      ← DNS 拦截/假 IP/远端解析/缓存           │
│  └── AppMetrics          ← 日志故障归因计数                       │
├────────────────────────────────────────────────────────────────────┤
│  System Layer                                                     │
│  ├── SshVpnService       ← VpnService (TUN/白名单/数据包循环)     │
│  └── BootReceiver        ← 开机自启                               │
└────────────────────────────────────────────────────────────────────┘
```

---

## 🔧 核心技术栈

| 分类 | 技术 |
|------|------|
| 语言/UI | Kotlin 2.4.20 + Jetpack Compose (Material 3) |
| 架构 | MVVM + Clean Architecture + Repository 模式 |
| DI | Hilt 2.60.1 (KSP) |
| 数据库 | Room 2.8.4 (KSP) |
| 偏好设置 | DataStore Preferences (Flow) |
| SSH 客户端 | mwiede/jsch:2.28.7 (维护活跃，支持 Ed25519) |
| DNS 解析 | dnsjava 3.6.5 |
| 网络 I/O | Java NIO Selector + Kotlinx Coroutines |
| 密钥存储 | Android Keystore + BiometricPrompt（密钥不可导出） |
| 协程 | Kotlinx Coroutines 1.10.1 + Flow |
| 导航 | Navigation Compose 2.8.5 |

---

## 🚀 快速开始

### 前置要求

- Android 14 (API 34) 或更高
- 一台服务器：主流 Linux 或 **OpenWrt**，运行 OpenSSH 或 dropbear；一键配置需要 **root 或 sudo** 权限（免密 sudo 或密码 sudo 均可）
- OpenSSH 需 `AllowTcpForwarding yes`（默认开启；dropbear 默认允许 TCP 转发）

### 服务端准备：一键配置向导（推荐）

> **入口（最容易找错的地方）**：首页的「+」是**手动添加服务器**。一键配置在
> **设置 → 服务器管理 → 服务器列表页右上角「+」→「一键服务器配置」**（下拉菜单第一项，第二项是「手动添加」）。

向导共 4 步：

1. **服务器**：填名称、主机、SSH 端口（默认 22）
2. **登录**：填服务器上有 **root/sudo 权限**的用户名与密码；该凭证仅用于本次远程配置，**不入库**
3. **配置**：App 先自动生成 ECDSA P-256 密钥对（若设置里开启「生物识别解锁」，这把密钥也受保护），随后依次执行：
   - 检测权限（root → 免密 sudo → 密码 sudo，密码经 stdin 传递，不写日志）
   - 上传配置脚本并在远端校验 SHA-256（不一致立即中止，防篡改）
   - 上传公钥（经 stdin，不拼 shell 参数）
   - 执行脚本并验证配置
4. **完成**：自动保存服务器配置，登录用户名固定为隧道账号 `sshproxy`，密钥指向向导生成的那把

脚本会自动完成：

- 创建专用隧道账号 `sshproxy`：`nologin` + 密码锁定，无法登录 shell
- chroot 隔离，仅暴露最小化文件系统（`ChrootDirectory`；上级目录必须为 root 拥有）
- 仅公钥认证（`PasswordAuthentication no`），`authorized_keys` 加 immutable 锁（`chattr +i`）
- sshd 追加 `Match User sshproxy` 加固块；修改前备份，`sshd -t` 通过才重载
- OpenWrt/dropbear：检测到 dropbear 走基础兼容模式（建号 + 仅公钥，不改全局 dropbear 配置）；安装 `openssh-server` 后自动走完整加固

结果说明：无 root/sudo → 显示「仅本机配置」（服务器仍可保存，需手动部署公钥）；脚本哈希不符 → 明确报篡改并中止；其它失败 → 指出失败步骤。**手动配置**与回滚步骤见 **[docs/server-setup.md](docs/server-setup.md)**。

### 编译安装

```bash
# 克隆项目
git clone https://github.com/tongtf/sshinjector.git
cd sshinjector

# 需要 JDK 17（默认 JDK 25 构建失败）
export JAVA_HOME=/path/to/jdk-17

# 编译 Debug APK（按 ABI 拆分；产物名含版本号，引用时用通配符别写死）
./gradlew assembleDebug
#   app/build/outputs/apk/debug/sshinjector-<版本>-<abi>-debug.apk

# 编译 Release APK
./gradlew assembleRelease
#   app/build/outputs/apk/release/sshinjector-<版本>-<abi>.apk
#   ABI: arm64-v8a / armeabi-v7a（Android 14+ 真机无 x86/x86_64）

# 编译 Release AAB
./gradlew bundleRelease
#   app/build/outputs/bundle/release/sshinjector-<版本>-release.aab

# 签名统一走仓库根 keystore.properties（gitignored）或 KEYSTORE_* 环境变量；
# 两者都缺失时 debug/release 均回退本机 AGP debug key 并打印告警。
# CI 的 Release 构建强制要求共享 keystore，并在构建后用 apksigner 校验签名指纹。
```

---

## 📖 使用指南

> 完整文档见 **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)**（安装、配置、故障排查、FAQ）；**[docs/README.md](docs/README.md)** 区分活文档与历史记录
> 服务端逐条配置命令与回滚见 **[docs/server-setup.md](docs/server-setup.md)**。
> 架构/建连/TCP/一键配置等流程图见 **[docs/diagrams/index.html](docs/diagrams/index.html)**。

### 1. 添加服务器

- **一键配置（推荐）**：设置 → 服务器管理 → 右上角「+」→「一键服务器配置」。按 4 步向导填服务器信息与 root/sudo 凭证，自动生成本地密钥并部署服务端（详见上文「服务端准备」）。
- **手动添加**：首页「+」或服务器列表「+」→「手动添加」。填名称/主机/端口/用户名，再单独生成或导入密钥，把公钥部署到服务器 `~/.ssh/authorized_keys`。
- 两种方式保存在同一服务器列表；一键配置的登录用户名自动设为 `sshproxy`。

### 2. 配置白名单

1. 进入「设置 → 白名单」页面
2. 搜索/浏览已安装应用，可用「全部 / 已选 / 系统应用」筛选
3. 勾选需要走代理的应用（仅勾选的应用流量进 TUN；非白名单模式下所有应用都走隧道）

### 3. 连接 VPN

1. 主界面点击大按钮「连接」
2. 首次会请求 VPN 权限，允许即可
3. 连接成功显示：本机 IP / 出口 IP / 连接状态；状态来自端到端探测与分阶段健康，不只是「连上了」
4. 通知栏常驻显示状态，可点击断开

### 4. 设置

| 设置项 | 说明 |
|--------|------|
| DNS 模式 | 远端解析（默认，防泄露）/ 本地直连 / 白名单 / 域名分流；白名单为空时自动退化为本地直连，避免假 IP 无路由 |
| 域名列表 | 配置域名分流规则的域名列表 |
| 域名访问统计 | 本会话域名连接/流量/应用归属；可把域名送进广告拦截或域名名单的待确认建议，复核后即时生效 |
| MTU / 保活 | 可覆盖服务器设置；留空 =「跟随服务器设置」。保活填 0 表示关闭心跳 |
| 连通性探测地址 | 留空使用默认 NCSI 端点；换节点后探测持续失败时可改自建 204 端点 |
| 语言 | 中文 / English / Русский / 跟随系统 |
| 生物识别 | 开启后解锁私钥需指纹/面容 |

### 5. 域名访问统计与排障

- 「设置 → 域名访问统计」：按域名查看连接数、上下行、最近时间与应用归属；支持 CSV 导出到 `Download/`
- 每行「广告 / 代理」chip 只把域名加入**待确认建议**，复核后才能应用；应用后可用 Snackbar 撤销
- 「设置 → 日志」：可复制或导出应用内日志（默认隐藏 DEBUG）。没有 adb 时，日志末尾的「数据面快照」与「故障归因」是最主要的判读依据

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
- 算法白名单：KEX 含 ECDH（nistp256/384/521）+ diffie-hellman-group14+，主机密钥支持 ECDSA P-256+ / RSA / Ed25519（需显式配置），已移除 `ssh-rsa`。Android 不处理 JSch 的 MR-JAR，curve25519 类会被静默过滤，因此 ECDH 是连 OpenSSH 10 的必需项
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
│   │   │   │   ├── vpn/            # 核心 VPN 组件 (Socks5/TCP/UDP/DNS/TunWriter/域名统计)
│   │   │   │   └── obs/            # AppMetrics 日志故障归因
│   │   │   ├── di/                 # Hilt Modules
│   │   │   ├── ui/
│   │   │   │   ├── screen/         # 页面 (dashboard/server/whitelist/settings/domainstats/log/keymanager)
│   │   │   │   ├── component/      # 通用组件
│   │   │   │   └── theme/          # Material3 主题
│   │   │   └── vpn/                # SshVpnService + BootReceiver
│   │   ├── res/                    # 资源文件
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   ├── README.md                 # 文档索引：活文档 vs 历史记录
│   ├── server-setup.md           # 服务端配置/回滚指南
│   ├── USER_GUIDE.md             # 使用指南
│   ├── specs/                    # 设计/审计/施工图 (多为时点历史记录)
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
# 需要 JDK 17，详见 AGENTS.md 的构建环境说明
# 单元测试
./gradlew testDebugUnitTest

# 快速编译检查
./gradlew assembleDebug

# 代码规范检查（CI 分开跑；detekt 与 lint 同一次调用会 OOM）
./gradlew ktlintCheck
./gradlew detekt
./gradlew lint

# androidTest 只在有设备的机器上编译/运行，CI 不跑
./gradlew connectedDebugAndroidTest
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

- [mwiede/jsch](https://github.com/mwiede/jsch) - 活跃维护的 JSch 分支，支持 Ed25519
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