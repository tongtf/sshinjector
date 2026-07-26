# SSHInjector 插件化隧道架构设计文档

> 版本: v1.0  
> 日期: 2026-07-26  
> 状态: 设计阶段

---

## 1. 项目背景与目标

### 1.1 当前架构

SSHInjector 是一个 Android 14+ SSH SOCKS5 代理应用。当前数据流为硬编码的单链路：

```
TUN Interface
    ↓
VpnController.processPacket()     [读取 IP 包]
    ↓
PacketProcessor                   [解析 IPv4/IPv6/TCP/UDP]
    ↓
Socks5ProxyServer (本地 SOCKS5)   [SOCKS5 握手 + CONNECT]
    ↓
JschSshClient (SSH DirectTCPIP)   [SSH 隧道转发]
    ↓
远端 SSH 服务器
```

### 1.2 关键耦合点

| 耦合位置 | 耦合方式 | 问题 |
|----------|----------|------|
| `PacketProcessor` → `Socks5ProxyServer` | 构造函数直接注入 | 无法替换为其他隧道 |
| `Socks5ProxyServer` → `SshChannelFactory` | 构造函数直接注入 | 无法使用非 SSH 隧道 |
| `VpnController.connect()` | 硬编码 5 步流程 | 新增隧道类型需改此处 |
| `DnsInterceptor` | 通过 SOCKS5 TCP 转发 | DNS 方案与隧道强绑定 |

### 1.3 设计目标

| 目标 | 描述 |
|------|------|
| **可插拔** | 新增隧道类型只需实现接口 + 注册，0 核心代码改动 |
| **可切换** | UI 上选择隧道模式，运行时切换，无需重装 |
| **多隧道并行** | 多个隧道同时活跃，按应用/标签分流 |
| **按应用分流** | 不同应用走不同隧道（如微信走 SOCKS5，浏览器走直连） |
| **标签路由** | 为应用打标签，按标签匹配隧道（如 "工作" 标签走 HTTPS Proxy） |
| **负载均衡** | 多条隧道间流量均衡分配 |
| **向后兼容** | 现有 SSH+SOCKS5 作为默认插件保留 |
| **类型安全** | 编译期保证配置正确性，无运行时 ClassCastException |
| **渐进式** | 分 3 个 Phase 实施，每 Phase 独立可交付 |

---

## 2. 支持的隧道类型

### 2.1 隧道协议矩阵

| 协议 | TCP | UDP | DNS | 域名直连 | IP 直连 | 实现复杂度 | 优先级 |
|------|-----|-----|-----|----------|---------|------------|--------|
| **SOCKS5 (SSH)** | ✅ | ✅ | ✅ | ✅ | ✅ | 低 (已有) | P0 |
| **直连** | ✅ | ✅ | ✅ | ✅ | ✅ | 极低 | P0 |
| **HTTPS Proxy** | ✅ | ❌ | ❌ | ✅ | ✅ | 中 | P1 |
| **V2Ray/VMess** | ✅ | ✅ | ✅ | ✅ | ✅ | 高 | P1 |
| **Trojan** | ✅ | ❌ | ✅ | ✅ | ✅ | 中 | P2 |
| **Shadowsocks** | ✅ | ✅ | ✅ | ❌ | ✅ | 中 | P2 |

### 2.2 各协议特点

**SOCKS5 (SSH)** — 当前模式。通过 SSH ChannelDirectTCPIP 建立隧道，本地运行 SOCKS5 代理服务器。支持 TCP CONNECT + UDP ASSOCIATE。

**直连** — 不经过任何代理，流量直接通过物理网卡发出。用于白名单模式下不需要代理的应用。

**HTTPS Proxy** — 通过 HTTP CONNECT 方法建立隧道。仅支持 TCP，不支持 UDP。适合企业环境。

**V2Ray/VMess** — 基于 V2Ray-core 的加密隧道协议。支持多种传输层（TCP/WS/gRPC）+ TLS。功能最强但依赖体积大。

**Trojan** — 伪装为 HTTPS 流量的代理协议。基于 TLS，抗检测能力强。

**Shadowsocks** — 轻量级加密代理协议。支持 UDP 转发，实现简单。

---

## 3. 接口设计

### 3.1 核心接口: TunnelPlugin

```kotlin
// domain/vpn/tunnel/TunnelPlugin.kt
package com.sshinjector.domain.vpn.tunnel

/**
 * 隧道插件接口
 *
 * 每种隧道模式实现此接口。职责：
 * 1. 管理与远端代理服务器的连接生命周期
 * 2. 提供 TCP 通道建立能力
 * 3. 可选：提供 UDP 转发、DNS 转发能力
 */
interface TunnelPlugin {

    /** 插件唯一标识 (如 "socks5", "https_proxy", "v2ray") */
    val id: String

    /** 显示名称 (如 "SOCKS5 (SSH)", "HTTPS Proxy") */
    val displayName: String

    /** 插件图标资源 ID (UI 用) */
    val iconResId: Int

    /** 支持的能力集合 */
    val capabilities: Set<TunnelCapability>

    /** 配置描述器 (UI 动态渲染配置表单用) */
    val configDescriptor: TunnelConfigDescriptor

    // ── 生命周期 ──

    /**
     * 连接到远端代理服务器
     * @param config 隧道配置 (具体类型由插件自行转换)
     * @return 成功或失败原因
     */
    suspend fun connect(config: TunnelConfig): Result<Unit>

    /** 断开连接并释放资源 */
    suspend fun disconnect()

    /** 当前连接状态 */
    val state: StateFlow<TunnelState>

    // ── TCP 转发 ──

    /**
     * 建立到目标主机的 TCP 通道
     *
     * @param host 目标主机名或 IP
     * @param port 目标端口
     * @return TunnelChannel 用于双向数据传输，失败返回 null
     */
    fun openTcpChannel(host: String, port: Int): TunnelChannel?

    // ── UDP 转发 (可选) ──

    /**
     * 发送 UDP 数据到远端代理服务器
     *
     * 默认实现抛出 UnsupportedOperationException。
     * 仅 UDP-capable 的插件需要覆写。
     */
    fun sendUdp(dstHost: String, dstPort: Int, payload: ByteArray) {
        throw UnsupportedOperationException("UDP not supported by ${id}")
    }

    // ── DNS 转发 (可选) ──

    /**
     * 通过隧道转发 DNS 查询到远端 DNS 服务器
     *
     * @param query DNS 查询报文 (wire format)
     * @return DNS 响应报文，null 表示不支持或失败
     */
    suspend fun forwardDns(query: ByteArray): ByteArray? = null

    // ── 统计 ──

    /** 实时统计信息 */
    val stats: StateFlow<TunnelStats>
}
```

### 3.2 能力枚举

