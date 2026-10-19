package cn.srv0.sshinjector.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_REMOTE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
            private val KEY_LAST_SERVER_ID = longPreferencesKey("last_server_id")
            private val KEY_BIOMETRIC_UNLOCK = booleanPreferencesKey("biometric_unlock")
            internal val KEY_MTU = intPreferencesKey("mtu")
            internal val KEY_KEEP_ALIVE = intPreferencesKey("keep_alive")
            private val KEY_ENABLE_IPV6 = booleanPreferencesKey("enable_ipv6")
            private val KEY_DNS_MODE = intPreferencesKey("dns_mode")
            private val KEY_ADBLOCK_ENABLED = booleanPreferencesKey("ad_block_enabled")
            private val KEY_ADBLOCK_RULES = stringPreferencesKey("ad_block_rules")
            private val KEY_ADBLOCK_URL = stringPreferencesKey("ad_block_remote_url")
            private val KEY_ADBLOCK_INTERVAL = intPreferencesKey("ad_block_refresh_interval_min")
            private val KEY_PROBE_URL = stringPreferencesKey("probe_url")
            private val KEY_DOMAIN_LIST_URL = stringPreferencesKey("domain_list_url")
            private val KEY_DOMAIN_LIST_LAST_UPDATE = longPreferencesKey("domain_list_last_update")
            private val KEY_LANGUAGE = stringPreferencesKey("language")

            // 以下三键是"统计生成"的名单 (P1), 与用户手写规则**分键存放**:
            // 这样"移出"永远是精确删行/删元素, 绝不会误删用户手写规则 (设计 §8.0 纪律 ①)。
            private val KEY_ADBLOCK_RULES_FROM_STATS = stringPreferencesKey("ad_block_rules_from_stats")
            private val KEY_DOMAIN_LIST_ADDED_FROM_STATS =
                stringSetPreferencesKey("domain_list_added_from_stats")
            private val KEY_DOMAIN_LIST_REMOVED_FROM_STATS =
                stringSetPreferencesKey("domain_list_removed_from_stats")

            /**
             * 域名统计「使用远端解析 (经 SSH)」开关 (**默认关**, 设计 §7.2/§15 已定)。
             * 默认值由读取方 (`DomainStatsViewModel`) 补 false —— 无键 = 关。
             */
            private val KEY_USE_REMOTE_RESOLVE = booleanPreferencesKey("use_remote_resolve")

            const val DEFAULT_DOMAIN_LIST_URL = "https://gitlab.com/gfwlist/gfwlist/raw/master/gfwlist.txt"

            // 广告规则远程刷新间隔默认值(分钟); 实际取值由 AdBlockManager 约束在 [MIN, MAX]
            const val DEFAULT_ADBLOCK_REFRESH_MINUTES = 360
            const val MIN_ADBLOCK_REFRESH_MINUTES = 5L
            const val MAX_ADBLOCK_REFRESH_MINUTES = 1440L

            // Default remote source (jsDelivr mirror): identical content to GitHub raw, reachable where raw is reset.
            const val DEFAULT_ADBLOCK_REMOTE_URL =
                "https://cdn.jsdelivr.net/gh/tongtf/sshinjector@main/" +
                    "adblock_rules.txt"

            /**
             * 清除全局 MTU 覆盖 (回退 per-server 字段)。
             * 抽成纯变换 (无 Context) 便于 JVM 单测直接验证键被移除。
             */
            internal fun clearMtuOverride(prefs: MutablePreferences) {
                prefs.remove(KEY_MTU)
            }

            /** 清除全局 keepAlive 覆盖 (回退 per-server 字段)。 */
            internal fun clearKeepAliveOverride(prefs: MutablePreferences) {
                prefs.remove(KEY_KEEP_ALIVE)
            }
        }

        val autoConnect: Flow<Boolean> =
            context.dataStore.data
                .map { it[KEY_AUTO_CONNECT] ?: true }

        val lastServerId: Flow<Long?> =
            context.dataStore.data
                .map { it[KEY_LAST_SERVER_ID] }

        val biometricUnlock: Flow<Boolean> =
            context.dataStore.data
                .map { it[KEY_BIOMETRIC_UNLOCK] ?: true }

        // 全局网络设置: null 表示未设置 → 运行时回退 per-server 字段
        val mtu: Flow<Int?> =
            context.dataStore.data
                .map { it[KEY_MTU] }

        val keepAlive: Flow<Int?> =
            context.dataStore.data
                .map { it[KEY_KEEP_ALIVE] }

        val enableIPv6: Flow<Boolean?> =
            context.dataStore.data
                .map { it[KEY_ENABLE_IPV6] }

        val dnsMode: Flow<Int> =
            context.dataStore.data
                .map { it[KEY_DNS_MODE] ?: DNS_MODE_REMOTE } // 默认远程代理模式

        // 广告过滤总开关, 默认开启
        val adBlockEnabled: Flow<Boolean> =
            context.dataStore.data.map { it[KEY_ADBLOCK_ENABLED] ?: true }

        // 运行时编辑的完整广告规则(每行一条, 支持 AdGuard/gfwlist 语法);
        // 空串 = 未编辑, 连接时回退内置清单 assets/adblock.txt
        val adBlockRules: Flow<String> =
            context.dataStore.data.map { it[KEY_ADBLOCK_RULES]?.takeIf { s -> s.isNotBlank() } ?: "" }

        // 广告规则远程源地址; 未单独配置时自动使用预设的 GitHub raw 清单, 无需手动填写。
        val adBlockRemoteUrl: Flow<String> =
            context.dataStore.data.map { it[KEY_ADBLOCK_URL] ?: DEFAULT_ADBLOCK_REMOTE_URL }

        // 自动刷新间隔(分钟); 默认 6 小时
        val adBlockRefreshInterval: Flow<Int> =
            context.dataStore.data.map { it[KEY_ADBLOCK_INTERVAL] ?: DEFAULT_ADBLOCK_REFRESH_MINUTES }

        /** 连通性探测端点; null/未设置 = ConnectivityProber.DEFAULT_ENDPOINT。 */
        val probeUrl: Flow<String?> =
            context.dataStore.data
                .map { it[KEY_PROBE_URL]?.takeIf { url -> url.isNotBlank() } }

        val domainListUrl: Flow<String> =
            context.dataStore.data
                .map { it[KEY_DOMAIN_LIST_URL] ?: DEFAULT_DOMAIN_LIST_URL }

        val domainListLastUpdate: Flow<Long?> =
            context.dataStore.data
                .map { it[KEY_DOMAIN_LIST_LAST_UPDATE] }

        /** 统计生成的广告规则行 (`||d^` / `@@||d^`); 空串 = 无。 */
        val adBlockRulesFromStats: Flow<String> =
            context.dataStore.data
                .map { it[KEY_ADBLOCK_RULES_FROM_STATS].orEmpty() }

        /** 统计生成的"强制走隧道"域名集合 (代理名单叠加, 优先级低于"强制直连")。 */
        val domainListAddedFromStats: Flow<Set<String>> =
            context.dataStore.data
                .map { it[KEY_DOMAIN_LIST_ADDED_FROM_STATS] ?: emptySet() }

        /** 统计生成的"强制直连"域名集合 (优先级最高)。 */
        val domainListRemovedFromStats: Flow<Set<String>> =
            context.dataStore.data
                .map { it[KEY_DOMAIN_LIST_REMOVED_FROM_STATS] ?: emptySet() }

        /** 「使用远端解析」开关 (无键 = 关; 默认关是设计 §15 的已定项)。 */
        val useRemoteResolve: Flow<Boolean> =
            context.dataStore.data
                .map { it[KEY_USE_REMOTE_RESOLVE] ?: false }

        suspend fun setAutoConnect(enabled: Boolean) {
            context.dataStore.edit { it[KEY_AUTO_CONNECT] = enabled }
        }

        suspend fun setLastServerId(id: Long) {
            context.dataStore.edit { it[KEY_LAST_SERVER_ID] = id }
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

        /** 清除全局 MTU 覆盖 → 运行时回退 per-server 的 mtu。 */
        suspend fun clearMtu() {
            context.dataStore.edit { clearMtuOverride(it) }
        }

        /** 清除全局 keepAlive 覆盖 → 运行时回退 per-server 的 keepAliveInterval。 */
        suspend fun clearKeepAlive() {
            context.dataStore.edit { clearKeepAliveOverride(it) }
        }

        suspend fun setEnableIPv6(enabled: Boolean) {
            context.dataStore.edit { it[KEY_ENABLE_IPV6] = enabled }
        }

        suspend fun setDnsMode(mode: Int) {
            context.dataStore.edit { it[KEY_DNS_MODE] = mode }
        }

        suspend fun setAdBlockEnabled(enabled: Boolean) {
            context.dataStore.edit { it[KEY_ADBLOCK_ENABLED] = enabled }
        }

        /** 传入 null/空串 = 清空, 回退内置清单。多行, 每行一条规则 (支持 ||domain^ / @@例外)。 */
        suspend fun setAdBlockRules(rules: String?) {
            context.dataStore.edit { it[KEY_ADBLOCK_RULES] = rules.orEmpty() }
        }

        /** 传 null/空串 = 关闭远程加载。 */
        suspend fun setAdBlockRemoteUrl(url: String?) {
            context.dataStore.edit {
                if (url.isNullOrBlank()) {
                    it.remove(KEY_ADBLOCK_URL)
                } else {
                    it[KEY_ADBLOCK_URL] = url.trim()
                }
            }
        }

        /** 刷新间隔(分钟); 约束在 [MIN, MAX] 范围内。 */
        suspend fun setAdBlockRefreshInterval(minutes: Int) {
            val safe =
                minutes.toLong().coerceIn(
                    SettingsDataStore.MIN_ADBLOCK_REFRESH_MINUTES,
                    SettingsDataStore.MAX_ADBLOCK_REFRESH_MINUTES,
                )
            context.dataStore.edit { it[KEY_ADBLOCK_INTERVAL] = safe.toInt() }
        }

        /** 传 null/空串 = 恢复默认探测端点。 */
        suspend fun setProbeUrl(url: String?) {
            context.dataStore.edit {
                if (url.isNullOrBlank()) {
                    it.remove(KEY_PROBE_URL)
                } else {
                    it[KEY_PROBE_URL] = url.trim()
                }
            }
        }

        suspend fun setDomainListUrl(url: String) {
            context.dataStore.edit { it[KEY_DOMAIN_LIST_URL] = url }
        }

        suspend fun setDomainListLastUpdate(timestamp: Long) {
            context.dataStore.edit { it[KEY_DOMAIN_LIST_LAST_UPDATE] = timestamp }
        }

        suspend fun getDomainListLastUpdate(): Long? =
            context.dataStore.data
                .map { it[KEY_DOMAIN_LIST_LAST_UPDATE] }
                .first()

        // ---- 统计生成名单 (P1): 读-改-写由 StatsListApplier 的互斥锁串行, 见其 KDoc ----

        /** 读当前统计广告规则 (读-改-写用); null = 无该键。 */
        suspend fun getAdBlockRulesFromStats(): String? =
            context.dataStore.data
                .map { it[KEY_ADBLOCK_RULES_FROM_STATS] }
                .first()

        suspend fun getDomainListAddedFromStats(): Set<String> =
            context.dataStore.data
                .map { it[KEY_DOMAIN_LIST_ADDED_FROM_STATS] ?: emptySet() }
                .first()

        suspend fun getDomainListRemovedFromStats(): Set<String> =
            context.dataStore.data
                .map { it[KEY_DOMAIN_LIST_REMOVED_FROM_STATS] ?: emptySet() }
                .first()

        /** null/空白 = 移除该键 (统计不再贡献广告规则)。 */
        suspend fun setAdBlockRulesFromStats(rules: String?) {
            context.dataStore.edit {
                if (rules.isNullOrBlank()) {
                    it.remove(KEY_ADBLOCK_RULES_FROM_STATS)
                } else {
                    it[KEY_ADBLOCK_RULES_FROM_STATS] = rules
                }
            }
        }

        /** 空集 = 移除该键。 */
        suspend fun setDomainListAddedFromStats(domains: Set<String>) {
            context.dataStore.edit {
                if (domains.isEmpty()) {
                    it.remove(KEY_DOMAIN_LIST_ADDED_FROM_STATS)
                } else {
                    it[KEY_DOMAIN_LIST_ADDED_FROM_STATS] = domains
                }
            }
        }

        /** 空集 = 移除该键。 */
        suspend fun setDomainListRemovedFromStats(domains: Set<String>) {
            context.dataStore.edit {
                if (domains.isEmpty()) {
                    it.remove(KEY_DOMAIN_LIST_REMOVED_FROM_STATS)
                } else {
                    it[KEY_DOMAIN_LIST_REMOVED_FROM_STATS] = domains
                }
            }
        }

        /** 「使用远端解析」开关: 关 = 移除该键 (与"默认关"同义, 不留脏键)。 */
        suspend fun setUseRemoteResolve(enabled: Boolean) {
            context.dataStore.edit {
                if (enabled) {
                    it[KEY_USE_REMOTE_RESOLVE] = true
                } else {
                    it.remove(KEY_USE_REMOTE_RESOLVE)
                }
            }
        }

        val language: Flow<String> =
            context.dataStore.data
                .map { it[KEY_LANGUAGE] ?: "system" }

        suspend fun setLanguage(code: String) {
            context.dataStore.edit { it[KEY_LANGUAGE] = code }
            context
                .getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("language", code)
                .apply()
        }
    }
