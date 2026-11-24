# SSHInjector 服务器端配置指南

本文档给出服务端配置的**手动步骤、幂等性说明与回滚方法**，并说明 App 内「一键配置」实际做了什么。

> App 内一键配置入口：**设置 → 服务器管理 → 右上角「+」→「一键服务器配置」**。
> **OpenSSH** 后端自动执行本文全部加固步骤；**dropbear/OpenWrt** 只走基础兼容模式（建号 + 锁密码 + 公钥 + marker），**不做 chroot / `chattr +i` / sshd reload**。

> 安全提醒：以下命令涉及系统账号与 sshd 配置。**执行前请备份 `/etc/ssh/sshd_config`**。

---

## 0. App「一键配置」内部流程（便于对照排障）

| 阶段 | 实际行为 |
|------|----------|
| 权限探测 | `id -u == 0` → `sudo -n -p '' true` → 有密码时 `sudo -S -p ''`（密码经 stdin 首行传入，不落日志）。三者都不满足 → 结果 `LocalOnly`（仅保存本机配置，仍生成密钥） |
| 本地校验 | 读取 assets 脚本，与硬编码的 `ServerProvisioning.SETUP_SCRIPT_SHA256` 比对，不一致立即中止（构建期漂移） |
| 上传脚本 | 本地生成 `runId`（UUID 去 `-` 取 12 位），写入 `/tmp/sshinjector_setup_<runId>.sh`，远端 `sha256sum` 比对；不符 → `TamperDetected`。本轮所有 exec 复用同一 `runId`，避免旧实现用 shell `$$` 导致每次新 SSH 会话路径失配 |
| 上传公钥 | `/tmp/sshinjector_pubkey_<runId>.pub`，内容经 stdin 写入，不拼 shell 参数 |
| 执行 + 验证 | `sh <脚本> <公钥路径>`；App 自动验证仅做 `id sshproxy && (grep -c 'Match User sshproxy' /etc/ssh/sshd_config || test -f /etc/dropbear/sshinjector.configured)`，随后 `rm -f` 临时文件 |
| 后端探测 | 脚本按 `command -v sshd` 优先选 OpenSSH 完整模式，否则 dropbear 基础模式；两者都没有则失败 |

完整步骤枚举：`DETECT_PRIVILEGE → UPLOAD_SCRIPT → UPLOAD_PUBKEY → EXECUTE_SCRIPT → VERIFY → DONE`。

---

## 1. 概念

SSHInjector 通过 `ssh -D`（SOCKS5 over SSH）转发流量。为了**最小权限**，推荐使用一个
**独立的隧道账号**（默认 `sshproxy`），而不是直接使用 root 或普通登录账号：

- 账号无法登录 shell（`nologin`）、无法获取 TTY
- OpenSSH 后端下账号被 chroot 隔离，只能看到最小化的文件系统
- 仅允许 TCP 转发，禁止 X11 转发
- **仅公钥认证**，密码认证关闭（`PasswordAuthentication no`）
- OpenSSH 后端下 `authorized_keys` 设置 immutable 锁（`chattr +i`），防止被篡改

---

## 2. 创建隧道账号

```bash
# nologin 路径随发行版不同（BusyBox/OpenWrt 通常是 /bin/false）
NOLOGIN=$(command -v nologin 2>/dev/null || echo /bin/false)

# 创建账号（固定名称 sshproxy），无密码、无登录 shell；无 useradd 时用 adduser -H -D
useradd -M -s "$NOLOGIN" -d /home/sshproxy -p '!' sshproxy

# 锁定密码（纵深防御）
passwd -l sshproxy

# 家目录
mkdir -p /home/sshproxy/.ssh
chown -R sshproxy:sshproxy /home/sshproxy/.ssh
chmod 700 /home/sshproxy/.ssh
```

> 幂等说明：一键配置会先检测 `id sshproxy`；已存在则跳过创建，仅补齐缺失结构。

---

## 3. 建立 chroot 隔离目录（仅 OpenSSH 后端）

OpenSSH 要求 `ChrootDirectory` 的**每一层目录都必须由 root 所有，且不可被其他用户写**，
否则 sshd 会以 `bad ownership or modes for chroot directory component` 拒绝每一条会话。

```bash
CHROOT=/home/sshproxy/chroot
mkdir -p "$CHROOT/dev" "$CHROOT/etc" "$CHROOT/lib" "$CHROOT/lib64"
chown -R root:root "$CHROOT"
chmod 755 "$CHROOT"

# 关键：上级目录也必须 root 拥有（脚本已内置，手动配置别漏）
chown root:root /home/sshproxy
chmod 755 /home/sshproxy
```

### 3.1 设备节点

```bash
mknod -m 666 "$CHROOT/dev/null"    c 1 3
mknod -m 666 "$CHROOT/dev/zero"    c 1 5
mknod -m 666 "$CHROOT/dev/random"  c 1 8
mknod -m 666 "$CHROOT/dev/urandom" c 1 9
```

