package cn.srv0.sshinjector.ui.screen.settings

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.DomainStatSnapshot
import cn.srv0.sshinjector.domain.vpn.DomainStatsCsv
import cn.srv0.sshinjector.domain.vpn.DomainStatsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 列表排序维度。 */
enum class DomainStatsSort {
    CONNECTIONS,
    TRAFFIC,
    RECENT,
    DOMAIN,
}

/** 列表筛选维度 (P0 只读, 不涉及任何名单写入)。 */
enum class DomainStatsFilter {
    ALL,
    WITH_REAL_IP,
    UNKNOWN_IP,
    FAILED,
}

/** 页面状态: `rows` 是已排序/筛选后的展示行, 其余是统计口径与一次性交互状态。 */
data class DomainStatsUiState(
    val rows: List<DomainStatSnapshot> = emptyList(),
    val total: Int = 0,
    val cap: Int = DomainStatsStore.MAX_DOMAINS,
    val trimmed: Long = 0,
    val query: String = "",
    val sort: DomainStatsSort = DomainStatsSort.CONNECTIONS,
    val filter: DomainStatsFilter = DomainStatsFilter.ALL,
    val loading: Boolean = false,
    val resolving: String? = null,
    val message: String? = null,
)

/**
 * 域名访问统计页面 (P0 只读展示)。
 *
 * 性能纪律 (设计 §9.4): **绝不**订阅数据面 —— 快照只在开屏 / 手动刷新时抓一次; 排序/筛选/搜索
 * 全在 [Dispatchers.Default]; 搜索 300ms debounce; 搜索/排序/筛选都不在 `@Composable` 里做。
 *
 * `message` 是一次性交互结果 (清空 / 导出 / 解析), 值为 **语言无关的哨兵常量**, 由页面映射成
 * `stringResource` 再 Toast —— ViewModel 拿不到 `Context`, 不能在这里解析资源 (i18n 硬要求)。
 */
