package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删除服务器的**唯一入口** (三个 UI 入口共用)。
 *
 * 旧实现的三个入口各写一遍: 仪表盘只删行、列表页删完提升默认、编辑页删完提升默认 ——
 * 没有任何一处清 `lastServerId`, 删除"最后连接的那台"后开机自启会去连一个不存在的 id,
 * 表现为"开机连不上 + 永久 Connecting"。
 *
 * 出口回收由 [VpnController] 订阅 `ProxyExitRepository.allExitsFlow` 完成 —— 不能放在这里:
 * `ServerRepository` 已被 `VpnController` 依赖, 反向依赖会形成 Hilt 循环。
 */
@Singleton
class ServerDeleter
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val settingsDataStore: SettingsDataStore,
    ) {
        suspend fun delete(id: Long) =
            withContext(Dispatchers.IO) {
                val wasActive = serverRepository.getServerById(id)?.isActive == true
                val remaining = serverRepository.getAllServers().map { it.id }.filter { it != id }
                val plan =
                    serverDeletionPlan(
                        wasActive = wasActive,
                        remainingIds = remaining,
                        lastServerId = settingsDataStore.lastServerId.first(),
                        deletedId = id,
                    )
                serverRepository.deleteServer(id)
                plan.nextActiveId?.let { serverRepository.setActiveServer(it) }
                if (plan.clearLastServerId) settingsDataStore.clearLastServerId()
            }
    }