```kotlin
// domain/vpn/tunnel/TunnelCapability.kt
package com.sshinjector.domain.vpn.tunnel

/**
 * 隧道能力声明
 * 用于 UI 展示和运行时能力检查
 */
enum class TunnelCapability {
    /** TCP CONNECT 转发 */
    TCP,
    /** UDP ASSOCIATE 转发 */
    UDP,
    /** DNS 查询转发 (通过隧道) */
    DNS_OVER_TUNNEL,
    /** 支持域名直连 (远端服务器解析 DNS) */
    DOMAIN_RESOLVE,
    /** 支持 IP 直连 */
    IP_CONNECT,
    /** 支持 TLS 传输层加密 */
    TLS,
}
```

### 3.3 配置模型

```kotlin
// domain/vpn/tunnel/TunnelConfig.kt
package com.sshinjector.domain.vpn.tunnel

import kotlinx.serialization.Serializable

/**
 * 隧道配置 — sealed class 保证类型安全
 *
 * 每种隧道模式一个子类，字段各不相同。
 * 序列化为 JSON 存储到数据库，反序列化时由 TunnelManager 按 tunnelType 分发。
 */
@Serializable
sealed class TunnelConfig {

    /** 公共字段 */
    abstract val common: CommonConfig

    @Serializable
    data class CommonConfig(
        val connectTimeout: Int = 10000,
        val keepAliveInterval: Int = 30000,
    )

    // ── SOCKS5 (SSH) ──

    @Serializable
    data class Socks5(
        override val common: CommonConfig = CommonConfig(),
        val sshHost: String,
        val sshPort: Int = 22,
        val sshUsername: String,
        val sshKeyAlias: String,
        val sshPassword: String? = null,
        val sshKeyAlgorithm: String = "Ed25519",
        val socksPort: Int = 1080,
    ) : TunnelConfig()

    // ── 直连 ──

    @Serializable
    data object Direct : TunnelConfig() {
        override val common: CommonConfig = CommonConfig()
    }

    // ── HTTPS Proxy ──

    @Serializable
    data class HttpsProxy(
        override val common: CommonConfig = CommonConfig(),
        val proxyHost: String,
        val proxyPort: Int = 443,
        val username: String? = null,
        val password: String? = null,
        val useTls: Boolean = true,
        val sni: String? = null,
    ) : TunnelConfig()

    // ── V2Ray / VMess ──

    @Serializable
    data class V2Ray(
        override val common: CommonConfig = CommonConfig(),
        val serverHost: String,
        val serverPort: Int = 443,
        val uuid: String,
        val alterId: Int = 0,
        val security: String = "auto",       // auto, aes-128-gcm, chacha20-poly1305, none
        val network: String = "tcp",          // tcp, ws, grpc, h2, quic
        val path: String? = null,             // ws/h2 路径
        val serviceName: String? = null,      // gRPC 服务名
        val useTls: Boolean = true,
        val sni: String? = null,
        val allowInsecure: Boolean = false,
    ) : TunnelConfig()

    // ── Trojan ──

    @Serializable
    data class Trojan(
        override val common: CommonConfig = CommonConfig(),
        val serverHost: String,
        val serverPort: Int = 443,
        val password: String,
        val sni: String? = null,
        val allowInsecure: Boolean = false,
        val peer: String? = null,
    ) : TunnelConfig()

    // ── Shadowsocks ──

    @Serializable
    data class Shadowsocks(
        override val common: CommonConfig = CommonConfig(),
        val serverHost: String,
        val serverPort: Int = 8388,
        val password: String,
        val method: String = "aes-256-gcm",  // 加密方法
        val plugin: String? = null,           // 插件名称 (如 obfs-local)
        val pluginOpts: String? = null,
    ) : TunnelConfig()
}
```

### 3.4 配置描述器 (UI 动态渲染)

```kotlin
// domain/vpn/tunnel/TunnelConfigDescriptor.kt
package com.sshinjector.domain.vpn.tunnel

/**
 * 配置描述器 — 告诉 UI 如何渲染隧道配置表单
 *
 * 每个插件提供自己的描述器，UI 按描述器动态生成输入字段。
 */
data class TunnelConfigDescriptor(
    val fields: List<ConfigField>
)

sealed class ConfigField {
    abstract val key: String
    abstract val label: String
    abstract val required: Boolean
    abstract val defaultValue: Any?

    data class TextField(
        override val key: String,
        override val label: String,
        override val required: Boolean = true,
        override val defaultValue: Any? = null,
        val placeholder: String = "",
        val isPassword: Boolean = false,
    ) : ConfigField()

    data class NumberField(
        override val key: String,
        override val label: String,
        override val required: Boolean = true,
        override val defaultValue: Any? = null,
        val min: Int = 0,
        val max: Int = 65535,
    ) : ConfigField()

    data class SwitchField(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val defaultValue: Any? = true,
    ) : ConfigField()

    data class DropdownField(
        override val key: String,
        override val label: String,
        override val required: Boolean = true,
        override val defaultValue: Any? = null,
        val options: List<Pair<String, String>>, // (value, label)
    ) : ConfigField()
}
```

### 3.5 连接状态与统计

```kotlin
// domain/vpn/tunnel/TunnelState.kt
package com.sshinjector.domain.vpn.tunnel

data class TunnelState(
    val status: Status = Status.Disconnected,
    val error: String? = null,
    val serverAddress: String? = null,
) {
    enum class Status {
        Disconnected,
        Connecting,
        Authenticating,  // 部分协议需要独立认证步骤
        Connected,
        Disconnecting,
        Failed
    }
}

data class TunnelStats(
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val activeTcpConnections: Int = 0,
    val uptimeMs: Long = 0,
)
```

### 3.6 隧道通道 (保留现有)

```kotlin
// domain/vpn/TunnelChannel.kt — 保持不变
interface TunnelChannel {
    fun connect(timeoutMs: Int): Boolean
    val inputStream: InputStream?
    val outputStream: OutputStream?
    val isConnected: Boolean
    fun disconnect()
}
```

---

## 4. 多隧道路由架构

### 4.1 架构总览

改造后的数据流支持多隧道并行 + 按应用分流：

```
TUN Interface
    ↓
VpnController.processPacket()
    ↓
PacketProcessor                   [解析 IPv4/IPv6/TCP/UDP + 提取 UID]
    ↓
TunnelRouter                      [按 UID → 标签 → 隧道 映射选择插件]
    ↓
┌──────────┬──────────┬──────────┐
│ SOCKS5   │ HTTPS    │ Direct   │  ← 多个插件同时活跃
│ Plugin   │ Plugin   │ Plugin   │
└──────────┴──────────┴──────────┘
    ↓            ↓           ↓
  远端A       远端B       物理网卡
```

### 4.2 路由决策流程

```
收到 IP 包
    ↓
提取源 UID (通过 /proc/net/tcp 或 Android NetStats)
    ↓
查询 AppTagMapping: UID → [tag1, tag2, ...]
    ↓
查询 TagTunnelMapping: tag → tunnelId
    ↓
查询 TunnelManager: tunnelId → activePlugin
    ↓
调用 plugin.openTcpChannel() 转发
```

### 4.3 路由配置模型