### 3.2 动态链接库（用 ldd 探测 sshd 依赖）

```bash
ldd "$(command -v sshd)" | awk '/=>/ {print $3}' | grep '^/' \
  | while read -r lib; do
      mkdir -p "$CHROOT$(dirname "$lib")"
      cp -n "$lib" "$CHROOT$lib"
    done
```

### 3.3 固定兜底清单（跨发行版，含 musl）

```bash
for lib in libc.so.6 libnss_dns.so.2 libnss_files.so.2 libresolv.so.2 \
           ld-linux.so.2 ld-linux-x86-64.so.2 ld-linux-aarch64.so.1 \
           ld-musl-i386.so.1 ld-musl-x86_64.so.1 ld-musl-aarch64.so.1; do
  find /lib /lib64 /usr/lib /usr/lib64 -name "$lib" \
    | while read -r found; do
        mkdir -p "$CHROOT$(dirname "$found")"
        cp -n "$found" "$CHROOT$found"
      done
done
```

### 3.4 域名解析配置

```bash
cp -n /etc/host.conf /etc/nsswitch.conf /etc/resolv.conf "$CHROOT/etc/" 2>/dev/null || true
```

> 结果目录树（参考）：
> ```
> /home/sshproxy/chroot
> ├── dev/{null, zero, random, urandom}
> ├── etc/{host.conf, nsswitch.conf, resolv.conf}
> ├── lib/{ld-linux-x86-64.so.2, libc.so.6, libnss_dns.so.2, libnss_files.so.2, libresolv.so.2, ld-musl-*.so.1}
> └── lib64/
> ```

---

## 4. 安装公钥（免密登录）

将 APP 生成的 **OpenSSH 公钥**（形如 `ecdsa-sha2-nistp256 AAAA... comment`）追加到授权文件。
**去重按完整公钥行**（`grep -qFf`），不要只按算法名——否则同算法的另一把钥匙会被误判为已存在而跳过。

```bash
AUTHKEYS=/home/sshproxy/.ssh/authorized_keys

# 修改前先解除 immutable 锁（若存在；仅 OpenSSH 后端加过锁）
chattr -i "$AUTHKEYS" 2>/dev/null || true

# 幂等追加：按完整公钥行去重（PUBKEY_FILE 为含公钥内容的文件）
grep -qFf "$PUBKEY_FILE" "$AUTHKEYS" 2>/dev/null \
  || cat "$PUBKEY_FILE" >> "$AUTHKEYS"

# 属主与权限
chown sshproxy:sshproxy "$AUTHKEYS"
chmod 600 "$AUTHKEYS"

# 加 immutable 锁（仅 OpenSSH 后端；dropbear 不调用 chattr）
chattr +i "$AUTHKEYS" 2>/dev/null || true
```

> `chattr +i` 后文件不可被修改/删除/重命名（包括 root），提升授权文件完整性；overlayfs 上可能不生效，脚本会容错，公钥仍以 600 权限保护。
> 如需更新公钥，先 `chattr -i` 解锁。

---

## 5. sshd_config：Match User 块（仅 OpenSSH 后端）

在 `/etc/ssh/sshd_config` **末尾**追加（仅一次，幂等）：

```ini
# --- SSHInjector tunnel account ---
Match User sshproxy
    ChrootDirectory /home/sshproxy/chroot
    X11Forwarding no
    AllowTcpForwarding yes
    PermitTTY no
    PasswordAuthentication no
    PubkeyAuthentication yes
```

各指令含义：

| 指令 | 说明 |
|------|------|
| `ChrootDirectory` | 强制 chroot，账号只能看到该目录 |
| `X11Forwarding no` | 禁止 X11 转发 |
| `AllowTcpForwarding yes` | 允许 `ssh -D` / `direct-tcpip` 动态端口转发（核心能力） |
| `PermitTTY no` | 禁止分配 TTY（无法交互登录） |
| `PasswordAuthentication no` | 仅公钥认证，杜绝密码爆破 |
| `PubkeyAuthentication yes` | 启用公钥认证 |

---

## 6. 安全门控：校验配置 + 重载 sshd（仅 OpenSSH 后端）

**修改配置后必须验证语法，通过才重载**；失败则恢复备份。一键脚本会尝试下列全部重载方式并吞掉失败（`|| true`），重载失败本身不会让脚本退出。

```bash
# 备份（修改前做一次）
cp -a /etc/ssh/sshd_config /etc/ssh/sshd_config.sshinjector.bak

# 权限与属主必须正确
chmod 600 /etc/ssh/sshd_config
chown root:root /etc/ssh/sshd_config

# 语法校验
sshd -t

# 通过后重载（发行版差异，按顺序尝试）
systemctl reload sshd 2>/dev/null || systemctl reload ssh 2>/dev/null \
  || service ssh reload 2>/dev/null || service sshd reload 2>/dev/null \
  || /etc/init.d/ssh reload 2>/dev/null || /etc/init.d/sshd restart 2>/dev/null \
  || true
```

