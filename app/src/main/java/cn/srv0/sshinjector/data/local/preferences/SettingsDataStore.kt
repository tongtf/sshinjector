package cn.srv0.sshinjector.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    private val context: Context
) {
    companion object {
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val KEY_LAST_SERVER_ID = longPreferencesKey("last_server_id")
        private val KEY_NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_BIOMETRIC_UNLOCK = booleanPreferencesKey("biometric_unlock")
        private val KEY_MTU = intPreferencesKey("mtu")
        private val KEY_KEEP_ALIVE = intPreferencesKey("keep_alive")
        private val KEY_ENABLE_IPV6 = booleanPreferencesKey("enable_ipv6")
        private val KEY_DNS_MODE = intPreferencesKey("dns_mode")
        private val KEY_LOG_LEVEL = intPreferencesKey("log_level") // 0=简洁 1=详细
        private val KEY_ROUTE_CONFIG = stringPreferencesKey("route_config")
        private val KEY_DOMAIN_LIST_URL = stringPreferencesKey("domain_list_url")
        private val KEY_DOMAIN_LIST_LAST_UPDATE = longPreferencesKey("domain_list_last_update")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private const val KEY_KEYSTORE_ALIAS_PREFIX = "keystore_alias_"

        const val DEFAULT_DOMAIN_LIST_URL = "https://gitlab.com/gfwlist/gfwlist/raw/master/gfwlist.txt"
    }

    val autoConnect: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_AUTO_CONNECT] ?: true }

    val lastServerId: Flow<Long?> = context.dataStore.data
        .map { it[KEY_LAST_SERVER_ID] }

    val notificationEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_NOTIFICATION_ENABLED] ?: true }

    val theme: Flow<String> = context.dataStore.data
        .map { it[KEY_THEME] ?: "system" }

    val biometricUnlock: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_BIOMETRIC_UNLOCK] ?: true }

    val mtu: Flow<Int> = context.dataStore.data
        .map { it[KEY_MTU] ?: 1500 }

    val keepAlive: Flow<Int> = context.dataStore.data
        .map { it[KEY_KEEP_ALIVE] ?: 30 }

    val enableIPv6: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ENABLE_IPV6] ?: true }

    val dnsMode: Flow<Int> = context.dataStore.data
        .map { it[KEY_DNS_MODE] ?: 0 } // 默认远程代理模式

    val logLevel: Flow<Int> = context.dataStore.data
        .map { it[KEY_LOG_LEVEL] ?: 1 } // 默认详细模式

    val routeConfig: Flow<String?> = context.dataStore.data
        .map { it[KEY_ROUTE_CONFIG] }

    val domainListUrl: Flow<String> = context.dataStore.data
        .map { it[KEY_DOMAIN_LIST_URL] ?: DEFAULT_DOMAIN_LIST_URL }

    val domainListLastUpdate: Flow<Long?> = context.dataStore.data
        .map { it[KEY_DOMAIN_LIST_LAST_UPDATE] }

    suspend fun setRouteConfig(json: String) {
        context.dataStore.edit { it[KEY_ROUTE_CONFIG] = json }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CONNECT] = enabled }
    }

    suspend fun setLastServerId(id: Long) {
        context.dataStore.edit { it[KEY_LAST_SERVER_ID] = id }
    }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[KEY_THEME] = theme }
    }

    suspend fun setBiometricUnlock(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BIOMETRIC_UNLOCK] = enabled }
    }

    suspend fun setMtu(value: Int) {
        context.dataStore.edit { it[KEY_MTU] = value }
    }

    suspend fun setKeepAlive(value: Int) {
        context.dataStore.edit { it[KEY_KEEP_ALIVE] = value }
    }

    suspend fun setEnableIPv6(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_IPV6] = enabled }
    }

    suspend fun setDnsMode(mode: Int) {
        context.dataStore.edit { it[KEY_DNS_MODE] = mode }
    }

    suspend fun setDomainListUrl(url: String) {
        context.dataStore.edit { it[KEY_DOMAIN_LIST_URL] = url }
    }

    suspend fun setDomainListLastUpdate(timestamp: Long) {
        context.dataStore.edit { it[KEY_DOMAIN_LIST_LAST_UPDATE] = timestamp }
    }

    suspend fun getDomainListLastUpdate(): Long? {
        return context.dataStore.data.map { it[KEY_DOMAIN_LIST_LAST_UPDATE] }.first()
    }

    val language: Flow<String> = context.dataStore.data
        .map { it[KEY_LANGUAGE] ?: "system" }

suspend fun setLanguage(code: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = code }
        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("language", code).apply()
    }

    suspend fun setLogLevel(level: Int) {
        context.dataStore.edit { it[KEY_LOG_LEVEL] = level }
    }

    suspend fun setKeyAlias(serverId: Long, alias: String) {
        val key = stringPreferencesKey("${KEY_KEYSTORE_ALIAS_PREFIX}$serverId")
        context.dataStore.edit { it[key] = alias }
    }

    suspend fun getKeyAlias(serverId: Long): String? {
        val key = stringPreferencesKey("${KEY_KEYSTORE_ALIAS_PREFIX}$serverId")
        return context.dataStore.data.map { it[key] }.first()
    }

    suspend fun removeKeyAlias(serverId: Long) {
        val key = stringPreferencesKey("${KEY_KEYSTORE_ALIAS_PREFIX}$serverId")
        context.dataStore.edit { it.remove(key) }
    }
}