```kotlin
// domain/vpn/tunnel/RouteConfig.kt
package com.sshinjector.domain.vpn.tunnel

import kotlinx.serialization.Serializable

/**
 * 应用标签配置
 * 一个应用可以有多个标签，一个标签可以关联多个应用
 */
@Serializable
data class AppTagEntry(
    val packageName: String,
    val tags: Set<String>,          // 如 {"工作", "社交"}
)

/**
 * 标签到隧道的映射
 * 一个标签可以有主隧道 + 备用隧道 (故障切换)
 */
@Serializable
data class TagTunnelEntry(
    val tag: String,
    val primaryTunnelId: String,    // 主隧道 ID
    val fallbackTunnelId: String? = null,  // 备用隧道 (可选)
)

/**
 * 完整路由配置
 */
@Serializable
data class RouteConfig(
    /** 应用 → 标签映射 */
    val appTags: List<AppTagEntry> = emptyList(),
    /** 标签 → 隧道映射 */
    val tagTunnels: List<TagTunnelEntry> = emptyList(),
    /** 默认隧道 (未匹配任何标签时使用) */
    val defaultTunnelId: String = "socks5",
    /** 负载均衡策略 */
    val loadBalancing: LoadBalancingConfig? = null,
)

@Serializable
data class LoadBalancingConfig(
    val strategy: Strategy = Strategy.RoundRobin,
    val tunnelIds: List<String>,    // 参与负载均衡的隧道 ID 列表
) {
    enum class Strategy {
        RoundRobin,      // 轮询
        LeastConn,       // 最少连接数
        Random,          // 随机
        Weighted,        // 加权 (需要额外配置权重)
    }
}
```

### 4.4 路由管理器

```kotlin
// domain/vpn/tunnel/TunnelRouter.kt
package com.sshinjector.domain.vpn.tunnel

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隧道路由器
 *
 * 根据应用 UID/包名 → 标签 → 隧道 映射，选择正确的隧道插件。
 * 支持：按应用分流、标签路由、负载均衡、故障切换。
 */
@Singleton
class TunnelRouter @Inject constructor(
    private val tunnelManager: TunnelManager
) {
    companion object {
        private const val TAG = "TunnelRouter"
    }

    // UID → 包名缓存 (Android UID 是每个应用唯一的)
    private val uidToPackage = ConcurrentHashMap<Int, String>()

    // 当前路由配置
    @Volatile
    private var routeConfig: RouteConfig = RouteConfig()

    // 标签 → 隧道映射缓存
    private val tagTunnelCache = ConcurrentHashMap<String, String>()

    // 负载均衡轮询计数器
    private val lbCounter = AtomicInteger(0)

    /**
     * 更新路由配置
     */
    fun updateConfig(config: RouteConfig) {
        routeConfig = config
        tagTunnelCache.clear()
        config.tagTunnels.forEach { entry ->
            tagTunnelCache[entry.tag] = entry.primaryTunnelId
        }
        Log.d(TAG, "Route config updated: ${config.appTags.size} apps, ${config.tagTunnels.size} tags")
    }

    /**
     * 注册 UID → 包名映射
     * 由 VpnController 在 packetLoop 中调用
     */
    fun registerUid(uid: Int, packageName: String) {
        uidToPackage[uid] = packageName
    }

    /**
     * 根据 UID 选择隧道插件
     *
     * @param uid 发起连接的应用 UID
     * @return 匹配的隧道插件，若无匹配则返回默认插件
     */
    fun selectPlugin(uid: Int): TunnelPlugin {
        val packageName = uidToPackage[uid] ?: return getDefaultPlugin()

        // 1. 查找应用标签
        val tags = routeConfig.appTags
            .find { it.packageName == packageName }
            ?.tags

        if (tags.isNullOrEmpty()) {
            Log.d(TAG, "No tags for $packageName, using default")
            return getDefaultPlugin()
        }

        // 2. 查找标签对应的隧道
        for (tag in tags) {
            val tunnelId = tagTunnelCache[tag]
            if (tunnelId != null) {
                val plugin = tunnelManager.getPlugin(tunnelId)
                if (plugin != null && plugin.state.value.status == TunnelState.Status.Connected) {
                    Log.d(TAG, "Selected tunnel: $tunnelId for $packageName (tag: $tag)")
                    return plugin
                }
            }
        }

        // 3. 所有标签隧道都不可用，检查是否有负载均衡配置
        routeConfig.loadBalancing?.let { lb ->
            return selectByLoadBalancing(lb)
        }

        // 4. 兜底: 使用默认隧道
        Log.d(TAG, "No matching tunnel for $packageName tags=$tags, using default")
        return getDefaultPlugin()
    }

    /**
     * 负载均衡选择
     */
    private fun selectByLoadBalancing(config: LoadBalancingConfig): TunnelPlugin {
        val candidates = config.tunnelIds.mapNotNull { tunnelManager.getPlugin(it) }
            .filter { it.state.value.status == TunnelState.Status.Connected }

        if (candidates.isEmpty()) return getDefaultPlugin()

        return when (config.strategy) {
            LoadBalancingConfig.Strategy.RoundRobin -> {
                val idx = lbCounter.getAndIncrement() % candidates.size
                candidates[idx]
            }
            LoadBalancingConfig.Strategy.LeastConn -> {
                candidates.minByOrNull { it.stats.value.activeTcpConnections }
                    ?: candidates.first()
            }
            LoadBalancingConfig.Strategy.Random -> {
                candidates.random()
            }
            LoadBalancingConfig.Strategy.Weighted -> {
                // 简化: 当前不支持权重，退化为 Round Robin
                val idx = lbCounter.getAndIncrement() % candidates.size
                candidates[idx]
            }
        }
    }

    private fun getDefaultPlugin(): TunnelPlugin {
        return tunnelManager.getPlugin(routeConfig.defaultTunnelId)
            ?: tunnelManager.getActiveOrFallback()
    }

    /**
     * 获取所有活跃隧道的统计信息
     */
    fun getAllStats(): Map<String, TunnelStats> {
        return tunnelManager.getAllActivePlugins().associate { plugin ->
            plugin.id to plugin.stats.value
        }
    }
}
```

### 4.5 隧道管理器扩展

```kotlin
// domain/vpn/tunnel/TunnelManager.kt — 扩展多隧道支持

@Singleton
class TunnelManager @Inject constructor(
    private val plugins: Map<String, @JvmSuppressWildcards TunnelPlugin>
) {
    // ... 现有字段 ...

    // 多隧道并行: 保存多个活跃插件
    private val activePlugins = ConcurrentHashMap<String, TunnelPlugin>()

    /**
     * 启动指定隧道 (不替换其他隧道)
     */
    suspend fun startPlugin(pluginId: String, config: TunnelConfig): Result<Unit> {
        val plugin = plugins[pluginId]
            ?: return Result.failure(IllegalArgumentException("Unknown: $pluginId"))

        val result = plugin.connect(config)
        if (result.isSuccess) {
            activePlugins[pluginId] = plugin
            _activePlugin.value = plugin  // 保持兼容: 最后启动的作为 "主" 插件
        }
        return result
    }

    /**
     * 停止指定隧道
     */
    suspend fun stopPlugin(pluginId: String) {
        activePlugins.remove(pluginId)?.disconnect()
    }

    /**
     * 停止所有隧道
     */
    suspend fun stopAll() {
        activePlugins.values.forEach { it.disconnect() }
        activePlugins.clear()
        _activePlugin.value = null
    }

    /**
     * 获取所有活跃插件
     */
    fun getAllActivePlugins(): List<TunnelPlugin> {
        return activePlugins.values.toList()
    }

    /**
     * 获取指定插件
     */
    fun getPlugin(id: String): TunnelPlugin? = plugins[id]

    // 保留原有 switchTo 方法 (单隧道切换兼容)
    suspend fun switchTo(pluginId: String, config: TunnelConfig): Result<Unit> {
        stopAll()
        return startPlugin(pluginId, config)
    }
}
```

