package cn.srv0.sshinjector.vpn

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, checking auto-connect")

            // goAsync: 持有广播时间窗口, 防止 DataStore 首次初始化阻塞时协程未完成被终止
            val pendingResult = goAsync()
            val settings = SettingsDataStore(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val autoConnect = settings.autoConnect.first()
                    if (autoConnect) {
                        val lastServerId = settings.lastServerId.first()
                        if (lastServerId != null && lastServerId > 0) {
                            val startIntent =
                                Intent(context, SshVpnService::class.java).apply {
                                    action = SshVpnService.ACTION_CONNECT
                                    putExtra(SshVpnService.EXTRA_SERVER_ID, lastServerId)
                                }
                            try {
                                context.startForegroundService(startIntent)
                            } catch (e: ForegroundServiceStartNotAllowedException) {
                                // Android 12+ 后台启动前台服务受限 (开机自启场景常见): 不崩溃, 记日志
                                Log.w(TAG, "startForegroundService not allowed on boot", e)
                                VpnController.appLogThrottled(
                                    "开机自启被系统限制 · 无法启动前台服务 — 请手动打开应用连接",
                                    level = LogLevel.WARNING,
                                    throttleKey = "开机自启被系统限制",
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 设置读取失败 / 其它异常不得让开机广播崩溃
                    Log.w(TAG, "Boot auto-connect failed", e)
                    VpnController.appLogThrottled(
                        "开机自启失败 · ${e.message}",
                        level = LogLevel.WARNING,
                        throttleKey = "开机自启失败",
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
