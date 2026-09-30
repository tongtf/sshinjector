package cn.srv0.sshinjector.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.AdBlockManager
import cn.srv0.sshinjector.data.local.AdBlockRemoteState
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdBlockSettingsViewModel
    @Inject
    constructor(
        private val settingsDataStore: SettingsDataStore,
        private val adBlockManager: AdBlockManager,
    ) : ViewModel() {
        // 当前可见 / 可编辑的规则文本 (每行一条); 首次打开 = 运行时已保存内容, 否则内置清单
        private val _rulesText = MutableStateFlow("")
        val rulesText: StateFlow<String> = _rulesText.asStateFlow()

        // 持久化的远程缓存规则文本 (连接时与 rules 叠加生效); UI 仅用于计数, 不展示其内容。
        private val _remoteText = MutableStateFlow("")
        val remoteText: StateFlow<String> = _remoteText.asStateFlow()

        // 搜索关键字 (对规则做子串过滤, 仅影响展示与统计)
        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        // 过滤后的命中行数 (用于统计)
        val visibleRuleCount: StateFlow<Int> =
            combine(_rulesText.asStateFlow(), _query.asStateFlow(), ::Pair)
                .map { (text, q) -> countRules(text, q) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

        // 过滤后的规则行列表 (用于快速查看匹配项)
        val filteredLines: StateFlow<List<String>> =
            combine(_rulesText.asStateFlow(), _query.asStateFlow(), ::Pair)
                .map { (text, q) -> filterRules(text, q) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        // 远程规则源配置与上次拉取状态
        val remoteUrl: StateFlow<String> =
            settingsDataStore.adBlockRemoteUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")

        val refreshInterval: StateFlow<Int> =
            settingsDataStore.adBlockRefreshInterval.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                SettingsDataStore.DEFAULT_ADBLOCK_REFRESH_MINUTES,
            )

        val remoteState: StateFlow<AdBlockRemoteState> =
            adBlockManager.state.stateIn(viewModelScope, SharingStarted.Eagerly, AdBlockRemoteState.Idle)

        init {
            // 手动编辑的规则仅来自 adBlockRules, 与远程刷新互不影响; 远程规则在连接时作为基础清单静默生效, UI 不展示其内容。
            viewModelScope.launch { loadInitialRules() }
            // 持久化的远程缓存文本 (磁盘旧缓存 + 刷新成功的新内容); 供顶部计数使用。
            viewModelScope.launch {
                adBlockManager.state.collect { state ->
                    if (state is AdBlockRemoteState.Ready) _remoteText.value = state.text.orEmpty()
                }
            }
        }

        /** 读取运行时保存的规则; 未保存则加载内置清单作为可编辑起点。 */
        private suspend fun loadInitialRules() {
            val saved =
                try {
                    settingsDataStore.adBlockRules.first().takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                }
            _rulesText.value = saved.orEmpty()
        }

        fun setQuery(query: String) {
            _query.value = query.lowercase()
        }

        /** 保存用户编辑; 空串 = 清空运行时覆盖, 界面回退内置清单以保持可见可继续编辑。 */
        fun setRules(text: String) {
            val trimmed = text.trimEnd('\n')
            viewModelScope.launch {
                settingsDataStore.setAdBlockRules(trimmed.ifBlank { null })
                _rulesText.value = trimmed.orEmpty()
            }
        }

        /** 恢复内置默认并清除运行时覆盖标记。 */
        fun resetToDefault() {
            // 清空自定义查漏补缺规则, 回退到远程/内置基础清单。
            viewModelScope.launch {
                settingsDataStore.setAdBlockRules(null)
                _rulesText.value = ""
            }
        }

        /** 保存远程规则源 URL (空串 = 关闭远程加载) 并立即拉取一次。 */
        fun saveRemoteUrlAndRefresh(url: String) =
            viewModelScope.launch {
                settingsDataStore.setAdBlockRemoteUrl(url.trim())
                adBlockManager.update()
            }

        /** 保存远程规则源 URL; 空串 = 关闭远程加载。 */
        fun setRemoteUrl(url: String) {
            viewModelScope.launch { settingsDataStore.setAdBlockRemoteUrl(url.trim()) }
        }

        /** 把远程源重置为内置默认 URL (jsDelivr); 而非清空成空串导致输入框只剩 example.com 占位提示。 */
        fun resetRemoteUrlToDefault() {
            val url = SettingsDataStore.DEFAULT_ADBLOCK_REMOTE_URL
            viewModelScope.launch { settingsDataStore.setAdBlockRemoteUrl(url) }
        }

        /** 自动刷新间隔(分钟); 约束在允许范围内。 */
        fun setRefreshInterval(minutes: Int) {
            val safe =
                minutes
                    .toLong()
                    .coerceIn(
                        SettingsDataStore.MIN_ADBLOCK_REFRESH_MINUTES,
                        SettingsDataStore.MAX_ADBLOCK_REFRESH_MINUTES,
                    ).toInt()
            viewModelScope.launch { settingsDataStore.setAdBlockRefreshInterval(safe) }
        }

        /** 立即从配置的 URL 拉取一次。 */
        fun refreshNow() = viewModelScope.launch { adBlockManager.update() }

        private fun countRules(
            text: String,
            query: String,
        ): Int {
            var n = 0
            for (line in text.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || isComment(t)) continue
                if (query.isBlank() || t.lowercase().contains(query)) n++
            }
            return n
        }

        private fun filterRules(
            text: String,
            query: String,
        ): List<String> {
            val result = ArrayList<String>()
            for (line in text.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || isComment(t)) continue
                if (query.isBlank() || t.lowercase().contains(query)) result.add(t)
            }
            return result
        }

        private fun isComment(line: String): Boolean = line.startsWith("!") || line.startsWith("[")
    }
