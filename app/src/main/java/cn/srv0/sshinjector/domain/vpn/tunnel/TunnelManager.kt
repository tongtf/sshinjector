package cn.srv0.sshinjector.domain.vpn.tunnel

import android.util.Log
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelManager
    @Inject
    constructor(
        private val plugins: Map<String, @JvmSuppressWildcards TunnelPlugin>,
    ) {
        companion object {
            private const val TAG = "TunnelManager"
            private const val DEFAULT_PLUGIN_ID = "socks5"
        }

        private val activePlugin = MutableStateFlow<TunnelPlugin?>(null)

        private val activePlugins = ConcurrentHashMap<String, TunnelPlugin>()

        init {
            Log.d(TAG, "Registered plugins: ${plugins.keys}")
        }

        suspend fun startPlugin(
            pluginId: String,
            config: TunnelConfig,
        ): Result<Unit> {
            val plugin =
                plugins[pluginId]
                    ?: return Result.failure(IllegalArgumentException("Unknown tunnel: $pluginId"))

            Log.d(TAG, "Starting plugin: $pluginId")
            val result = plugin.connect(config)
            if (result.isSuccess) {
                activePlugins[pluginId] = plugin
                activePlugin.value = plugin
            } else {
                Log.e(TAG, "Failed to start $pluginId: ${result.exceptionOrNull()?.message}")
                VpnController.appLogThrottled(
                    "隧道插件启动失败 · $pluginId — ${result.exceptionOrNull()?.message}",
                    level = LogLevel.ERROR,
                    throttleKey = "隧道插件启动失败",
                )
            }
            return result
        }

        suspend fun stopAll() {
            activePlugins.values.forEach { it.disconnect() }
            activePlugins.clear()
            activePlugin.value = null
        }

        fun getActiveOrFallback(): TunnelPlugin =
            activePlugin.value
                ?: plugins[DEFAULT_PLUGIN_ID]
                ?: error("No tunnel plugin available")
    }
