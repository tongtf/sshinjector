package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.model.HealthStep
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 端到端连通性探测: 经本地 SOCKS5 出口 (与真实业务流量同一路径) 请求 HTTP 端点,
 * 收到任何 HTTP 状态行 = 端到端可用。
 *
 * 手写 SOCKS5 握手 (RFC 1928/1929, 用户名密码 0x02, 与 TcpStateMachine 客户端一致);
 * CONNECT 用域名类型 (0x03) 交远端解析, 不依赖本地 DNS, 在任何分流模式下路径一致
 * (探测走 loopback -> SSH, 不经 TUN)。
 *
 * 失败按握手阶段归因: 代理端口/握手阶段 -> PROXY; CONNECT 回复超时(通道建立卡住) -> TUNNEL;
 * CONNECT 回复 rep!=0 或 HTTP 阶段失败 -> REMOTE。
 */
class ConnectivityProber(
    private val socksPort: Int,
    private val socksAuth: Pair<String, String>?,
    private val endpointUrl: String,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    sealed interface Result {
        data object Ok : Result

        data class Failed(
            val step: HealthStep,
            val reason: String,
        ) : Result
    }

    private data class Endpoint(
        val host: String,
        val port: Int,
        val path: String,
        val tls: Boolean,
    )

    /** 当前握手阶段, 供 SocketTimeoutException/通用异常归因; 每次 probe() 从头重置。 */
    private var phase = PHASE_CONNECT

    fun probe(): Result {
        val endpoint =
            try {
                parseEndpoint(endpointUrl)
            } catch (e: Exception) {
                return Result.Failed(HealthStep.REMOTE, "invalid endpoint: ${e.message}")
            }

        phase = PHASE_CONNECT
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socksHandshake(socket, endpoint) ?: httpExchange(socket, endpoint)
        } catch (e: ConnectException) {
            // 连本地代理都被拒 = 代理没起来/端口错
            Result.Failed(HealthStep.PROXY, "proxy unreachable: ${e.message}")
        } catch (e: SocketTimeoutException) {
            Result.Failed(phaseToStep(phase), "timeout at $phase")
        } catch (e: Exception) {
            Result.Failed(phaseToStep(phase), "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** SOCKS5 握手 (greet/auth/CONNECT, RFC 1928/1929); null = 成功, 否则为归因后的失败。 */
    private fun socksHandshake(
        socket: Socket,
        endpoint: Endpoint,
    ): Result? {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        phase = PHASE_GREET
        output.write(byteArrayOf(0x05, 0x01, 0x02))
        output.flush()
        val greet = readFully(input, 2)
        if (greet[0].toInt() and 0xFF != 5 || greet[1].toInt() and 0xFF != 0x02) {
            return Result.Failed(HealthStep.PROXY, "auth method rejected: ${greet.toHex()}")
        }

        phase = PHASE_AUTH
        val auth = socksAuth ?: return Result.Failed(HealthStep.PROXY, "credentials missing")
        val user = auth.first.toByteArray(Charsets.UTF_8)
        val pass = auth.second.toByteArray(Charsets.UTF_8)
        require(user.size <= 255 && pass.size <= 255) { "credentials too long" }
        output.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        output.flush()
        val authReply = readFully(input, 2)
        if (authReply[0].toInt() and 0xFF != 1 || authReply[1].toInt() and 0xFF != 0) {
            return Result.Failed(HealthStep.PROXY, "auth rejected: ${authReply.toHex()}")
        }

        phase = PHASE_SOCKS_CONNECT
        val host = endpoint.host.toByteArray(Charsets.UTF_8)
        require(host.size <= 255) { "host too long" }
        val connectReq =
            byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) +
                host +
                byteArrayOf(
                    (endpoint.port shr 8).toByte(),
                    (endpoint.port and 0xFF).toByte(),
                )
        output.write(connectReq)
        output.flush()
        val rep = readFully(input, SOCKS_REPLY_MIN)
        if (rep[0].toInt() and 0xFF != 5) {
            return Result.Failed(HealthStep.PROXY, "bad connect reply: ${rep.toHex()}")
        }
        val replyCode = rep[1].toInt() and 0xFF
        return if (replyCode != 0) {
            Result.Failed(HealthStep.REMOTE, "socks connect refused: rep=$replyCode")
        } else {
            null
        }
    }

    /** 经隧道发出 HTTP 请求; 收到任何 HTTP 状态行 = 端到端可用。 */
    private fun httpExchange(
        socket: Socket,
        endpoint: Endpoint,
    ): Result {
        phase = PHASE_HTTP
        var input: InputStream = socket.getInputStream()
        var output: OutputStream = socket.getOutputStream()
        if (endpoint.tls) {
            // getDefault() 静态返回类型是 javax.net.SocketFactory (无 4 参重载), 需收窄
            val ssl =
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, endpoint.host, endpoint.port, true) as SSLSocket
            ssl.soTimeout = readTimeoutMs
            ssl.startHandshake()
            input = ssl.getInputStream()
            output = ssl.getOutputStream()
        }
        val request =
            "GET ${endpoint.path} HTTP/1.1\r\n" +
                "Host: ${endpoint.host}\r\n" +
                "Connection: close\r\n" +
                "User-Agent: sshinjector-health\r\n\r\n"
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val statusLine = readLine(input)
        return if (statusLine.startsWith("HTTP/1.")) {
            Result.Ok
        } else {
            Result.Failed(HealthStep.REMOTE, "no http status line: ${statusLine.take(32)}")
        }
    }

    private fun phaseToStep(phase: String): HealthStep =
        when (phase) {
            PHASE_CONNECT, PHASE_GREET, PHASE_AUTH -> HealthStep.PROXY
            PHASE_SOCKS_CONNECT -> HealthStep.TUNNEL
            else -> HealthStep.REMOTE
        }

    private fun readFully(
        input: InputStream,
        n: Int,
    ): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("EOF at $off/$n")
            off += r
        }
        return buf
    }

    /** 读到 \n 为止 (上限 512 字节), 去掉尾部 \r\n。 */
    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < MAX_STATUS_LINE) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun parseEndpoint(url: String): Endpoint {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("no scheme")
        require(scheme == "http" || scheme == "https") { "unsupported scheme: $scheme" }
        val tls = scheme == "https"
        val host = uri.host ?: throw IllegalArgumentException("no host")
        val port =
            when {
                uri.port != -1 -> uri.port
                tls -> 443
                else -> 80
            }
        val path =
            (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "")
        return Endpoint(host, port, path, tls)
    }

    companion object {
        /** 默认探测端点: 国内可达的 204 端点; 收到任意 HTTP 状态行都算可用 (可配置覆盖)。 */
        const val DEFAULT_ENDPOINT = "http://connect.rom.miui.com/generate_204"

        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5000
        private const val DEFAULT_READ_TIMEOUT_MS = 5000
        private const val SOCKS_REPLY_MIN = 10
        private const val MAX_STATUS_LINE = 512
        private const val PHASE_CONNECT = "connect"
        private const val PHASE_GREET = "greet"
        private const val PHASE_AUTH = "auth"
        private const val PHASE_SOCKS_CONNECT = "socks-connect"
        private const val PHASE_HTTP = "http"
    }
}
