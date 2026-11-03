package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 最小 SOCKS5 服务端: 用户名/密码认证 + CONNECT, 支持多连接。 */
internal class FakeSocksServer : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort
    val accepted = AtomicInteger(0)

    init {
        Thread {
            try {
                while (true) {
                    val s = serverSocket.accept()
                    // 每连接一个线程: 单线程顺序 handle 会让第二条连接卡在 accept 队列里
                    // (第一条 handle 一直读到对端关闭), 多连接用例直接假阴性
                    Thread {
                        handle(s)
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 5000
            val inp = s.getInputStream()
            val out = s.getOutputStream()

            inp.read()
            val nm = inp.read()
            repeat(nm) { inp.read() }
            out.write(byteArrayOf(0x05, 0x02))
            out.flush()

            inp.read()
            val ul = inp.read()
            repeat(ul) { inp.read() }
            val pl = inp.read()
            repeat(pl) { inp.read() }
            out.write(byteArrayOf(0x01, 0x00))
            out.flush()

            inp.read()
            inp.read()
            inp.read()
            when (inp.read()) {
                0x01 -> repeat(6) { inp.read() }
                0x04 -> repeat(18) { inp.read() }
                0x03 -> {
                    val l = inp.read()
                    repeat(l + 2) { inp.read() }
                }
                else -> return
            }
            accepted.incrementAndGet()
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            while (inp.read() != -1) {
                // 消费直到对端关闭
            }
        } catch (_: Exception) {
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: Exception) {
        }
    }
}

internal class FakeSocksPlugin(
    override val localSocksPort: Int,
    override val activeConnections: Int = 0,
) : TunnelPlugin {
    @Volatile var tunCallback: ((ByteArray, Int, Int) -> Unit)? = null

    @Volatile var targetEofCallback: ((Boolean) -> Unit)? = null
    val removedPorts = CopyOnWriteArrayList<Int>()

    override val id = "fake-socks"
    override val socksAuth: Pair<String, String> = "user" to "pass"

    override suspend fun connect(config: TunnelConfig): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override fun registerTunCallback(
        clientPort: Int,
        callback: (ByteArray, Int, Int) -> Unit,
    ) {
        tunCallback = callback
    }

    override fun removeTunCallback(clientPort: Int) {
        removedPorts += clientPort
        tunCallback = null
    }

    override fun registerTargetEofCallback(
        clientPort: Int,
        callback: (Boolean) -> Unit,
    ) {
        targetEofCallback = callback
    }

    override fun removeTargetEofCallback(clientPort: Int) {
        targetEofCallback = null
    }
}