> 若 `sshd -t` 失败：恢复备份 `cp -a /etc/ssh/sshd_config.sshinjector.bak /etc/ssh/sshd_config`，
> 修正后重新 `sshd -t`。

---

## 7. 验证

### 7.1 App 自动验证的范围

一键配置末尾只检查：账号存在 **且**（`Match User sshproxy` 存在 **或** dropbear marker 存在）。
它**不**校验公钥内容、chroot 属主，也不做 `ssh -D` 冒烟——下面这些属于人工验证。

### 7.2 人工验证（手动配置 / 排障时建议执行）

```bash
id sshproxy
ls -ld /home/sshproxy /home/sshproxy/chroot
grep "Match User sshproxy" /etc/ssh/sshd_config
grep -Ff <你的公钥文件> /home/sshproxy/.ssh/authorized_keys

# 从本机冒烟测试
ssh -D 1080 -N sshproxy@<服务器IP> -p 22
curl --socks5 127.0.0.1:1080 https://example.com
```

---

## 8. 发行版差异

| 场景 | 说明 |
|------|------|
| Ubuntu/Debian | `lib/x86_64-linux-gnu` 路径；`sshd` 在 `/usr/sbin`；重载用 `systemctl reload ssh` |
| CentOS/RHEL | `lib64/` 路径；重载用 `systemctl reload sshd` |
| Alpine (musl) | 脚本内置 musl loader 兜底（`ld-musl-*.so.1`，见 §3.3）；如仍缺库可额外 `ldd /sbin/sshd` 复查 |
| OpenWrt（默认 dropbear） | 基础兼容模式：账号 + 锁密码 + 公钥 + marker，无 chroot/Match/immutable/重载；不修改全局 dropbear 配置 |
| OpenWrt（openssh-server） | BusyBox 用户态：脚本用 `adduser` 建号、`/etc/init.d/sshd restart` 重载，完整加固可用 |

> **OpenWrt 说明**：
> - 默认 SSH 服务端是 **dropbear**：不支持 `ChrootDirectory` / `Match` 块 / `sshd -t`，因此走
>   **基础兼容模式** —— 创建 `sshproxy` 账号（`nologin` + 密码锁定）、安装公钥
>   **仅写入 `/home/sshproxy/.ssh/authorized_keys`（600）**，写入标记文件
>   `/etc/dropbear/sshinjector.configured` 供 App 验证。**不写 `/etc/dropbear/authorized_keys`，不碰 root 的 authorized_keys，不修改**全局 `/etc/config/dropbear`。
>   无 chroot 隔离是该模式的安全折衷（仅靠密码锁定保证仅公钥可登录）。
> - 安装 OpenSSH 后获得完整加固：`opkg install openssh-server`，脚本自动检测到 `sshd` 即走完整模式。
> - BusyBox 差异：无 `useradd`（回退 `adduser -H -D`）、无 `systemctl`/`service`（回退 `/etc/init.d/sshd restart`）、`chattr` 在 overlayfs 上不生效（容错）。
> - dropbear 无需重启即生效：`authorized_keys` 每次连接动态读取。

---

## 9. 回滚 / 卸载

```bash
# 1. 恢复 sshd_config（仅 OpenSSH 后端改过）
cp -a /etc/ssh/sshd_config.sshinjector.bak /etc/ssh/sshd_config
sshd -t && (systemctl reload sshd 2>/dev/null || /etc/init.d/sshd restart 2>/dev/null || true)

# 2. 删除账号前先解 immutable 锁，否则 authorized_keys 无法删除（仅 OpenSSH 后端）
chattr -i /home/sshproxy/.ssh/authorized_keys 2>/dev/null || true

# 3. 删除账号（连带家目录；OpenWrt/BusyBox 用 deluser）
userdel -r sshproxy 2>/dev/null || deluser sshproxy

# 4. dropbear 模式额外清理 marker
rm -f /etc/dropbear/sshinjector.configured

# 5. 清理备份
rm -f /etc/ssh/sshd_config.sshinjector.bak
```

---

## 10. 安全要点总结

- [x] 独立最小权限账号 `sshproxy`，`nologin` + 密码锁定
- [x] **OpenSSH 后端**：chroot 根目录与上级目录均 root 所有，`PermitTTY no`，`X11Forwarding no`
- [x] 仅公钥认证（`PasswordAuthentication no`）
- [x] **OpenSSH 后端**：`authorized_keys` immutable 锁（`chattr +i`）
- [x] **OpenSSH 后端**：sshd 修改前备份、`sshd -t` 通过才重载、失败自动回滚
- [x] 所有操作幂等，可安全重复执行（公钥按完整行去重）
- [x] 一键配置脚本经本机 + 远端双重 SHA-256 校验，临时路径使用本地 `runId`
