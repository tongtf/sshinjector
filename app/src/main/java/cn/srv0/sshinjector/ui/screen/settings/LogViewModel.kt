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
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
)

/**
 * 日志查看: 订阅 VpnController.logFlow (replay=500, 打开界面即回放最近条目),
 * 维护有界列表, 提供复制到剪贴板与导出到系统"下载"目录。
 */
@HiltViewModel
class LogViewModel
    @Inject
    constructor(
        private val vpnController: VpnController,
    ) : ViewModel() {
        private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
        val entries = _entries.asStateFlow()

        // clear 时刻: replay 缓存 reset 与订阅者在途缓冲之间有窗口, 按写入时间戳剔除
        // 早于 clear 的存量条目, 避免清空后旧日志回灌
        private var clearedAt = 0L

        init {
            viewModelScope.launch {
                vpnController.logFlow.collect { line ->
                    if (line.timestamp < clearedAt) return@collect
                    _entries.update { list ->
                        val next = list + LogEntry(line.timestamp, line.level, line.message)
                        if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
                    }
                }
            }
        }

        fun clear() {
            clearedAt = System.currentTimeMillis()
            vpnController.clearLogs()
            _entries.value = emptyList()
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

        companion object {
            private const val MAX_ENTRIES = 2000
        }
    }
