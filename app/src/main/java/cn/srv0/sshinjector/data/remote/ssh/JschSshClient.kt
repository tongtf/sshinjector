package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import android.util.Log
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.SshChannelFactory
import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
@Singleton
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
            VpnController.appLogThrottled(
                "主机密钥固定失败 · $host:$port — ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "主机密钥固定失败",
            )
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
    /** 重建进行中标志: keepAlive 与通道创建失败可能同时触发同一 session 的重建, CAS 防重入 */
    @Volatile var reconnecting: Boolean = false,
    /** 往返心跳失败标记: 静默黑洞 (单向心跳无感) 的会话级证据, 通道失败时据此判定会话层失效 */
    @Volatile var suspect: Boolean = false,
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

            /** 每 N 次 keepAlive 做一次**往返**心跳 (约 N × 30s)。 */
            private const val ROUND_TRIP_HEARTBEAT_EVERY = 6

            /** 保活周期下限 (秒): 挡住 delay(0)/delay(sub-second) 自旋, 0 由 startSessionKeepAlive 视为关闭。 */
            private const val MIN_KEEPALIVE_INTERVAL_SECONDS = 1L

            /** 往返心跳 exec 通道的 connect 超时 (毫秒)。 */
            private const val HEARTBEAT_EXEC_TIMEOUT_MS = 5000

            /**
             * 往返探活三态 (见 [verifySessionRoundTrip])。
             * UNAVAILABLE 必须与 FAILED 分开: 前者是服务器禁用 exec, 会话是好的。
             */
            private const val ROUND_TRIP_OK = 0
            private const val ROUND_TRIP_UNAVAILABLE = 1
            private const val ROUND_TRIP_FAILED = 2

            /**
             * 探活耗时低于此值仍失败 → 判为「服务器拒绝 exec 通道」而非黑洞。
             * 取 2.5s (1/2 的 exec 超时): 正常 RTT + 通道开销远小于此, 黑洞则会撑满超时。
             * 宁可保守 —— 误判成 UNAVAILABLE 只是丢一个探活手段, 误判成 FAILED 会引爆整池重建。
             */
            private const val ROUND_TRIP_SLOW_FAIL_MS = 2500L

            private const val CHANNEL_WINDOW_SIZE = 8 * 1024 * 1024

            /**
             * JSch 每条 Session 只有**一个读线程** (`Session.run`), 它把 CHANNEL_DATA
             * 直接写进通道 `getInputStream()` 的 `PipedInputStream`, 而该管道默认只有
             * 32KB 且**不可增长** (`resizable = 32KB < max_input_buffer_size`, 默认值同为 32KB)。
             * 管道一满 `PipedOutputStream.write` 就把读线程阻塞住 —— 这条会话的
             * WINDOW_ADJUST、其它通道的数据、OPEN_CONFIRMATION **全部停摆**,
             * 而写方向完全正常 (走的是 session 的 socket, 与该管道无关)。
             * 现场形态: 多个通道同时「已写 517B / 读回 0B」、新通道 `connect` 慢 1~2.5s、
             * 浏览正常但下载卡"等待中"。放大到 1MB 让管道可增长, 单个慢消费者
             * (TUN 写被 `@Synchronized writeToTun` 卡住) 不再 32KB 就冻住整条会话。
             */
            private const val MAX_INPUT_BUFFER_SIZE = "1048576"
            private const val CHANNEL_SEND_MAX_PACKET_SIZE = 64 * 1024
            private const val CHANNEL_CONNECT_TIMEOUT_MS = 10000

            /**
             * `createDirectChannel` 全部候选**共享**的总预算。
             *
             * `Socks5ProxyServer` 在 Connecting 状态 10s 就关连接 (`connectionTimeoutMs`),
             * 而单候选也是 10s —— 3 候选 ×10s = 30s, 首个候选超时时代理早已关闭, 后续候选
             * 全是白跑 + 白占 `SshIoDispatcher` 线程, "逐个重试"实际不起作用。
             * 留 1s 余量给 loopback connect + SOCKS 协商, 保证在代理关连接前收尾。
             */
            private const val CHANNEL_BUDGET_MS = 9000L

            // 通道健康统计 (排障唯一可见面): 区分「目标层拒绝」与「会话层失效」是本次雪崩 bug 的关键,
            // 只看单条错误日志无法判断是远端连不上目标还是 SSH 会话坏了。
            private val statChannelOk = AtomicLong()
            private val statChannelTargetFail = AtomicLong()
            private val statChannelSessionFail = AtomicLong()
            private val statReconnectOk = AtomicLong()
            private val statReconnectFail = AtomicLong()
            private val statLastSnapshotAt = AtomicLong()
            private const val STAT_SNAPSHOT_INTERVAL_MS = 300_000L

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
                            it.name == "setLocalPacketSize"
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
                        Log.d(TAG, "Session pool [$i/$SESSION_POOL_SIZE] connected")
                    } else {
                        Log.w(TAG, "Session pool [$i/$SESSION_POOL_SIZE] failed")
                    }
                }

                if (successCount == 0) {
                    throw Exception("All $SESSION_POOL_SIZE SSH sessions failed to connect")
                }
                if (successCount < SESSION_POOL_SIZE) {
                    // 部分失败不触发 handleError (状态仍 Connected), 但可用会话下降、
                    // 建连变慢 —— 只打 logcat 用户无从知晓, 必须进应用日志
                    VpnController.appLog(
                        "SSH 会话池部分失败 · $successCount/$SESSION_POOL_SIZE 条可用 — 建连可能变慢",
                        level = LogLevel.WARNING,
                    )
                }

                isConnectedFlag.set(true)
                connectionState.value = ConnectionState.Connected
                lastError.value = null

                Log.d(TAG, "Session pool ready: $successCount/$SESSION_POOL_SIZE sessions active")
                SshChannelFactory.ConnectionResult(true)
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
                                        Log.w(TAG, "[${levels[level] ?: level}] $message")
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
                // 必须在任何 getInputStream() 之前: 该配置在建流时读取 (见常量注释)
                s.setConfig("max_input_buffer_size", MAX_INPUT_BUFFER_SIZE)
                s.setConfig("PreferredAuthentications", "publickey,password")
                s.setConfig("PubkeyAuthentication", "yes")
                s.setConfig("PasswordAuthentication", "yes")
                // KEX/主机密钥/Cipher/MAC 白名单 (移除 group1-sha1/ssh-dss/CBC/hmac-sha1)
                applyAlgorithmWhitelist(s)
                s.setTimeout(config.connectTimeout)
                // 注意: 绝不要用 mwiede JSch 的 setServerAliveInterval/setServerAliveCountMax ——
                // 0.2.17 里这两个字段只写不读 (功能未实现), 而 setServerAliveInterval 内部会顺手
                // setTimeout(同值): 传 30 (秒) 会把 socket 超时改成 30ms, 握手必失败
                // ("timeout: socket is not established")。往返探活由自建的 exec 心跳实现, 见 startSessionKeepAlive。

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
                Log.e(TAG, "createSession[$index] failed: ${e.message}", e)
                VpnController.appLogThrottled(
                    "SSH 会话创建失败 · pool-$index — ${e.message}",
                    level = LogLevel.ERROR,
                    throttleKey = "SSH 会话创建失败",
                )
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
                VpnController.appLogThrottled(
                    "主机指纹保存失败 · ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "主机指纹保存失败",
                )
            }
        }

        /**
         * @param intervalSeconds keepAlive 周期, **单位秒** (ServerEntity/UI 均为秒)。
         * 历史 bug: 曾把秒值直接当毫秒 delay(30), 每 30ms 发一个 SSH 心跳 ×3 会话,
         * 持续高频小包约 20s 后被中间设备/服务器 RST, 表现为"连接建立后 ~20s 三个会话批量断"。
         */
        private fun startSessionKeepAlive(
            pooled: PooledSession,
            intervalSeconds: Long,
            index: Int,
        ) {
            // 0 = 用户关闭保活 (UI 允许 0-3600 秒)。不加这道门会 delay(0) 忙循环:
            // 协程满速发 sendKeepAliveMsg, 3 条会话一起把 CPU 和 SSH 通道刷爆。
            if (intervalSeconds <= 0L) {
                Log.i(TAG, "Session pool-$index: keepAlive disabled (interval=$intervalSeconds)")
                // 文案不带 pool-$index: appLogThrottled 以整条 message 为节流 key, 动态段会让节流失效
                VpnController.appLogThrottled("SSH 保活已关闭 · 间隔 0 秒")
                pooled.keepAliveJob = null
                return
            }
            // 防御: 即便上游把 sub-second/0 传进来, 也绝不能进入 delay(0) 自旋
            val delayMs = intervalSeconds.coerceAtLeast(MIN_KEEPALIVE_INTERVAL_SECONDS) * 1000L
            pooled.keepAliveJob =
                pooled.scope.launch {
                    var tick = 0
                    while (isActive && isConnectedFlag.get()) {
                        delay(delayMs)
                        if (!isActive) break
                        try {
                            if (pooled.session.isConnected) {
                                pooled.session.sendKeepAliveMsg()
                                // 不在此把 healthy 置回 true: 静默黑洞下 sendKeepAliveMsg 本地写恒成功,
                                // 置 true 会抵消通道失败路径的 unhealthy 标记, 让黑洞会话被持续优先选中。
                                // healthy 只由「重连成功」恢复; 会话真死时 isConnected=false → 走下方分支。
                                //
                                // 每 N 跳做一次**往返**心跳 (exec 'true' = POSIX 内置 no-op, 不 fork 进程):
                                // 单向心跳在静默黑洞下永远"成功", 只有真正收发一次才能发现黑洞。
                                // 失败只标记 suspect, 不直接重建 —— 服务器禁用 exec 时不误杀健康会话;
                                // 真正的重建由「通道失败 + suspect」判定触发 (见 createDirectChannel)。
                                tick++
                                if (tick % ROUND_TRIP_HEARTBEAT_EVERY == 0) {
                                    when (verifySessionRoundTrip(pooled)) {
                                        ROUND_TRIP_OK -> {
                                            if (pooled.suspect) {
                                                VpnController.appLogThrottled(
                                                    "SSH 会话往返恢复 · pool-$index",
                                                    level = LogLevel.INFO,
                                                    throttleKey = "SSH 会话往返恢复",
                                                )
                                                pooled.suspect = false
                                            }
                                        }
                                        ROUND_TRIP_FAILED -> {
                                            pooled.suspect = true
                                            VpnController.appLogThrottled(
                                                "SSH 会话往返心跳超时 · pool-$index — 标记可疑(仅通道也失败才重建)",
                                                level = LogLevel.WARNING,
                                                throttleKey = "SSH 会话往返心跳超时",
                                            )
                                        }
                                        else -> {
                                            // ROUND_TRIP_UNAVAILABLE: 服务器禁用 exec, 秒内 channel-open-failure。
                                            // **不得**标 suspect —— 否则 exec 受限的服务器上 suspect 常驻为 true,
                                            // 任意一次正常的「远端连不上目标」都会被判成 sessionDead → 整池重建
                                            // → 「重建窗口内新连接全失败 → 再重建」雪崩, 下载全挂 (见 createDirectChannel)。
                                            Log.d(
                                                TAG,
                                                "Session pool-$index: exec probe unsupported",
                                            )
                                        }
                                    }
                                }
                            } else {
                                Log.w(TAG, "Session pool-$index: not connected, attempting reconnect")
                                VpnController.appLogThrottled(
                                    "SSH 会话 pool-$index 未连接, 尝试重连",
                                    level = LogLevel.WARNING,
                                    throttleKey = "SSH 会话未连接",
                                )
                                pooled.healthy = false
                                tryReconnectSession(pooled, index)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // 协程被 cancel (断开/重建) 是正常退出, 不能当 keepAlive 故障去触发重连:
                            // 否则用户正常断开时会打出 ERROR「已耗尽所有重试」、污染 statReconnectFail,
                            // checkPoolHealth() 还可能把 Failed 盖到 Disconnected 上。
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Session pool-$index: keepAlive failed, attempting reconnect", e)
                            VpnController.appLogThrottled(
                                "SSH 会话 pool-$index keepAlive 失败, 尝试重连",
                                level = LogLevel.WARNING,
                                throttleKey = "SSH keepAlive 失败",
                            )
                            pooled.healthy = false
                            tryReconnectSession(pooled, index)
                        }
                    }
                }
        }

        /**
         * 每 5 分钟打一条通道/重连统计快照 (应用内日志是唯一可见面)。
         * 目标层拒绝 vs 会话层失效的比例能一眼区分"远端连不上目标"与"SSH 隧道整体失效"。
         */
        private fun maybeLogChannelStats(lastHost: String) {
            val now = System.currentTimeMillis()
            val last = statLastSnapshotAt.get()
            if (now - last < STAT_SNAPSHOT_INTERVAL_MS || !statLastSnapshotAt.compareAndSet(last, now)) return
            VpnController.appLog(
                "SSH 通道统计 · 成功 ${statChannelOk.get()} / 目标层失败 ${statChannelTargetFail.get()} " +
                    "/ 会话层失败 ${statChannelSessionFail.get()} · 重连成功 ${statReconnectOk.get()} " +
                    "/ 重连失败 ${statReconnectFail.get()} · 池 ${pool.size} 会话 (最近目标 $lastHost)",
                level = LogLevel.INFO,
            )
        }

        /**
         * 往返心跳: 开一个 exec 通道跑 `true` (POSIX shell 内置, 不 fork 进程, 服务器开销可忽略),
         * 等 exit-status 回来, 验证会话真的能双向收发。
         * 单向 sendKeepAliveMsg 在静默黑洞下本地写恒成功, 只有真正往返才能发现黑洞。
         * 失败/超时都只标记 [PooledSession.suspect], 不直接重建 —— 服务器禁用 exec 或命令卡住时
         * 不能误杀健康会话; 真正的重建由「通道失败 + suspect」判定触发 (见 createDirectChannel)。
         *
         * @return [ROUND_TRIP_OK] 成功; [ROUND_TRIP_FAILED] 超时/黑洞 (→ 标 suspect);
         *   [ROUND_TRIP_UNAVAILABLE] 服务器禁用 exec, **秒内**被 channel-open-failure 拒绝。
         *   UNAVAILABLE 绝不能算失败: exec 受限的服务器上若照常标 suspect, suspect 会常驻为 true,
         *   于是任意一次正常的「远端连不上目标」都凑成 `sessionDead` → 整池重建雪崩。
         */
        private suspend fun verifySessionRoundTrip(pooled: PooledSession): Int {
            val session = pooled.session
            val probe =
                pooled.scope.async<Int>(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    try {
                        val ch = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                        ch.setCommand("true")
                        ch.setInputStream(null)
                        ch.setOutputStream(java.io.ByteArrayOutputStream())
                        ch.setErrStream(java.io.ByteArrayOutputStream())
                        ch.connect(HEARTBEAT_EXEC_TIMEOUT_MS)
                        ch.disconnect()
                        // connect 能等到通道建立并收到收发 = 真正的往返证据 (exit-status 不必等,
                        // 'true' 在部分受限 shell 上拿不到, 但通道能开就足以证明会话活着)
                        ROUND_TRIP_OK
                    } catch (e: Exception) {
                        Log.w(TAG, "round-trip heartbeat failed: ${e.message}")
                        // 撑到慢失败阈值 = 服务器没回应 (黑洞); 秒级被拒 = 服务器不支持 exec
                        val stalled = System.currentTimeMillis() - startedAt >= ROUND_TRIP_SLOW_FAIL_MS
                        if (stalled || e.message?.contains("timeout", ignoreCase = true) == true) {
                            ROUND_TRIP_FAILED
                        } else {
                            ROUND_TRIP_UNAVAILABLE
                        }
                    }
                }
            // exec 阻塞在 socket 上, withTimeout 中断不了: 超时即判黑洞, 泄漏的 IO 线程自会结束
            return withTimeoutOrNull(HEARTBEAT_EXEC_TIMEOUT_MS * 2L) { probe.await() } ?: ROUND_TRIP_FAILED
        }

        /**
         * 一次心跳确认会话写路径是否可用。
         * 注意: 静默黑洞 (中间设备静默丢包) 下本地 socket 写恒成功, 这里仍返回 true ——
         * 那类死亡由**往返探活** ([verifySessionRoundTrip], exec 通道 channel-open 必须收到服务端
         * 确认才能返回) 发现。**不要**改用 JSch 原生 `setServerAliveInterval`: 它做的是
         * 单向 keepalive 消息, 黑洞下同样永远"成功", 且历史上被误设成 30ms 间隔,
         * 高频小包 20s 后被中间设备 RST。
         */
        private fun isSessionWritable(pooled: PooledSession): Boolean =
            try {
                pooled.session.sendKeepAliveMsg()
                true
            } catch (e: Exception) {
                Log.w(TAG, "session heartbeat probe failed: ${e.message}")
                false
            }

        /**
         * 把除 [except] 外的全部会话标记 unhealthy 并并行触发重建。
         * 用于会话层失效 (channel is not opened / session is down) — 静默黑洞是路径级的,
         * 逐个会话独立重建会让新连接在重建窗口内反复撞上黑洞会话。
         */
        private fun markWholePoolUnhealthy(except: PooledSession) {
            var affected = 0
            for (other in pool) {
                if (other === except || !other.healthy) continue
                other.healthy = false
                if (!other.reconnecting) {
                    val idx = pool.indexOf(other).coerceAtLeast(0)
                    other.scope.launch { tryReconnectSession(other, idx) }
                }
                affected++
            }
            if (affected > 0) {
                VpnController.appLogThrottled(
                    "SSH 会话池整池重建 · 会话层失效 ($affected 个会话)",
                    level = LogLevel.WARNING,
                    throttleKey = "SSH 整池重建",
                )
            }
        }

        /**
         * 尝试重连单个 session（不影响其他 session）。
         * 防重入: [PooledSession.reconnecting] CAS — keepAlive 周期与 createDirectChannel
         * 失败路径可能同时请求重建同一 session, 两个循环并发 pool.remove/add 会互相踩。
         */
        private suspend fun tryReconnectSession(
            pooled: PooledSession,
            index: Int,
        ) {
            synchronized(pooled) {
                if (pooled.reconnecting) return
                pooled.reconnecting = true
            }
            try {
                reconnectSessionUnlocked(pooled, index)
            } finally {
                synchronized(pooled) {
                    pooled.reconnecting = false
                }
            }
        }

        private suspend fun reconnectSessionUnlocked(
            pooled: PooledSession,
            index: Int,
        ) {
            val config = currentConfig ?: return
            var retryCount = 0
            val maxRetries = 5

            while (retryCount < maxRetries && isConnectedFlag.get()) {
                retryCount++
                val backoffMs = minOf(1000L * retryCount, 10000L)
                Log.d(
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

                    statReconnectOk.incrementAndGet()
                    Log.d(TAG, "Session pool-$index: reconnected successfully")
                    // 取消旧 session 的 keepAlive 及其 scope (避免 CoroutineScope 泄漏)
                    pooled.keepAliveJob?.cancel()
                    pooled.scope.cancel()
                    startSessionKeepAlive(newPooled, config.keepAliveInterval.toLong(), index)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Session pool-$index: reconnect failed: ${e.message}")
                    VpnController.appLogThrottled(
                        "SSH 会话重连失败 · pool-$index — ${e.message}",
                        level = LogLevel.ERROR,
                        throttleKey = "SSH 会话重连失败",
                    )
                }
            }

            Log.e(TAG, "Session pool-$index: all reconnect attempts exhausted")
            statReconnectFail.incrementAndGet()
            VpnController.appLog(
                "SSH 会话 pool-$index 重连失败 · 已耗尽所有重试",
                LogLevel.ERROR,
            )
            // 如果所有 session 都挂了，通知断开
            checkPoolHealth()
        }

        private fun checkPoolHealth() {
            if (pool.isEmpty() || pool.all { !it.healthy && !it.session.isConnected }) {
                Log.e(TAG, "All sessions unhealthy, triggering disconnect")
                VpnController.appLog(
                    "SSH 会话全部丢失 · 连接即将断开并触发重连",
                    level = LogLevel.ERROR,
                )
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
                Log.w(TAG, "createDirectChannel: session pool is empty")
                VpnController.appLogThrottled("SSH 会话池为空 · 无法打开通道", level = LogLevel.WARNING)
                return null
            }

            // 候选列表: 轮询起点保证公平, 按活跃 channel 数排序; **逐个尝试** —
            // 单个会话可能正处在断开瞬间 (JSch connected 标志是异步翻转的),
            // "channel is not opened"/"session is down" 换下一个健康会话即可透明吸收,
            // 不再像旧实现那样单次失败就把错误抛给客户端 (rep=5 → 下载失败)
            val snapshot = pool.toList()
            val size = snapshot.size
            if (size == 0) return null
            val start = sessionIndex.getAndIncrement()
            val candidates =
                (0 until size)
                    .map { snapshot[Math.floorMod(start + it, size)] }
                    .filter { it.session.isConnected && it.healthy }
                    .sortedBy { it.activeChannels.get() }

            if (candidates.isEmpty()) {
                Log.w(TAG, "createDirectChannel: all sessions failed for $host:$port")
                val poolState =
                    pool.joinToString(prefix = "[", postfix = "]") {
                        "conn=${it.session.isConnected}/healthy=${it.healthy}"
                    }
                VpnController.appLogThrottled(
                    "SSH 通道打开失败 · 无可用会话 ($host:$port) (pool: $poolState)",
                    level = LogLevel.ERROR,
                    throttleKey = "SSH 通道打开失败",
                )
                // 池里一个可用会话都没有 (全断 / 全被判 unhealthy) 时必须主动体检:
                // keepAlive=0 没有周期协程去发现 session.isConnected==false, 只返回 null 会让
                // 新连接一直拿 0x05 而状态仍显示"已连接"。checkPoolHealth 的条件是
                // `all { !healthy && !isConnected }` (严格), 池里还有活口时不会误触发重建。
                checkPoolHealth()
                return null
            }

            var lastError: Exception? = null
            val connectStartedAt = System.currentTimeMillis()
            for (pooled in candidates) {
                // 共享预算: 超了就不再试后续候选 (代理在 Connecting 10s 就关连接, 再试也是白跑)
                val budgetLeftMs = connectStartedAt + CHANNEL_BUDGET_MS - System.currentTimeMillis()
                if (budgetLeftMs <= 0) break
                val candidateStartedAt = System.currentTimeMillis()
                try {
                    val channel =
                        pooled.session.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
                    channel.setHost(host)
                    channel.setPort(port)
                    channel.setInputStream(null)
                    channel.setOutputStream(null)
                    setChannelWindowSize(channel, CHANNEL_WINDOW_SIZE)
                    val input = channel.getInputStream()
                    val output = channel.getOutputStream()
                    pooled.activeChannels.incrementAndGet()
                    try {
                        channel.connect(minOf(CHANNEL_CONNECT_TIMEOUT_MS.toLong(), budgetLeftMs).toInt())
                    } catch (e: Exception) {
                        pooled.activeChannels.decrementAndGet()
                        throw e
                    }
                    statChannelOk.incrementAndGet()
                    // 通道成功 = 往返证据: 清除往返心跳的 suspect 标记
                    pooled.suspect = false
                    maybeLogChannelStats(host)
                    // 会话标识: 「已写未读」排障时用来区分会话级冻结 vs 单主机不应答
                    val sessionTag = "S" + Integer.toHexString(System.identityHashCode(pooled.session))
                    return JschTunnelChannel(channel, input, output, sessionTag) {
                        pooled.activeChannels.decrementAndGet()
                    }
                } catch (e: Exception) {
                    val elapsedMs = System.currentTimeMillis() - candidateStartedAt
                    Log.w(TAG, "createDirectChannel failed: $host:$port", e)
                    lastError = e
                    // 区分「SSH 会话坏了」与「这一个 channel 打不开」:
                    // direct-tcpip 的 channel-open-failure 绝大多数来自**远端连不上目标**(目标层),
                    // 不是 session 死亡 —— 旧实现把两者都当 session 死亡并触发整池重建, 于是形成
                    // 「重建 → 重建窗口内新连接全失败 → 再重建」的雪崩, 下载反而全挂。
                    // 只在确认会话真的死了 (isConnected=false / session is down / 心跳写失败) 时才重建。
                    //
                    // `suspect` 只作为**静默黑洞**的旁证: 它只在往返探活撑到超时时才置位
                    // (服务器禁用 exec 时探活返回 ROUND_TRIP_UNAVAILABLE, 不置位) —— 否则
                    // exec 受限的服务器上 suspect 常驻, 一次正常的目标层失败就会凑成 sessionDead。
                    val sessionDead =
                        !pooled.session.isConnected ||
                            e.message?.contains("session is down") == true ||
                            pooled.suspect ||
                            !isSessionWritable(pooled)
                    if (sessionDead) {
                        statChannelSessionFail.incrementAndGet()
                        pooled.healthy = false
                        if (!pooled.reconnecting) {
                            val index = pool.indexOf(pooled).coerceAtLeast(0)
                            pooled.scope.launch {
                                tryReconnectSession(pooled, index)
                            }
                        }
                        // 会话层失效通常是路径级问题 (静默黑洞): 一次把其余会话也标记并并行重建,
                        // 压缩整体不可用窗口
                        markWholePoolUnhealthy(pooled)
                    } else {
                        statChannelTargetFail.incrementAndGet()
                    }
                    Log.d(
                        TAG,
                        "channel open failed: $host:$port elapsed=${elapsedMs}ms " +
                            "sessionDead=$sessionDead msg=${e.message}",
                    )
                    // 否则: 只跳过这个候选, 继续试下一个 (可能是目标临时不可达/超并发)
                }
            }

            // 所有候选均失败: 附 pool 快照 (暴露"选择时 isConnected 与 connect 时状态脱节"的模式)
            val poolState =
                pool.joinToString(prefix = "[", postfix = "]") {
                    "conn=${it.session.isConnected}/healthy=${it.healthy}"
                }
            VpnController.appLogThrottled(
                "SSH 通道打开失败 · $host:$port — ${lastError?.message} (pool: $poolState)",
                level = LogLevel.ERROR,
                throttleKey = "SSH 通道打开失败",
            )
            return null
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
                    if (m.name == "setLocalPacketSize") {
                        m.invoke(channel, CHANNEL_SEND_MAX_PACKET_SIZE)
                    } else {
                        m.invoke(channel, windowSize)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setChannelWindowSize failed: ${e.message}")
                VpnController.appLogThrottled(
                    "SSH 通道窗口设置失败 — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "SSH 通道窗口设置失败",
                )
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
                    s.setConfig("max_input_buffer_size", MAX_INPUT_BUFFER_SIZE)
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
                    Log.w(TAG, "execSingleShot failed: ${e.message}")
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
