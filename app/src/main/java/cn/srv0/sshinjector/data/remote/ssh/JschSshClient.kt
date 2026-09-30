package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import android.util.Log
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.vpn.SshChannelFactory
import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计算 SSH 主机密钥的 SHA-256 指纹 (OpenSSH 格式): "SHA256:<base64"。
 * 文件级私有函数，KnownHostsManager、KnownHostsHostKeyRepository 与 JschSshClient 共用，避免逻辑重复。
 * 用 java.util.Base64 (与 android.util.Base64 NO_WRAP 输出一致)，JVM 单测可直接断言。
 */
private fun computeFingerprint(hostKey: HostKey): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // JSch HostKey.getKey() 返回公钥 base64 字符串
    val bytes = hostKey.getKey().toByteArray()
    digest.update(bytes)
    return "SHA256:" + Base64.getEncoder().encodeToString(digest.digest())
}

/**
 * 单次远程命令执行结果。
 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * SSH Host Key 管理工具
 * 使用 OpenSSH 兼容的 known_hosts 文件格式
 */
class KnownHostsManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val knownHostsFile = File(context.filesDir, "known_hosts")
        private val lock = Any()

        init {
            if (!knownHostsFile.exists()) {
                knownHostsFile.createNewFile()
            }
        }

        /** known_hosts 文件路径（HostKeyRepository.getKnownHostsRepositoryID 用）。 */
        val repositoryId: String
            get() = knownHostsFile.absolutePath

        /**
         * 保存主机密钥到 known_hosts 文件
         */
        fun saveHostKey(
            host: String,
            port: Int,
            hostKey: HostKey,
        ) {
            val fingerprint = computeFingerprint(hostKey)
            val keyType = hostKey.getType()
            // JSch HostKey.getKey() 返回公钥 base64 字符串
            val keyBytes = hostKey.getKey().toByteArray()
            val keyBlob = Base64.getEncoder().encodeToString(keyBytes)
            val line = "$host,$port $keyType $keyBlob $fingerprint\n"

            synchronized(lock) {
                // 移除旧记录
                val lines = knownHostsFile.readText().lines().filter { !it.startsWith("$host,$port ") }
                FileWriter(knownHostsFile).use { writer ->
                    lines.forEach { writer.write("$it\n") }
                    writer.write(line)
                }
            }
            Log.d("KnownHosts", "Saved host key for $host:$port ($fingerprint)")
        }

        /**
         * 删除指定主机的已存记录（服务器重装导致 key 变更时调用）
         * @return true 如果确实存在并删除了记录
         */
        fun removeHostKey(
            host: String,
            port: Int,
        ): Boolean {
            val prefix = "$host,$port "
            synchronized(lock) {
                val lines = knownHostsFile.readText().lines()
                val kept = lines.filter { !it.startsWith(prefix) }
                if (kept.size == lines.size) return false
                FileWriter(knownHostsFile).use { writer ->
                    kept.forEach { writer.write("$it\n") }
                }
            }
            Log.d("KnownHosts", "Removed host key for $host:$port")
            return true
        }

        /**
         * 获取存储的主机指纹
         */
        fun getStoredFingerprint(
            host: String,
            port: Int,
        ): String? {
            val line = findHostLine(host, port)
            return line?.let { extractFingerprint(it) }
        }

        /**
         * 读取已存主机密钥，供 HostKeyRepository.getHostKey 的 @revoked 检查；行损坏返回 null。
         * 行内 key 字段为 base64(base64(blob)) 双重编码（历史格式），解两次还原原始 blob。
         */
        fun getStoredHostKey(
            host: String,
            port: Int,
        ): HostKey? {
            val line = findHostLine(host, port) ?: return null
            val parts = line.split(" ")
            if (parts.size < 3) return null
            return try {
                val blob = Base64.getDecoder().decode(Base64.getDecoder().decode(parts[2]))
                HostKey(host, blob)
            } catch (_: Exception) {
                null
            }
        }

        private fun findHostLine(
            host: String,
            port: Int,
        ): String? = knownHostsFile.readText().lines().firstOrNull { it.startsWith("$host,$port ") }

        private fun extractFingerprint(line: String): String {
            // 格式: host,port keytype base64key SHA256:fingerprint
            return line.split(" ").last()
        }
    }

