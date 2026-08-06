# SSHInjector

<div align="center">

**🌐 Languages:** [中文](README.md) · [English](README.en.md) · [Русский](README.ru.md)

</div>

<p align="center">
  <img src="docs/images/icon.png" width="128" height="128" alt="SSHInjector">
</p>

<div align="center">
  <img src="https://img.shields.io/badge/Android-14%2B-green.svg?style=flat-square&logo=android" alt="Android 14+">
  <img src="https://img.shields.io/badge/Kotlin-1.9.24-blue.svg?style=flat-square&logo=kotlin" alt="Kotlin 1.9.24">
  <img src="https://img.shields.io/badge/Compose-Material3-orange.svg?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/SSH-ECDSA-red.svg?style=flat-square" alt="SSH ECDSA">
</div>

<div align="center">
  <h3>🔐 Android 14+ SSH SOCKS5 proxy app</h3>
  <p>Encrypted tunnel over SSH dynamic port forwarding (<code>ssh -D</code>) using VpnService; only whitelisted apps are routed through the proxy.</p>
</div>

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| **SSH key auth** | ECDSA P-256 stored in hardware-backed Android Keystore, biometric unlock, in-app generate/import/export |
| **SOCKS5 proxy** | Full RFC 1928: TCP CONNECT + UDP ASSOCIATE, IPv4/IPv6/domain |
| **Whitelist mode** | `VpnService.addAllowedApplication()` — only selected apps go through the proxy |
| **Dual-stack network** | IPv4 + IPv6 with dual-stack addresses on the TUN interface |
| **Remote DNS** | Intercepts UDP:53 and sends it over the SSH tunnel to a remote resolver, preventing leaks |
| **HTTP/3 support** | QUIC traffic via SOCKS5 UDP ASSOCIATE |
| **Live stats** | Upload/download speed, cumulative traffic, session time, per-app ranking |
| **Auto-reconnect** | Seamless reconnect on network switch (WiFi↔5G) with exponential backoff, boot start |
| **Material 3 UI** | Server management, whitelist picker, dashboard, settings, key management |

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Android 14+                            │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Compose)                                         │
│  ├── DashboardScreen    ← live traffic / status / actions   │
│  ├── ServerListScreen   ← server CRUD / test / keys         │
│  ├── WhitelistScreen    ← app list / groups / presets       │
│  ├── SettingsScreen     ← MTU / keepalive / DNS / IPv6/theme│
│  └── KeyManagerScreen   ← ECDSA P-256 gen/import/export/QR      │
├─────────────────────────────────────────────────────────────┤
│  Domain Layer (UseCases)                                    │
│  ├── VpnController       ← VPN lifecycle / state machine    │
│  ├── ServerRepository    ← server/whitelist/session CRUD    │
│  └── KeyManager          ← key gen/import/sign/export       │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  ├── Room Database       ← Server/Whitelist/Session/Traffic │
│  ├── DataStore           ← preferences / keystore alias map │
│  ├── SshKeyManager       ← Android Keystore (hardware)      │
│  ├── JschSshClient       ← SSH connect/tunnel/keepalive     │
│  ├── Socks5ProxyServer   ← RFC 1928 server (NIO Selector)   │
│  ├── PacketProcessor     ← IP/TCP/UDP parse/forward         │
│  └── DnsInterceptor      ← DNS intercept/remote-resolve     │
├─────────────────────────────────────────────────────────────┤
│  System Layer                                               │
│  ├── SshVpnService       ← VpnService (TUN/whitelist/loop)  │
│  └── BootReceiver        ← boot start / network monitor     │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 Tech Stack

| Category | Tech |
|----------|------|
| Language/UI | Kotlin 1.9.24 + Jetpack Compose (Material 3) |
| Architecture | MVVM + Clean Architecture + Repository |
| DI | Hilt (KSP) |
| Database | Room 2.6.1 (KSP) |
| Preferences | DataStore Preferences (RxJava3 Flow) |
| SSH client | mwiede/jsch:0.2.14 (active fork, ECDSA P-256 support) |
| DNS | dnsjava 3.5.7 |
| Network I/O | Java NIO Selector + Kotlinx Coroutines |
| Keystore | Android Keystore (hardware) + BiometricPrompt |
| Coroutines | Kotlinx Coroutines 1.8.1 + Flow |
| Navigation | Navigation Compose 2.7.7 |
| Image loading | Coil 2.6.0 |

---

## 🚀 Quick Start

### Prerequisites

- Android 14 (API 34) or higher
- A server running mainstream Linux or **OpenWrt**, with OpenSSH or dropbear (one-click setup needs root or sudo)
- OpenSSH requires `AllowTcpForwarding yes` (default; dropbear allows TCP forwarding by default)

### Server setup

**Option A: One-click setup in-app (recommended)**

In the "Add server" wizard, enter an account with **root or sudo** access on your server and the app does everything:

- Creates a dedicated tunnel account `sshproxy`: `nologin` + locked password, cannot log into a shell
- Chroot isolation, exposing only a minimal filesystem (`ChrootDirectory`)
- Public-key-only auth (`PasswordAuthentication no`), `authorized_keys` locked with `chattr +i`
- Appends a `Match User sshproxy` hardening block to sshd; backs up the config and reloads only after `sshd -t` passes

**Option B: Manual setup**

