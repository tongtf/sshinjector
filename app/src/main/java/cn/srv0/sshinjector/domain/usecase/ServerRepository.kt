package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.entity.WhitelistAppEntity
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
import cn.srv0.sshinjector.data.local.entity.DnsMode as EntityDnsMode

class ServerRepository
    @Inject
    constructor(
        private val serverDao: ServerDao,
        private val whitelistDao: WhitelistDao,
        private val credentialCrypto: CredentialCrypto,
    ) {
        suspend fun getAllServers(): List<ServerConfig> =
            withContext(Dispatchers.IO) {
                serverDao.getAllBlocking().map { it.toDomain(credentialCrypto) }
            }

        val allServersFlow: Flow<List<ServerConfig>> =
            serverDao.getAll().map {
                it.map { it.toDomain(credentialCrypto) }
            }

        val activeServerFlow: Flow<ServerConfig?> =
            serverDao.getActive().map {
                it?.toDomain(credentialCrypto)
            }

        suspend fun getServerById(id: Long): ServerConfig? =
            withContext(Dispatchers.IO) {
                serverDao.getByIdBlocking(id)?.toDomain(credentialCrypto)
            }

        suspend fun getActiveServer(): ServerConfig? =
            withContext(Dispatchers.IO) {
                serverDao.getActiveSync()?.toDomain(credentialCrypto)
            }

        suspend fun saveServer(config: ServerConfig): Long =
            withContext(Dispatchers.IO) {
                val entity = config.toEntity(credentialCrypto)
                if (entity.id == 0L) {
                    serverDao.insert(entity)
                } else {
                    serverDao.update(entity)
                    entity.id
                }
            }

        suspend fun updateServer(config: ServerConfig) =
            withContext(Dispatchers.IO) {
                serverDao.update(config.toEntity(credentialCrypto))
            }

        /**
         * 编辑/新建服务器并保留表单未展示的字段（已加密密码、指纹、DNS 模式等）。
         *
         * 凭据加密与不可编辑字段保留统一收敛于此，UI 层不再直连 DAO：
         * - existingId == -1L 视为新建；否则加载既有实体并合并可编辑字段。
         * - 密码仅在表单提供时重新加密，否则原样保留已加密密文（字节稳定）。
         * - setAsDefault 为真时将该服务器设为唯一激活项。
         */
        suspend fun saveServerEdit(
            existingId: Long,
            incoming: ServerConfig,
            setAsDefault: Boolean = false,
        ): Long =
            withContext(Dispatchers.IO) {
                val id =
                    if (existingId == -1L) {
                        serverDao.insert(incoming.toEntity(credentialCrypto).copy(isActive = false))
                    } else {
                        val existing = serverDao.getByIdBlocking(existingId) ?: return@withContext existingId
                        val originalIsActive = existing.isActive
                        val merged = mergeForEdit(existing, incoming)
                        serverDao.update(merged.copy(isActive = if (setAsDefault) false else originalIsActive))
                        existingId
                    }
                if (setAsDefault) {
                    serverDao.setActive(id)
                }
                id
            }

        /**
         * 编辑合并：以既有实体为基底，仅覆盖表单可编辑字段。密码仅在提供时重新加密，
         * 否则原样保留已加密密文（字节稳定）；keyAlgorithm/keyPassphrase/hostKeyFingerprint/
         * dnsMode/remoteDnsServer/allowedPackages/excludedRoutes/createdAt 全部沿用既有值。
         */
        private fun mergeForEdit(
            existing: ServerEntity,
            incoming: ServerConfig,
        ): ServerEntity =
            existing.copy(
                name = incoming.name,
                host = incoming.host,
                port = incoming.port,
                username = incoming.username,
                keyAlias = incoming.keyAlias,
                enableIPv6 = incoming.enableIPv6,
                mtu = incoming.mtu,
                keepAliveInterval = incoming.keepAliveInterval,
                socksPort = incoming.socksPort,
                password = incoming.password?.let { credentialCrypto.encrypt(it) } ?: existing.password,
                updatedAt = Date(),
            )

        suspend fun deleteServer(id: Long) =
            withContext(Dispatchers.IO) {
                serverDao.delete(id)
            }

        suspend fun setActiveServer(id: Long) =
            withContext(Dispatchers.IO) {
                serverDao.setActive(id)
            }

        suspend fun deactivateAllServers() =
            withContext(Dispatchers.IO) {
                serverDao.deactivateAll()
            }

        // ===== 白名单 =====

        suspend fun getEnabledWhitelist(): List<WhitelistApp> =
            withContext(Dispatchers.IO) {
                whitelistDao.getEnabledBlocking().map { it.toDomain() }
            }

        val enabledWhitelistFlow: Flow<List<WhitelistApp>> =
            whitelistDao.getEnabled().map {
                it.map { it.toDomain() }
            }

        suspend fun getAllWhitelist(): List<WhitelistApp> =
            withContext(Dispatchers.IO) {
                whitelistDao.getAll().first().map { it.toDomain() }
            }

        suspend fun addToWhitelist(app: WhitelistApp) =
            withContext(Dispatchers.IO) {
                whitelistDao.insert(app.toEntity())
            }

        suspend fun removeFromWhitelist(packageName: String) =
            withContext(Dispatchers.IO) {
                whitelistDao.delete(packageName)
            }

        suspend fun updateWhitelist(app: WhitelistApp) =
            withContext(Dispatchers.IO) {
                whitelistDao.update(app.toEntity())
            }

        suspend fun getEnabledPackageNames(): List<String> =
            withContext(Dispatchers.IO) {
                whitelistDao.getEnabledPackageNames()
            }
    }

