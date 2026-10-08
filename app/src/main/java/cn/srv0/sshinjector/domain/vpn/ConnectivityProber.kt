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
        val (input, output) = openHttpStreams(socket, endpoint)
        val request =
            "GET ${endpoint.path} HTTP/1.1\r\n" +
                "Host: ${endpoint.host}\r\n" +
                "Connection: close\r\n" +
                "User-Agent: sshinjector-health\r\n\r\n"
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val statusLine = readLine(input)
        return when {
            // 0 字节关闭: 探测端点 (miui 204 / ipify) 是**必然**回状态行的健康服务,
            // 一个字节都没回来 = 本栈到远端之间的路径断了。归 TUNNEL 而非 REMOTE ——
            // 归 REMOTE 会把排查方向带向"远端不可达", 而真正该看的是隧道侧的
            // 零回程 / 回程读取异常 / 隧道无回程 日志 (SSH 会话健康但单条通道死也长这样)。
            statusLine == null -> Result.Failed(HealthStep.TUNNEL, "tunnel closed with 0B before status line")
            statusLine.startsWith("HTTP/1.") -> Result.Ok
            else -> Result.Failed(HealthStep.REMOTE, "no http status line: ${statusLine.take(32)}")
        }
    }

    /**
     * 经隧道请求 IP 回显端点并返回出口 IP (互联网视角的代理出口地址)。
     * 任何失败返回 null — 仅影响显示, 不参与健康归因、不重试风暴
     * (调用方在探测成功且未取回时才重试)。
     */
    fun fetchExitIp(endpointUrl: String = DEFAULT_EXIT_IP_ENDPOINT): String? {
        val endpoint =
            try {
                parseEndpoint(endpointUrl)
            } catch (_: Exception) {
                return null
            }
        phase = PHASE_CONNECT
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            if (socksHandshake(socket, endpoint) != null) return null
            readIpBody(socket, endpoint)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 读状态行 + 头部 (取 Content-Length) + 正文, 解析 IP 回显。 */
    private fun readIpBody(
        socket: Socket,
        endpoint: Endpoint,
    ): String? {
        phase = PHASE_HTTP
        val (input, output) = openHttpStreams(socket, endpoint)
        val request =
            "GET ${endpoint.path} HTTP/1.1\r\n" +
                "Host: ${endpoint.host}\r\n" +
                "Accept: text/plain, application/json\r\n" +
                "Connection: close\r\n" +
                "User-Agent: sshinjector-exit-ip\r\n\r\n"
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        if (readLine(input)?.startsWith("HTTP/1.") != true) return null
        var contentLength = -1
        var headerLines = 0
        while (headerLines < MAX_HEADER_LINES) {
            val line = readLine(input)
            headerLines++
            if (line == null || line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }
        val raw =
            when {
                contentLength == 0 -> return null
                contentLength in 1..MAX_IP_BODY -> String(readFully(input, contentLength), Charsets.UTF_8)
                else -> readUntilEof(input, MAX_IP_BODY)
            }
        return parseIpBody(raw)
    }

    /**
     * 回显正文 -> IP: 优先取 JSON 的 "ip" 值, 否则整段;
     * 须含 '.' 或 ':' (排除裸 chunk-size/纯词) 且仅 IPv4/IPv6 字面量字符。
     */
    private fun parseIpBody(raw: String): String? {
        val candidate = (JSON_IP.find(raw)?.groupValues?.get(1) ?: raw).trim()
        val looksLikeIp =
            candidate.isNotEmpty() &&
                candidate.length <= MAX_IP_LENGTH &&
                candidate.any { it == '.' || it == ':' } &&
                candidate.all { it.isDigit() || it in ".:abcdefABCDEF" }
        return candidate.takeIf { looksLikeIp }
    }

    /** TLS 握手 (如需) 并返回读写流。 */
    private fun openHttpStreams(
        socket: Socket,
        endpoint: Endpoint,
    ): Pair<InputStream, OutputStream> {
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
        return input to output
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

    /**
     * 读到 \n 为止 (上限 512 字节), 去掉尾部 \r\n。
     *
     * @return null = **首字节即 EOF** (对端一个字节都没回)。不能折叠成空串: 空串还包含
     * "对端先回了个空行"这一种, 而 0 字节关闭恰恰是隧道断 / 远端未应答的现场 —— 折叠后
     * reason 恒为 `no http status line: `, 排障时四种成因分不出来 (见 httpExchange)。
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var sawByte = false
        while (sb.length < MAX_STATUS_LINE) {
            val b = input.read()
            if (b < 0) return if (sawByte) sb.toString() else null
            sawByte = true
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** 读到 EOF 为止, 上限 max 字节 (无 Content-Length 时的兜底)。 */
    private fun readUntilEof(
        input: InputStream,
        max: Int,
    ): String {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return String(buf, 0, n, Charsets.UTF_8)
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
        /** 默认探测端点: 香港/海外服务器出口可达; 收到任意 HTTP 状态行都算可用 (可配置覆盖)。 */
        const val DEFAULT_ENDPOINT = "http://www.gstatic.com/generate_204"

        /** 默认出口 IP 回显端点 (JSON body {"ip":"..."}; 失败仅显示占位符, 不影响健康状态)。 */
        const val DEFAULT_EXIT_IP_ENDPOINT = "https://api.ipify.org/?format=json"

        private val JSON_IP = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"")
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5000
        private const val DEFAULT_READ_TIMEOUT_MS = 5000
        private const val SOCKS_REPLY_MIN = 10
        private const val MAX_STATUS_LINE = 512
        private const val MAX_HEADER_LINES = 64
        private const val MAX_IP_BODY = 256
        private const val MAX_IP_LENGTH = 45
        private const val PHASE_CONNECT = "connect"
        private const val PHASE_GREET = "greet"
        private const val PHASE_AUTH = "auth"
        private const val PHASE_SOCKS_CONNECT = "socks-connect"
        private const val PHASE_HTTP = "http"
    }
}
