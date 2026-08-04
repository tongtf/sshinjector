package cn.srv0.sshinjector.domain.vpn.tunnel

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelManager @Inject constructor(
    private val plugins: Map<String, @JvmSuppressWildcards TunnelPlugin>
) {
    companion object {
        private const val TAG = "TunnelManager"
        private const val DEFAULT_PLUGIN_ID = "socks5"
    }

    private val _activePlugin = MutableStateFlow<TunnelPlugin?>(null)
    val activePlugin: StateFlow<TunnelPlugin?> = _activePlugin

    private val _availablePlugins = MutableStateFlow(plugins.values.toList())
    val availablePlugins: StateFlow<List<TunnelPlugin>> = _availablePlugins

    private val activePlugins = ConcurrentHashMap<String, TunnelPlugin>()

    init {
        Log.d(TAG, "Registered plugins: ${plugins.keys}")
    }

    suspend fun startPlugin(pluginId: String, config: TunnelConfig): Result<Unit> {
        val plugin = plugins[pluginId]
            ?: return Result.failure(IllegalArgumentException("Unknown tunnel: $pluginId"))

        Log.d(TAG, "Starting plugin: $pluginId")
        val result = plugin.connect(config)
        if (result.isSuccess) {
            activePlugins[pluginId] = plugin
            _activePlugin.value = plugin
        } else {
            Log.e(TAG, "Failed to start $pluginId: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    suspend fun stopPlugin(pluginId: String) {
        activePlugins.remove(pluginId)?.disconnect()
        if (_activePlugin.value?.id == pluginId) {
            _activePlugin.value = activePlugins.values.firstOrNull()
        }
    }

    suspend fun stopAll() {
        activePlugins.values.forEach { it.disconnect() }
        activePlugins.clear()
        _activePlugin.value = null
    }

    suspend fun switchTo(pluginId: String, config: TunnelConfig): Result<Unit> {
        stopAll()
        return startPlugin(pluginId, config)
    }

    fun getPlugin(id: String): TunnelPlugin? = plugins[id]

    fun getAllActivePlugins(): List<TunnelPlugin> = activePlugins.values.toList()

    fun hasPlugin(id: String): Boolean = plugins.containsKey(id)

    fun getActiveOrFallback(): TunnelPlugin {
        return _activePlugin.value
            ?: plugins[DEFAULT_PLUGIN_ID]
            ?: throw IllegalStateException("No tunnel plugin available")
    }
}
