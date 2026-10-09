package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.entity.WhitelistAppEntity
import cn.srv0.sshinjector.data.remote.ssh.AesGcmCipher
import cn.srv0.sshinjector.data.remote.ssh.CredentialCrypto
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.model.WhitelistApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
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

        // F12-k: map 内含 Keystore 解密 — flowOn 把上游执行移出 Main
        val allServersFlow: Flow<List<ServerConfig>> =
            serverDao
                .getAll()
                .map {
                    it.map { it.toDomain(credentialCrypto) }
                }.flowOn(Dispatchers.IO)

        val activeServerFlow: Flow<ServerConfig?> =
            serverDao
                .getActive()
                .map {
                    it?.toDomain(credentialCrypto)
                }.flowOn(Dispatchers.IO)

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
                val entity: cn.srv0.sshinjector.data.local.entity.ServerEntity
                val baseId: Long
                if (existingId == -1L) {
                    entity = incoming.toEntity(credentialCrypto).copy(isActive = false)
                    baseId = -1L
                } else {
                    val existing = serverDao.getByIdBlocking(existingId) ?: return@withContext existingId
                    entity =
                        mergeForEdit(existing, incoming)
                            .copy(isActive = if (setAsDefault) false else existing.isActive)
                    baseId = existingId
                }
                // L7: 写入 + 激活单事务 — 中途失败不会留下"存了但没激活"的半状态
                serverDao.insertOrUpdateAndActivate(entity, baseId, setAsDefault)
            }

        /**
         * 编辑合并：以既有实体为基底，仅覆盖表单可编辑字段。密码仅在提供时重新加密，
         * 否则原样保留已加密密文（字节稳定）；存量明文（无 enc:v1: 前缀）在下次保存时惰性重加密，
         * 读端仍兼容明文。keyAlgorithm/hostKeyFingerprint/excludedRoutes/createdAt 等
         * 表单未展示字段全部沿用既有实体值 (Room 列保留, 不参与 domain 往返)。
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
                password =
                    incoming.password?.let { credentialCrypto.encrypt(it) }
                        ?: existing.password?.takeIf { it.startsWith(AesGcmCipher.ENCRYPTED_PREFIX) }
                        ?: existing.password?.let { credentialCrypto.encrypt(it) },
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

        val enabledWhitelistFlow: Flow<List<WhitelistApp>> =
            whitelistDao.getEnabled().map {
                it.map { it.toDomain() }
            }

        suspend fun addToWhitelist(app: WhitelistApp) =
            withContext(Dispatchers.IO) {
                whitelistDao.insert(app.toEntity())
            }

        suspend fun removeFromWhitelist(packageName: String) =
            withContext(Dispatchers.IO) {
                whitelistDao.delete(packageName)
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
        connectTimeout = 10000,
        keepAliveInterval = keepAliveInterval,
        mtu = mtu,
        enableIPv6 = enableIPv6,
        excludedRoutes = parseJsonStringList(excludedRoutes),
        socksPort = socksPort,
        hostKeyFingerprint = hostKeyFingerprint,
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
        excludedRoutes = toJsonStringList(excludedRoutes),
        socksPort = socksPort,
        createdAt = createdAt,
        updatedAt = updatedAt,
        hostKeyFingerprint = hostKeyFingerprint,
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
