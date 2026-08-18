package cn.srv0.sshinjector.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
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
            Log.d("BootReceiver", "Boot completed, checking auto-connect")

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
                            context.startForegroundService(startIntent)
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
