package cn.srv0.sshinjector.data.local

import android.content.Context
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.vpn.GfwListMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DomainListState {
    data object Idle : DomainListState

    data object Loading : DomainListState

    data class Ready(
        val matcher: GfwListMatcher,
        val source: DomainListSource,
        val updatedAt: Long?,
    ) : DomainListState

    data class Error(
        val message: String,
    ) : DomainListState
}

enum class DomainListSource { BUILTIN, DISK, URL }

/**
 * 域名列表(白名单/黑名单)的拉取、解码、持久化与匹配。
 *
 * 列表来源优先级: 磁盘缓存 > 内置默认列表; 手动/自动更新时从配置的 URL 拉取,
 * 支持 base64(gfwlist) 与纯文本两种格式。
 */
@Singleton
class DomainListManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsDataStore,
    ) {
        private val _state = MutableStateFlow<DomainListState>(DomainListState.Idle)
        val state: StateFlow<DomainListState> = _state.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            scope.launch { loadFromDisk() }
        }

        val matcher: GfwListMatcher?
            get() = (_state.value as? DomainListState.Ready)?.matcher

        fun matches(host: String): Boolean = matcher?.matches(host) == true

        /**
         * 从磁盘加载上次持久化的列表, 无缓存则使用内置默认列表。
         */
        private suspend fun loadFromDisk() {
            val file = listFile()
            if (file.exists() && verifyChecksum(file.readText())) {
                val matcher = GfwListMatcher.parse(file.readText())
                _state.value = DomainListState.Ready(matcher, DomainListSource.DISK, settings.getDomainListLastUpdate())
            } else {
                file.delete()
                _state.value = DomainListState.Ready(GfwListMatcher.parse(BUILTIN_LIST), DomainListSource.BUILTIN, null)
            }
        }

        /**
         * 从配置的 URL 拉取并应用新列表, 成功时持久化到磁盘。
         */
        suspend fun update(): DomainListState {
            _state.value = DomainListState.Loading
            return try {
                withContext(Dispatchers.IO) {
                    val url = settings.domainListUrl.first()
                    val raw = fetch(url)
                    val decoded = decodeBase64OrPlain(raw)
                    require(decoded.isNotBlank()) { "列表内容为空" }
                    // 拒绝 HTML 错误页 (网关/中间人常见响应), 防止恶意内容混入
                    require(!decoded.looksLikeHtml()) { "列表内容不是有效文本 (疑似 HTML 错误页)" }
                    val matcher = GfwListMatcher.parse(decoded)
                    require(matcher.ruleCount > 0) { "列表未包含任何有效规则" }
                    listFile().writeText(decoded)
                    writeChecksum(decoded)
                    settings.setDomainListLastUpdate(System.currentTimeMillis())
                    DomainListState.Ready(matcher, DomainListSource.URL, settings.getDomainListLastUpdate()).also {
                        _state.value = it
                    }
                }
            } catch (e: Exception) {
                DomainListState.Error(e.message ?: "列表更新失败").also { _state.value = it }
            }
        }

        /**
         * 距上次更新超过 24 小时时提示需要刷新。
         */
        suspend fun shouldRefresh(): Boolean {
            val last = settings.getDomainListLastUpdate()
            if (last == null) return true
            return System.currentTimeMillis() - last > UPDATE_INTERVAL_MILLIS
        }

        private fun fetch(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "SSHInjector/1.0")
            return connection.inputStream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
        }

        /**
         * gfwlist 原始内容是 base64, 若文本全部由 base64 字符组成则解码, 否则按纯文本处理。
         */
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

        private fun listFile(): File = File(context.filesDir, LIST_FILE_NAME)

        private fun checksumFile(): File = File(context.filesDir, LIST_FILE_NAME + ".sha256")

        /**
         * 持久化列表内容时记录 SHA-256 摘要, 用于加载时检测磁盘文件被篡改/损坏。
         */
        private fun writeChecksum(content: String) {
            checksumFile().writeText(sha256Hex(content))
        }

        /**
         * 校验磁盘缓存与持久化时的摘要一致。摘要缺失 (旧版缓存) 时视为通过, 兼容升级。
         */
        private fun verifyChecksum(content: String): Boolean {
            val expected = runCatching { checksumFile().readText().trim() }.getOrNull()
            if (expected.isNullOrEmpty()) return true
            return sha256Hex(content) == expected
        }

        private fun sha256Hex(content: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /**
         * 判断文本是否像 HTML (网关/中间人错误页特征), 用于拒绝下载内容。
         */
        private fun String.looksLikeHtml(): Boolean {
            val head = trimStart().take(256)
            if (head.startsWith("<!DOCTYPE", ignoreCase = true) || head.startsWith("<html", ignoreCase = true)) {
                return true
            }
            return head.contains("<title", ignoreCase = true) && head.contains("</title>", ignoreCase = true)
        }

        private companion object {
            const val LIST_FILE_NAME = "domain_list.txt"
            const val UPDATE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
            const val CONNECT_TIMEOUT_MILLIS = 10_000
            const val READ_TIMEOUT_MILLIS = 15_000

            val BUILTIN_LIST =
                """
                google.com
                www.google.com
                youtube.com
                facebook.com
                twitter.com
                x.com
                instagram.com
                wikipedia.org
                github.com
                telegram.org
                t.me
                wa.me
                vk.com
                """.trimIndent()
        }
    }
