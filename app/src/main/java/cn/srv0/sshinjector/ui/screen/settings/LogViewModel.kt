package cn.srv0.sshinjector.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.usecase.ProxyExitRepository
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.domain.vpn.routing.LogDomainParser
import cn.srv0.sshinjector.domain.vpn.routing.RoutingRuleEditor
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

/** 一条带时间戳的应用日志 (时间戳由 VpnController.addLog 在写入时生成)。 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
    /**
     * 该行关联的目标域名 —— 收集时由 [LogDomainParser] 从消息文本里解析
     * (null = 与具体域名无关, 或目标是 IP 字面量/`?`)。
     *
     * 有默认值: 既有构造点 (其它单测) 不必跟着改。
     */
    val domain: String? = null,
)

/**
 * 一键固定域名出口的结果类别。
 *
 * 只传**类别**不传文案: ViewModel 里没有 Context, 硬编码文案会绕开 i18n
 * (见 AGENTS「多语言」: 用户可见文案一律来自 `strings.xml`, 由 UI 侧按本枚举映射)。
 */
enum class LogRoutingOutcome {
    APPLIED,
    APPLIED_DISABLED,
    FAILED,
}

/**
 * 一次待提示的路由结果 (Snackbar 用一次就抛)。
 *
 * [token] 的唯一作用是让 StateFlow **每次都能发出**: 连续两次结果相同 (`APPLIED` + 同域名) 时
 * data class 的 equals 会把后一次去重掉, 界面上就变成"点了没反应"。
 */
data class LogRoutingMessage(
    val token: Long,
    val outcome: LogRoutingOutcome,
    val domain: String,
)

/**
 * 日志查看: 订阅 VpnController.logFlow (replay=500, 打开界面即回放最近条目),
 * 维护有界列表, 提供复制到剪贴板与导出到系统"下载"目录。
 *
 * 另提供"一键固定域名出口": 对带域名的日志行调用 [RoutingRuleEditor.upsertDomain],
 * 行级插入 (保留用户排版), 并保留一次 [undo] 的原样回写机会。
 */
@HiltViewModel
class LogViewModel
    @Inject
    constructor(
        private val vpnController: VpnController,
        private val routingRuleEditor: RoutingRuleEditor,
        proxyExitRepository: ProxyExitRepository,
        settingsDataStore: SettingsDataStore,
    ) : ViewModel() {
        private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
        val entries = _entries.asStateFlow()

        // default 恒在首位 (UI 显示为"主出口"); 其余只取**启用**的附加出口
        private val _exits = MutableStateFlow(listOf(ExitId.DEFAULT))
        val exits: StateFlow<List<String>> = _exits.asStateFlow()

        private val _routingEnabled = MutableStateFlow(false)

        /** 多出口总开关的只读镜像 (应用成功时同步); 关闭时仍可保存, 结果文案会区分。 */
        val routingEnabled: StateFlow<Boolean> = _routingEnabled.asStateFlow()

        private val _routingMessage = MutableStateFlow<LogRoutingMessage?>(null)
        val routingMessage: StateFlow<LogRoutingMessage?> = _routingMessage.asStateFlow()

        private val _routingCanUndo = MutableStateFlow(false)
        val routingCanUndo: StateFlow<Boolean> = _routingCanUndo.asStateFlow()

        // clear 时刻: replay 缓存 reset 与订阅者在途缓冲之间有窗口, 按写入时间戳剔除
        // 早于 clear 的存量条目, 避免清空后旧日志回灌
        private var clearedAt = 0L

        // 以下三个字段只在 viewModelScope (主线程) 内读写, 不需要加锁
        private var previousRulesText: String? = null
        private var lastAppliedDomain: String = ""
        private var messageToken = 0L

        init {
            viewModelScope.launch {
                vpnController.logFlow.collect { line ->
                    if (line.timestamp < clearedAt) return@collect
                    // 域名解析与落列在同一次 collect 里完成: 解析失败 (null) 只表示该行没有一键入口
                    val domain = LogDomainParser.domainOf(line.message)
                    _entries.update { list ->
                        val next = list + LogEntry(line.timestamp, line.level, line.message, domain)
                        if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
                    }
                }
            }
            viewModelScope.launch {
                proxyExitRepository.allExitsFlow.collect { list ->
                    _exits.value = listOf(ExitId.DEFAULT) + list.filter { it.enabled }.map { it.name }
                }
            }
            viewModelScope.launch {
                settingsDataStore.multiExitEnabled.collect { _routingEnabled.value = it }
            }
        }

        fun clear() {
            clearedAt = System.currentTimeMillis()
            vpnController.clearLogs()
            _entries.value = emptyList()
        }

        /**
         * 把 [domain] 固定到 [exit] (写规则文本, 立即生效)。
         * 多出口未启用时**仍然保存** —— 文案走 [LogRoutingOutcome.APPLIED_DISABLED] 提示。
         */
        fun applyDomain(
            domain: String,
            exit: String,
        ) {
            val target = domain.trim()
            if (target.isEmpty()) return
            viewModelScope.launch {
                try {
                    val result = routingRuleEditor.upsertDomain(target, exit)
                    previousRulesText = result.previousText
                    lastAppliedDomain = target
                    _routingCanUndo.value = true
                    _routingEnabled.value = result.enabled
                    emitRoutingMessage(
                        if (result.enabled) LogRoutingOutcome.APPLIED else LogRoutingOutcome.APPLIED_DISABLED,
                        target,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    previousRulesText = null
                    _routingCanUndo.value = false
                    emitRoutingMessage(LogRoutingOutcome.FAILED, target)
                }
            }
        }

        /** 撤销上一次 [applyDomain] (原样回写"应用前文本", 不是反向操作一遍)。 */
        fun undo() {
            val previous = previousRulesText ?: return
            previousRulesText = null
            _routingCanUndo.value = false
            viewModelScope.launch {
                try {
                    routingRuleEditor.undo(previous)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 回写失败必须把撤销机会还回去: 否则用户以为已恢复, 规则其实还在
                    previousRulesText = previous
                    _routingCanUndo.value = true
                    emitRoutingMessage(LogRoutingOutcome.FAILED, lastAppliedDomain)
                }
            }
        }

        /** Snackbar 展示后清掉一次性提示 (重复 value 否则会被 StateFlow 去重, 下一次不再弹)。 */
        fun consumeRoutingMessage() {
            _routingMessage.value = null
        }

        fun copyToClipboard(
            context: Context,
            text: String,
        ) {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val label = context.getString(R.string.settings_logs)
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        }

        /**
         * 导出文本到系统"下载"目录 (MediaStore, 无需权限), 成功返回文件名。
         * 失败返回带消息的 Result.failure。
         */
        suspend fun exportToDownloads(
            context: Context,
            fileName: String,
            text: String,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                    val uri =
                        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@withContext Result.failure(IllegalStateException("insert failed"))
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext Result.failure(IllegalStateException("openOutputStream failed"))
                    Result.success(fileName)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        fun formatEntries(
            entries: List<LogEntry>,
            timeFormat: SimpleDateFormat,
        ): String =
            entries.joinToString(separator = "\n") { entry ->
                "${timeFormat.format(Date(entry.timestamp))} [${entry.level.name}] ${entry.message}"
            }

        private fun emitRoutingMessage(
            outcome: LogRoutingOutcome,
            domain: String,
        ) {
            messageToken += 1
            _routingMessage.value = LogRoutingMessage(messageToken, outcome, domain)
        }

        companion object {
            private const val MAX_ENTRIES = 2000
        }
    }
