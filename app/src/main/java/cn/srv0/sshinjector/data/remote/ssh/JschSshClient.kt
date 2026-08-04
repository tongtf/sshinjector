package cn.srv0.sshinjector.data.remote.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.vpn.SshChannelFactory
import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

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
class JschSshClient @Inject constructor(
    private val keyManager: SshKeyManager
) : SshChannelFactory {

    companion object {
        private const val TAG = "JschSshClient"
        private const val SESSION_POOL_SIZE = 3
        private const val CHANNEL_WINDOW_SIZE = 8 * 1024 * 1024
    }

    private val pool = ConcurrentLinkedQueue<PooledSession>()
    private val sessionIndex = AtomicInteger(0)
    @Volatile private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isConnectedFlag = AtomicBoolean(false)
    private var currentConfig: ServerConfig? = null

    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val lastError = MutableStateFlow<String?>(null)

    enum class ConnectionState {
        Disconnected, Connecting, Authenticating, EstablishingTunnel, Connected, Disconnecting, Failed
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

    private fun createSession(config: ServerConfig, index: Int): PooledSession? {
        return try {
            val jsch = JSch()
            jsch.setKnownHosts(java.io.ByteArrayInputStream(byteArrayOf()))
            JSch.setLogger(object : com.jcraft.jsch.Logger {
                private val levels = mapOf(
                    com.jcraft.jsch.Logger.DEBUG to "DEBUG",
                    com.jcraft.jsch.Logger.INFO to "INFO",
                    com.jcraft.jsch.Logger.WARN to "WARN",
                    com.jcraft.jsch.Logger.ERROR to "ERROR",
                    com.jcraft.jsch.Logger.FATAL to "FATAL"
                )
                override fun isEnabled(level: Int) = true
                override fun log(level: Int, message: String) {
                    android.util.Log.d(TAG, "[pool-$index] [${levels[level] ?: level}] $message")
                }
            })
            val keyAdded = keyManager.createJSchIdentity(jsch, config.keyAlias)
            if (!keyAdded) {
                throw Exception("无法访问私钥")
            }

            val s = jsch.getSession(config.username, config.host, config.port)
            if (!config.password.isNullOrEmpty()) {
                s.setPassword(config.password)
            }
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "publickey,password")
            s.setConfig("PubkeyAuthentication", "yes")
            s.setConfig("PasswordAuthentication", "yes")
            s.setConfig("KexAlgorithms", "curve25519-sha256,curve25519-sha256@libssh.org,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1")
            s.setConfig("HostKeyAlgorithms", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa,ssh-dss")
            s.setConfig("PubkeyAcceptedAlgorithms", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa,ssh-dss")
            s.setTimeout(config.connectTimeout)

            connectionState.value = ConnectionState.Authenticating
            s.connect()

            val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pooled = PooledSession(session = s, scope = sessionScope)
            startSessionKeepAlive(pooled, config.keepAliveInterval.toLong(), index)

            pooled
        } catch (e: Exception) {
            android.util.Log.w(TAG, "createSession[$index] failed: ${e.message}")
            null
        }
    }

    private fun startSessionKeepAlive(pooled: PooledSession, intervalMs: Long, index: Int) {
        pooled.keepAliveJob = pooled.scope.launch {
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
    private suspend fun tryReconnectSession(pooled: PooledSession, index: Int) {
        val config = currentConfig ?: return
        var retryCount = 0
        val maxRetries = 5

        while (retryCount < maxRetries && isConnectedFlag.get()) {
            retryCount++
            val backoffMs = minOf(1000L * retryCount, 10000L)
            android.util.Log.d(TAG, "Session pool-$index: reconnect attempt $retryCount/$maxRetries (backoff ${backoffMs}ms)")
            delay(backoffMs)

            if (!isConnectedFlag.get()) break

            try {
                pooled.session.disconnect()
            } catch (_: Exception) {}

            try {
                val newPooled = createSession(config, index) ?: continue

                // 原子替换: 取出旧的, 放入新的
                pool.remove(pooled)
                pool.add(newPooled)

                android.util.Log.d(TAG, "Session pool-$index: reconnected successfully")
                // 更新 keepAlive 引用
                pooled.keepAliveJob?.cancel()
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
            } catch (_: Exception) {}
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
    override fun createDirectChannel(host: String, port: Int): TunnelChannel? {
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
                channel.connect(10000)
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

    private fun setChannelWindowSize(channel: com.jcraft.jsch.Channel, windowSize: Int) {
        try {
            for (m in com.jcraft.jsch.Channel::class.java.declaredMethods) {
                when (m.name) {
                    "setLocalWindowSizeMax" -> {
                        m.isAccessible = true
                        m.invoke(channel, windowSize)
                    }
                    "setLocalWindowSize" -> {
                        m.isAccessible = true
                        m.invoke(channel, windowSize)
                    }
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
            try { pooled.session.disconnect() } catch (_: Exception) {}
            pooled.scope.cancel()
        }
    }

    fun getSession(): Session? = pool.firstOrNull()?.session

    suspend fun cleanup() {
        disconnect()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
