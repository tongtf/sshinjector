package com.sshinjector.domain.usecase

import com.sshinjector.data.local.dao.ServerDao
import com.sshinjector.data.local.dao.WhitelistDao
import com.sshinjector.data.local.entity.ServerEntity
import com.sshinjector.data.local.entity.WhitelistAppEntity
import com.sshinjector.data.local.entity.DnsMode as EntityDnsMode
import com.sshinjector.domain.model.ServerConfig
import com.sshinjector.domain.model.WhitelistApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Date
import javax.inject.Inject

class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val whitelistDao: WhitelistDao
) {
    
    suspend fun getAllServers(): List<ServerConfig> = withContext(Dispatchers.IO) {
        serverDao.getAllBlocking().map { it.toDomain() }
    }

    val allServersFlow: Flow<List<ServerConfig>> = serverDao.getAll().map { 
        it.map { it.toDomain() } 
    }

    val activeServerFlow: Flow<ServerConfig?> = serverDao.getActive().map { 
        it?.toDomain() 
    }

    suspend fun getServerById(id: Long): ServerConfig? = withContext(Dispatchers.IO) {
        serverDao.getByIdBlocking(id)?.toDomain()
    }

    suspend fun getActiveServer(): ServerConfig? = withContext(Dispatchers.IO) {
        serverDao.getActiveSync()?.toDomain()
    }

    suspend fun saveServer(config: ServerConfig): Long = withContext(Dispatchers.IO) {
        val entity = config.toEntity()
        if (entity.id == 0L) {
            serverDao.insert(entity)
        } else {
            serverDao.update(entity)
            entity.id
        }
    }

    suspend fun updateServer(config: ServerConfig) = withContext(Dispatchers.IO) {
        serverDao.update(config.toEntity())
    }

    suspend fun deleteServer(id: Long) = withContext(Dispatchers.IO) {
        serverDao.delete(id)
    }

    suspend fun setActiveServer(id: Long) = withContext(Dispatchers.IO) {
        serverDao.setActive(id)
    }

    // ===== 白名单 =====
    
    suspend fun getEnabledWhitelist(): List<WhitelistApp> = withContext(Dispatchers.IO) {
        whitelistDao.getEnabledBlocking().map { it.toDomain() }
    }

    val enabledWhitelistFlow: Flow<List<WhitelistApp>> = whitelistDao.getEnabled().map {
        it.map { it.toDomain() }
    }

    suspend fun getAllWhitelist(): List<WhitelistApp> = withContext(Dispatchers.IO) {
        whitelistDao.getAll().first().map { it.toDomain() }
    }

    suspend fun addToWhitelist(app: WhitelistApp) = withContext(Dispatchers.IO) {
        whitelistDao.insert(app.toEntity())
    }

    suspend fun removeFromWhitelist(packageName: String) = withContext(Dispatchers.IO) {
        whitelistDao.delete(packageName)
    }

    suspend fun updateWhitelist(app: WhitelistApp) = withContext(Dispatchers.IO) {
        whitelistDao.update(app.toEntity())
    }

    suspend fun getEnabledPackageNames(): List<String> = withContext(Dispatchers.IO) {
        whitelistDao.getEnabledPackageNames()
    }
}

private fun ServerEntity.toDomain(): ServerConfig {
    return ServerConfig(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        keyAlias = keyAlias,
        keyAlgorithm = try { ServerConfig.KeyAlgorithm.valueOf(keyAlgorithm) } catch (_: Exception) { ServerConfig.KeyAlgorithm.Ed25519 },
        password = password,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastConnectedAt = null,
        connectTimeout = 10000,
        keepAliveInterval = keepAliveInterval,
        mtu = mtu,
        enableIPv6 = enableIPv6,
        dnsMode = when (dnsMode) {
            EntityDnsMode.REMOTE -> ServerConfig.DnsMode.Remote
            EntityDnsMode.LOCAL -> ServerConfig.DnsMode.Local
            EntityDnsMode.SYSTEM -> ServerConfig.DnsMode.System
            EntityDnsMode.SPLIT -> ServerConfig.DnsMode.Remote
        },
        allowedPackages = parseJsonStringList(allowedPackages),
        excludedRoutes = parseJsonStringList(excludedRoutes)
    )
}

private fun ServerConfig.toEntity(): ServerEntity {
    return ServerEntity(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        keyAlias = keyAlias,
        keyAlgorithm = keyAlgorithm.name,
        password = password,
        isActive = isActive,
        mtu = mtu,
        keepAliveInterval = keepAliveInterval,
        enableIPv6 = enableIPv6,
        dnsMode = when (dnsMode) {
            ServerConfig.DnsMode.Remote -> EntityDnsMode.REMOTE
            ServerConfig.DnsMode.Local -> EntityDnsMode.LOCAL
            ServerConfig.DnsMode.System -> EntityDnsMode.SYSTEM
        },
        allowedPackages = toJsonStringList(allowedPackages),
        excludedRoutes = toJsonStringList(excludedRoutes),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun parseJsonStringList(json: String?): List<String> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).map { array.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun toJsonStringList(list: List<String>): String? {
    if (list.isEmpty()) return null
    return JSONArray(list).toString()
}

private fun WhitelistAppEntity.toDomain(): WhitelistApp {
    return WhitelistApp(
        packageName = packageName,
        appName = appName,
        iconHash = "",
        isEnabled = isEnabled,
        addedAt = addedAt
    )
}

private fun WhitelistApp.toEntity(): WhitelistAppEntity {
    return WhitelistAppEntity(
        packageName = packageName,
        appName = appName,
        isEnabled = isEnabled,
        addedAt = addedAt
    )
}