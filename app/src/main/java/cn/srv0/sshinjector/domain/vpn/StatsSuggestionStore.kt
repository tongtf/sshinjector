package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 建议的目标名单。 */
enum class SuggestionTarget { AD_BLOCK, DOMAIN_LIST }

/** 建议方向: 加入 / 移出。 */
enum class SuggestionDirection { ADD, REMOVE }

/**
 * 建议依据 (数字原样存, 文案由 UI 按当前语言渲染 —— ViewModel 不许拼用户可见文案)。
 *
 * @param connCount 该域名的 TCP 连接数 (频率口径, 不是 DNS 查询数)。
 * @param downBytes 会话内下行字节。
 * @param failedConnCount 失败连接数 (内部失败)。
 */
data class SuggestionNote(
    val connCount: Long,
    val downBytes: Long,
    val failedConnCount: Long,
)

/** 建议的唯一键 (与 [StatsSuggestionStore] 的去重键一致)。 */
data class SuggestionKey(
    val domain: String,
    val target: SuggestionTarget,
)

/**
 * 一条待确认建议。
 *
 * @param note 依据 —— 复核页展示, 让用户能判断该不该应用。
 */
data class Suggestion(
    val domain: String,
    val target: SuggestionTarget,
    val direction: SuggestionDirection,
    val note: SuggestionNote,
    val createdAt: Long,
) {
    /** 唯一键 (派生属性, 不参与 equals)。 */
    val key: SuggestionKey
        get() = SuggestionKey(domain, target)
}

/**
 * 待确认建议列表 (**会话内存, 刻意不持久化** —— 应用前退出即丢弃, 设计 §8.1/§9.3)。
 *
 * 唯一的去重键是 `(domain, target)`: 同一对只保留一条, 于是"再点一次 chip = 取消"无歧义,
 * 方向由 [toggle] 的 `currentlyInList` 决定 (与当前生效状态相反)。
 *
 * 线程安全: 全部改动走 `@Synchronized` (并发来源是 UI 事件与幂等的批量操作, 冲突概率低但代价小)。
 */
@Singleton
class StatsSuggestionStore
    @Inject
    constructor() {
        private val _state = MutableStateFlow<List<Suggestion>>(emptyList())
        val state: StateFlow<List<Suggestion>> = _state.asStateFlow()

        /**
         * 点 chip 的语义: 已有同 (domain,target) 建议 → 取消并返回 false; 否则按"与当前状态相反"
         * 建一条并返回 true。
         */
        @Synchronized
        fun toggle(
            domain: String,
            target: SuggestionTarget,
            currentlyInList: Boolean,
            note: SuggestionNote,
        ): Boolean {
            val d = DomainSuffixSet.normalize(domain)
            if (d.isEmpty()) return false
            val existing = _state.value.firstOrNull { it.domain == d && it.target == target }
            if (existing != null) {
                _state.value = _state.value - existing
                return false
            }
            val direction = if (currentlyInList) SuggestionDirection.REMOVE else SuggestionDirection.ADD
            _state.value = _state.value + Suggestion(d, target, direction, note, System.currentTimeMillis())
            return true
        }

        /** 直接放入一条 (批量入口); 同 (domain,target) 已存在时替换。 */
        @Synchronized
        fun put(
            domain: String,
            target: SuggestionTarget,
            direction: SuggestionDirection,
            note: SuggestionNote,
        ) {
            val d = DomainSuffixSet.normalize(domain)
            if (d.isEmpty()) return
            val kept = _state.value.filterNot { it.domain == d && it.target == target }
            _state.value = kept + Suggestion(d, target, direction, note, System.currentTimeMillis())
        }

        @Synchronized
        fun remove(
            domain: String,
            target: SuggestionTarget,
        ) {
            val d = DomainSuffixSet.normalize(domain)
            _state.value = _state.value.filterNot { it.domain == d && it.target == target }
        }

        @Synchronized
        fun clear() {
            _state.value = emptyList()
        }

        fun pendingDirection(
            domain: String,
            target: SuggestionTarget,
        ): SuggestionDirection? {
            val d = DomainSuffixSet.normalize(domain)
            return _state.value.firstOrNull { it.domain == d && it.target == target }?.direction
        }
    }
