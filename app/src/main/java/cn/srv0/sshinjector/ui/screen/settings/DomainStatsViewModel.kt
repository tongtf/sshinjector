package cn.srv0.sshinjector.ui.screen.settings

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.model.VpnState.VpnStatus
import cn.srv0.sshinjector.domain.usecase.DomainIpResolver
import cn.srv0.sshinjector.domain.usecase.ResolvePath
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.AppUidProbe
import cn.srv0.sshinjector.domain.vpn.DomainStatSnapshot
import cn.srv0.sshinjector.domain.vpn.DomainStatsCsv
import cn.srv0.sshinjector.domain.vpn.DomainStatsStore
import cn.srv0.sshinjector.domain.vpn.DomainSuffixSet
import cn.srv0.sshinjector.domain.vpn.StatsSuggestionStore
import cn.srv0.sshinjector.domain.vpn.Suggestion
import cn.srv0.sshinjector.domain.vpn.SuggestionDirection
import cn.srv0.sshinjector.domain.vpn.SuggestionNote
import cn.srv0.sshinjector.domain.vpn.SuggestionTarget
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

/**
 * 单行的名单状态 (P1)。
 *
 * @param inAdBlock 是否已被广告规则命中; `null` = 未知 (超预算 / 该行从未问过)。
 * @param inDomainList 是否已在代理名单 (含叠加层); `null` = 未知。
 * @param adPending 广告侧的待确认方向; `null` = 无待确认。
 * @param listPending 代理名单侧的待确认方向。
 */
data class RowListMarks(
    val inAdBlock: Boolean? = null,
    val inDomainList: Boolean? = null,
    val adPending: SuggestionDirection? = null,
    val listPending: SuggestionDirection? = null,
)

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
    /** 「使用远端解析 (经 SSH)」开关 (默认关, 记忆到 DataStore)。 */
    val useRemoteResolve: Boolean = false,
    /** 开关打开但远端不可用 → 页面标注 `本地视角 (远端不可用)` (设计 §7.2)。 */
    val remoteUnavailable: Boolean = false,
    /**
     * 一次性「按应用 uid (P2) 可行性探针」结果; 页面弹一次 Toast 后调
     * [DomainStatsViewModel.consumeAppUidProbe] 消费掉 (避免重组时反复弹)。
     */
    val appUidProbe: AppUidProbe.Result? = null,
    /** 域名 (已规范化) → 名单状态。只收录**非 IP 字面量**行。 */
    val marks: Map<String, RowListMarks> = emptyMap(),
    /** 待确认建议条数 (底部条 / 设置页徽标)。 */
    val pendingCount: Int = 0,
    /**
     * 展示行里出现的 uid → 应用名 (只在 `publishRows` 里重建; 见 [AppUidLabels])。
     * 未解析到 (超预算 / 已卸载) 的 uid 由页面退化成 `uid N`。
     */
    val appLabels: Map<Int, String> = emptyMap(),
)

/**
 * 域名访问统计页面 (P0 只读展示 + P1 chip 进/出待确认建议)。
 *
 * 性能纪律 (设计 §9.4): **绝不**订阅数据面 —— 快照只在开屏 / 手动刷新时抓一次; 排序/筛选/搜索
 * 全在 [Dispatchers.Default]; 搜索 300ms debounce; 搜索/排序/筛选都不在 `@Composable` 里做。
 *
 * 名单成员判定同样不进热路径: 只在 `refresh()` 时算一次, 且带 [MEMBERSHIP_BUDGET_MS] 预算
 * (用户自配的远端广告清单可能含上千条 `*` 正则, 20000 行逐个匹配会秒级卡住) —— 超预算的行
 * `inAdBlock` 留 `null` (chip 显示未知), 用户点 chip 时再单独问一次。
 *
 * `message` 是一次性交互结果 (清空 / 导出 / 解析), 值为 **语言无关的哨兵常量**, 由页面映射成
 * `stringResource` 再 Toast —— ViewModel 拿不到 `Context`, 不能在这里解析资源 (i18n 硬要求)。
 */
