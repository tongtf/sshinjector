# SSHInjector - Agent Guide

## Project Overview

Android 14+ (minSdk 34) SSH SOCKS5 proxy app. Kotlin + Jetpack Compose + Hilt + Room. Single module `app/`, package `com.sshinjector`.

Traffic path: VpnService TUN → PacketProcessor → TunnelRouter (per-UID selection) → TunnelPlugin → remote server. Default tunnel is `socks5` (JSch → ChannelDirectTCPIP → Socks5ProxyServer NIO).

## Build & Verify

```bash
./gradlew assembleDebug                    # Build Debug APK
./gradlew testDebugUnitTest                # Run unit tests (JUnit4 + Mockito-Kotlin)
./gradlew ktlintCheck                      # CI runs each quality gate separately:
./gradlew detekt
./gradlew lint
./gradlew bundleRelease                    # Release AAB
```

**Release signing**: `buildTypes.release` wires `signingConfigs.getByName("debug")` — the `signingConfigs.create("release")` block reads `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` env vars but is **never referenced**. Release is always signed with `app/debug.keystore`; the keystore import + env vars in CI's `build-release` job are dead config.

**CI** (`.github/workflows/ci.yml`): push/PR to `main`/`develop`, tags `v*` → `lint-and-test` (ktlint → detekt → lint → testDebugUnitTest) → `build-debug`; `build-release` (tag only); `dependency-check` runs `./gradlew dependencyCheckAnalyze` but **no OWASP dependency-check plugin is configured in any build file — that task doesn't exist**. CI also uploads jacoco reports but no jacoco plugin is configured. Those CI parts are stale.

## Code Style

- **Must pass** all three before commit: detekt + ktlint + Android Lint
- Line length: 120 chars, indent: 4, continuation indent: 8
- Import order: java → kotlin → blank → static; `androidx.compose.*` and `kotlinx.coroutines.*` allow wildcards
- MagicNumber whitelist: 0-4096 (see `detekt.yml`); local vars/properties/function calls ignored
- Commit messages: Conventional Commits (see git log)
- Compiler flag: `-Xopt-in=kotlin.RequiresOptIn` enabled project-wide

## Architecture

- **Single module**: all code in `app/src/main/java/com/sshinjector/`
- **DI**: Hilt (KSP). Two modules: `di/Modules.kt` (`AppModule`, `@Provides`) and `di/TunnelModule.kt` (abstract `@Binds @IntoMap @StringKey`). ksp args: `hilt.disableAggregatingTask=true`
- **Tunnel plugin** (`domain/vpn/tunnel/` + `data/remote/tunnel/`): only `socks5` (SSH-D). To add a tunnel: implement `TunnelPlugin`, bind it in `TunnelModule.kt`, describe fields in `TunnelConfigDescriptor`
- **Data flow**: `domain/vpn/PacketProcessor` → `TunnelRouter.selectPlugin(uid)` / `TunnelManager` → `TunnelPlugin` (default `socks5` in `TunnelManager`) → `data/remote/ssh/JschSshClient` + `domain/vpn/Socks5ProxyServer` → `domain/vpn/TunnelChannel` (`data/remote/ssh/JschTunnelChannel`)
- **Database**: Room 2.6.1, schema exports to `app/schemas/` (versions 1-3 via ksp arg `room.schemaLocation`)
- **VPN**: `vpn/SshVpnService` (foreground service), `vpn/BootReceiver`
- **Navigation**: `ui/navigation/NavGraph.kt` (separated from MainActivity)
- **Key storage**: Android Keystore (hardware encryption) + BiometricPrompt
- **Tests**: unit tests in `app/src/test/` (PacketProcessor, Socks5Protocol, DnsInterceptor, SshKeyManager, VpnController); androidTest (Database/Settings integration) requires a device

## Key Gotchas

- **JSch via jitpack.io** — `settings.gradle.kts` configures jitpack maven repo (needed for mwiede/jsch)
- **Compose Compiler version mismatch**: `app/build.gradle.kts` hardcodes `1.5.11`, `gradle.properties` has `1.5.14` (stale). Update both when changing Compose Compiler
- **Version drift**: `gradle.properties` versions don't match root `build.gradle.kts` (e.g., Kotlin `1.9.23` vs `1.9.24`, AGP `8.4.0` vs `8.5.2`, Hilt `2.48` vs `2.51`). **Always use `build.gradle.kts` as source of truth**
- `configurations.all` in `app/build.gradle.kts` forces `androidx.tracing:tracing:1.1.0` — don't remove when bumping deps
- `local.properties` not committed (SDK path + signing)
- ProGuard/R8 enabled in Release (`isMinifyEnabled = true`) — watch keep rules in `proguard-rules.pro` when modifying reflected code
- `dnsjava` version in `build.gradle.kts` is `3.6.5`, not `3.5.7` as in `gradle.properties`
