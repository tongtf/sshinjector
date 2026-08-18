package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import android.util.Base64
import android.util.Log
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.vpn.SshChannelFactory
import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

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

        /**
         * 检查主机密钥是否匹配
         * @return true 如果匹配或首次连接(TOFU), false 如果不匹配
         */
        fun verifyHostKey(
            host: String,
            port: Int,
            hostKey: HostKey,
        ): Boolean {
            val fingerprint = computeFingerprint(hostKey)
            val storedLine = findHostLine(host, port)

            return if (storedLine == null) {
                // 首次连接：TOFU - 保存并接受
                saveHostKey(host, port, hostKey)
                true
            } else {
                // 验证指纹匹配
                val storedFingerprint = extractFingerprint(storedLine)
                storedFingerprint == fingerprint
            }
        }

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
            // JSch HostKey.getKey() 返回 OpenSSH 公钥字符串
            val keyBytes = hostKey.getKey().toByteArray()
            val keyBlob = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
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

        private fun findHostLine(
            host: String,
            port: Int,
        ): String? {
            return knownHostsFile.readText().lines().firstOrNull { it.startsWith("$host,$port ") }
        }

        private fun extractFingerprint(line: String): String {
            // 格式: host,port keytype base64key SHA256:fingerprint
            return line.split(" ").last()
        }

        private fun computeFingerprint(key: HostKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // JSch HostKey.getKey() 返回 OpenSSH 公钥字符串
            val bytes = key.getKey().toByteArray()
            digest.update(bytes)
            return "SHA256:" + Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
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
    ) : SshChannelFactory, RemoteCommandExecutor {
        companion object {
            private const val TAG = "JschSshClient"
            private const val SESSION_POOL_SIZE = 3
            private const val CHANNEL_WINDOW_SIZE = 8 * 1024 * 1024
            private const val CHANNEL_SEND_MAX_PACKET_SIZE = 64 * 1024
            private const val CHANNEL_CONNECT_TIMEOUT_MS = 10000

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
                    }
                    .map {
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
                if (!config.password.isNullOrEmpty()) {
                    val passwordBytes = config.password.toByteArray(Charsets.UTF_8)
                    try {
                        // JSch setPassword(byte[]) 内部会 clone，之后本地字节数组可安全清零
                        s.setPassword(passwordBytes)
                    } finally {
                        java.util.Arrays.fill(passwordBytes, 0)
                    }
                }
                // 先连接，获取主机密钥，然后验证
                s.setConfig("StrictHostKeyChecking", "no") // 临时禁用，手动验证
                s.setConfig("TCPNoDelay", "yes") // 禁用 Nagle, 降低 SSH 小包 (ACK/交互) 的 RTT
                s.setConfig("PreferredAuthentications", "publickey,password")
                s.setConfig("PubkeyAuthentication", "yes")
                s.setConfig("PasswordAuthentication", "yes")
                // 仅保留安全算法：移除 diffie-hellman-group1-sha1 (1024-bit, 已被攻破) 和 ssh-dss (DSA)
                s.setConfig(
                    "KexAlgorithms",
                    "curve25519-sha256,curve25519-sha256@libssh.org," +
                        "diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256",
                )
                s.setConfig(
                    "HostKeyAlgorithms",
                    "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa",
                )
                s.setConfig(
                    "PubkeyAcceptedAlgorithms",
                    "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa",
                )
                s.setTimeout(config.connectTimeout)

                connectionState.value = ConnectionState.Authenticating
                s.connect()

                verifyHostKey(config, s.getHostKey())

                val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val pooled = PooledSession(session = s, scope = sessionScope)
                startSessionKeepAlive(pooled, config.keepAliveInterval.toLong(), index)

                pooled
            } catch (e: Exception) {
                android.util.Log.w(TAG, "createSession[$index] failed: ${e.message}")
                null
            }
        }

        private fun verifyHostKey(
            config: ServerConfig,
            hostKey: HostKey?,
        ) {
            checkNotNull(hostKey) { "SSH 服务器未提供主机密钥" }

            // 如果配置中已有预期指纹，优先验证
            if (!config.hostKeyFingerprint.isNullOrEmpty()) {
                val expectedFingerprint = config.hostKeyFingerprint!!
                val actualFingerprint = computeFingerprint(hostKey)
                check(expectedFingerprint == actualFingerprint) {
                    "主机密钥指纹不匹配! 预期: $expectedFingerprint, 实际: $actualFingerprint. 可能遭受中间人攻击!"
                }
                Log.d(TAG, "Host key verified against configured fingerprint: $expectedFingerprint")
            } else {
                // TOFU 模式：使用 KnownHostsManager 验证/保存
                val verified = knownHostsManager.verifyHostKey(config.host, config.port, hostKey)
                check(verified) {
                    "主机密钥已变更! 可能遭受中间人攻击! Host: ${config.host}:${config.port}"
                }
                // 更新当前配置中的指纹（首次连接时保存）
                currentConfig = currentConfig?.copy(hostKeyFingerprint = computeFingerprint(hostKey))
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

        private fun computeFingerprint(hostKey: HostKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // JSch HostKey.getKey() 返回 OpenSSH 公钥字符串
            val bytes = hostKey.getKey().toByteArray()
            digest.update(bytes)
            return "SHA256:" + Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
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
                    if (!target.password.isNullOrEmpty()) {
                        val passwordBytes = target.password.toByteArray(Charsets.UTF_8)
                        try {
                            s.setPassword(passwordBytes)
                        } finally {
                            java.util.Arrays.fill(passwordBytes, 0)
                        }
                    }
                    s.setConfig("StrictHostKeyChecking", "no")
                    s.setConfig("PreferredAuthentications", "publickey,password")
                    s.setConfig("PubkeyAuthentication", "yes")
                    s.setConfig("PasswordAuthentication", "yes")
                    s.setConfig(
                        "KexAlgorithms",
                        "curve25519-sha256,curve25519-sha256@libssh.org," +
                            "diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256",
                    )
                    s.setConfig(
                        "HostKeyAlgorithms",
                        "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa",
                    )
                    s.setConfig(
                        "PubkeyAcceptedAlgorithms",
                        "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa",
                    )
                    s.setTimeout(config.connectTimeout)
                    s.connect()

                    verifyHostKey(config, s.getHostKey())

                    session = s
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
