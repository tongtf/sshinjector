package com.sshinjector.domain.vpn.tunnel

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelRouter @Inject constructor(
    private val tunnelManager: TunnelManager
) {
    companion object {
        private const val TAG = "TunnelRouter"
    }

    private val uidToPackage = ConcurrentHashMap<Int, String>()

    @Volatile
    private var routeConfig: RouteConfig = RouteConfig()

    private val tagTunnelCache = ConcurrentHashMap<String, String>()
    private val lbCounter = AtomicInteger(0)

    fun updateConfig(config: RouteConfig) {
        routeConfig = config
        tagTunnelCache.clear()
        config.tagTunnels.forEach { entry ->
            tagTunnelCache[entry.tag] = entry.primaryTunnelId
        }
        Log.d(TAG, "Route config updated: ${config.appTags.size} apps, ${config.tagTunnels.size} tags")
    }

    fun registerUid(uid: Int, packageName: String) {
        uidToPackage[uid] = packageName
    }

    fun selectPlugin(uid: Int): TunnelPlugin {
        val packageName = uidToPackage[uid] ?: return getDefaultPlugin()

        val tags = routeConfig.appTags
            .find { it.packageName == packageName }
            ?.tags

        if (tags.isNullOrEmpty()) {
            return getDefaultPlugin()
        }

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

        routeConfig.loadBalancing?.let { lb ->
            return selectByLoadBalancing(lb)
        }

        return getDefaultPlugin()
    }

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
                val idx = lbCounter.getAndIncrement() % candidates.size
                candidates[idx]
            }
        }
    }

    private fun getDefaultPlugin(): TunnelPlugin {
        return tunnelManager.getPlugin(routeConfig.defaultTunnelId)
            ?: tunnelManager.getActiveOrFallback()
    }

    fun getAllStats(): Map<String, TunnelStats> {
        return tunnelManager.getAllActivePlugins().associate { plugin ->
            plugin.id to plugin.stats.value
        }
    }
}
