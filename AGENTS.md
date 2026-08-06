# SSHInjector - Agent Guide

## Project Overview

Android 14+ (minSdk 34) SSH SOCKS5 proxy app. Kotlin + Jetpack Compose + Hilt + Room. Single module `app/`, package **`cn.srv0.sshinjector`** (not `com.*`).

Traffic path: VpnService TUN → `PacketProcessor` → `TcpStateMachine`/`UdpRelay` → local SOCKS5 (`Socks5ProxyServer`) → JSch `ChannelDirectTCPIP` → remote. Default tunnel is `socks5`.

## Build & Verify

**JDK 17 is required** — system default JDK 25 fails the build. Use:
```bash
JAVA_HOME=/usr/lib/jvm/jdk-17.0.19+10 ./gradlew ...
```

```bash
./gradlew testDebugUnitTest   # unit tests (JUnit4)
./gradlew assembleDebug       # fast compile check (surfaces Kotlin warnings)
./gradlew ktlintCheck         # CI runs each quality gate separately:
./gradlew detekt
./gradlew lint
./gradlew assembleRelease     # release APK (debug-signed, see below)
```

**Release signing**: `buildTypes.release` wires `signingConfigs.getByName("debug")`. The `signingConfigs.create("release")` block (env-var based) is **dead config**. Release always uses `app/debug.keystore`.

**CI** (`.github/workflows/ci.yml`): `lint-and-test` runs ktlint/detekt/lint/test as **separate steps** (detekt+lint in one gradle invocation OOMs the runner). Then `build-debug`. Pushing a `v*` tag additionally triggers `build-release`: `assembleRelease bundleRelease` + creates a GitHub Release with the APK/AAB (job needs `permissions: contents: write`). `actions/upload-artifact@v5` / `setup-android@v3` print a harmless Node 20 deprecation warning.

## Code Style

- **Must pass** all three before commit: detekt + ktlint + Android Lint
- Line length: 120, indent: 4, continuation indent: 8
- Import order: java → kotlin → blank → static; `androidx.compose.*` / `kotlinx.coroutines.*` allow wildcards
- MagicNumber whitelist: 0-4096 (detekt.yml); local vars/properties/calls ignored
- Commits: Conventional Commits

## Architecture (as of the perf rewrite on `main`)

- **Single module**: `app/src/main/java/cn/srv0/sshinjector/`
- **DI**: Hilt (KSP). `di/AppModule.kt` (`@Provides`) + `di/TunnelModule.kt` (`@Binds @IntoMap @StringKey`). ksp arg `hilt.disableAggregatingTask=true`
- **Tunnel plugin** (`domain/vpn/tunnel/` + `data/remote/tunnel/`): only `socks5`. Add a tunnel: implement `TunnelPlugin`, bind in `TunnelModule.kt`, describe in `TunnelConfigDescriptor`
- **TCP data flow**: `VpnController.packetLoop` → `PacketProcessor` → `TcpStateMachine.processTcpPacket` → `forwardThroughLocalSocks` (SOCKS5 to `127.0.0.1:1080`) → `Socks5ProxyServer`
  - **Outgoing** (Socks5ProxyServer): eventLoop reads local channel → `enqueueToSsh` → **`kotlinx.coroutines.channels.Channel`** (`toSshChannel`) → writer coroutine `for (data in channel)` **suspends** (no thread held) → SSH. Queue-full data goes to a connection-level single slot `pendingToSshBlock` (never overwritten — read pauses right after), pausing `OP_READ` as backpressure.
  - **Return path** (`TcpStateMachine`): `registerTunCallback(localPort)` → `Socks5ProxyServer` invokes callback → `writeTcpPayloadToTun` → TUN directly (skips local SOCKS round-trip). Fallback to `startRelayFromSocks` if registration fails.