private fun ServerEntity.toDomain(credentialCrypto: CredentialCrypto): ServerConfig =
    ServerConfig(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        keyAlias = keyAlias,
        keyAlgorithm =
            try {
                ServerConfig.KeyAlgorithm.valueOf(keyAlgorithm)
            } catch (_: Exception) {
                ServerConfig.KeyAlgorithm.ECDSA_P256
            },
        password = credentialCrypto.decrypt(password),
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastConnectedAt = null,
        connectTimeout = 10000,
        keepAliveInterval = keepAliveInterval,
        mtu = mtu,
        enableIPv6 = enableIPv6,
        dnsMode =
            when (dnsMode) {
                EntityDnsMode.REMOTE -> ServerConfig.DnsMode.Remote
                EntityDnsMode.LOCAL -> ServerConfig.DnsMode.Local
                EntityDnsMode.SYSTEM -> ServerConfig.DnsMode.System
                EntityDnsMode.SPLIT -> ServerConfig.DnsMode.Remote
            },
        allowedPackages = parseJsonStringList(allowedPackages),
        excludedRoutes = parseJsonStringList(excludedRoutes),
        socksPort = socksPort,
        hostKeyFingerprint = hostKeyFingerprint,
        keyPassphrase = keyPassphrase,
        remoteDnsServer = remoteDnsServer,
    )

private fun ServerConfig.toEntity(credentialCrypto: CredentialCrypto): ServerEntity =
    ServerEntity(
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
        dnsMode =
            when (dnsMode) {
                ServerConfig.DnsMode.Remote -> EntityDnsMode.REMOTE
                ServerConfig.DnsMode.Local -> EntityDnsMode.LOCAL
                ServerConfig.DnsMode.System -> EntityDnsMode.SYSTEM
            },
        allowedPackages = toJsonStringList(allowedPackages),
        excludedRoutes = toJsonStringList(excludedRoutes),
        socksPort = socksPort,
        createdAt = createdAt,
        updatedAt = updatedAt,
        hostKeyFingerprint = hostKeyFingerprint,
        keyPassphrase = keyPassphrase,
        remoteDnsServer = remoteDnsServer,
    )

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

private fun WhitelistAppEntity.toDomain(): WhitelistApp =
    WhitelistApp(
        packageName = packageName,
        appName = appName,
        iconHash = "",
        isEnabled = isEnabled,
        addedAt = addedAt,
    )

private fun WhitelistApp.toEntity(): WhitelistAppEntity =
    WhitelistAppEntity(
        packageName = packageName,
        appName = appName,
        isEnabled = isEnabled,
        addedAt = addedAt,
    )
