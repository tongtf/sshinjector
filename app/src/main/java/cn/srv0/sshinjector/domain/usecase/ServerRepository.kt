package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.entity.WhitelistAppEntity
import cn.srv0.sshinjector.data.local.entity.DnsMode as EntityDnsMode
import cn.srv0.sshinjector.data.remote.ssh.CredentialCrypto
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.model.WhitelistApp
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
    private val whitelistDao: WhitelistDao,
    private val credentialCrypto: CredentialCrypto
) {
    
    suspend fun getAllServers(): List<ServerConfig> = withContext(Dispatchers.IO) {
        serverDao.getAllBlocking().map { it.toDomain(credentialCrypto) }
    }

    val allServersFlow: Flow<List<ServerConfig>> = serverDao.getAll().map { 
        it.map { it.toDomain(credentialCrypto) } 
    }

    val activeServerFlow: Flow<ServerConfig?> = serverDao.getActive().map { 
        it?.toDomain(credentialCrypto) 
    }

    suspend fun getServerById(id: Long): ServerConfig? = withContext(Dispatchers.IO) {
        serverDao.getByIdBlocking(id)?.toDomain(credentialCrypto)
    }

    suspend fun getActiveServer(): ServerConfig? = withContext(Dispatchers.IO) {
        serverDao.getActiveSync()?.toDomain(credentialCrypto)
    }

    suspend fun saveServer(config: ServerConfig): Long = withContext(Dispatchers.IO) {
        val entity = config.toEntity(credentialCrypto)
        if (entity.id == 0L) {
            serverDao.insert(entity)
        } else {
            serverDao.update(entity)
            entity.id
        }
    }

    suspend fun updateServer(config: ServerConfig) = withContext(Dispatchers.IO) {
        serverDao.update(config.toEntity(credentialCrypto))
    }

    suspend fun deleteServer(id: Long) = withContext(Dispatchers.IO) {
        serverDao.delete(id)
    }

    suspend fun setActiveServer(id: Long) = withContext(Dispatchers.IO) {
        serverDao.setActive(id)
    }

    suspend fun deactivateAllServers() = withContext(Dispatchers.IO) {
        serverDao.deactivateAll()
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

private fun ServerEntity.toDomain(credentialCrypto: CredentialCrypto): ServerConfig {
    return ServerConfig(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        keyAlias = keyAlias,
        keyAlgorithm = try { ServerConfig.KeyAlgorithm.valueOf(keyAlgorithm) } catch (_: Exception) { ServerConfig.KeyAlgorithm.Ed25519 },
        password = credentialCrypto.decrypt(password),
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
        excludedRoutes = parseJsonStringList(excludedRoutes),
        socksPort = socksPort,
        hostKeyFingerprint = hostKeyFingerprint,
    )
}

private fun ServerConfig.toEntity(credentialCrypto: CredentialCrypto): ServerEntity {
    return ServerEntity(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        keyAlias = keyAlias,
        keyAlgorithm = keyAlgorithm.name,
        password = credentialCrypto.encrypt(password),
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
        socksPort = socksPort,
        createdAt = createdAt,
        updatedAt = updatedAt,
        hostKeyFingerprint = hostKeyFingerprint
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