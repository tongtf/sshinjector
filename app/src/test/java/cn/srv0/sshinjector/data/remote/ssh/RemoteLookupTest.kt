package cn.srv0.sshinjector.data.remote.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.InetAddress

/**
 * 远端解析的报文构造与解析 (**纯逻辑**)。
 *
 * 三条防线:
 * ① 名字必须**绝对化** —— 缺尾点会让 dnsjava 抛 `RelativeNameException`, 查询根本发不出去
 *    (真机踩过同类形态: 点「解析真实 IP」只有 `解析查询失败 … is not an absolute name`);
 * ② 线格式 = RFC 1035 §4.2.2 的 **2 字节长度前缀 + 报文**, 前缀必须能被 [RemoteLookup.unframe] 还原;
 * ③ 网络来的应答字节不可信: 截断 / 垃圾 / 超长长度都判掉 (返回空表 = 回退本地视角, 不是故障)。
 */
class RemoteLookupTest {
    @Test
    fun `host whitelist accepts real domains`() {
        val accepted =
            listOf(
                "example.com",
                "a.b.c.example.co.uk",
                "host-1.example.com",
                "xn--ngstr-lra8j.com", // punycode (Play CDN)
                "1.2.3.4", // IP 字面量也能过 (没有域名语义, 但字符集合法)
                "a".repeat(63) + ".com",
            )
        for (host in accepted) {
            assertTrue(host, RemoteLookup.isValidHost(host))
        }
    }

    @Test
    fun `host whitelist rejects control and separator characters`() {
        val rejected =
            listOf(
                "",
                " ",
                "exa mple.com",
                "example.com;id",
                "example.com|id",
                "example.com&&id",
                "example.com`id`",
                "example.com\$(id)",
                "example.com>out",
                "example.com\nid",
                "example.com\tid",
                "\$(whoami).example.com",
            )
        for (host in rejected) {
            assertFalse(host, RemoteLookup.isValidHost(host))
        }
    }

    @Test
    fun `host whitelist rejects malformed names and over-long input`() {
        val malformed = listOf("-x.com", ".example.com", "example..com", "example.com.", "x-.com")
        for (host in malformed) {
            assertFalse(host, RemoteLookup.isValidHost(host))
        }
        // 单标签 64 字符超限; 总长 254 超限
        assertFalse(RemoteLookup.isValidHost("a".repeat(64) + ".com"))
        assertFalse(RemoteLookup.isValidHost("a".repeat(254)))
    }

    @Test
    fun `query wire carries an absolute question name and a two byte length prefix`() {
        val id = 0x1234
        val framed = RemoteLookup.queryWire("example.com", Type.A, id)

        val wire = RemoteLookup.unframe(framed)
        assertTrue("长度前缀 + 报文必须是自洽的一帧", wire != null)

        val message = Message(wire)
        assertEquals(id, message.header.id)
        assertTrue("RD 必须置位 (递归查询)", message.header.getFlag(Flags.RD.toInt()))
        val question = message.getQuestion()
        assertEquals(Type.A, question.type)
        assertEquals("name must be absolute (trailing dot)", "example.com.", question.name.toString())
        assertTrue(question.name.isAbsolute)
    }

    @Test
    fun `query wire type is carried through`() {
        val framed = RemoteLookup.queryWire("example.com", Type.AAAA, 7)
        val question = Message(RemoteLookup.unframe(framed)!!).getQuestion()
        assertEquals(Type.AAAA, question.type)
    }

    @Test
    fun `unframe rejects a header shorter than the length prefix`() {
        assertNull(RemoteLookup.unframe(ByteArray(0)))
        assertNull(RemoteLookup.unframe(ByteArray(1)))
    }

    @Test
    fun `unframe rejects zero, over-long and truncated frames`() {
        // 长度 0: 不是合法 DNS 报文 (dnsjava 也解析不了), 提前判掉
        assertNull(RemoteLookup.unframe(byteArrayOf(0, 0)))
        // 长度超上限 (8KB)
        assertNull(RemoteLookup.unframe(byteArrayOf((0x20).toByte(), 0x01)))
        // 声明 4 字节但只跟了 2 字节 = 截断 (半个报文喂给 dnsjava 只会解析失败)
        assertNull(RemoteLookup.unframe(byteArrayOf(0, 4, 1, 2)))
        // 声明 2 字节却多给了字节 = 也不是一帧
        assertNull(RemoteLookup.unframe(byteArrayOf(0, 2, 1, 2, 3)))
    }

    @Test
    fun `parse answers keeps record order and dedupes`() {
        val v6 = InetAddress.getByName("2a00:1450:4010:c0e::200e")
        val wire =
            response(
                a = listOf("142.250.66.78", "142.250.66.78", "142.250.66.79"),
                aaaa = listOf("2a00:1450:4010:c0e::200e"),
            )
        // 顺序 = 应答里的出现顺序 (首个就是这一侧解析器给出的偏好), 不去重以外的重排。
        // 字面量形态与本地腿一致 (`InetAddress.hostAddress`, v6 展开成全 8 组) —— 两腿展示同一列, 别一边缩写一边展开。
        assertEquals(
            listOf("142.250.66.78", "142.250.66.79", v6.hostAddress),
            RemoteLookup.parseAnswers(wire),
        )
    }

    @Test
    fun `parse answers caps the result`() {
        val wire = response(a = (1..20).map { "10.0.0.$it" })
        assertEquals(RemoteLookup.MAX_IPS, RemoteLookup.parseAnswers(wire).size)
    }

    @Test
    fun `parse answers returns an empty list for an answer-less or garbage response`() {
        assertTrue(RemoteLookup.parseAnswers(ByteArray(0)).isEmpty())
        assertTrue(RemoteLookup.parseAnswers(ByteArray(8)).isEmpty())
        // 只有问题、没有答案 (NXDOMAIN/空应答) → 远端给不出答案
        assertTrue(RemoteLookup.parseAnswers(response()).isEmpty())
    }

    @Test
    fun `query id is a sane non-zero 16 bit value`() {
        val ids = (1..50).map { RemoteLookup.nextQueryId() }
        assertTrue(ids.all { it in 1..0xFFFF })
    }

    private fun response(
        a: List<String> = emptyList(),
        aaaa: List<String> = emptyList(),
    ): ByteArray {
        val name = Name.fromString("example.com", Name.root)
        val message = Message()
        message.header.setID(0x1234)
        message.header.setFlag(Flags.QR.toInt())
        message.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION)
        for (ip in a) {
            message.addRecord(ARecord(name, DClass.IN, 60, InetAddress.getByName(ip)), Section.ANSWER)
        }
        for (ip in aaaa) {
            message.addRecord(AAAARecord(name, DClass.IN, 60, InetAddress.getByName(ip)), Section.ANSWER)
        }
        return message.toWire()
    }
}