Follow **[docs/server-setup.md](docs/server-setup.md)** to create the `sshproxy` account, chroot directory and sshd hardening yourself.

> **OpenWrt**: install `openssh-server` for full hardening (chroot + Match); the default dropbear uses basic compatibility mode (`sshproxy` account + pubkey-only, no chroot isolation) and does not modify the global dropbear config. See [docs/server-setup.md](docs/server-setup.md).

### Build

```bash
# Requires JDK 17 (default JDK 25 fails the build)
export JAVA_HOME=/path/to/jdk-17

# Clone
git clone https://github.com/tongtf/sshinjector.git
cd sshinjector

# Debug APK
./gradlew assembleDebug

# Release AAB
./gradlew bundleRelease
```

---

## 📖 Usage

> Full guide: **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)** (installation, configuration, troubleshooting, FAQ)

### 1. Add a server

1. Tap **+** to add a server
2. Fill in: name, host, port (default 22), username
3. Tap **Generate key** to create an ECDSA P-256 key pair (biometric required)
4. Copy the public key → paste into `~/.ssh/authorized_keys` on the VPS
5. Tap **Test connection** to verify

### 2. Configure the whitelist

1. Open the **Whitelist** page
2. Search/browse installed apps
3. Check the apps to route through the proxy (only checked apps are intercepted)
4. Presets: browser-only / social / custom

### 3. Connect

1. Tap the big **Connect** button
2. Grant the VPN permission when prompted
3. On success: local IP / remote IP / live traffic graph
4. Persistent status in the notification; support disconnect/pause

### 4. Advanced settings

| Setting | Recommended | Description |
|---------|-------------|-------------|
| MTU | 1500 | Adjust for your network; lower MTU may fix stalls |
| Keepalive | 30s | SSH heartbeat, prevents server-side disconnects |
| DNS mode | Remote | Prevents DNS leaks; server must reach 8.8.8.8 |
| IPv6 | On | Dual-stack, access IPv6 sites |
| Biometric | On | Protects the private key |

---

## 🔒 Security Model

```
Private key → Android Keystore (TEE/StrongBox) → hardware isolation
     ↓
Key signing → BiometricPrompt (fingerprint/face/password) → user consent
     ↓
SSH auth   → JSch Identity bridge → Keystore signature
     ↓
Public key → OpenSSH format → deployed manually to the server
```

**Key points:**
- The private key **never leaves** the Keystore and cannot be exported
- First-connection TOFU (Trust On First Use) + host key fingerprint cache
- Host key verification via `StrictHostKeyChecking=ask` and known_hosts
- Foreground service type `FOREGROUND_SERVICE_SPECIAL_USE`

---

## 📂 Project Structure

```
SSHInjector/
├── app/
│   ├── src/main/
│   │   ├── java/cn/srv0/sshinjector/
│   │   │   ├── data/
│   │   │   │   ├── local/          # Room + DataStore
│   │   │   │   ├── remote/ssh/     # JSch wrapper + Keystore
│   │   │   │   └── repository/     # Repository implementations
│   │   │   ├── domain/
│   │   │   │   ├── model/          # Domain models
│   │   │   │   ├── usecase/        # VpnController/Repository
│   │   │   │   └── vpn/            # Core VPN components
│   │   │   ├── di/                 # Hilt Modules/EntryPoints
│   │   │   ├── ui/                 # Compose screens
│   │   │   └── vpn/                # SshVpnService + BootReceiver
│   │   ├── res/                    # Resources
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   └── server-setup.md             # Server setup guide
├── .github/workflows/ci.yml        # CI/CD
├── detekt.yml                      # Code style rules
├── build.gradle.kts / settings.gradle.kts
└── README.md
```

---

## 🧪 Testing

```bash
# Unit tests
./gradlew testDebugUnitTest

# Code quality gates
./gradlew ktlintCheck detekt lint
```

---

## 📦 Release Checklist

- [ ] Version bump (`versionCode` / `versionName`)
- [ ] Update `CHANGELOG.md`
- [ ] Run the full CI pipeline
- [ ] Publish the release APK/AAB to GitHub Releases / Play Console
- [ ] Privacy policy link (required by Play Console)

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Commit changes: `git commit -m 'Add amazing feature'`
4. Push: `git push origin feature/amazing-feature`
5. Open a Pull Request

### Code style

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- `ktlint` + `detekt` + `Android Lint` must all pass
- Conventional Commits

---

## 📄 License

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

## 🙏 Acknowledgments

- [mwiede/jsch](https://github.com/mwiede/jsch) — actively maintained JSch fork with ECDSA P-256 support
- [dnsjava](https://github.com/dnsjava/dnsjava) — Java DNS protocol library
- [Android Networking Samples](https://github.com/android/networking-samples) — VPNService reference
- [SocksDroid](https://github.com/6b6b6b/socksdroid) — SOCKS5 proxy reference

---

## ⚠️ Disclaimer

This project is for learning, research, and personal privacy protection only. Users are responsible for complying with local laws and regulations. The authors are not liable for any consequences of using this software.

---

<div align="center">
  <sub>Built with ❤️ for Android 14+ | <a href="https://github.com/tongtf/sshinjector">GitHub</a> | <a href="https://github.com/tongtf/sshinjector/issues">Issues</a></sub>
</div>