### 4.6 PacketProcessor 路由改造

```kotlin
// domain/vpn/PacketProcessor.kt — 路由改造

class PacketProcessor @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val tunnelRouter: TunnelRouter  // 新增
) {
    // ... 现有字段 ...

    /**
     * 处理 TCP SYN 包 — 增加路由选择
     */
    private fun processTcpPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress, dstIp: InetAddress,
        payloadStart: Int, payloadLength: Int,
        tunFd: java.io.FileDescriptor,
        uid: Int = -1  // 新增: 来源应用 UID
    ): Boolean {
        // ... 现有 TCP 解析逻辑 ...

        if (syn && !ack) {
            conn = createTcpConnection(connKey, srcIp, dstIp, srcPort, dstPort)
            conn.browserSeq = (seqNum.toLong()) and 0xFFFFFFFFL
            conn.state = TcpConnection.TcpState.SynSent

            // 路由选择: 根据 UID 选择隧道插件
            val plugin = if (uid >= 0) {
                tunnelRouter.selectPlugin(uid)
            } else {
                tunnelManager.getActiveOrFallback()
            }
            conn.tunnelPluginId = plugin.id  // 记录使用的隧道

            forwardSynToTunnel(conn, plugin)
        }
        // ...
    }

    /**
     * 转发 SYN 到指定隧道插件
     */
    private fun forwardSynToTunnel(conn: TcpConnection, plugin: TunnelPlugin) {
        scope.launch {
            try {
                val channel = plugin.openTcpChannel(conn.dstIp.hostAddress, conn.dstPort)
                if (channel == null) {
                    Log.e(TAG, "openTcpChannel failed for plugin ${plugin.id}")
                    return@launch
                }

                val connected = channel.connect(5000)
                if (!connected) {
                    channel.disconnect()
                    Log.e(TAG, "channel.connect failed for plugin ${plugin.id}")
                    return@launch
                }

                conn.tunnelChannel = channel
                conn.state = TcpConnection.TcpState.Established
                // ... 后续逻辑不变 ...
            } catch (e: Exception) {
                Log.e(TAG, "forwardSynToTunnel failed", e)
            }
        }
    }
}
```

### 4.7 UID 提取方案

Android VPNService 可以通过以下方式获取包名/UID：

```kotlin
// 方案 1: 使用 Android ProxiedAppsManager (推荐)
// Android 14+ 支持通过 VPN 接口获取包名
// 需要在 establishVpnInterface 时配置 allowedApplications

// 方案 2: 通过 /proc/net/tcp 解析
// 读取 /proc/net/tcp 获取 (local_ip:port → uid) 映射
// 性能开销较大，需要定期刷新

// 方案 3: 使用 ConnectivityManager + NetworkStatsManager
// 查询每个 UID 的网络流量
```

推荐方案 1: 在 `VpnService.establishVpnInterface` 中通过 `addAllowedApplication` / `addDisallowedApplication` 配置白名单，Android 系统会自动为每个包分配 UID。PacketProcessor 通过 `socket.getPeerCredential().uid` 或解析 `/proc/net/tcp` 获取 UID。

---

## 5. 隧道管理器 (多隧道版)

```kotlin
// domain/vpn/tunnel/TunnelManager.kt
package com.sshinjector.domain.vpn.tunnel

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隧道管理器
 *
 * 职责：
 * 1. 维护已注册的插件列表 (由 Hilt Map 注入)
 * 2. 管理当前活跃插件
 * 3. 处理插件切换 (断开旧 → 连接新)
 * 4. 提供 fallback 机制
 */
@Singleton
class TunnelManager @Inject constructor(
    private val plugins: Map<String, @JvmSuppressWildcards TunnelPlugin>
) {
    companion object {
        private const val TAG = "TunnelManager"
        private const val DEFAULT_PLUGIN_ID = "socks5"
    }

    private val _activePlugin = MutableStateFlow<TunnelPlugin?>(null)
    val activePlugin: StateFlow<TunnelPlugin?> = _activePlugin.asStateFlow()

    private val _availablePlugins = MutableStateFlow(plugins.values.toList())
    val availablePlugins: StateFlow<List<TunnelPlugin>> = _availablePlugins.asStateFlow()

    init {
        Log.d(TAG, "Registered plugins: ${plugins.keys}")
    }

    /**
     * 切换到指定隧道插件
     *
     * @param pluginId 目标插件 ID
     * @param config 隧道配置
     * @return 连接结果
     */
    suspend fun switchTo(pluginId: String, config: TunnelConfig): Result<Unit> {
        val plugin = plugins[pluginId]
            ?: return Result.failure(IllegalArgumentException("Unknown tunnel: $pluginId"))

        Log.d(TAG, "Switching to plugin: $pluginId")

        // 1. 断开当前插件
        _activePlugin.value?.let { current ->
            Log.d(TAG, "Disconnecting current plugin: ${current.id}")
            try {
                current.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting ${current.id}: ${e.message}")
            }
        }

        // 2. 连接新插件
        val result = plugin.connect(config)
        if (result.isSuccess) {
            _activePlugin.value = plugin
            Log.d(TAG, "Successfully connected to: $pluginId")
        } else {
            Log.e(TAG, "Failed to connect to $pluginId: ${result.exceptionOrNull()?.message}")
            _activePlugin.value = null
        }

        return result
    }

    /**
     * 断开当前活跃插件
     */
    suspend fun disconnect() {
        _activePlugin.value?.let { plugin ->
            Log.d(TAG, "Disconnecting: ${plugin.id}")
            plugin.disconnect()
            _activePlugin.value = null
        }
    }

    /**
     * 获取当前活跃插件，若无则返回默认插件 (SOCKS5)
     */
    fun getActiveOrFallback(): TunnelPlugin {
        return _activePlugin.value
            ?: plugins[DEFAULT_PLUGIN_ID]
            ?: throw IllegalStateException("No tunnel plugin available")
    }

    /**
     * 按 ID 获取插件
     */
    fun getPlugin(id: String): TunnelPlugin? = plugins[id]

    /**
     * 检查指定插件是否已注册
     */
    fun hasPlugin(id: String): Boolean = plugins.containsKey(id)
}
```

---

## 5. 插件实现

### 5.1 SOCKS5 插件 (重构现有代码)

