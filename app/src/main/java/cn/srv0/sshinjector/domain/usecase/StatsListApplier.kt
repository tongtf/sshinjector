package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.vpn.StatsListOps
import cn.srv0.sshinjector.domain.vpn.Suggestion
import cn.srv0.sshinjector.domain.vpn.SuggestionTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「待确认建议 → 批量应用 / 撤销」(P1 设计 §8.2–§8.4) 的唯一写入者。
 *
 * 三条纪律:
 * ① **读-改-写串行** (`applyMutex`): 广告规则是**文本键**, 两个并发 apply 会互相覆盖;
 * ② 只写"统计生成"的三个键, 绝不碰用户手写规则 (`ad_block_rules`) 与基础清单 (设计 §8.0);
 * ③ 写完后立刻让两边**即时生效**: `reloadOverlay()` (DOMAIN_SPLIT 每次查询实时读叠加层) +
 *   `reloadAdBlockRules()` (广告 matcher 连接时只编译一次, 不重编译会出现"应用了没反应")。
 */
@Singleton
class StatsListApplier
    @Inject
    constructor(
        private val settings: SettingsDataStore,
        private val domainListManager: DomainListManager,
        private val vpnController: VpnController,
    ) {
        private val applyMutex = Mutex()

        /**
         * 套用一批建议; 返回**应用前**的快照 —— 直接当作撤销 token 交给 [restore]。
         * 幂等: 同一批应用两次结果相同 (行级增删与集合增删都是幂等的)。
         */
        suspend fun apply(items: List<Suggestion>): StatsListOps.State =
            applyMutex.withLock {
                val before = readState()
                if (items.isEmpty()) return@withLock before
                writeState(StatsListOps.apply(before, items))
                reload(touchedAdBlock = items.any { it.target == SuggestionTarget.AD_BLOCK })
                before
            }

        /** 撤销: 原样写回应用前的快照 (精确可逆), 并重新让两边生效。 */
        suspend fun restore(token: StatsListOps.State) {
            applyMutex.withLock {
                writeState(token)
                reload(touchedAdBlock = true)
            }
        }

        private suspend fun readState(): StatsListOps.State =
            StatsListOps.State(
                adBlockRulesFromStats = settings.getAdBlockRulesFromStats(),
                domainListAdded = settings.getDomainListAddedFromStats(),
                domainListRemoved = settings.getDomainListRemovedFromStats(),
            )

        private suspend fun writeState(state: StatsListOps.State) {
            settings.setAdBlockRulesFromStats(state.adBlockRulesFromStats)
            settings.setDomainListAddedFromStats(state.domainListAdded)
            settings.setDomainListRemovedFromStats(state.domainListRemoved)
        }

        private suspend fun reload(touchedAdBlock: Boolean) {
            domainListManager.reloadOverlay()
            if (touchedAdBlock) vpnController.reloadAdBlockRules()
        }
    }
