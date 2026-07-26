# SSHInjector - Agent Guide

## Project Overview

Android 14+ (minSdk 34) SSH SOCKS5 proxy app. Kotlin + Jetpack Compose + Hilt + Room. Single module `app/`.

SSH chain: JSch (mwiede/jsch) → ChannelDirectTCPIP → SOCKS5 ProxyServer (NIO) → VpnService TUN whitelist mode.

## Build & Verify

```bash
./gradlew assembleDebug                    # Build Debug APK
./gradlew testDebugUnitTest                # Run unit tests
./gradlew ktlintCheck detekt lint          # Code quality triad
./gradlew bundleRelease                    # Build Release AAB (needs signing config)
```

CI runs lint checks separately, not as a combined command:
```bash
./gradlew ktlintCheck    # Then detekt, then lint individually
./gradlew detekt
./gradlew lint
```

**Release signing**: env vars `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`, or in `local.properties` (gitignored). Falls back to `app/debug.keystore` if not configured.

**CI** (`.github/workflows/ci.yml`):
- Triggers: push to `main`/`develop`, PRs to `main`/`develop`, tags `v*`
- Jobs: `lint-and-test` → `build-debug` (parallel) → `build-release` (tag only, needs signing secrets)
- Also runs `dependencyCheckAnalyze` in a separate job
- JDK 17, Android SDK 34, Gradle caching enabled

## Code Style

- **Must pass** all three before commit: detekt + ktlint + Android Lint
- Line length: 120 chars, indent: 4, continuation indent: 8
- Import order: java → kotlin → blank → static; `androidx.compose.*` and `kotlinx.coroutines.*` allow wildcards
- MagicNumber whitelist configured (see `detekt.yml` — numbers 0-2048, 4096 ignored)
- Commit messages: Conventional Commits
- Compiler flag: `-Xopt-in=kotlin.RequiresOptIn` enabled project-wide

## Architecture

- **Single module**: all code in `app/src/main/java/com/sshinjector/`
- **DI**: Hilt (KSP), all bindings in `di/Modules.kt` (SingletonComponent)
- **Database**: Room 2.6.1, schema exports to `app/schemas/` (versions 1 and 2)
- **VPN**: `vpn/SshVpnService` (foreground service), `vpn/BootReceiver`
- **Core data flow**: `domain/vpn/PacketProcessor` → `domain/vpn/Socks5ProxyServer` → `data/remote/ssh/JschSshClient` → `domain/vpn/TunnelChannel` interface
- **Navigation**: `ui/navigation/NavGraph.kt` (separated from MainActivity)
- **Key storage**: Android Keystore (hardware encryption) + BiometricPrompt
- **Testing**: Mockito-Kotlin, Coroutines Test

## Key Gotchas

- **JSch via jitpack.io** — `settings.gradle.kts` configures jitpack maven repo
- **Compose Compiler version mismatch**: `app/build.gradle.kts` hardcodes `1.5.11`, `gradle.properties` has `1.5.14` (stale). Update both when changing Compose Compiler
- **Version drift**: `gradle.properties` versions don't match `build.gradle.kts` (e.g., Kotlin `1.9.23` vs `1.9.24`, AGP `8.4.0` vs `8.5.2`, Hilt `2.48` vs `2.51`). **Always use `build.gradle.kts` as source of truth**
- `local.properties` not committed (contains signing + SDK path)
- `debug.keystore` in `app/` directory, used for local builds
- ProGuard/R8 enabled in Release (`isMinifyEnabled = true`) — watch keep rules when modifying reflected code
- `dnsjava` version in `build.gradle.kts` is `3.6.5`, not `3.5.7` as in `gradle.properties`