/**
 * JSch HostKeyRepository 适配器：主机密钥校验发生在 KEX 内、userauth 之前
 * （Session.checkHost，StrictHostKeyChecking=yes 时校验不过 connect() 直接失败）。
 *
 * - 构造时绑定 (host, port)：JSch 对非 22 端口传入 "[host]:port" 形式的 chost，
 *   绑定值规避解析歧义，与 KnownHostsManager 的 "host,port" 键一致。
 * - shkc=yes 下 JSch 对 NOT_INCLUDED 直接抛异常、不会自动 add——TOFU 保存必须在 check() 内完成。
 * - 配置了 hostKeyFingerprint 时指纹为权威比对（匹配才 OK，否则 CHANGED）。
 */
internal class KnownHostsHostKeyRepository(
    private val knownHosts: KnownHostsManager,
    private val host: String,
    private val port: Int,
    private val expectedFingerprint: String?,
    private val onFirstTrust: (fingerprint: String) -> Unit = {},
) : HostKeyRepository {
    override fun check(
        chost: String,
        key: ByteArray,
    ): Int {
        val fingerprint = fingerprintOf(key) ?: return HostKeyRepository.NOT_INCLUDED
        val expected = expectedFingerprint
        if (!expected.isNullOrEmpty()) {
            return if (expected == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        val stored = knownHosts.getStoredFingerprint(host, port)
        if (stored == null) {
            // TOFU：首连保存并信任；JSch 不会代为 add，必须在本函数内落盘
            saveTofu(key, fingerprint)
            return HostKeyRepository.OK
        }
        return if (stored == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(
        hostkey: HostKey,
        ui: UserInfo?,
    ) {
        val (h, p) = parseChost(hostkey.getHost())
        knownHosts.saveHostKey(h, p, hostkey)
    }

    override fun remove(
        chost: String,
        type: String,
    ) {
        knownHosts.removeHostKey(host, port)
    }

    override fun remove(
        chost: String,
        type: String,
        key: ByteArray?,
    ) {
        knownHosts.removeHostKey(host, port)
    }

    override fun getKnownHostsRepositoryID(): String = knownHosts.repositoryId

    override fun getHostKey(): Array<HostKey> {
        val key = knownHosts.getStoredHostKey(host, port) ?: return emptyArray()
        return arrayOf(key)
    }

    override fun getHostKey(
        chost: String?,
        type: String?,
    ): Array<HostKey> {
        val key = knownHosts.getStoredHostKey(host, port) ?: return emptyArray()
        return if (type == null || key.getType() == type) arrayOf(key) else emptyArray()
    }

    private fun fingerprintOf(key: ByteArray): String? =
        try {
            computeFingerprint(HostKey(host, HostKey.GUESS, key))
        } catch (_: Exception) {
            null
        }

    private fun saveTofu(
        key: ByteArray,
        fingerprint: String,
    ) {
        try {
            knownHosts.saveHostKey(host, port, HostKey(host, HostKey.GUESS, key))
            onFirstTrust(fingerprint)
        } catch (e: Exception) {
            Log.w("KnownHosts", "TOFU save failed for $host:$port: ${e.message}")
        }
    }

    /** chost 可能为 "[host]:port"（非 22 端口）——剥壳取裸 host/port，裸名回退绑定端口。 */
    private fun parseChost(chost: String): Pair<String, Int> {
        if (chost.startsWith("[")) {
            val sep = chost.indexOf("]:")
            if (sep > 1) {
                chost.substring(sep + 2).toIntOrNull()?.let { return chost.substring(1, sep) to it }
            }
        }
        return chost to port
    }
}

/**
 * SSH session pool wrapper: one SSH session + its own keepalive + health tracking
 */
private data class PooledSession(
    val session: Session,
    val scope: CoroutineScope,
    var keepAliveJob: kotlinx.coroutines.Job? = null,
    @Volatile var healthy: Boolean = true,
    var activeChannels: AtomicInteger = AtomicInteger(0),
)

@Singleton
class JschSshClient
    @Inject
    constructor(
        private val keyManager: SshKeyManager,
        private val knownHostsManager: KnownHostsManager,
        private val serverDao: ServerDao,
    ) : SshChannelFactory,
        RemoteCommandExecutor {
        companion object {
            private const val TAG = "JschSshClient"
            private const val SESSION_POOL_SIZE = 3
            private const val CHANNEL_WINDOW_SIZE = 8 * 1024 * 1024
            private const val CHANNEL_SEND_MAX_PACKET_SIZE = 64 * 1024
            private const val CHANNEL_CONNECT_TIMEOUT_MS = 10000

            // S7 算法白名单。注意 JSch 0.2.x 的 session 配置键是 kex/server_host_key/cipher.c2s/mac.c2s,
            // 写 KexAlgorithms/HostKeyAlgorithms/Cipher/MAC 不会被 Session 读取 (静默 no-op)。
            internal const val KEX_ALGORITHMS =
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                    "diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256"
            internal const val HOST_KEY_ALGORITHMS =
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
                    "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
            internal const val PUBKEY_ACCEPTED_ALGORITHMS =
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
                    "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
            internal const val CIPHER_ALGORITHMS =
                "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes256-ctr"
            internal const val MAC_ALGORITHMS =
                "hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com,hmac-sha2-512,hmac-sha2-256"

            /** KEX/主机密钥/公钥/Cipher/MAC 白名单 (createSession 与 execSingleShot 共用)。 */
            internal fun applyAlgorithmWhitelist(s: Session) {
                s.setConfig("kex", KEX_ALGORITHMS)
                s.setConfig("server_host_key", HOST_KEY_ALGORITHMS)
                s.setConfig("PubkeyAcceptedAlgorithms", PUBKEY_ACCEPTED_ALGORITHMS)
                // GCM(AEAD)优先, 兼容旧服务器用 CTR; 不含 CBC (无认证加密, BEAST 类攻击面)
                s.setConfig("cipher.c2s", CIPHER_ALGORITHMS)
                s.setConfig("cipher.s2c", CIPHER_ALGORITHMS)
                s.setConfig("mac.c2s", MAC_ALGORITHMS)
                s.setConfig("mac.s2c", MAC_ALGORITHMS)
            }

            /**
             * JSch Channel 的窗口/包大小 setter 为包内可见, 只能反射调用。
             * 每个 setter 只解析一次并缓存, 避免每连接遍历 declaredMethods。
             */
            private val CHANNEL_METHODS: List<java.lang.reflect.Method> by lazy {
                com.jcraft.jsch.Channel::class.java.declaredMethods
                    .filter {
                        it.name == "setLocalWindowSizeMax" ||
                            it.name == "setLocalWindowSize" ||
                            it.name == "setSendMaxPacketSize"
                    }.map {
                        it.isAccessible = true
                        it
                    }
            }

            @Volatile private var loggerSet = false
        }

        private val pool = ConcurrentLinkedQueue<PooledSession>()
        private val sessionIndex = AtomicInteger(0)

        @Volatile private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isConnectedFlag = AtomicBoolean(false)
        private var currentConfig: ServerConfig? = null

        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val lastError = MutableStateFlow<String?>(null)

        enum class ConnectionState {
            Disconnected,
            Connecting,
            Authenticating,
            EstablishingTunnel,
            Connected,
            Disconnecting,
            Failed,
        }

        override suspend fun connect(config: ServerConfig): SshChannelFactory.ConnectionResult {
            if (isConnectedFlag.get()) {
                return SshChannelFactory.ConnectionResult(false, error = "Already connected")
            }

            connectionState.value = ConnectionState.Connecting
            lastError.value = null
            currentConfig = config

            return try {
                var successCount = 0
                for (i in 0 until SESSION_POOL_SIZE) {
                    val pooled = createSession(config, i)
                    if (pooled != null) {
                        pool.add(pooled)
                        successCount++
                        android.util.Log.d(TAG, "Session pool [$i/$SESSION_POOL_SIZE] connected")
                    } else {
                        android.util.Log.w(TAG, "Session pool [$i/$SESSION_POOL_SIZE] failed")
                    }
                }

                if (successCount == 0) {
                    throw Exception("All $SESSION_POOL_SIZE SSH sessions failed to connect")
                }

                isConnectedFlag.set(true)
                connectionState.value = ConnectionState.Connected
                lastError.value = null

                android.util.Log.d(TAG, "Session pool ready: $successCount/$SESSION_POOL_SIZE sessions active")
                SshChannelFactory.ConnectionResult(true, 1080)
            } catch (e: JSchException) {
                handleError("SSH connection failed: ${e.message}")
                SshChannelFactory.ConnectionResult(false, error = e.message)
            } catch (e: Exception) {
                handleError("Unexpected error: ${e.message}")
                SshChannelFactory.ConnectionResult(false, error = e.message)
            }
        }

        private fun createSession(
            config: ServerConfig,
            index: Int,
        ): PooledSession? {
            var session: Session? = null
            return try {
                val jsch = JSch()
                // JSch.setLogger 是全局静态，只设置一次
                synchronized(JSch::class.java) {
                    if (!loggerSet) {
                        JSch.setLogger(
                            object : com.jcraft.jsch.Logger {
                                private val levels =
                                    mapOf(
                                        com.jcraft.jsch.Logger.DEBUG to "DEBUG",
                                        com.jcraft.jsch.Logger.INFO to "INFO",
                                        com.jcraft.jsch.Logger.WARN to "WARN",
                                        com.jcraft.jsch.Logger.ERROR to "ERROR",
                                        com.jcraft.jsch.Logger.FATAL to "FATAL",
                                    )

                                override fun isEnabled(level: Int) = level >= com.jcraft.jsch.Logger.WARN

                                override fun log(
                                    level: Int,
                                    message: String,
                                ) {
                                    if (level >= com.jcraft.jsch.Logger.WARN) {
                                        android.util.Log.w(TAG, "[${levels[level] ?: level}] $message")
                                    }
                                }
                            },
                        )
                        loggerSet = true
                    }
                }
                val keyAdded = keyManager.createJSchIdentity(jsch, config.keyAlias)
                if (!keyAdded) {
                    throw Exception("无法访问私钥")
                }

                val s = jsch.getSession(config.username, config.host, config.port)
                session = s
                if (!config.password.isNullOrEmpty()) {
                    val passwordBytes = config.password.toByteArray(Charsets.UTF_8)
                    try {
                        // JSch setPassword(byte[]) 内部会 clone，之后本地字节数组可安全清零
                        s.setPassword(passwordBytes)
                    } finally {
                        java.util.Arrays.fill(passwordBytes, 0)
                    }
                }
                // 主机密钥校验在 KEX 内完成（认证前拦截）；TOFU 首次保存后指纹落库，下次连接即强校验
                s.setHostKeyRepository(
                    KnownHostsHostKeyRepository(
                        knownHostsManager,
                        config.host,
                        config.port,
                        config.hostKeyFingerprint,
                    ) { fp ->
                        persistFingerprint(config, fp)
                    },
                )
                s.setConfig("StrictHostKeyChecking", "yes")
                s.setConfig("TCPNoDelay", "yes") // 禁用 Nagle, 降低 SSH 小包 (ACK/交互) 的 RTT
                s.setConfig("PreferredAuthentications", "publickey,password")
                s.setConfig("PubkeyAuthentication", "yes")
                s.setConfig("PasswordAuthentication", "yes")
                // KEX/主机密钥/Cipher/MAC 白名单 (移除 group1-sha1/ssh-dss/CBC/hmac-sha1)
                applyAlgorithmWhitelist(s)
                s.setTimeout(config.connectTimeout)

                connectionState.value = ConnectionState.Authenticating
                s.connect()

                val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val pooled = PooledSession(session = s, scope = sessionScope)
                startSessionKeepAlive(pooled, config.keepAliveInterval.toLong(), index)

                pooled
            } catch (e: Exception) {
                // 校验/连接失败的 Session 必须断开，否则 socket + 线程泄漏
                try {
                    session?.disconnect()
                } catch (_: Exception) {
                }
                // 带堆栈记录：暴露 JSch KEX/主机密钥/鉴权的真实异常链，便于区分握手阶段失败原因
                android.util.Log.e(TAG, "createSession[$index] failed: ${e.message}", e)
                null
            }
        }

        /** TOFU 首次保存后指纹落库（id=0 的临时 config 如 provisioning 跳过）。 */
        private fun persistFingerprint(
            config: ServerConfig,
            fingerprint: String,
        ) {
            if (config.id <= 0) return
            try {
                serverDao.updateHostKeyFingerprint(config.id, fingerprint)
            } catch (e: Exception) {
                Log.w(TAG, "updateHostKeyFingerprint failed: ${e.message}")
            }
        }

        private fun startSessionKeepAlive(
            pooled: PooledSession,
            intervalMs: Long,
            index: Int,
        ) {
            pooled.keepAliveJob =
                pooled.scope.launch {
                    while (isActive && isConnectedFlag.get()) {
                        delay(intervalMs)
                        if (!isActive) break
                        try {
                            if (pooled.session.isConnected) {
                                pooled.session.sendKeepAliveMsg()
                                pooled.healthy = true
                            } else {
                                android.util.Log.w(TAG, "Session pool-$index: not connected, attempting reconnect")
                                pooled.healthy = false
                                tryReconnectSession(pooled, index)
                            }
                        } catch (_: Exception) {
                            android.util.Log.w(TAG, "Session pool-$index: keepAlive failed, attempting reconnect")
                            pooled.healthy = false
                            tryReconnectSession(pooled, index)
                        }
                    }
                }
        }

        /**
         * 尝试重连单个 session（不影响其他 session）
         */
        private suspend fun tryReconnectSession(
            pooled: PooledSession,
            index: Int,
        ) {
            val config = currentConfig ?: return
            var retryCount = 0
            val maxRetries = 5

            while (retryCount < maxRetries && isConnectedFlag.get()) {
                retryCount++
                val backoffMs = minOf(1000L * retryCount, 10000L)
                android.util.Log.d(
                    TAG,
                    "Session pool-$index: reconnect attempt $retryCount/$maxRetries (backoff ${backoffMs}ms)",
                )
                delay(backoffMs)

                if (isConnectedFlag.get()) {
                    try {
                        pooled.session.disconnect()
                    } catch (_: Exception) {
                    }
                }

                try {
                    val newPooled = createSession(config, index)
                    if (newPooled == null) {
                        continue
                    }

                    // 原子替换: 取出旧的, 放入新的
                    pool.remove(pooled)
                    pool.add(newPooled)

                    android.util.Log.d(TAG, "Session pool-$index: reconnected successfully")
                    // 取消旧 session 的 keepAlive 及其 scope (避免 CoroutineScope 泄漏)
                    pooled.keepAliveJob?.cancel()
                    pooled.scope.cancel()
                    startSessionKeepAlive(newPooled, config.keepAliveInterval.toLong(), index)
                    return
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Session pool-$index: reconnect failed: ${e.message}")
                }
            }

            android.util.Log.e(TAG, "Session pool-$index: all reconnect attempts exhausted")
            // 如果所有 session 都挂了，通知断开
            checkPoolHealth()
        }

        private fun checkPoolHealth() {
            if (pool.isEmpty() || pool.all { !it.healthy && !it.session.isConnected }) {
                android.util.Log.e(TAG, "All sessions unhealthy, triggering disconnect")
                isConnectedFlag.set(false)
                connectionState.value = ConnectionState.Failed
                lastError.value = "All SSH sessions lost"
            }
        }

        override suspend fun disconnect(): Boolean {
            if (!isConnectedFlag.getAndSet(false)) return true

            connectionState.value = ConnectionState.Disconnecting

            while (pool.isNotEmpty()) {
                val pooled = pool.poll() ?: break
                pooled.keepAliveJob?.cancel()
                try {
                    pooled.session.disconnect()
                } catch (_: Exception) {
                }
                pooled.scope.cancel()
            }

            currentConfig = null
            connectionState.value = ConnectionState.Disconnected
            return true
        }

        fun isConnected(): Boolean = isConnectedFlag.get() && pool.any { it.session.isConnected }

        /**
         * 连接池中是否有 session 已被标记为不健康 (断线已被 keepAlive/重连检测到)。
         * 供外部 (解锁后自动重连) 判断是否需要恢复, 比 isConnected() 更敏感:
         * isConnected() 只对『全部 session 都断开』才返回 false, 而死 socket 可能仍 isConnected=true。
         */
        fun hasUnhealthySession(): Boolean = pool.any { !it.healthy }

        /**
         * 创建直连通道 - 从 session 池中轮询选择健康的 session
         */
        override fun createDirectChannel(
            host: String,
            port: Int,
        ): TunnelChannel? {
            if (pool.isEmpty()) {
                android.util.Log.w(TAG, "createDirectChannel: session pool is empty")
                return null
            }

            // 选择活跃 channel 数最少的健康 session
            val snapshot = pool.toList()
            val size = snapshot.size
            if (size == 0) return null
            var bestPooled: PooledSession? = null
            var bestCount = Int.MAX_VALUE
            for (i in 0 until size) {
                val idx = Math.floorMod(sessionIndex.getAndIncrement(), size)
                val pooled = snapshot[idx]
                if (!pooled.session.isConnected || !pooled.healthy) continue
                val count = pooled.activeChannels.get()
                if (count < bestCount) {
                    bestCount = count
                    bestPooled = pooled
                }
            }

            val pooled = bestPooled
            if (pooled == null) {
                android.util.Log.w(TAG, "createDirectChannel: all sessions failed for $host:$port")
                return null
            }
            return try {
                val channel = pooled.session.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
                channel.setHost(host)
                channel.setPort(port)
                channel.setInputStream(null)
                channel.setOutputStream(null)
                setChannelWindowSize(channel, CHANNEL_WINDOW_SIZE)
                val input = channel.getInputStream()
                val output = channel.getOutputStream()
                pooled.activeChannels.incrementAndGet()
                try {
                    channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    pooled.activeChannels.decrementAndGet()
                    throw e
                }
                JschTunnelChannel(channel, input, output) {
                    pooled.activeChannels.decrementAndGet()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "createDirectChannel failed: $host:$port", e)
                null
            }
        }

        /**
         * 反射设置通道窗口大小与发送包上限 (JSch 对应方法为包内可见)。
         * Method 引用只解析一次并缓存, 避免每连接遍历 declaredMethods。
         */
        private fun setChannelWindowSize(
            channel: com.jcraft.jsch.Channel,
            windowSize: Int,
        ) {
            try {
                CHANNEL_METHODS.forEach { m ->
                    if (m.name == "setSendMaxPacketSize") {
                        m.invoke(channel, CHANNEL_SEND_MAX_PACKET_SIZE)
                    } else {
                        m.invoke(channel, windowSize)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "setChannelWindowSize failed: ${e.message}")
            }
        }

        private fun handleError(message: String) {
            isConnectedFlag.set(false)
            connectionState.value = ConnectionState.Failed
            lastError.value = message

            while (pool.isNotEmpty()) {
                val pooled = pool.poll() ?: break
                pooled.keepAliveJob?.cancel()
                try {
                    pooled.session.disconnect()
                } catch (_: Exception) {
                }
                pooled.scope.cancel()
            }
        }

        fun getSession(): Session? = pool.firstOrNull()?.session

        /**
         * 单次远程命令执行（用于服务器端配置助手）。
         *
         * 独立 Session，不占用 VPN 会话池；执行完立即断开。
         * 与 createSession 相同的安全算法白名单 + hostKey TOFU 校验。
         *
         * @param host 目标主机
         * @param port SSH 端口
         * @param username 登录账户
         * @param password 可选密码（仅内存，不落日志）
         * @param keyAlias 可选密钥别名（密码/密钥二选一）
         * @param stdinData 可选 stdin 数据（脚本/公钥/密码，避免 shell 参数拼接）
         * @param command 要执行的命令（由调用方构造，脚本内容固定）
         * @param timeoutMs 命令超时
         */
        override suspend fun execSingleShot(
            target: SshConnectionTarget,
            stdinData: ByteArray?,
            command: String,
            timeoutMs: Int,
        ): ExecResult {
            val config =
                ServerConfig(
                    name = "provision",
                    host = target.host,
                    port = target.port,
                    username = target.username,
                    keyAlias = target.keyAlias ?: "",
                    password = target.password,
                    connectTimeout = 15000,
                )
            return withContext(Dispatchers.IO) {
                var session: Session? = null
                var channel: ChannelExec? = null
                try {
                    val jsch = JSch()
                    if (!target.keyAlias.isNullOrEmpty()) {
                        keyManager.createJSchIdentity(jsch, target.keyAlias)
                    }
                    val s = jsch.getSession(target.username, target.host, target.port)
                    // 尽早赋值：connect/后续任何一步抛异常都由 finally 兜底 disconnect（修 Session 泄漏）
                    session = s
                    if (!target.password.isNullOrEmpty()) {
                        val passwordBytes = target.password.toByteArray(Charsets.UTF_8)
                        try {
                            s.setPassword(passwordBytes)
                        } finally {
                            java.util.Arrays.fill(passwordBytes, 0)
                        }
                    }
                    s.setHostKeyRepository(
                        KnownHostsHostKeyRepository(
                            knownHostsManager,
                            config.host,
                            config.port,
                            config.hostKeyFingerprint,
                        ) { fp ->
                            persistFingerprint(config, fp)
                        },
                    )
                    s.setConfig("StrictHostKeyChecking", "yes")
                    s.setConfig("PreferredAuthentications", "publickey,password")
                    s.setConfig("PubkeyAuthentication", "yes")
                    s.setConfig("PasswordAuthentication", "yes")
                    applyAlgorithmWhitelist(s)
                    s.setTimeout(config.connectTimeout)

                    s.connect()

                    val exec = s.openChannel("exec") as ChannelExec
                    channel = exec
                    exec.setCommand(command)
                    if (stdinData != null) {
                        exec.setInputStream(ByteArrayInputStream(stdinData))
                    } else {
                        exec.setInputStream(java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    exec.setErrStream(ByteArrayOutputStream())
                    exec.connect(timeoutMs)

                    val stdout = ByteArrayOutputStream()
                    val stderr = ByteArrayOutputStream()
                    val outStream = exec.getInputStream()
                    val buf = ByteArray(8192)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var timedOut = false
                    var interrupted = false
                    while (!exec.isClosed && !timedOut && !interrupted) {
                        if (System.currentTimeMillis() > deadline) {
                            timedOut = true
                            break
                        }
                        if (outStream.available() > 0) {
                            val n = outStream.read(buf)
                            if (n > 0) stdout.write(buf, 0, n)
                        }
                        val errData = (exec.errStream as? ByteArrayOutputStream)?.toByteArray()
                        if (errData != null && errData.isNotEmpty()) {
                            stderr.write(errData)
                            (exec.errStream as ByteArrayOutputStream).reset()
                        }
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    // 通道关闭前可能残留未读数据
                    if (exec.isClosed && outStream.available() > 0) {
                        val n = outStream.read(buf)
                        if (n > 0) stdout.write(buf, 0, n)
                    }
                    if (timedOut) {
                        exec.disconnect()
                        return@withContext ExecResult(-1, stdout.toString(Charsets.UTF_8.name()), "timeout")
                    }
                    val exitCode = exec.exitStatus
                    ExecResult(exitCode, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "execSingleShot failed: ${e.message}")
                    ExecResult(-1, "", e.message ?: "exec failed")
                } finally {
                    try {
                        channel?.disconnect()
                    } catch (_: Exception) {
                    }
                    try {
                        session?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        suspend fun cleanup() {
            disconnect()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }
