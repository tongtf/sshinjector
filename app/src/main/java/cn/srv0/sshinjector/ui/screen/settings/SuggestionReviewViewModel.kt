package cn.srv0.sshinjector.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.domain.usecase.StatsListApplier
import cn.srv0.sshinjector.domain.vpn.StatsListOps
import cn.srv0.sshinjector.domain.vpn.StatsSuggestionStore
import cn.srv0.sshinjector.domain.vpn.Suggestion
import cn.srv0.sshinjector.domain.vpn.SuggestionKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 复核页状态。 */
data class SuggestionReviewUiState(
    val items: List<Suggestion> = emptyList(),
    /** 已勾选 (新出现的条目默认勾选); 元素是 [SuggestionKey]。 */
    val selected: Set<SuggestionKey> = emptySet(),
    val applying: Boolean = false,
    /** 最近一次应用条数; 页面据此弹带「撤销」的 Snackbar, 消费后置 null。 */
    val appliedCount: Int? = null,
    /** 撤销成功 (一次性)。 */
    val undone: Boolean = false,
    /** 应用 / 撤销失败 (一次性; 页面对应 Snackbar)。 */
    val failed: Boolean = false,
)

/**
 * 「待确认建议」复核页 (设计 §9.3)。
 *
 * 页面只做三件事: 勾选 (默认全选)、删单条 / 全部丢弃、应用勾选项。应用后把**已应用**的条目从
 * 待确认列表移除, 未勾选的留在列表里; 撤销用 [StatsListApplier] 返回的"应用前快照"精确回写。
 *
 * 状态映射 (i18n) 全部交给页面: 这里只有 `count` / 三个一次性布尔, 没有任何用户可见文案。
 */
@HiltViewModel
class SuggestionReviewViewModel
    @Inject
    constructor(
        private val suggestionStore: StatsSuggestionStore,
        private val applier: StatsListApplier,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SuggestionReviewUiState())
        val state: StateFlow<SuggestionReviewUiState> = _state.asStateFlow()

        /** 最近一次应用的"应用前快照" —— 撤销就是原样写回它。 */
        private var undoToken: StatsListOps.State? = null

        /** 上一次看到的键集合: 用来判定"新出现的条目"并默认勾选。 */
        private var seenKeys: Set<SuggestionKey> = emptySet()

        init {
            viewModelScope.launch {
                suggestionStore.state.collect { items ->
                    val keys = items.map { it.key }.toSet()
                    val fresh = keys - seenKeys
                    seenKeys = keys
                    _state.update { cur ->
                        cur.copy(items = items, selected = (cur.selected intersect keys) + fresh)
                    }
                }
            }
        }

        fun setSelected(
            key: SuggestionKey,
            checked: Boolean,
        ) {
            _state.update { cur ->
                cur.copy(selected = if (checked) cur.selected + key else cur.selected - key)
            }
        }

        fun selectAll() {
            _state.update { it.copy(selected = it.items.map { item -> item.key }.toSet()) }
        }

        fun deselectAll() {
            _state.update { it.copy(selected = emptySet()) }
        }

        /** 删单条 (只出待确认列表, 不动任何名单)。 */
        fun discard(key: SuggestionKey) {
            suggestionStore.remove(key.domain, key.target)
        }

        fun discardAll() {
            suggestionStore.clear()
        }

        fun applySelected() {
            val cur = _state.value
            if (cur.applying) return
            val chosen = cur.items.filter { it.key in cur.selected }
            if (chosen.isEmpty()) return
            _state.update { it.copy(applying = true) }
            viewModelScope.launch {
                try {
                    undoToken = applier.apply(chosen)
                    // 只在写成功后移除 (失败则条目留在列表里让用户重试)
                    chosen.forEach { suggestionStore.remove(it.domain, it.target) }
                    _state.update { it.copy(applying = false, appliedCount = chosen.size, undone = false) }
                } catch (_: Exception) {
                    _state.update { it.copy(applying = false, failed = true) }
                }
            }
        }

        /** 撤销最近一次应用 (精确回写应用前快照)。 */
        fun undo() {
            val token = undoToken ?: return
            viewModelScope.launch {
                try {
                    applier.restore(token)
                    _state.update { it.copy(appliedCount = null, undone = true) }
                } catch (_: Exception) {
                    _state.update { it.copy(appliedCount = null, failed = true) }
                }
            }
        }

        /** 一次性状态消费 (页面弹出 Snackbar 后调用)。 */
        fun consumeTransient() {
            _state.update { it.copy(appliedCount = null, undone = false, failed = false) }
        }
    }