@HiltViewModel
class DomainStatsViewModel
    @Inject
    constructor(
        private val store: DomainStatsStore,
        private val dnsInterceptor: DnsInterceptor,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DomainStatsUiState())
        val state: StateFlow<DomainStatsUiState> = _state.asStateFlow()

        /** 最近一次抓取的全量快照 (未排序/未筛选); 只在 Default 线程读写。 */
        private var allRows: List<DomainStatSnapshot> = emptyList()

        /** 搜索 debounce 任务。 */
        private var queryJob: Job? = null

        init {
            refresh()
        }

        /** 抓一次快照并重建展示行。开屏与手动刷新调用; 数据面不触发它。 */
        fun refresh() {
            viewModelScope.launch {
                _state.update { it.copy(loading = true) }
                withContext(Dispatchers.Default) {
                    allRows = store.snapshot()
                    publishRows(
                        total = store.size(),
                        trimmed = store.trimmedTotal(),
                        loading = false,
                    )
                }
            }
        }

        /** 搜索关键字 (300ms debounce 后才重算列表)。 */
        fun setQuery(q: String) {
            _state.update { it.copy(query = q) }
            queryJob?.cancel()
            queryJob =
                viewModelScope.launch {
                    delay(QUERY_DEBOUNCE_MS)
                    withContext(Dispatchers.Default) { publishRows() }
                }
        }

        fun setSort(s: DomainStatsSort) {
            _state.update { it.copy(sort = s) }
            viewModelScope.launch { withContext(Dispatchers.Default) { publishRows() } }
        }

        fun setFilter(f: DomainStatsFilter) {
            _state.update { it.copy(filter = f) }
            viewModelScope.launch { withContext(Dispatchers.Default) { publishRows() } }
        }

        /** 清空内存统计 (含淘汰计数)。 */
        fun clear() {
            viewModelScope.launch {
                withContext(Dispatchers.Default) {
                    store.clear()
                    allRows = emptyList()
                }
                _state.update {
                    it.copy(
                        rows = emptyList(),
                        total = 0,
                        trimmed = 0,
                        message = MSG_CLEARED,
                    )
                }
            }
        }

        /**
         * 对单个域名做**本地视角**解析 (受保护 socket, 不经隧道), 结果回写统计条目并刷新。
         * 单飞: 已有解析在跑时直接忽略。`resolveLocally` 是阻塞调用, 必须离开主线程。
         */
        fun resolveRealIp(domain: String) {
            if (_state.value.resolving != null) return
            _state.update { it.copy(resolving = domain) }
            viewModelScope.launch {
                try {
                    val ips = withContext(Dispatchers.IO) { dnsInterceptor.resolveLocally(domain) }
                    if (ips.isEmpty()) {
                        _state.update { it.copy(resolving = null, message = MSG_IP_UNAVAILABLE) }
                        return@launch
                    }
                    withContext(Dispatchers.Default) {
                        for (ip in ips) store.attachRealIp(domain, ip)
                    }
                    withContext(Dispatchers.Default) {
                        allRows = store.snapshot()
                        publishRows()
                    }
                    _state.update { it.copy(resolving = null) }
                } catch (_: Exception) {
                    _state.update { it.copy(resolving = null, message = MSG_RESOLVE_FAILED) }
                }
            }
        }

        /**
         * 导出全量快照为 CSV 到系统"下载"目录 (MediaStore, 无需权限), 成功返回文件名。
         * 结果同样写入 [DomainStatsUiState.message] 供页面 Toast。
         */
        suspend fun exportCsv(context: Context): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    val text = DomainStatsCsv.render(store.snapshot())
                    val fileName = DomainStatsCsv.fileName(System.currentTimeMillis())
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                    val uri =
                        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@withContext exportFailure()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext exportFailure()
                    _state.update { it.copy(message = MSG_EXPORT_OK) }
                    Result.success(fileName)
                } catch (e: Exception) {
                    _state.update { it.copy(message = MSG_EXPORT_FAILED) }
                    Result.failure(e)
                }
            }

        /** 消费一次性消息 (页面 Toast 后调用)。 */
        fun consumeMessage() {
            _state.update { it.copy(message = null) }
        }

        /** 按当前 query/filter/sort 重建展示行; **只在 Default 线程调用**。 */
        private fun publishRows(
            total: Int = _state.value.total,
            trimmed: Long = _state.value.trimmed,
            loading: Boolean = _state.value.loading,
        ) {
            val current = _state.value
            val filtered = allRows.filter { matchesFilter(it, current.query, current.filter) }
            val sorted = sortRows(filtered, current.sort)
            _state.update {
                it.copy(
                    rows = sorted,
                    total = total,
                    cap = DomainStatsStore.MAX_DOMAINS,
                    trimmed = trimmed,
                    loading = loading,
                )
            }
        }

        private fun exportFailure(): Result<String> {
            _state.update { it.copy(message = MSG_EXPORT_FAILED) }
            return Result.failure(IllegalStateException("export failed"))
        }

        private fun matchesFilter(
            row: DomainStatSnapshot,
            query: String,
            filter: DomainStatsFilter,
        ): Boolean {
            val passFilter =
                when (filter) {
                    DomainStatsFilter.ALL -> true
                    DomainStatsFilter.WITH_REAL_IP -> row.realIp != null
                    DomainStatsFilter.UNKNOWN_IP -> row.realIp == null
                    DomainStatsFilter.FAILED -> row.failedConnCount > 0
                }
            if (!passFilter) return false
            val q = query.trim()
            if (q.isEmpty()) return true
            return row.domain.contains(q, ignoreCase = true) ||
                row.realIp?.contains(q, ignoreCase = true) == true ||
                row.realIp2?.contains(q, ignoreCase = true) == true
        }

        private fun sortRows(
            rows: List<DomainStatSnapshot>,
            sort: DomainStatsSort,
        ): List<DomainStatSnapshot> =
            when (sort) {
                DomainStatsSort.CONNECTIONS -> rows.sortedByDescending { it.connCount }
                DomainStatsSort.TRAFFIC -> rows.sortedByDescending { it.upBytes + it.downBytes }
                DomainStatsSort.RECENT -> rows.sortedByDescending { it.lastSeenAt }
                DomainStatsSort.DOMAIN -> rows.sortedBy { it.domain }
            }

        companion object {
            /** 搜索 debounce 窗口 (设计 §9.4)。 */
            private const val QUERY_DEBOUNCE_MS = 300L

            /** 一次性消息哨兵: 页面按它映射成 i18n 文案 (ViewModel 不能解析资源)。 */
            const val MSG_CLEARED = "cleared"
            const val MSG_EXPORT_OK = "export_ok"
            const val MSG_EXPORT_FAILED = "export_failed"
            const val MSG_RESOLVE_FAILED = "resolve_failed"
            const val MSG_IP_UNAVAILABLE = "ip_unavailable"
        }
    }
