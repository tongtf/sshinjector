package cn.srv0.sshinjector

import android.app.Application
import cn.srv0.sshinjector.ui.locale.LocaleManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SshInjectorApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        val prefs = base.getSharedPreferences("app_prefs", MODE_PRIVATE)
        val lang = prefs.getString("language", LocaleManager.LANGUAGE_SYSTEM) ?: LocaleManager.LANGUAGE_SYSTEM
        super.attachBaseContext(LocaleManager.applyLanguageBase(base, lang))
    }
}
