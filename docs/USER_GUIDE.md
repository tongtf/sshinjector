# SSHInjector 使用文档

> 完整的安装、配置与使用指南。快速入门见 [README](../README.md)。

## 目录

1. [安装](#1-安装)
2. [快速开始](#2-快速开始)
3. [服务器管理](#3-服务器管理)
4. [密钥管理](#4-密钥管理)
5. [白名单配置](#5-白名单配置)
6. [连接与状态](#6-连接与状态)
7. [设置详解](#7-设置详解)
8. [故障排查](#8-故障排查)
9. [安全建议](#9-安全建议)
10. [FAQ](#10-faq)

---

## 1. 安装

### 要求

- Android 14 (API 34) 或更高
- 一台支持 SSH 密钥认证的 VPS（OpenSSH 服务端）

### 安装 APK

从 [GitHub Releases](https://github.com/tongtf/sshinjector/releases) 下载最新 APK 并安装。

> Release APK 使用 debug keystore 签名，若之前安装过其他版本，请先卸载。

### 从源码编译

```bash
# 需要 JDK 17（默认 JDK 25 构建失败）
export JAVA_HOME=/path/to/jdk-17

git clone https://github.com/tongtf/sshinjector.git
cd sshinjector
./gradlew assembleDebug   # Debug APK
```

---

## 2. 快速开始

1. **添加服务器**：主页 →「+」→ 填写服务器信息
2. **生成密钥**：应用内生成 ECDSA P-256 密钥对（需生物识别授权）
3. **部署公钥**：复制公钥到服务器 `~/.ssh/authorized_keys`
4. **测试连接**：确认 SSH 认证通过
5. **配置白名单**：选择要走代理的应用
6. **连接 VPN**：主页点击「连接」，授权 VPN 权限

---

## 3. 服务器管理

### 添加服务器

| 字段 | 说明 |
|------|------|
| 名称 | 自定义名称，便于识别 |
| 主机 | 服务器 IP 或域名 |
| 端口 | SSH 端口（默认 22） |
| 用户名 | SSH 登录用户 |
| 密钥算法 | ECDSA P-256 / RSA 4096 / Ed25519 |

### 服务端要求

```bash
# sshd_config 需允许端口转发（默认开启）
grep -E '^(AllowTcpForwarding|GatewayPorts)' /etc/ssh/sshd_config
# AllowTcpForwarding yes
```

### 一键部署（推荐）

服务器列表页的「向导」可自动完成：创建专用 `sshproxy` 账户、chroot 隔离、密钥部署、sshd 加固。

---

## 4. 密钥管理

- **生成**：应用内生成 ECDSA P-256 密钥对，私钥保存在 Android Keystore（硬件级隔离）
- **导入**：从文件导入已有私钥（OpenSSH 格式）
- **导出**：导出公钥（OpenSSH 格式）部署到服务器
- **二维码**：通过二维码分享公钥

> **安全**：私钥永不离开 Keystore，无法导出；所有签名操作需生物识别授权。

---

## 5. 白名单配置

- 搜索/浏览已安装应用
- 勾选需要走代理的应用（**仅勾选的应用**流量会被拦截）
- 支持分组与预设（仅浏览器 / 社交应用 / 自定义）

### 三种流量模式

| 模式 | 行为 |
|------|------|
| **普通模式（全部走隧道）** | 所有流量经 SSH 隧道 |
| **白名单模式** | 仅勾选应用走隧道，其余直连 |
| **域名分流** | 命中列表的域名走隧道，其余直连 |

---

## 6. 连接与状态

连接成功后主页显示：
- 本地 IP / 远程 IP
- 实时流量图（上传/下载速度、累计流量）
- 连接时长

通知栏常驻状态，支持**断开 / 暂停**。

### 自动重连

网络切换（WiFi ↔ 5G）时自动无感重连，指数退避策略，支持开机自启。

---

## 7. 设置详解

| 设置项 | 推荐值 | 说明 |
|--------|--------|------|
| MTU | 1500 | 低 MTU 可解决某些网络下的断流 |
| 保活间隔 | 30s | SSH 心跳，防止服务端断开长连接 |
| DNS 模式 | 远端解析 | 防 DNS 泄露；需服务端可访问公共 DNS |
| IPv6 | 开启 | 双栈网络，访问 IPv6 站点 |
| 生物识别 | 开启 | 保护私钥，解锁需指纹/面容 |
| 连接超时 | 15s | SSH 建立连接超时 |
| 空闲超时 | 5min | 无流量连接自动清理 |

---

## 8. 故障排查

### 连接失败 / SSH 超时

- **检查服务器可达性**：在手机浏览器/终端测试 `ssh user@host -p port` 是否可连
- **网络切换**：WiFi/移动数据切换后重试；SSH 服务器对某些网络路径不可达
- **SSH 配置**：确认 `AllowTcpForwarding yes`、密钥已部署
- **反复测试后超时**：多次连接/断开会累积服务器端 SSH 会话，等待服务器清理或切换网络后再试

### 能连上但无法上网

- 确认模式设置正确（普通/白名单/域名分流）
- 检查 DNS 模式（远端解析需要服务器可达公共 DNS）
- 测试单个网站 vs 全部网站，缩小范围

### 网速慢 / 多页面卡顿

- 确认使用最新版本（性能优化：SSH IO 独立线程池、回向直通）
- 尝试降低 MTU（如 1400）
- 检查服务器带宽与 SSH 加密开销

### VPN 自环（白名单模式连接超时）

- 白名单模式下应用自身不走 TUN 排除；若 SSH 连接超时而 `nc`/`ping` 正常，怀疑自环
- 切换到普通模式验证

---

## 9. 安全建议

- 使用**硬件级密钥**（Android Keystore）+ 生物识别保护
- 启用**远端 DNS**，避免 DNS 泄露
- 保持 **Host Key 指纹验证**（首次连接 TOFU）
- 服务器使用**密钥认证**，禁用密码登录
- 定期检查服务器 `auth.log` 异常登录

---

## 10. FAQ

**Q: 支持哪些 SSH 密钥类型？**
A: ECDSA P-256（推荐）、RSA 4096、Ed25519。

**Q: 支持 HTTP/3 (QUIC) 吗？**
A: 通过 SOCKS5 UDP ASSOCIATE 转发 UDP 流量（QUIC）。

**Q: 私钥可以导出吗？**
A: 不可以。私钥保存在 Android Keystore，只能导出公钥。

**Q: 需要 root 吗？**
A: 不需要，使用 Android 官方 VpnService API。

**Q: Release APK 签名为什么是 debug？**
A: 项目 Release 使用 debug keystore 签名，便于直接安装；上架 Play Console 需替换为正式签名。

---

<div align="center">
  <sub>问题反馈：<a href="https://github.com/tongtf/sshinjector/issues">GitHub Issues</a></sub>
</div>
