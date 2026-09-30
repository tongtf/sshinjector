package cn.srv0.sshinjector.data.local

import android.content.Context
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.vpn.GfwListMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 广告规则远程源的加载状态。 */
sealed interface AdBlockRemoteState {
    data object Idle : AdBlockRemoteState

    data object Loading : AdBlockRemoteState

    /** 成功拉取到的规则原文 (多行, AdGuard/gfwlist 语法)。 */
    data class Ready(
        val text: String,
        val updatedAt: Long?,
    ) : AdBlockRemoteState

    data class Error(
        val message: String,
    ) : AdBlockRemoteState
}

/**
 * 广告过滤规则的远程加载与周期性刷新。
 *
 * 域名列表 [DomainListManager] 的同类实现: 从配置的 URL 拉取规则, 持久化到磁盘(含校验和),
 * 并按用户设置的间隔自动刷新。优先级上, 远程内容在连接时优先于内置清单 (但不高于用户在编辑器里
 * 手动保存的规则); [AdBlocker] 持有最终编译好的匹配器, 由 [cn.srv0.sshinjector.domain.usecase.VpnController]
 * 在连接时读取本管理器当前内容后注入。
 */
@Singleton
class AdBlockManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsDataStore,
    ) {
        private val _state = MutableStateFlow<AdBlockRemoteState>(AdBlockRemoteState.Idle)
        val state: StateFlow<AdBlockRemoteState> = _state.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            // 配置了远程 URL 时启动周期刷新; 未配置则每隔一段时间复查一次, 以便开启后及时生效。
            scope.launch {
                loadFromDisk()
                while (true) {
                    refreshOnce()
                    val intervalMins =
                        settings.adBlockRefreshInterval.first().toLong().coerceIn(
                            MIN_INTERVAL_MINS,
                            MAX_INTERVAL_MINS,
                        )
                    delay(intervalMins * REFRESH_PER_MINUTE_MILLIS)
                }
            }
        }

        /**
         * 连接时读取当前已拉取的远程规则原文; 尚未拉取返回 null → [VpnController] 回退内置清单。
         * 线程安全 (读 StateFlow.value)。
         */
        fun currentRules(): String? = (state.value as? AdBlockRemoteState.Ready)?.text

        /** 立即从配置的 URL 拉取一次; 未配置 URL 时直接返回当前状态。 */
        suspend fun update(): AdBlockRemoteState {
            val url = settings.adBlockRemoteUrl.first()
            if (url.isNullOrBlank()) return _state.value
            return update(url)
        }

        private suspend fun refreshOnce() {
            val url = settings.adBlockRemoteUrl.first()
            if (url.isNullOrBlank()) return
            val file = remoteFile()
            // 拉取一次缓存 3 天: 未拉取过(文件不存在)或距上次写入 ≥ 3 天才重新拉取。
            val shouldFetch =
                !file.exists() || System.currentTimeMillis() - file.lastModified() >= CACHE_INTERVAL_MILLIS
            if (shouldFetch) {
                update(url)
            }
        }

        private suspend fun loadFromDisk() {
            val file = remoteFile()
            if (file.exists() && verifyChecksum(file.readText())) {
                val text = file.readText().trim()
                if (text.isNotBlank()) {
                    _state.value = AdBlockRemoteState.Ready(text, null)
                }
            } else {
                file.delete()
            }
        }

        private fun remoteFile(): File = File(context.filesDir, REMOTE_FILE_NAME)

        private suspend fun update(url: String): AdBlockRemoteState {
            _state.value = AdBlockRemoteState.Loading
            return try {
                withContext(Dispatchers.IO) {
                    val raw = fetch(url)
                    val decoded = decodeBase64OrPlain(raw)
                    require(decoded.isNotBlank()) { "规则内容为空" }
                    // 拒绝 HTML 错误页 (网关/中间人常见响应), 防止恶意内容混入
                    require(!decoded.looksLikeHtml()) { "规则内容不是有效文本 (疑似 HTML 错误页)" }
                    val parsed = GfwListMatcher.parse(decoded)
                    require(parsed.ruleCount > 0) { "未包含任何有效广告规则" }
                    val now = System.currentTimeMillis()
                    remoteFile().writeText(decoded)
                    writeChecksum(decoded)
                    AdBlockRemoteState.Ready(decoded, now).also { _state.value = it }
                }
            } catch (e: IOException) {
                // Network failure (reset/timeout/DNS): fall back to the verified local cache, not the raw socket error.
                val state = fallbackToCached() ?: AdBlockRemoteState.Error("网络不可达，暂时无法刷新广告规则")
                _state.value = state
                state
            } catch (e: Exception) {
                // Content-level problem (empty / HTML / no valid rules): report clearly, never degrade.
                AdBlockRemoteState.Error(e.message ?: "远程刷新失败").also { _state.value = it }
            }
        }

        /** Fall back to the verified local cache on network failure; returns null when no cache is available. */
        private fun fallbackToCached(): AdBlockRemoteState? {
            val file = remoteFile()
            if (file.exists() && verifyChecksum(file.readText())) {
                val text = file.readText().trim()
                if (text.isNotBlank()) {
                    return AdBlockRemoteState.Ready(text, null)
                }
            }
            return null
        }

        private fun fetch(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "SSHInjector/1.0")
            return connection.inputStream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
        }

        /** gfwlist 原始内容是 base64, 若文本全部由 base64 字符组成则解码, 否则按纯文本处理。 */
        private fun decodeBase64OrPlain(text: String): String {
            val compact = text.filterNot { it.isWhitespace() }
            val isBase64ish =
                compact.isNotEmpty() &&
                    compact.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            if (!isBase64ish) return text
            return try {
                Base64.getMimeDecoder().decode(text).toString(Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                text
            }
        }

        private fun writeChecksum(content: String) {
            remoteFile().resolveSibling(CHECKSUM_FILE_NAME).writeText(sha256Hex(content))
        }

        /** 校验磁盘缓存与持久化时的摘要一致; 摘要缺失 (旧版缓存) 时视为通过, 兼容升级。 */
        private fun verifyChecksum(content: String): Boolean {
            val expected = runCatching { remoteFile().resolveSibling(CHECKSUM_FILE_NAME).readText().trim() }.getOrNull()
            if (expected.isNullOrEmpty()) return true
            return sha256Hex(content) == expected
        }

        private fun sha256Hex(content: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /** 判断文本是否像 HTML (网关/中间人错误页特征), 用于拒绝下载内容。 */
        private fun String.looksLikeHtml(): Boolean {
            val head = trimStart().take(256)
            if (head.startsWith("<!DOCTYPE", ignoreCase = true) || head.startsWith("<html", ignoreCase = true)) {
                return true
            }
            return head.contains("<title", ignoreCase = true) && head.contains("</title>", ignoreCase = true)
        }

        private companion object {
            const val REMOTE_FILE_NAME = "adblock_remote.txt"
            const val CHECKSUM_FILE_NAME = "adblock_remote.txt.sha256"
            const val REFRESH_PER_MINUTE_MILLIS = 60_000L
            // 拉取一次缓存 3 天: 距上次写入 ≥ 此时长才重新拉取。

            const val CACHE_INTERVAL_MILLIS = 3L * 24 * 60 * 60 * 1000
            const val CONNECT_TIMEOUT_MILLIS = 10_000
            const val READ_TIMEOUT_MILLIS = 15_000

            // 刷新间隔约束 (分钟)
            private val MIN_INTERVAL_MINS = SettingsDataStore.MIN_ADBLOCK_REFRESH_MINUTES
            private val MAX_INTERVAL_MINS = SettingsDataStore.MAX_ADBLOCK_REFRESH_MINUTES
        }
    }
