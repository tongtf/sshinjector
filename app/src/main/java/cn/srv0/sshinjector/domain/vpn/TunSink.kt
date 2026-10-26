package cn.srv0.sshinjector.domain.vpn

/**
 * TUN 写入出口。**两类写入的语义必须分开**, 混用会静默丢数据:
 *
 * - [write] 控制包 (SYN-ACK / 纯 ACK / 关闭包 / ICMPv6 NA): 队列满**可以丢**,
 *   客户端会按 RTO 重传 (重传 SYN 会触发 SYN-ACK 补发, 重传 FIN 同理), 最多慢一点;
 * - [writeBlocking] 回程数据 / DNS 应答: **不可丢** —— 组包时 `serverSeq` 已经推进,
 *   本栈没有重传机制, 丢掉一段就是**永久洞** (客户端永远等不到那个字节)。
 *
 * 旧实现只有一个 `writeToTun(data): Boolean` (内部 `@Synchronized` + 阻塞 `FileOutputStream.write`),
 * 于是"控制包可丢"与"数据包不可丢"被迫共用一种行为: packetLoop 发 ACK 时也要挤同一把锁,
 * 一条慢连接会把所有连接一起拖住 (跨连接 head-of-line)。
 */
interface TunSink {
    /** 非阻塞: 成功入队返回 true; 队列满被丢弃返回 false (已计数, 由客户端重传兜底)。 */
    fun write(data: ByteArray): Boolean

    /** 等待真的写出去 (或写失败/会话关闭): 返回 true = 已写入 TUN。 */
    fun writeBlocking(data: ByteArray): Boolean

    /**
     * 批量写入回程数据 (顺序保持, 要么都尝试过, 要么会话已关闭)。
     *
     * 存在的理由纯是开销: 逐包 `writeBlocking` 每次都要入队 + park/unpark 交接一次,
     * 小包高吞吐时这一步能成为单核瓶颈 (实测逐段交接 ~325k pps, 组包本身 4.2M pps)。
     * 默认实现退化为逐包调用, 测试替身不需要关心批量语义。
     *
     * @return true = 全部写入成功; false = 至少一个失败/会话关闭 (已按失败计数)。
     */
    fun writeBlockingBatch(packets: List<ByteArray>): Boolean {
        var allWritten = true
        for (packet in packets) {
            if (!writeBlocking(packet)) allWritten = false
        }
        return allWritten
    }
}

/**
 * 测试与简单场景的 [TunSink]: 一个 lambda 同时满足两类写入。
 *
 * 既有测试只关心"收到哪些包", 用这个方法可以把 `{ bytes -> captured.add(bytes) }`
 * 一行改成 `tunSinkOf { captured.add(it) }`, 不必实现两个方法。
 */
fun tunSinkOf(write: (ByteArray) -> Unit): TunSink =
    object : TunSink {
        override fun write(data: ByteArray): Boolean {
            write.invoke(data)
            return true
        }

        override fun writeBlocking(data: ByteArray): Boolean {
            write.invoke(data)
            return true
        }
    }
