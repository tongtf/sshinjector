package cn.srv0.sshinjector.domain.vpn

import java.util.concurrent.ConcurrentHashMap

/**
 * 有界字符串映射, LRU 淘汰 (按最近访问时间)。
 *
 * 用于 fake-IP 双向映射表: 映射被随机清半会导致客户端本地 DNS 缓存中的假 IP
 * 失去映射 → SOCKS 拿假 IP 连远端 → 黑洞重试 (Play 下载卡"连接中")。
 * get() 即刷新访问时间, 超限时优先淘汰最久未访问的条目。
 *
 * 淘汰是按表独立进行的: ipToDomain 与 domainToIp 的部分失效互相不矛盾
 * (下次 DNS 查询会重建), 语义上始终 "key → value" 自洽。
 */
internal class LruStringMap(
    private val maxSize: Int,
) {
    private class Entry(
        val value: String,
    ) {
        @Volatile var lastAccessMs: Long = System.currentTimeMillis()
    }

    private val map = ConcurrentHashMap<String, Entry>()

    val size: Int
        get() = map.size

    /** 写入 (已存在则整体替换, 视为最新)。 */
    fun put(
        key: String,
        value: String,
    ) {
        map[key] = Entry(value)
    }

    /** 读取并刷新访问时间; 未命中返回 null。 */
    operator fun get(key: String): String? {
        val entry = map[key] ?: return null
        entry.lastAccessMs = System.currentTimeMillis()
        return entry.value
    }

    /** 当前所有值的弱一致快照 (用于假 IP 计数器复位)。 */
    fun values(): Collection<String> = map.values.map { it.value }

    /**
     * 按 key 删除, 返回被删的值 (未命中返回 null)。
     *
     * 与 [removeByValue] 配对维护双向表: 反向删除必须按**值**删 (key 里含 qtype,
     * 只拿到域名无法还原出全部 key), 否则会留下悬空条目或误删同域名的另一类型记录。
     */
    fun remove(key: String): String? = map.remove(key)?.value

    /** 按 value 删除所有匹配条目, 返回删除条数 (用于反查表按假 IP 清理)。 */
    fun removeByValue(value: String): Int {
        val victims = map.entries.filter { it.value.value == value }.map { it.key }
        var removed = 0
        for (k in victims) {
            if (map.remove(k) != null) removed++
        }
        return removed
    }

    /** 超过 [maxSize] 时淘汰最久未访问的条目, 压回容量的 90%。应在低频任务中调用。 */
    fun trim() {
        if (map.size <= maxSize) return
        val target = maxSize - (maxSize / 10)
        val toRemove = map.size - target
        map.entries
            .sortedBy { it.value.lastAccessMs }
            .take(toRemove)
            .forEach { evicted -> map.remove(evicted.key, evicted.value) }
    }
}
