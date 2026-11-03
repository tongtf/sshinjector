package cn.srv0.sshinjector.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cn.srv0.sshinjector.data.local.entity.ProxyExitEntity
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.entity.WhitelistAppEntity
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface ServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity): Int

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getByIdBlocking(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE isActive = 1")
    fun getActive(): Flow<ServerEntity?>

    @Query("SELECT * FROM servers WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveSync(): ServerEntity?

    @Query("SELECT * FROM servers ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY updatedAt DESC")
    suspend fun getAllBlocking(): List<ServerEntity>

    /**
     * L7: 写入 + 激活单事务。existingId == -1 表示新建 (server.id 未赋值, 走 insert)。
     * 中途失败不会留下"存了但没激活"的半状态。
     */
    @Transaction
    suspend fun insertOrUpdateAndActivate(
        server: ServerEntity,
        existingId: Long,
        activate: Boolean,
    ): Long {
        val id =
            if (existingId == -1L) {
                insert(server)
            } else {
                update(server)
                existingId
            }
        if (activate) {
            setActive(id)
        }
        return id
    }

    @Transaction
    @Query("UPDATE servers SET isActive = 0")
    suspend fun deactivateAll(): Int

    @Transaction
    @Query("UPDATE servers SET isActive = CASE WHEN id = :activeId THEN 1 ELSE 0 END")
    suspend fun setActive(activeId: Long): Int

    /** TOFU 首次连接后落库主机指纹（下次连接即强校验）；JSch KEX 线程同步调用，非 suspend。 */
    @Query("UPDATE servers SET hostKeyFingerprint = :fingerprint WHERE id = :id")
    fun updateHostKeyFingerprint(
        id: Long,
        fingerprint: String,
    )

    /**
     * 清空已落库的主机指纹，使下次连接回到 TOFU（KnownHostsHostKeyRepository.check()
     * 在 DB 指纹非空时以其为权威源，只删 known_hosts 文件对「重置指纹」无效）。
     */
    @Query("UPDATE servers SET hostKeyFingerprint = NULL WHERE id = :id")
    suspend fun clearHostKeyFingerprint(id: Long)
}

@Dao
interface WhitelistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: WhitelistAppEntity): Long

    @Query("DELETE FROM whitelist_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String): Int

    /**
     * 批量启用 (T-13)。必须走单事务: 逐条 insert 会让 `enabledWhitelistFlow` 逐条发射,
     * 而 `SshVpnService` 的观察者是 `collectLatest { rebuildVpnInterfaceInternal() }` ——
     * 一次「全选」几百个应用会触发几百次 TUN 重建 (还会与 disconnect 抢 connectMutex)。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<WhitelistAppEntity>)

    /** 批量取消 (T-13), 理由同 [insertAll]。调用方负责按 500 一批切分 (SQLite 变量上限 999)。 */
    @Query("DELETE FROM whitelist_apps WHERE packageName IN (:packageNames)")
    suspend fun deleteAll(packageNames: List<String>)

    @Query("SELECT * FROM whitelist_apps WHERE isEnabled = 1")
    fun getEnabled(): Flow<List<WhitelistAppEntity>>

    @Query("SELECT packageName FROM whitelist_apps WHERE isEnabled = 1")
    suspend fun getEnabledPackageNames(): List<String>
}

/**
 * 附加出口 DAO (多出口路由, 设计 §8.11)。
 *
 * 主出口 (`default`) 不在这张表里: 它恒等于"当前连接的服务器", 由 `TunnelManager` 直接启动。
 * 排序口径 `priority ASC, name ASC`: priority 是故障转移顺序, name 只做同优先级内的稳定排序
 * (否则 SQLite 返回顺序不确定 → 每次重建快照出口顺序抖动)。
 */
@Dao
interface ProxyExitDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(exit: ProxyExitEntity): Long

    @Update
    suspend fun update(exit: ProxyExitEntity): Int

    @Query("DELETE FROM proxy_exits WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM proxy_exits ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<ProxyExitEntity>>

    @Query("SELECT * FROM proxy_exits ORDER BY priority ASC, name ASC")
    suspend fun getAllBlocking(): List<ProxyExitEntity>

    /** 只取启用的 (连接时启动哪些出口的唯一依据)。 */
    @Query("SELECT * FROM proxy_exits WHERE enabled = 1 ORDER BY priority ASC, name ASC")
    suspend fun getEnabledBlocking(): List<ProxyExitEntity>

    @Query("SELECT * FROM proxy_exits WHERE name = :name")
    suspend fun getByName(name: String): ProxyExitEntity?

    @Query("UPDATE proxy_exits SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
        updatedAt: Date,
    ): Int

    @Query("UPDATE proxy_exits SET priority = :priority, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPriority(
        id: Long,
        priority: Int,
        updatedAt: Date,
    ): Int
}