@HiltViewModel
class DomainStatsViewModel
    @Inject
    constructor(
        private val store: DomainStatsStore,
        private val suggestionStore: StatsSuggestionStore,
        private val domainListManager: DomainListManager,
        private val vpnController: VpnController,
        private val domainIpResolver: DomainIpResolver,
        private val settingsDataStore: SettingsDataStore,
        private val appUidProbe: AppUidProbe,
        private val appUidLabels: AppUidLabels,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DomainStatsUiState())
        val state: StateFlow<DomainStatsUiState> = _state.asStateFlow()

        /** 最近一次抓取的全量快照 (未排序/未筛选); 只在 Default 线程写, 主线程也读 → @Volatile。 */
        @Volatile private var allRows: List<DomainStatSnapshot> = emptyList()

        /** 已解析出的名单成员关系 (不含待确认); 键 = 规范化域名。跨 Dispatchers 读写 → @Volatile。 */
        @Volatile private var membership: Map<String, RowListMarks> = emptyMap()

        /** [membership] 的读-改-写闸 (chip 按需解析 vs 叠加层整块重算)。锁内**不得**挂起。 */
        private val membershipLock = Any()

        /** 搜索 debounce 任务。 */
        private var queryJob: Job? = null

        init {
            refresh()
            // 待确认集合变化 (本页 chip / 复核页应用与撤销) → 只重算 chip 的 pending 部分, 不重算成员关系
            viewModelScope.launch {
                suggestionStore.state.collect { publishMarks() }
            }
            // 叠加层被应用/撤销 → 代理名单成员关系失效, 整块重算 (低频: 只发生在用户点"应用")
            viewModelScope.launch {
                domainListManager.overlayVersion.collect {
                    if (allRows.isNotEmpty()) {
                        val computed = withContext(Dispatchers.Default) { computeMembership(allRows) }
                        synchronized(membershipLock) { membership = computed }
                        publishMarks()
                    }
                }
            }
            // 「使用远端解析」开关: 以 DataStore 为单一真相 (开关只写库, 状态由这里回流)
            viewModelScope.launch {
                settingsDataStore.useRemoteResolve.collect { enabled ->
                    _state.update { it.copy(useRemoteResolve = enabled) }
                }
            }
        }

        /** 抓一次快照并重建展示行。开屏与手动刷新调用; 数据面不触发它。 */
        fun refresh() {
            viewModelScope.launch {
                _state.update { it.copy(loading = true) }
                withContext(Dispatchers.Default) {
                    val rows = store.snapshot()
                    allRows = rows
                    val computed = computeMembership(rows)
                    synchronized(membershipLock) { membership = computed }
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

        /**
         * 点 chip: 进 / 出「待确认建议」(**不直接改名单**)。
         *
         * 方向 = 与**当前生效状态**相反: 已在名单 → `REMOVE`, 不在 → `ADD`。当前状态未知时按需解析
         * 单个域名 (代理侧是纯内存查表; 广告侧需编译一次规则, 都在 Default 上做)。
         */
        fun toggleSuggestion(
            domain: String,
            target: SuggestionTarget,
        ) {
            val key = DomainSuffixSet.normalize(domain)
            if (key.isEmpty()) return
            viewModelScope.launch {
                val cur = membership[key] ?: RowListMarks()
                val known =
                    when (target) {
                        SuggestionTarget.AD_BLOCK -> cur.inAdBlock
                        SuggestionTarget.DOMAIN_LIST -> cur.inDomainList
                    }
                val inList = known ?: withContext(Dispatchers.Default) { resolveMembership(key, target) }
                // 解析在锁外 (内部会挂起), 回写必须与叠加层重算互斥 —— 否则两边各写各的 map, 后写者把前者的结果抹掉 (chip 退回 ?)
                synchronized(membershipLock) {
                    val base = membership[key] ?: RowListMarks()
                    membership =
                        membership +
                        (
                            key to
                                when (target) {
                                    SuggestionTarget.AD_BLOCK -> base.copy(inAdBlock = inList)
                                    SuggestionTarget.DOMAIN_LIST -> base.copy(inDomainList = inList)
                                }
                        )
                }
                suggestionStore.toggle(key, target, currentlyInList = inList, note = noteFor(key))
                publishMarks()
            }
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
         * 对单个域名解析真实 IP, 结果回写统计条目并刷新。
         *
         * 路径由页面开关决定 (设计 §7.2): 默认**本地视角** (受保护 socket 直查); 开关打开时先走远端
         * (池内会话 exec `getent ahosts`), 远端不可用则**静默回退本地**并在页面标注
         * `本地视角 (远端不可用)` —— 远端腿的裁决/日志全在 [DomainIpResolver] 里。
         *
         * 单飞: 已有解析在跑时直接忽略 (同时只允许一个解析)。两条腿都是阻塞调用, 由 resolver 保证
         * 离开调用线程。
         */
        fun resolveRealIp(domain: String) {
            if (_state.value.resolving != null) return
            _state.update { it.copy(resolving = domain) }
            viewModelScope.launch {
                try {
                    val result = domainIpResolver.resolve(domain, _state.value.useRemoteResolve)
                    val fellBack = result.path == ResolvePath.LOCAL_FALLBACK
                    if (result.ips.isEmpty()) {
                        _state.update {
                            it.copy(
                                resolving = null,
                                message = MSG_IP_UNAVAILABLE,
                                remoteUnavailable = fellBack,
                            )
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Default) {
                        store.attachRealIp(domain, result.ips)
                        allRows = store.snapshot()
                        publishRows()
                    }
                    _state.update {
                        it.copy(
                            resolving = null,
                            message = if (fellBack) MSG_REMOTE_UNAVAILABLE else null,
                            remoteUnavailable = fellBack,
                        )
                    }
                } catch (_: Exception) {
                    _state.update { it.copy(resolving = null, message = MSG_RESOLVE_FAILED) }
                }
            }
        }

        /**
         * 「使用远端解析 (经 SSH)」开关 (默认关): 只写 DataStore, 页面状态由 init 的 collector 回流
         * (单一真相, 避免两处各写一份)。切开关时清掉上一次的"远端不可用"标注 (`remoteUnavailable`)。
         */
        fun setUseRemoteResolve(enabled: Boolean) {
            _state.update { it.copy(remoteUnavailable = false) }
            viewModelScope.launch { settingsDataStore.setUseRemoteResolve(enabled) }
        }

        /**
         * 「按应用 uid (P2) 可行性探针」: 只读诊断, 结论进 [AppUidProbe] 的应用内日志 + 页面弹窗
         * (弹窗可**复制**结果文本 —— 无 adb 时用户要能把它贴出来)。
         *
         * 动机是"先取证再设计": 连接→uid 的能力能不能用只有真机说了算, 所以在写任何 `appUid` 进
         * 数据模型之前, 把结果变成一条可回报的证据 (设计附录 A)。
         * 纯查询 (反射 + app-op 查询), 不做 IO 也不改状态, 但仍放 Default 线程 (反射首次解析可能
         * 触发类加载)。
         *
         * [VpnController.appUidDiagnostics] 一起带上是**关键**: 环回探针拿不到归属是预期结果, 真正
         * 说明 ④ 是否可用的是数据面命中率 (2026-10-19 真机: 探针 NO_OWNER 而数据面 `命中 7/105`)。
         */
        fun probeAppUid() {
            if (_state.value.appUidProbe != null) return
            viewModelScope.launch {
                // 该 API 只对**活动** VpnService 开放: 未连接时调用必然 SecurityException, 那不是结论
                val vpnActive = vpnController.vpnState.value.status == VpnStatus.Connected
                val dataPlane = vpnController.appUidDiagnostics()
                val result = withContext(Dispatchers.Default) { appUidProbe.probe(vpnActive, dataPlane) }
                _state.update { it.copy(appUidProbe = result) }
            }
        }

        /** 页面消费掉探针结果 (用户读完/复制后关掉弹窗, 避免每次重组重弹)。 */
        fun consumeAppUidProbe() {
            _state.update { it.copy(appUidProbe = null) }
        }

        /**
         * 导出全量快照为 CSV 到系统"下载"目录 (MediaStore, 无需权限), 成功返回文件名。
         * 结果同样写入 [DomainStatsUiState.message] 供页面 Toast。
         */
        suspend fun exportCsv(context: Context): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    // 应用列 (④): uid 是数据面的机器事实, 应用名由展示层按需解析 ——
                    // 与页面「应用」列同口径 (超出 [MAX_APP_LABELS] 或解析不到时退化成 `uid N`)。
                    val rows = store.snapshot()
                    val text = DomainStatsCsv.render(rows, resolveAppLabels(rows))
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
            val labels = resolveAppLabels(sorted)
            _state.update {
                it.copy(
                    rows = sorted,
                    appLabels = labels,
                    total = total,
                    cap = DomainStatsStore.MAX_DOMAINS,
                    trimmed = trimmed,
                    loading = loading,
                )
            }
            publishMarks()
        }

        /**
         * 把展示行里出现的 uid 解析成应用名 (④ 按应用归属)。
         *
         * 预算纪律 (与 [computeMembership] 同精神): `PackageManager` 查询是 ~ms 级, 而 uid 的
         * **distinct 数量级 = 用户真用过的应用数** (几十) —— 仍加 [MAX_APP_LABELS] 上限兜底,
         * 绝不因为"20000 行里有很多 uid"让 refresh 变慢 (超上限的行退化成 `uid N`)。
         * [AppUidLabels] 自带缓存, 所以重复刷新基本是 map 命中。
         */
        private fun resolveAppLabels(rows: List<DomainStatSnapshot>): Map<Int, String> {
            val labels = HashMap<Int, String>()
            for (row in rows) {
                collectAppLabel(labels, row.appUid1)
                collectAppLabel(labels, row.appUid2)
                collectAppLabel(labels, row.appUid3)
            }
            return labels
        }

        private fun collectAppLabel(
            labels: HashMap<Int, String>,
            uid: Int,
        ) {
            if (uid < 0 || labels.size >= MAX_APP_LABELS || labels.containsKey(uid)) return
            labels[uid] = appUidLabels.label(uid)
        }

        /** 把「已解析的成员关系」与「待确认建议」合成 chip 状态。 */
        private fun publishMarks() {
            val suggestions = suggestionStore.state.value
            val marks = if (suggestions.isEmpty()) membership else mergePending(membership, suggestions)
            _state.update { it.copy(marks = marks, pendingCount = suggestions.size) }
        }

        private fun mergePending(
            membership: Map<String, RowListMarks>,
            suggestions: List<Suggestion>,
        ): Map<String, RowListMarks> {
            val out = HashMap(membership)
            for (s in suggestions) {
                val cur = out[s.domain] ?: RowListMarks()
                out[s.domain] =
                    when (s.target) {
                        SuggestionTarget.AD_BLOCK -> cur.copy(adPending = s.direction)
                        SuggestionTarget.DOMAIN_LIST -> cur.copy(listPending = s.direction)
                    }
            }
            return out
        }

        private suspend fun resolveMembership(
            domain: String,
            target: SuggestionTarget,
        ): Boolean =
            when (target) {
                SuggestionTarget.DOMAIN_LIST -> domainListManager.matches(domain)
                SuggestionTarget.AD_BLOCK -> {
                    val matcher = runCatching { vpnController.adBlockMatcherSnapshot() }.getOrNull()
                    matcher?.matches(domain) == true
                }
            }

        /**
         * 计算"域名是否已在名单" (只对可匹配的域名行)。
         *
         * 预算纪律: 超 [MEMBERSHIP_BUDGET_MS] 就停止算广告侧 (`inAdBlock` 留 null = 未知),
         * 代理名单侧是纯内存后缀查表, 一直算完。绝不能让 20000 行 × 上千条正则卡住 refresh。
         */
        private suspend fun computeMembership(rows: List<DomainStatSnapshot>): Map<String, RowListMarks> {
            val adMatcher = runCatching { vpnController.adBlockMatcherSnapshot() }.getOrNull()
            val deadline = System.nanoTime() + MEMBERSHIP_BUDGET_MS * NANOS_PER_MS
            val out = HashMap<String, RowListMarks>()
            for (row in rows.filter { isMatchableDomain(it) }) {
                val key = DomainSuffixSet.normalize(row.domain)
                if (key.isEmpty()) continue
                val inDomainList = domainListManager.matches(key)
                val inAdBlock =
                    if (adMatcher != null && System.nanoTime() < deadline) {
                        adMatcher.matches(key)
                    } else {
                        null
                    }
                out[key] = RowListMarks(inAdBlock = inAdBlock, inDomainList = inDomainList)
            }
            return out
        }

        /** IP 字面量与反向解析不参与名单判定 (与 `AdBlocker.isBlocked` 同口径)。 */
        private fun isMatchableDomain(row: DomainStatSnapshot): Boolean =
            !row.isIpLiteral && !row.domain.endsWith(ARPA_SUFFIX, ignoreCase = true)

        private fun noteFor(domain: String): SuggestionNote {
            val row = allRows.firstOrNull { DomainSuffixSet.normalize(it.domain) == domain }
            return SuggestionNote(
                connCount = row?.connCount ?: 0,
                downBytes = row?.downBytes ?: 0L,
                failedConnCount = row?.failedConnCount ?: 0,
            )
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

            /** 名单成员判定的时间预算 (超预算的行 chip 显示"未知", 点开再问)。 */
            private const val MEMBERSHIP_BUDGET_MS = 60L
            private const val NANOS_PER_MS = 1_000_000L
            private const val ARPA_SUFFIX = ".arpa"

            /** 每次刷新最多解析多少个 uid→应用名 (超出退化成 `uid N`; distinct uid 数量级本来就很小)。 */
            private const val MAX_APP_LABELS = 64

            /** 一次性消息哨兵: 页面按它映射成 i18n 文案 (ViewModel 不能解析资源)。 */
            const val MSG_CLEARED = "cleared"
            const val MSG_EXPORT_OK = "export_ok"
            const val MSG_EXPORT_FAILED = "export_failed"
            const val MSG_RESOLVE_FAILED = "resolve_failed"
            const val MSG_IP_UNAVAILABLE = "ip_unavailable"

            /** 远端解析不可用 → 已回退本地视角 (设计 §7.2 的 `本地视角 (远端不可用)`)。 */
            const val MSG_REMOTE_UNAVAILABLE = "remote_unavailable"
        }
    }