```kotlin
// data/remote/tunnel/Socks5TunnelPlugin.kt
package com.sshinjector.data.remote.tunnel

import com.sshinjector.R
import com.sshinjector.data.remote.ssh.JschSshClient
import com.sshinjector.data.remote.ssh.SshKeyManager
import com.sshinjector.domain.model.ServerConfig
import com.sshinjector.domain.vpn.Socks5ProxyServer
import com.sshinjector.domain.vpn.TunnelChannel
import com.sshinjector.domain.vpn.tunnel.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SOCKS5 隧道插件
 *
 * 包装现有 JschSshClient + Socks5ProxyServer 为插件接口。
 * 这是默认插件，功能最完整。
 */
@Singleton
class Socks5TunnelPlugin @Inject constructor(
    private val keyManager: SshKeyManager
) : TunnelPlugin {

    override val id = "socks5"
    override val displayName = "SOCKS5 (SSH)"
    override val iconResId = R.drawable.ic_vpn_key
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.UDP,
        TunnelCapability.DNS_OVER_TUNNEL,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "sshHost", label = "SSH 服务器", placeholder = "example.com"),
            ConfigField.NumberField(key = "sshPort", label = "SSH 端口", defaultValue = 22),
            ConfigField.TextField(key = "sshUsername", label = "用户名"),
            ConfigField.TextField(key = "sshKeyAlias", label = "密钥别名"),
            ConfigField.TextField(key = "sshPassword", label = "密码", isPassword = true, required = false),
            ConfigField.DropdownField(
                key = "sshKeyAlgorithm", label = "密钥算法",
                options = listOf("Ed25519" to "Ed25519", "RSA4096" to "RSA 4096", "ECDSA_P256" to "ECDSA P-256")
            ),
            ConfigField.NumberField(key = "socksPort", label = "本地 SOCKS 端口", defaultValue = 1080, min = 1024),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var jschClient: JschSshClient? = null
    private var socksServer: Socks5ProxyServer? = null
    private var startTime: Long = 0

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        val c = config as TunnelConfig.Socks5
        _state.value = TunnelState(status = TunnelState.Status.Connecting, serverAddress = c.sshHost)

        return try {
            // 1. SSH 连接
            _state.value = _state.value.copy(status = TunnelState.Status.Authenticating)
            val client = JschSshClient(keyManager)
            val sshConfig = ServerConfig(
                host = c.sshHost, port = c.sshPort,
                username = c.sshUsername, keyAlias = c.sshKeyAlias,
                password = c.sshPassword,
                keyAlgorithm = ServerConfig.KeyAlgorithm.valueOf(c.sshKeyAlgorithm),
                connectTimeout = c.common.connectTimeout,
                keepAliveInterval = c.common.keepAliveInterval,
            )
            val result = client.connect(sshConfig)
            if (!result.success) throw Exception(result.error ?: "SSH connection failed")

            // 2. 启动本地 SOCKS5 代理
            val proxy = Socks5ProxyServer(client)
            val proxyResult = proxy.start(c.socksPort, "127.0.0.1")
            if (proxyResult.isFailure) throw proxyResult.exceptionOrNull()!!

            jschClient = client
            socksServer = proxy
            startTime = System.currentTimeMillis()
            _state.value = TunnelState(status = TunnelState.Status.Connected, serverAddress = c.sshHost)
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = TunnelState(status = TunnelState.Status.Failed, error = e.message)
            disconnect()
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        _state.value = _state.value.copy(status = TunnelState.Status.Disconnecting)
        socksServer?.stop()
        jschClient?.disconnect()
        jschClient = null
        socksServer = null
        startTime = 0
        _state.value = TunnelState()
    }

    override fun openTcpChannel(host: String, port: Int): TunnelChannel? {
        return jschClient?.createDirectChannel(host, port)
    }

    override fun sendUdp(dstHost: String, dstPort: Int, payload: ByteArray) {
        // TODO: 通过 SOCKS5 UDP ASSOCIATE 转发
        // 当前实现由 PacketProcessor 直接处理 UDP，此处预留
    }

    override suspend fun forwardDns(query: ByteArray): ByteArray? {
        // DNS 转发由 DnsInterceptor 处理，此处不重复实现
        return null
    }

    override val stats: StateFlow<TunnelStats> = _stats
}
```

### 5.2 直连插件

```kotlin
// data/remote/tunnel/DirectTunnelPlugin.kt
package com.sshinjector.data.remote.tunnel

import com.sshinjector.R
import com.sshinjector.domain.vpn.TunnelChannel
import com.sshinjector.domain.vpn.tunnel.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直连插件 — 不经过任何代理，流量直接通过物理网卡
 *
 * 用于白名单模式下不需要代理的应用。
 * openTcpChannel 直接建立到目标的 TCP 连接。
 */
@Singleton
class DirectTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "direct"
    override val displayName = "直连"
    override val iconResId = R.drawable.ic_vpn_key // TODO: 换图标
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.UDP,
        TunnelCapability.DNS_OVER_TUNNEL,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
    )

    override val configDescriptor = TunnelConfigDescriptor(fields = emptyList())

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        _state.value = TunnelState(status = TunnelState.Status.Connected, serverAddress = "direct")
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _state.value = TunnelState()
    }

    override fun openTcpChannel(host: String, port: Int): TunnelChannel? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            DirectTunnelChannel(socket)
        } catch (e: Exception) {
            null
        }
    }

    override fun sendUdp(dstHost: String, dstPort: Int, payload: ByteArray) {
        // 直连模式下 UDP 直接由系统路由处理，不需要隧道转发
    }

    override suspend fun forwardDns(query: ByteArray): ByteArray? = null
}

/**
 * 直连通道 — 包装普通 Socket
 */
private class DirectTunnelChannel(
    private val socket: Socket
) : TunnelChannel {

    override fun connect(timeoutMs: Int): Boolean = socket.isConnected

    override val inputStream: InputStream? get() = if (socket.isConnected) socket.getInputStream() else null
    override val outputStream: OutputStream? get() = if (socket.isConnected) socket.getOutputStream() else null
    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    override fun disconnect() {
        try { socket.close() } catch (_: Exception) {}
    }
}
```

### 5.3 HTTPS Proxy 插件

