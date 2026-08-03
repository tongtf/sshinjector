# 精简清理：只保留单 SSH-D(socks5) 隧道

## 目标
分支 `refactor/ssh-only`。删除除 SSH-D 之外的所有协议隧道
(direct / https_proxy / v2ray / trojan / shadowsocks)，
保留应用自身的路由/分流/DNS 等代理模式能力，全部由 socks5 承载。
**不保留任何旧模式兼容。**

## 删除清单

### 文件
- `data/remote/tunnel/DirectTunnelPlugin.kt`
- `data/remote/tunnel/HttpsProxyTunnelPlugin.kt`
- `data/remote/tunnel/V2RayTunnelPlugin.kt`
- `data/remote/tunnel/TrojanTunnelPlugin.kt`
- `data/remote/tunnel/ShadowsocksTunnelPlugin.kt`
- `ui/screen/server/TunnelConfigForm.kt` (动态表单)
- `domain/vpn/tunnel/TunnelRouter.kt`
- `domain/vpn/tunnel/RouteConfig.kt`
- `ui/screen/settings/RouteSettingsScreen.kt`
- `ui/screen/settings/RouteSettingsViewModel.kt`

> 保留: `Socks5TunnelPlugin.kt`, `Socks5ProxyServer.kt`,
> `JschTunnelChannel.kt`, `JschSshClient.kt`
> (其中 "direct"/ChannelDirectTCPIP 是 SSH direct-tcpip, 属 SSH-D 必需)

### 代码
- `di/TunnelModule.kt`: 只留 `bindSocks5`
- `domain/vpn/tunnel/TunnelConfig.kt`: 只留 `Socks5` + `CommonConfig`
- `domain/model/DomainModels.kt`: `ServerConfig` 删 `tunnelType`, `tunnelConfigJson`
- `domain/usecase/VpnController.kt`:
  - `buildTunnelConfig` 直构 `TunnelConfig.Socks5`
  - `connect` 固定 `startPlugin("socks5", ...)`
  - 删 `loadRouteConfig`, `tunnelRouter` 依赖
- 数据: `Entities.kt` 删两列; `AppDatabase.kt` version 3->4,
  migration `DROP COLUMN tunnelType/tunnelConfigJson`; `ServerRepository.kt` 同步
- UI: `ServerEditScreen`/`ViewModel` 删隧道选择 + 动态字段;
  保留 socksPort 可编辑
- 路由规则整页 `RouteSettings*` 删除
- `SettingsScreen`/`SettingsViewModel`: 隧道选择相关同步精简
- `ui/navigation/NavGraph.kt`: 删 route 路由 + 其余隧道引用
- `AGENTS.md`: 隧道清单收敛为仅 `socks5`

## 验证
```
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew ktlintCheck
./gradlew detekt
./gradlew lint
```