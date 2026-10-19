package cn.srv0.sshinjector.data.remote.ssh

import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import java.util.concurrent.ThreadLocalRandom

/**
 * 远端解析的**报文构造与解析** (**纯逻辑**, 单测直接锁死)。
 *
 * 机制 (2026-10-19 真机定案): 远端腿**不再**用 exec `getent ahosts`, 而是在隧道上开一条
 * `direct-tcpip` 通道到一台公共 DNS (53/TCP) 自己问。原因是加固过的服务器对 exec 永远给不出
 * 输出 —— 服务器侧的 Match 块 (我们自己向导生成的那套, 以及运维手写的硬化配置) 长这样:
 *
 * ```
 * Match User sproxy
 *     ChrootDirectory /home/sproxy/chroot
 *     AllowTcpForwarding yes
 *     ForceCommand /bin/false      ← 任何 exec 都被替换成 /bin/false: 无输出、exit 1
 *     PermitTTY no
 * ```
 *
 * 实测形态: 会话池健康 3/3、耗时 <1s 就回退, 日志只有
 * `远端解析不可用 · <域名> — 已回退本地视角 (exec 不可用或会话池无活口)` —— 而上面那行
 * `AllowTcpForwarding yes` 恰恰说明 direct-tcpip 是**被允许**的那条路 (整个数据面本来就靠它)。
 *
 * DNS over TCP 的线格式 (RFC 1035 §4.2.2): **2 字节大端长度前缀 + 报文本身**。
 * 域名来自网络 (DNS 应答), 是攻击者可控输入 —— 这里不用拼 shell, 但名字仍要过
 * [isValidHost] 白名单: 垃圾名直接跳过, 不进查询 (省一次往返, 也让日志干净)。
 */
internal object RemoteLookup {
    /** 结果上限: 统计条目只留两槽 (UI 显示首个 + `+N`), 再多也没有展示位。 */
    const val MAX_IPS: Int = 8

    /** DNS 名总长上限 (RFC 1035)。 */
    private const val MAX_HOST_LENGTH = 253

    /** 标签首尾之间的字符数上限: 首 + 61 + 尾 = 63 = DNS 单标签上限。 */
    private const val LABEL_MIDDLE_MAX = 61

    /** TCP 长度前缀字节数 (RFC 1035 §4.2.2)。 */
    const val FRAME_HEADER_BYTES = 2

    /** 单条应答上限: 正常 A/AAAA 应答几百字节, 上限挡的是被灌爆内存。 */
    private const val MAX_FRAME_BYTES = 8 * 1024

    /** 16 位查询 ID 上界 (不含): `nextInt(1, 本值)` 取有效非零 ID。 */
    private const val QUERY_ID_EXCLUSIVE_BOUND = 0x1_0000

    private val HOST_PATTERN =
        Regex(
            "^[A-Za-z0-9]([A-Za-z0-9-]{0,$LABEL_MIDDLE_MAX}[A-Za-z0-9])?" +
                "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,$LABEL_MIDDLE_MAX}[A-Za-z0-9])?)*$",
        )

    /**
     * 名字是否值得拿去查询 (空串 / 超长 / 前后导点 / 连续点 / 以 `-` 开头的标签 / 任何非
     * `[A-Za-z0-9.-]` 字符都拒绝)。全小写不是要求 (DNS 大小写不敏感)。
     */
    fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
        return HOST_PATTERN.matches(host)
    }

    /** 随机查询 ID (与事务匹配无关, 只是不让固定 ID 撞上"陈旧应答"这类猜疑)。 */
    fun nextQueryId(): Int = ThreadLocalRandom.current().nextInt(1, QUERY_ID_EXCLUSIVE_BOUND)

    /**
     * 组一条 TCP 上的 DNS 查询 (**含 2 字节长度前缀**), 直接写进通道。
     *
     * 名字必须传成**绝对名**: `Name.fromString(name, Name.root)` —— 缺尾点会抛
     * `RelativeNameException`, 查询根本发不出去 (同类 bug 在本地腿真机上踩过, 见
     * `DnsInterceptor.absoluteName`)。
     */
    fun queryWire(
        host: String,
        type: Int,
        id: Int,
    ): ByteArray {
        val message = Message()
        message.header.setID(id)
        // RD = 递归请求 (与本地腿/隧道查询同姿势)
        message.header.setFlag(Flags.RD.toInt())
        message.addRecord(
            Record.newRecord(Name.fromString(host, Name.root), type, DClass.IN),
            Section.QUESTION,
        )
        val wire = message.toWire()
        val framed = ByteArray(FRAME_HEADER_BYTES + wire.size)
        framed[0] = ((wire.size shr 8) and 0xFF).toByte()
        framed[1] = (wire.size and 0xFF).toByte()
        System.arraycopy(wire, 0, framed, FRAME_HEADER_BYTES, wire.size)
        return framed
    }

    /**
     * 从 TCP 缓冲里剥掉长度前缀; 头部不足 / 长度非法 (0 或超 [MAX_FRAME_BYTES]) / 尾部不足
     * 都返回 **null** (调用方按"远端给不出答案"处理, 回退本地视角)。
     *
     * 尾部不足返回 null (而不是返回部分报文): 截断的报文喂给 dnsjava 只会解析失败, 提前判掉
     * 更省事, 也让"读完了没有"这个问题只在一个地方回答。
     */
    fun unframe(framed: ByteArray): ByteArray? {
        if (framed.size < FRAME_HEADER_BYTES) return null
        val length = ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF)
        if (length <= 0 || length > MAX_FRAME_BYTES) return null
        if (framed.size != FRAME_HEADER_BYTES + length) return null
        return framed.copyOfRange(FRAME_HEADER_BYTES, framed.size)
    }

    /**
     * 解析 DNS 应答报文, 取 A/AAAA 记录的字面量: **按首次出现顺序去重**, 上限 [MAX_IPS]。
     *
     * **顺序即答案**: 不重排 v4/v6 —— 首个地址就是这一侧解析器给出的偏好 (双栈域名通常 v6 优先)。
     * 解析失败 (垃圾/截断应答) 返回空表 = "远端给不出答案", 调用方回退本地视角且**不报错**。
     */
    fun parseAnswers(wire: ByteArray): List<String> {
        if (wire.isEmpty()) return emptyList()
        return try {
            Message(wire)
                .getSection(Section.ANSWER)
                .asSequence()
                .mapNotNull {
                    when (it) {
                        is ARecord -> it.address.hostAddress
                        is AAAARecord -> it.address.hostAddress
                        else -> null
                    }
                }.distinct()
                .take(MAX_IPS)
                .toList()
        } catch (e: Exception) {
            // 垃圾/截断应答: 远端给不出答案 (不是本栈故障), 调用方按 Unavailable 处理
            emptyList()
        }
    }
}