- **SSH blocking IO isolation** (`domain/vpn/SshIoDispatcher.kt`): `ThreadPoolExecutor(core=16, max=128, SynchronousQueue, allowCoreThreadTimeOut=true, CallerRunsPolicy)`. `Socks5Connection.scope` uses it, so SSH read/write never occupy Dispatchers.IO. **Each active connection holds 1 thread** (JSch blocking read is not suspendable/reusable).
- **UDP**: `UdpRelay` NIO selector event loop (replaced the old busy-wait thread-per-association). NOTE: `forwardUdpToSocks` sends to `127.0.0.1:1080` but `Socks5ProxyServer` only listens TCP — **UDP relay path may not actually work**.
- **DNS**: `DnsInterceptor` REMOTE mode fakes IPs (198.18.x.x / fd00::x); SYSTEM/DOMAIN_SPLIT use protected sockets. `pendingResponses` is a kotlinx `Channel`, delivered by `VpnController.dnsResponseDeliveryLoop` (suspending).
- **VPN**: `vpn/SshVpnService` (foreground), `vpn/BootReceiver`. `buildVpnBuilder` adds `addDisallowedApplication(ownPackage)` **only in non-whitelist mode**.
- **Room** 2.6.1, schemas in `app/schemas/` (v1-3, ksp `room.schemaLocation`)
- **Provisioning**: `data/remote/config/ServerProvisioner.kt` + wizard UI. Script `assets/ssh_setup_script.sh` with hardcoded SHA-256 verified server-side; pubkey via SSH stdin. Guide: `docs/server-setup.md`

## Tests

Unit tests in `app/src/test/java/cn/srv0/sshinjector/`. Concurrency-critical ones:
- `Socks5BackpressureTest` — Channel outgoing backpressure must not lose/reorder data (previous ArrayDeque+suspended-slot queue **failed consistently**: accepted>consumed)
- `SshIoDispatcherTest` — dynamic pool must cover 40 concurrent blocking tasks (no queue starvation)
- `BoundedBackpressureQueue` was **deleted** (concurrency bug); don't reintroduce it.

## Key Gotchas

- **JDK 17 required** (default JDK 25 fails build) — see Build & Verify
- **Outgoing queue**: use kotlinx `Channel` with connection-level single pending slot. Do **not** use `ArrayBlockingQueue`+suspended-slot (data loss under concurrency broke connectivity)
- **SSH isolation**: use the dynamic `SshIoDispatcher`. Do **not** use `Dispatchers.IO.limitedParallelism(small)` — coroutines queue when connections exceed parallelism, breaking connectivity
- **JSch via jitpack.io** — `settings.gradle.kts` configures jitpack (needed for mwiede/jsch)
- **Versions live only in root `build.gradle.kts`** plugins block (AGP 8.6.0, Kotlin 2.0.21, Hilt 2.53.1, KSP 2.0.21-1.0.28). `gradle.properties` holds no versions. Compose compiler comes from `org.jetbrains.kotlin.plugin.compose` (tracks the Kotlin version — no manual pin)
- `configurations.all` forces `androidx.tracing:tracing:1.1.0` — don't remove when bumping deps
- `local.properties` not committed (SDK path + signing)
- ProGuard/R8 on in Release — watch `proguard-rules.pro` for reflected code (e.g. `setChannelWindowSize` uses reflection on JSch)

## Device Testing (adb)

- Debug builds get `applicationIdSuffix = ".debug"` → package is `cn.srv0.sshinjector.debug` (force-stop that, not the release id)
- After `adb install -r`, **must `am force-stop`** the package before relaunching, or the **old process keeps running old code** (cost hours of confusing logs)
- Repeated connect/disconnect cycles accumulate **server-side SSH sessions** → SSH connect times out (`socket is not established`); wait for server cleanup or switch network before assuming a code regression
- Whitelist mode does NOT `addDisallowedApplication(ownPackage)`; Android sends the VPN owner's traffic into the TUN, so the SSH connect can self-loop. If SSH times out while nc/ping work, suspect this

## Remote

- Repo moved: `tongtf/ssh-injector.git` → **`tongtf/sshinjector.git`**
- HTTPS push needs credentials; **use SSH** (`git@github.com:tongtf/sshinjector.git`) — local `~/.ssh/id_ed25519` is set up
- **Release flow**: pushing a `v*` tag triggers build-release (APK/AAB + GitHub Release). To re-release after a fix: `git tag -d vX && git push origin :refs/tags/vX && git tag vX && git push origin vX`. Poll `https://api.github.com/repos/tongtf/sshinjector/actions/runs?per_page=1` for the run status
