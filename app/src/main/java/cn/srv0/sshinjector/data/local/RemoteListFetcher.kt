package cn.srv0.sshinjector.data.local

import java.net.HttpURLConnection
import java.net.URL

/** 清单下载公共上限: gfwlist/广告列表正常 ~500KB, 20MB 足够; 防远端/中间人返回巨型响应 OOM。 */
private const val MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 15_000

/**
 * 下载远端清单文本 (gfwlist / adblock hosts)。
 * DomainListManager 与 AdBlockManager 共用 — 两处曾是逐字节相同的复制。
 * - 大小上限 20MB: 超限抛 IOException, 拒绝把任意长度响应读进内存 (OOM 风险)。
 * - finally disconnect: 输入流读尽后 HttpURLConnection 会复用连接, 但读失败/超限
 *   路径不 disconnect 会占住底层 socket 直到 GC。
 */
internal fun fetchListText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", "SSHInjector/1.0")
        return connection.inputStream.bufferedReader(Charsets.ISO_8859_1).use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (sb.length + n > MAX_DOWNLOAD_BYTES) {
                    throw java.io.IOException("manifest too large: >${MAX_DOWNLOAD_BYTES / (1024 * 1024)}MB")
                }
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    } finally {
        connection.disconnect()
    }
}
