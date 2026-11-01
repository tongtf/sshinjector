package cn.srv0.sshinjector.domain.vpn.tunnel

import android.util.Log
import cn.srv0.sshinjector.data.remote.tunnel.TunnelPluginFactory
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
        private val factories: Map<String, @JvmSuppressWildcards TunnelPluginFactory>,
    ) {
        companion object {
            private const val TAG = "TunnelManager"
            const val DEFAULT_EXIT_ID = "default"
        }

        private val activePlugin = MutableStateFlow<TunnelPlugin?>(null)

        private val activePlugins = ConcurrentHashMap<String, TunnelPlugin>()

        init {
            Log.d(TAG, "Registered tunnel factories: ${factories.keys}")
        }

        suspend fun startPlugin(
            pluginId: String,
            config: TunnelConfig,
        ): Result<Unit> {
            val factory =
                factories[pluginId]
                    ?: return Result.failure(IllegalArgumentException("Unknown tunnel: $pluginId"))

            // P0: 只有主出口 —— 标签为空串 (日志不加前缀), 对象图与改造前一致
            val plugin = factory.create(DEFAULT_EXIT_ID, "")

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

        /**
         * 当前活动插件。**可空**: 插件改为按出口由工厂创建后, 不再存在"DI 里那个未连接的实例"
         * 可以兜底 (旧实现返回未连接的 `plugins["socks5"]`, 调用方拿到 `localSocksPort == 0`)。
         * 三个调用点各自决定"没有活动插件"时的语义。
         */
        fun getActiveOrFallback(): TunnelPlugin? = activePlugin.value
    }
