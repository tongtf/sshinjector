package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.dao.ProxyExitDao
import cn.srv0.sshinjector.data.local.entity.ProxyExitEntity
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.domain.vpn.exit.ProxyExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附加出口读写 (设计 §7.1 / §8.11)。
 *
 * 与 `ServerRepository` 同分层: UI 只构造/读取领域模型 [ProxyExit], 不碰 Entity。
 *
 * 三条纪律:
 * 1. **名字即规则里的引用** —— 所有写入先 [ExitId.normalize] 规范化, 唯一性由 DB 唯一索引兜底
 *    (并发写时 ABORT 抛异常, 这里翻译成 [Result.failure] 交给 UI 提示, 不静默吞掉);
 * 2. 主出口 `default` **不能**建成附加出口 (名字是保留字, [ExitId.normalize] 直接返回 null);
 * 3. 删除是外键级联的相反方向: 删 `servers` 行会自动删出口; 删出口**不影响**规则文本
 *    (规则里的引用变成 unresolved, 由 UI 提示用户决定清理, 绝不自动改写用户的文本)。
 */
@Singleton
class ProxyExitRepository
    @Inject
    constructor(
        private val dao: ProxyExitDao,
    ) {
        val allExitsFlow: Flow<List<ProxyExit>> =
            dao
                .observeAll()
                .map { list -> list.map { it.toDomain() } }
                .flowOn(Dispatchers.IO)

        /** 连接时启动哪些出口的唯一依据 (只取启用项, priority 升序)。 */
        suspend fun getEnabled(): List<ProxyExit> =
            withContext(Dispatchers.IO) {
                dao.getEnabledBlocking().map { it.toDomain() }
            }

        /**
         * 新建出口。
         * @return 新 id; 失败 = 名字非法/重复/服务器不存在 (外键)
         */
        suspend fun create(
            rawName: String,
            serverId: Long,
            priority: Int = DEFAULT_PRIORITY,
        ): Result<Long> {
            val name =
                ExitId.normalize(rawName)
                    ?: return Result.failure(IllegalArgumentException("出口名非法"))
            return withContext(Dispatchers.IO) {
                runCatching {
                    val now = Date()
                    dao.insert(
                        ProxyExitEntity(
                            name = name,
                            serverId = serverId,
                            priority = priority,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }

        /**
         * 改名。规则文本**不会**被改写 —— 调用方必须先用 [countRulesReferencing] 提示用户
         * "有 N 条规则引用旧名", 由用户决定是否同步修改规则文本。
         */
        suspend fun rename(
            id: Long,
            rawName: String,
        ): Result<Unit> {
            val name =
                ExitId.normalize(rawName)
                    ?: return Result.failure(IllegalArgumentException("出口名非法"))
            return withContext(Dispatchers.IO) {
                runCatching {
                    val existing = dao.getByName(name)
                    require(existing == null || existing.id == id) { "出口名已存在" }
                    val current =
                        dao.getAllBlocking().firstOrNull { it.id == id }
                            ?: error("出口不存在")
                    dao.update(current.copy(name = name, updatedAt = Date()))
                    Unit
                }
            }
        }

        suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            withContext(Dispatchers.IO) { dao.setEnabled(id, enabled, Date()) }
        }

        suspend fun delete(id: Long) {
            withContext(Dispatchers.IO) { dao.delete(id) }
        }

        companion object {
            const val DEFAULT_PRIORITY = 100

            /** 规则文本里引用该出口的行数 (改名/删除前的提示用; 纯文本匹配, 不做完整解析)。 */
            fun countRulesReferencing(
                rulesText: String,
                exitName: String,
            ): Int {
                val target = exitName.trim().lowercase()
                if (target.isEmpty()) return 0
                return rulesText
                    .lineSequence()
                    .count { line ->
                        val body = line.substringBefore('#').trim()
                        val arrow = body.indexOf("->")
                        arrow > 0 && body.substring(arrow + 2).trim().lowercase() == target
                    }
            }
        }
    }

private fun ProxyExitEntity.toDomain(): ProxyExit =
    ProxyExit(
        id = id,
        name = name,
        serverId = serverId,
        enabled = enabled,
        priority = priority,
    )
