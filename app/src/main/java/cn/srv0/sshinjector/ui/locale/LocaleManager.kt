package cn.srv0.sshinjector.ui.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_CHINESE = "zh_CN"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_RUSSIAN = "ru"

    fun getDisplayLabel(
        langCode: String,
        context: Context,
    ): String =
        when (langCode) {
            LANGUAGE_SYSTEM -> context.getString(cn.srv0.sshinjector.R.string.language_system)
            LANGUAGE_CHINESE -> context.getString(cn.srv0.sshinjector.R.string.language_chinese)
            LANGUAGE_ENGLISH -> context.getString(cn.srv0.sshinjector.R.string.language_english)
            LANGUAGE_RUSSIAN -> context.getString(cn.srv0.sshinjector.R.string.language_russian)
            else -> langCode
        }

    fun applyLanguage(
        context: Context,
        langCode: String,
    ): Context {
        val locale =
            when (langCode) {
                LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
                LANGUAGE_ENGLISH -> Locale.ENGLISH
                LANGUAGE_RUSSIAN -> Locale("ru")
                else ->
                    run {
                        val config = Configuration(context.resources.configuration)
                        return context.createConfigurationContext(config)
                    }
            }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun applyLanguageBase(
        base: Context,
        langCode: String,
    ): Context {
        val locale =
            when (langCode) {
                LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
                LANGUAGE_ENGLISH -> Locale.ENGLISH
                LANGUAGE_RUSSIAN -> Locale("ru")
                else -> Locale.getDefault()
            }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
