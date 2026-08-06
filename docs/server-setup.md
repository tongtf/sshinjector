# SSHInjector 服务器端手动配置指南

本文档描述如何**手动**在 SSH 服务器上配置 SSHInjector 需要的隧道账号与 sshd 加固。
APP 内「一键配置」自动执行以下全部步骤；如果你希望手动操作，或需要排查/回滚，请参考本文。

> 安全提醒：以下命令涉及系统账号与 sshd 配置。**执行前请备份 `/etc/ssh/sshd_config`**。

---

## 1. 概念

SSHInjector 通过 `ssh -D`（SOCKS5 over SSH）转发流量。为了**最小权限**，推荐使用一个
**独立的隧道账号**（默认 `sshproxy`），而不是直接使用 root 或普通登录账号：

- 账号无法登录 shell（`nologin`）、无法获取 TTY
- 账号被 chroot 隔离，只能看到最小化的文件系统
- 仅允许 TCP 转发，禁止 X11 转发
- **仅公钥认证**，密码认证关闭（`PasswordAuthentication no`）
- `authorized_keys` 设置 immutable 锁（`chattr +i`），防止被篡改

## 2. 创建隧道账号

```bash
# 创建账号（固定名称 sshproxy），无密码、无登录 shell
useradd -M -s /usr/sbin/nologin -d /home/sshproxy -p '!' sshproxy

# 锁定密码（纵深防御）
passwd -l sshproxy

# 家目录
mkdir -p /home/sshproxy/.ssh
chown -R sshproxy:sshproxy /home/sshproxy/.ssh
chmod 700 /home/sshproxy/.ssh
```

> 幂等说明：一键配置会先检测 `id sshproxy`；已存在则跳过创建，仅补齐缺失结构。

## 3. 建立 chroot 隔离目录

OpenSSH 要求 `ChrootDirectory` 的**每一层目录都必须由 root 所有，且不可被其他用户写**，
否则 sshd 会拒绝 chroot。

```bash
CHROOT=/home/sshproxy/chroot
mkdir -p "$CHROOT/dev" "$CHROOT/etc" "$CHROOT/lib" "$CHROOT/lib64"
chown -R root:root "$CHROOT"
chmod 755 "$CHROOT"
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

### 3.3 固定兜底清单（跨发行版）

```bash
for lib in libc.so.6 libnss_dns.so.2 libnss_files.so.2 libresolv.so.2 \
           ld-linux.so.2 ld-linux-x86-64.so.2 ld-linux-aarch64.so.1; do
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
> ├── lib/{ld-linux-x86-64.so.2, libc.so.6, libnss_dns.so.2, libnss_files.so.2, libresolv.so.2}
> └── lib64/
> ```

## 4. 安装公钥（免密登录）

将 APP 生成的 **OpenSSH 公钥**（形如 `ssh-ed25519 AAAA... comment`）追加到授权文件。

```bash
AUTHKEYS=/home/sshproxy/.ssh/authorized_keys

# 修改前先解除 immutable 锁（若存在）
chattr -i "$AUTHKEYS" 2>/dev/null || true

# 幂等追加（已存在则跳过）
grep -qF "ssh-ed25519" "$AUTHKEYS" 2>/dev/null \
  || echo "ssh-ed25519 <你的公钥内容>" >> "$AUTHKEYS"

# 属主与权限
chown sshproxy:sshproxy "$AUTHKEYS"
chmod 600 "$AUTHKEYS"

# 加 immutable 锁，防止被篡改 / 被注入恶意 key
chattr +i "$AUTHKEYS"
```

> `chattr +i` 后文件不可被修改/删除/重命名（包括 root），提升授权文件完整性。
> 如需更新公钥，先 `chattr -i` 解锁。

## 5. sshd_config：Match User 块

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
| `AllowTcpForwarding yes` | 允许 `ssh -D` 动态端口转发（核心能力） |
| `PermitTTY no` | 禁止分配 TTY（无法交互登录） |
| `PasswordAuthentication no` | 仅公钥认证，杜绝密码爆破 |
| `PubkeyAuthentication yes` | 启用公钥认证 |

## 6. 安全门控：校验配置 + 重载 sshd

**修改配置后必须验证语法，通过才重载**；失败则恢复备份。

```bash
# 备份（修改前做一次）
cp -a /etc/ssh/sshd_config /etc/ssh/sshd_config.sshinjector.bak

# 权限与属主必须正确
chmod 600 /etc/ssh/sshd_config
chown root:root /etc/ssh/sshd_config

# 语法校验
sshd -t

# 通过后重载（发行版差异）
systemctl reload sshd 2>/dev/null \
  || service ssh reload 2>/dev/null \
  || /etc/init.d/ssh reload
```

> 若 `sshd -t` 失败：恢复备份 `cp -a /etc/ssh/sshd_config.sshinjector.bak /etc/ssh/sshd_config`，
> 修正后重新 `sshd -t`。

## 7. 验证

```bash
# 账号存在
id sshproxy

# chroot 目录属主
ls -ld /home/sshproxy/chroot

# Match 块存在
grep "Match User sshproxy" /etc/ssh/sshd_config

# 公钥已授权
grep "ssh-ed25519" /home/sshproxy/.ssh/authorized_keys

# 从本机冒烟测试 ssh -D
ssh -D 1080 -N sshproxy@<服务器IP> -p 22
# 然后 curl --socks5 127.0.0.1:1080 https://example.com
```

## 8. 发行版差异

| 场景 | 说明 |
|------|------|
| Ubuntu/Debian | `lib/x86_64-linux-gnu` 路径；`sshd` 在 `/usr/sbin`；重载用 `systemctl reload ssh` |
| CentOS/RHEL | `lib64/` 路径；重载用 `systemctl reload sshd` |
| Alpine (musl) | `ldd` 行为不同，需额外 `ldd /sbin/sshd` 并复制 `/lib/ld-musl-*.so.1` |
| OpenWrt | dropbear，不支持 Match/ChrootDirectory，不适用本配置 |

## 9. 回滚 / 卸载

```bash
# 1. 恢复 sshd_config
cp -a /etc/ssh/sshd_config.sshinjector.bak /etc/ssh/sshd_config
sshd -t && systemctl reload sshd

# 2. 删除账号（连带家目录）
userdel -r sshproxy

# 3. 清理备份
rm -f /etc/ssh/sshd_config.sshinjector.bak
```

## 10. 安全要点总结

- [x] 独立最小权限账号 `sshproxy`，`nologin` + 密码锁定
- [x] chroot 根目录 root 所有，`PermitTTY no`，`X11Forwarding no`
- [x] 仅公钥认证（`PasswordAuthentication no`）
- [x] `authorized_keys` immutable 锁（`chattr +i`）
- [x] sshd 修改前备份、`sshd -t` 通过才重载、失败自动回滚
- [x] 所有操作幂等，可安全重复执行
