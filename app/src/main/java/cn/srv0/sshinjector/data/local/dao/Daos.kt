package cn.srv0.sshinjector.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.entity.WhitelistAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity): Int

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM servers WHERE id = :id")
    fun getById(id: Long): Flow<ServerEntity?>

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

    @Transaction
    suspend fun insertAndSetActive(server: ServerEntity): Long {
        val id = insert(server)
        setActive(id)
        return id
    }

    @Transaction
    @Query("UPDATE servers SET isActive = 0")
    suspend fun deactivateAll(): Int

    @Transaction
    @Query("UPDATE servers SET isActive = CASE WHEN id = :activeId THEN 1 ELSE 0 END")
    suspend fun setActive(activeId: Long): Int

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int
}

@Dao
interface WhitelistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: WhitelistAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<WhitelistAppEntity>)

    @Update
    suspend fun update(app: WhitelistAppEntity): Int

    @Query("DELETE FROM whitelist_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String): Int

    @Query("SELECT * FROM whitelist_apps WHERE packageName = :packageName")
    fun getByPackageName(packageName: String): Flow<WhitelistAppEntity?>

    @Query("SELECT * FROM whitelist_apps WHERE isEnabled = 1")
    fun getEnabled(): Flow<List<WhitelistAppEntity>>

    @Query("SELECT * FROM whitelist_apps WHERE isEnabled = 1")
    suspend fun getEnabledBlocking(): List<WhitelistAppEntity>

    @Query("SELECT * FROM whitelist_apps ORDER BY appName ASC")
    fun getAll(): Flow<List<WhitelistAppEntity>>

    @Query("SELECT packageName FROM whitelist_apps WHERE isEnabled = 1")
    suspend fun getEnabledPackageNames(): List<String>

    @Query("SELECT COUNT(*) FROM whitelist_apps WHERE isEnabled = 1")
    suspend fun countEnabled(): Int
}
