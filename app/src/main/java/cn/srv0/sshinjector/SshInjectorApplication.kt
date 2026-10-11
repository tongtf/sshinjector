package cn.srv0.sshinjector

import android.app.Application
import android.util.Log
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.ui.locale.LocaleManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SshInjectorApplication : Application() {
    @Inject lateinit var serverRepository: ServerRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: android.content.Context) {
        val prefs = base.getSharedPreferences("app_prefs", MODE_PRIVATE)
        val lang = prefs.getString("language", LocaleManager.LANGUAGE_SYSTEM) ?: LocaleManager.LANGUAGE_SYSTEM
        super.attachBaseContext(LocaleManager.applyLanguageBase(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        // 启动期补加密: 加密边界启用前入库的明文密码 → enc:v1: (幂等, 失败留待下次启动)
        appScope.launch {
            try {
                val migrated = serverRepository.migrateLegacyPlaintextPasswords()
                if (migrated > 0) {
                    Log.i("SshInjector", "migrated $migrated plaintext server password(s) to encrypted form")
                }
            } catch (e: Exception) {
                Log.w("SshInjector", "password migration failed (will retry next launch): ${e.message}")
            }
        }
    }
}