```kotlin
// data/remote/tunnel/HttpsProxyTunnelPlugin.kt
package com.sshinjector.data.remote.tunnel

import com.sshinjector.R
import com.sshinjector.domain.vpn.TunnelChannel
import com.sshinjector.domain.vpn.tunnel.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTPS Proxy 隧道插件
 *
 * 通过 HTTP CONNECT 方法建立隧道。
 * 仅支持 TCP，不支持 UDP。
 */
@Singleton
class HttpsProxyTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "https_proxy"
    override val displayName = "HTTPS Proxy"
    override val iconResId = R.drawable.ic_vpn_key // TODO: 换图标
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
        TunnelCapability.TLS,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "proxyHost", label = "代理服务器", placeholder = "proxy.example.com"),
            ConfigField.NumberField(key = "proxyPort", label = "代理端口", defaultValue = 443),
            ConfigField.TextField(key = "username", label = "用户名", required = false),
            ConfigField.TextField(key = "password", label = "密码", isPassword = true, required = false),
            ConfigField.SwitchField(key = "useTls", label = "使用 TLS", defaultValue = true),
            ConfigField.TextField(key = "sni", label = "SNI", required = false, placeholder = "留空使用代理地址"),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var config: TunnelConfig.HttpsProxy? = null

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        val c = config as TunnelConfig.HttpsProxy
        _state.value = TunnelState(status = TunnelState.Status.Connecting, serverAddress = c.proxyHost)

        return try {
            // 测试连接到代理服务器
            val socket = Socket()
            socket.connect(InetSocketAddress(c.proxyHost, c.proxyPort), c.common.connectTimeout)
            socket.close()

            this.config = c
            _state.value = TunnelState(status = TunnelState.Status.Connected, serverAddress = c.proxyHost)
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = TunnelState(status = TunnelState.Status.Failed, error = e.message)
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        config = null
        _state.value = TunnelState()
    }

    override fun openTcpChannel(host: String, port: Int): TunnelChannel? {
        val c = config ?: return null
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(c.proxyHost, c.proxyPort), c.common.connectTimeout)

            // 发送 HTTP CONNECT 请求
            val output = socket.getOutputStream()
            val connectRequest = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n"
            output.write(connectRequest.toByteArray())
            output.flush()

            // 读取响应
            val input = socket.getInputStream()
            val response = ByteArray(1024)
            val len = input.read(response)
            val responseStr = String(response, 0, len)

            if (responseStr.contains("200")) {
                HttpsTunnelChannel(socket)
            } else {
                socket.close()
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

private class HttpsTunnelChannel(
    private val socket: Socket
) : TunnelChannel {
    override fun connect(timeoutMs: Int): Boolean = socket.isConnected
    override val inputStream: InputStream? get() = if (socket.isConnected) socket.getInputStream() else null
    override val outputStream: OutputStream? get() = if (socket.isConnected) socket.getOutputStream() else null
    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
    override fun disconnect() { try { socket.close() } catch (_: Exception) {} }
}
```

### 5.4 V2Ray 插件 (预留骨架)

```kotlin
// data/remote/tunnel/V2RayTunnelPlugin.kt
package com.sshinjector.data.remote.tunnel

import com.sshinjector.R
import com.sshinjector.domain.vpn.TunnelChannel
import com.sshinjector.domain.vpn.tunnel.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V2Ray/VMess 隧道插件
 *
 * Phase 3 实现。需要引入 V2Ray-core 库。
 * 支持 VMess 协议 + 多种传输层 (TCP/WS/gRPC) + TLS。
 */
@Singleton
class V2RayTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "v2ray"
    override val displayName = "V2Ray"
    override val iconResId = R.drawable.ic_vpn_key // TODO: 换图标
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.UDP,
        TunnelCapability.DNS_OVER_TUNNEL,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
        TunnelCapability.TLS,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "serverHost", label = "服务器地址"),
            ConfigField.NumberField(key = "serverPort", label = "端口", defaultValue = 443),
            ConfigField.TextField(key = "uuid", label = "UUID"),
            ConfigField.NumberField(key = "alterId", label = "AlterID", defaultValue = 0, min = 0),
            ConfigField.DropdownField(
                key = "security", label = "加密方式",
                options = listOf("auto" to "自动", "aes-128-gcm" to "AES-128-GCM", "chacha20-poly1305" to "ChaCha20", "none" to "无")
            ),
            ConfigField.DropdownField(
                key = "network", label = "传输协议",
                options = listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC")
            ),
            ConfigField.TextField(key = "path", label = "路径 (WS/gRPC)", required = false),
            ConfigField.SwitchField(key = "useTls", label = "使用 TLS", defaultValue = true),
            ConfigField.TextField(key = "sni", label = "SNI", required = false),
            ConfigField.SwitchField(key = "allowInsecure", label = "允许不安全连接", defaultValue = false),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        // Phase 3: 集成 V2Ray-core
        // 1. 生成 V2Ray JSON 配置
        // 2. 启动 V2Ray 进程
        // 3. 等待 SOCKS/HTTP 代理端口就绪
        TODO("Phase 3: V2Ray implementation")
    }

    override suspend fun disconnect() {
        // Phase 3: 停止 V2Ray 进程
        TODO("Phase 3")
    }

    override fun openTcpChannel(host: String, port: Int): TunnelChannel? {
        // Phase 3: 通过 V2Ray 本地 SOCKS 端口建立连接
        TODO("Phase 3")
    }
}
```

---

## 6. Hilt 注册

### 6.1 插件注册模块

```kotlin
// di/TunnelModule.kt
package com.sshinjector.di

import com.sshinjector.data.remote.tunnel.DirectTunnelPlugin
import com.sshinjector.data.remote.tunnel.HttpsProxyTunnelPlugin
import com.sshinjector.data.remote.tunnel.Socks5TunnelPlugin
import com.sshinjector.data.remote.tunnel.V2RayTunnelPlugin
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
abstract class TunnelModule {

    @Binds @IntoMap @StringKey("socks5")
    abstract fun bindSocks5(impl: Socks5TunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("direct")
    abstract fun bindDirect(impl: DirectTunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("https_proxy")
    abstract fun bindHttpsProxy(impl: HttpsProxyTunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("v2ray")
    abstract fun bindV2Ray(impl: V2RayTunnelPlugin): TunnelPlugin
}
```

### 6.2 原始 Modules.kt 改造

```kotlin
// di/Modules.kt — 移除隧道相关绑定
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ... 保留数据库、DAO、Settings 等绑定 ...

    // 移除以下绑定 (已迁移到 TunnelModule):
    // - provideSshChannelFactory
    // - provideSocks5ProxyServer
    // - providePacketProcessor

    // 新增:
    @Provides @Singleton
    fun providePacketProcessor(tunnelManager: TunnelManager): PacketProcessor {
        return PacketProcessor(tunnelManager)
    }
}
```

---

## 7. 核心组件改造

### 7.1 PacketProcessor 改造

```kotlin
// domain/vpn/PacketProcessor.kt — 关键改动

// 改造前:
class PacketProcessor @Inject constructor(
    private val socks5Proxy: Socks5ProxyServer
)

// 改造后:
class PacketProcessor @Inject constructor(
    private val tunnelManager: TunnelManager
)
```

`forwardSynToSocks` 方法改造:

```kotlin
// 改造前: 直接连接本地 SOCKS5
private fun forwardSynToSocks(conn: TcpConnection) {
    val socksPort = socks5Proxy.boundPort.value ?: 1080
    val sock = SocketChannel.open()
    sock.connect(InetSocketAddress("127.0.0.1", socksPort))
    // ... SOCKS5 握手 ...
}

// 改造后: 通过 TunnelManager 获取活跃插件
private fun forwardSynToSocks(conn: TcpConnection) {
    val plugin = tunnelManager.getActiveOrFallback()
    val channel = plugin.openTcpChannel(conn.dstIp.hostAddress, conn.dstPort)
    if (channel == null) {
        // 连接失败
        return
    }
    // ... 后续逻辑不变 ...
}
```

