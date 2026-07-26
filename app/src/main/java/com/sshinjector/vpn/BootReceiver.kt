package com.sshinjector.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sshinjector.data.local.preferences.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, checking auto-connect")
            
            val settings = SettingsDataStore(context)
            CoroutineScope(Dispatchers.IO).launch {
                val autoConnect = settings.autoConnect.first()
                if (autoConnect) {
                    val lastServerId = settings.lastServerId.first()
                    if (lastServerId != null && lastServerId > 0) {
                        val startIntent = Intent(context, SshVpnService::class.java).apply {
                            action = SshVpnService.ACTION_CONNECT
                            putExtra(SshVpnService.EXTRA_SERVER_ID, lastServerId)
                        }
                        context.startForegroundService(startIntent)
                    }
                }
            }
        }
    }
}
