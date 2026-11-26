package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一键路由 (域名统计页 / 日志页) 的**唯一**读写入口。
 *
 * 为什么不直接在各 ViewModel 里读改写: `multi_exit_rules` 是 DataStore 里的一个文本键,
 * 两处 UI (统计/日志) 与规则页可能同时写 —— 读-改-写不加锁会互相覆盖 (最后写的赢,
 * 前一条规则静默消失)。这里用 [Mutex] 把"读 → 行级改 → 写回 → 刷新快照"串成一步。
 *
 * [ApplyResult.previousText] 就是**撤销凭据**: 原样回写即可 (行级改写的逆运算不可靠,
 * 快照回写才既简单又精确, 且对"本来就存在的规则"也正确)。
 */
@Singleton
class RoutingRuleEditor
    @Inject
    constructor(
        private val settingsDataStore: SettingsDataStore,
        private val routingTableStore: RoutingTableStore,
    ) {
        /** [previousText] = 应用前的完整文本 (撤销用); [enabled] = 多出口总开关当前状态。 */
        data class ApplyResult(
            val previousText: String,
            val enabled: Boolean,
            /** false = 规则文本没有任何变化 (非法目标 / 已是同一出口)。 */
            val changed: Boolean = false,
        )

        private val mutex = Mutex()

        suspend fun upsertApp(
            packageName: String,
            exitId: String,
        ): ApplyResult = edit { text -> RoutingRuleText.upsertApp(text, packageName, exitId) }

        suspend fun upsertDomain(
            domain: String,
            exitId: String,
        ): ApplyResult = edit { text -> RoutingRuleText.upsertDomain(text, domain, exitId) }

        /** 整段替换 (导入/简单模式保存); 返回应用前文本供撤销。 */
        suspend fun replaceText(text: String): ApplyResult = edit { _ -> text }

        /** 撤销: 原样回写 [previousText]; 空串 = 清空 (DataStore 侧会删除该键)。 */
        suspend fun undo(previousText: String) {
            mutex.withLock { write(previousText) }
        }

        private suspend fun edit(transform: (String) -> String): ApplyResult =
            mutex.withLock {
                val previousText = settingsDataStore.multiExitRules.first()
                val enabled = settingsDataStore.multiExitEnabled.first()
                val next = transform(previousText)
                val changed = next != previousText
                if (changed) write(next)
                ApplyResult(previousText, enabled, changed)
            }

        private suspend fun write(text: String) {
            settingsDataStore.setMultiExitRules(text)
            // 快照必须立刻重建: 已连接时规则要即时生效 (未连接只是刷新内存态)
            routingTableStore.refresh()
        }
    }