### 7.2 VpnController 改造

```kotlin
// domain/usecase/VpnController.kt — 关键改动

// 改造前:
class VpnController @Inject constructor(
    private val sshChannelFactory: SshChannelFactory,
    private val socks5Proxy: Socks5ProxyServer,
    private val packetProcessor: PacketProcessor,
    ...
)

// 改造后:
class VpnController @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val packetProcessor: PacketProcessor,
    private val dnsInterceptor: DnsInterceptor,
    ...
)
```

`connect()` 方法改造:

```kotlin
// 改造前:
suspend fun connect(server: ServerConfig, password: String? = null): Result<Unit> {
    // 1. SSH 连接
    val connectResult = sshChannelFactory.connect(serverWithPassword)
    // 2. 启动 SOCKS5
    val proxyResult = socks5Proxy.start(1080, "127.0.0.1")
    // 3. DNS 设置
    // 4. 数据包处理
}

// 改造后:
suspend fun connect(server: ServerConfig, password: String? = null): Result<Unit> {
    // 1. 构建隧道配置
    val tunnelConfig = buildTunnelConfig(server, password)
    // 2. 切换到指定隧道
    val result = tunnelManager.switchTo(server.tunnelType, tunnelConfig)
    if (result.isFailure) throw result.exceptionOrNull()!!
    // 3. DNS 设置 (不变)
    // 4. 数据包处理 (不变)
}

private fun buildTunnelConfig(server: ServerConfig, password: String?): TunnelConfig {
    return when (server.tunnelType) {
        "socks5" -> TunnelConfig.Socks5(
            sshHost = server.host, sshPort = server.port,
            sshUsername = server.username, sshKeyAlias = server.keyAlias,
            sshPassword = password ?: server.password,
            sshKeyAlgorithm = server.keyAlgorithm.name,
        )
        "direct" -> TunnelConfig.Direct
        "https_proxy" -> TunnelConfig.HttpsProxy(
            proxyHost = server.host, proxyPort = server.port,
        )
        // ... 其他类型
        else -> TunnelConfig.Socks5(sshHost = server.host, ...)
    }
}
```

### 7.3 DomainModels 改造

```kotlin
// domain/model/DomainModels.kt — 增加字段

data class ServerConfig(
    // ... 现有字段 ...
    val tunnelType: String = "socks5",          // 隧道类型 ID
    val tunnelConfigJson: String? = null,       // 隧道特有配置 (JSON)
)
```

### 7.4 Room Entity 改造

```kotlin
// data/local/entity/Entities.kt — 增加字段

@Entity(tableName = "servers")
data class ServerEntity(
    // ... 现有字段 ...
    @ColumnInfo(name = "tunnel_type") val tunnelType: String = "socks5",
    @ColumnInfo(name = "tunnel_config_json") val tunnelConfigJson: String? = null,
)

// 需要数据库迁移: v1 → v2 (或 v2 → v3，取决于当前版本)
```

---

## 8. UI 设计

### 8.1 隧道选择 UI

在服务器编辑页面顶部增加隧道模式选择器:

```
┌─────────────────────────────────────┐
│  隧道模式                           │
│  ┌─────────┬─────────┬─────────┐   │
│  │ SOCKS5  │  直连   │  HTTPS  │   │
│  │  (SSH)  │         │  Proxy  │   │
│  └─────────┴─────────┴─────────┘   │
│                                     │
│  [根据选择动态显示配置表单]          │
│                                     │
│  SSH 服务器: [________________]     │
│  SSH 端口:   [22             ]     │
│  用户名:     [________________]     │
│  密钥别名:   [________________]     │
│  ...                                │
└─────────────────────────────────────┘
```

### 8.2 配置表单动态渲染

```kotlin
// ui/screen/server/TunnelConfigForm.kt
@Composable
fun TunnelConfigForm(
    plugin: TunnelPlugin,
    config: TunnelConfig?,
    onConfigChange: (TunnelConfig) -> Unit
) {
    val descriptor = plugin.configDescriptor

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = plugin.displayName, style = MaterialTheme.typography.titleMedium)

        // 能力标签
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            plugin.capabilities.forEach { cap ->
                AssistChip(
                    onClick = {},
                    label = { Text(cap.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 动态渲染配置字段
        descriptor.fields.forEach { field ->
            when (field) {
                is ConfigField.TextField -> {
                    OutlinedTextField(
                        value = getConfigValue(config, field.key) as? String ?: "",
                        onValueChange = { updateConfig(config, field.key, it, onConfigChange) },
                        label = { Text(field.label) },
                        placeholder = { Text(field.placeholder) },
                        visualTransformation = if (field.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is ConfigField.NumberField -> {
                    OutlinedTextField(
                        value = (getConfigValue(config, field.key) as? Int ?: field.defaultValue).toString(),
                        onValueChange = { updateConfig(config, field.key, it.toIntOrNull() ?: 0, onConfigChange) },
                        label = { Text(field.label) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is ConfigField.SwitchField -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(field.label)
                        Switch(
                            checked = getConfigValue(config, field.key) as? Boolean ?: true,
                            onCheckedChange = { updateConfig(config, field.key, it, onConfigChange) }
                        )
                    }
                }
                is ConfigField.DropdownField -> {
                    // ExposedDropdownMenu 或自定义 Spinner
                    DropdownField(
                        value = getConfigValue(config, field.key) as? String ?: "",
                        options = field.options,
                        onValueChange = { updateConfig(config, field.key, it, onConfigChange) },
                        label = field.label
                    )
                }
            }
        }
    }
}
```

### 8.3 路由配置 UI

在设置页面新增 "路由规则" 配置入口:

```
┌─────────────────────────────────────┐
│  路由规则                           │
│                                     │
│  ┌─ 活跃隧道 ──────────────────┐   │
│  │  🟢 SOCKS5 (SSH) server1   │   │
│  │  🟢 HTTPS Proxy server2    │   │
│  │  🔴 Direct (未启动)        │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─ 应用标签 ──────────────────┐   │
│  │  微信        → [工作]       │   │
│  │  钉钉        → [工作]       │   │
│  │  Chrome      → [浏览器]     │   │
│  │  系统更新    → [直连]       │   │
│  │  [+ 添加应用]               │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─ 标签路由 ──────────────────┐   │
│  │  工作        → SOCKS5       │   │
│  │  浏览器      → HTTPS Proxy  │   │
│  │  直连        → Direct       │   │
│  │  默认        → SOCKS5       │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─ 负载均衡 ──────────────────┐   │
│  │  策略: [轮询 ▾]            │   │
│  │  参与隧道: ☑ SOCKS5        │   │
│  │           ☑ HTTPS Proxy    │   │
│  │           ☐ Direct         │   │
│  └─────────────────────────────┘   │
└─────────────────────────────────────┘
```

### 8.4 Dashboard 多隧道状态

Dashboard 页面显示所有活跃隧道:

```
┌─────────────────────────────────────┐
│  🟢 已连接                          │
│                                     │
│  活跃隧道 (2):                      │
│  ┌─────────────────────────────┐   │
│  │ SOCKS5 (SSH) → server1.com │   │
│  │ ↑ 1.2 MB  ↓ 5.6 MB  12连接 │   │
│  ├─────────────────────────────┤   │
│  │ HTTPS Proxy → proxy.com    │   │
│  │ ↑ 0.8 MB  ↓ 3.2 MB   8连接 │   │
│  └─────────────────────────────┘   │
│                                     │
│  应用分流:                          │
│  微信/钉钉 → SOCKS5 (工作标签)     │
│  Chrome    → HTTPS Proxy (浏览器)  │
│  其他      → SOCKS5 (默认)         │
│                                     │
│  [断开连接]  [管理路由]            │
└─────────────────────────────────────┘
```

---

## 9. 实施计划

### Phase 1: 核心抽象层 (3-4 天)

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `domain/vpn/tunnel/TunnelPlugin.kt` | 新建接口 |
| 2 | `domain/vpn/tunnel/TunnelConfig.kt` | 新建配置模型 |
| 3 | `domain/vpn/tunnel/TunnelManager.kt` | 新建管理器 (支持多隧道) |
| 4 | `domain/vpn/tunnel/TunnelRouter.kt` | 新建路由器 (按 UID 分流) |
| 5 | `domain/vpn/tunnel/RouteConfig.kt` | 新建路由配置模型 |
| 6 | `domain/vpn/tunnel/TunnelCapability.kt` | 新建能力枚举 |
| 7 | `domain/vpn/tunnel/TunnelState.kt` | 新建状态模型 |
| 8 | `data/remote/tunnel/Socks5TunnelPlugin.kt` | 包装现有代码 |
| 9 | `data/remote/tunnel/DirectTunnelPlugin.kt` | 新建直连插件 |
| 10 | `di/TunnelModule.kt` | 新建 Hilt 模块 |
| 11 | `domain/vpn/PacketProcessor.kt` | 注入 TunnelRouter + 改造路由选择 |
| 12 | `domain/usecase/VpnController.kt` | 使用 TunnelManager + TunnelRouter |

**验收标准**: 现有 SSH+SOCKS5 功能不受影响，代码通过 lint + 测试。

### Phase 2: UI + 数据 (2-3 天)

| 步骤 | 文件 | 改动 |
|------|------|------|
| 13 | `domain/model/DomainModels.kt` | ServerConfig 增加字段 |
| 14 | `data/local/entity/Entities.kt` | Entity 增加字段 |
| 15 | `data/local/database/AppDatabase.kt` | 数据库版本升级 |
| 16 | `ui/screen/server/ServerEditScreen.kt` | 隧道选择 UI |
| 17 | `ui/screen/server/TunnelConfigForm.kt` | 动态配置表单 |
| 18 | `ui/screen/settings/RouteSettingsScreen.kt` | 路由规则配置 UI |
| 19 | `ui/screen/dashboard/DashboardScreen.kt` | 多隧道状态展示 |

**验收标准**: 可以在 UI 上选择隧道模式、配置路由规则、查看多隧道状态。

### Phase 3: 新插件 (1-2 天/插件)

| 步骤 | 文件 | 改动 |
|------|------|------|
| 16 | `data/remote/tunnel/HttpsProxyTunnelPlugin.kt` | HTTPS 代理实现 |
| 17 | `data/remote/tunnel/V2RayTunnelPlugin.kt` | V2Ray 骨架 |
| 18 | `data/remote/tunnel/TrojanTunnelPlugin.kt` | Trojan 骨架 |
| 19 | `data/remote/tunnel/ShadowsocksTunnelPlugin.kt` | Shadowsocks 骨架 |

**验收标准**: 新插件可注册、可选择、可连接（基础功能）。

---

## 10. 代码量预估

| 类别 | 新增行数 | 修改行数 | 说明 |
|------|----------|----------|------|
| 接口定义 | ~250 | 0 | TunnelPlugin + Config + State + Capability + Descriptor |
| TunnelManager (多隧道) | ~120 | 0 | 支持多插件并行 |
| TunnelRouter | ~150 | 0 | 按 UID/标签路由 + 负载均衡 |
| RouteConfig | ~60 | 0 | 路由配置模型 |
| SOCKS5 插件 | ~120 | 0 | 包装现有代码 |
| Direct 插件 | ~60 | 0 | 直连实现 |
| HttpsProxy 插件 | ~150 | 0 | HTTPS 代理 |
| V2Ray 插件 | ~100 | 0 | 骨架 |
| PacketProcessor 改造 | 0 | ~60 | 注入 TunnelRouter + 路由选择 |
| VpnController 改造 | 0 | ~80 | 多隧道管理 + 路由初始化 |
| Hilt Module | ~50 | ~60 | 拆分 + 新增 |
| DomainModels | 0 | ~8 | 增加字段 |
| Room Entity | 0 | ~15 | 增加字段 + 迁移 |
| UI 组件 | ~400 | ~150 | 隧道选择 + 配置表单 + 路由配置 + 状态展示 |
| **合计** | **~1460** | **~373** | |

---

## 11. 复杂度评估

### 11.1 改动风险矩阵

```
         低风险 ←───────────────────→ 高风险
         
Phase 1  █████░░░░░  核心抽象 (不改现有逻辑，但新增路由)
Phase 2  ██████░░░░  UI + 数据 (需数据库迁移)
Phase 3  ████████░░  新插件 (新增代码，不影响现有)
```

### 11.2 时间预估

| Phase | 工作量 | 说明 |
|-------|--------|------|
| Phase 1 | 3-4 天 | 核心抽象 + 多隧道管理器 + 路由器 + SOCKS5/Direct 插件 |
| Phase 2 | 2-3 天 | UI 集成 + 数据库迁移 + 路由配置页面 |
| Phase 3 | 1-2 天/插件 | 每个新插件独立开发 |
| **总计** | **6-10 天** | Phase 1+2 完成后即可使用，Phase 3 可按需添加 |

---

## 12. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 过早抽象 | 接口设计不合理 | Phase 1 先实现 SOCKS5 + Direct 验证接口 |
| 多隧道资源占用 | 内存/CPU 增加 | 限制最大并发隧道数 (建议 3-5 个) |
| UID 提取性能 | 每个包都要查 UID | 使用 `/proc/net/tcp` 缓存，定期刷新 (1s) |
| 路由配置复杂度 | 用户难以上手 | 提供预设模板 (如 "工作模式"、"娱乐模式") |
| PacketProcessor 改动 | 破坏现有 TCP 状态机 | 只改调用方式，状态机逻辑不变 |
| DNS 方案差异 | 不同隧道 DNS 处理不同 | `forwardDns()` 返回 null 时 DnsInterceptor 兜底 |
| 数据库迁移 | 用户数据丢失 | 使用 Room Migration，保留旧数据 |
| V2Ray 依赖体积 | APK 增大 | 作为可选依赖，可配置是否包含 |
| 负载均衡公平性 | 流量分布不均 | 使用加权轮询 + 实时连接数监控 |
