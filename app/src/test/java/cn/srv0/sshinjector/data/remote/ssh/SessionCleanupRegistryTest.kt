package cn.srv0.sshinjector.data.remote.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SSH 建连取消时的会话回收表 (P1-14): "已建成但未入池"的会话必须 **exactly-once** 释放。
 *
 * 三条语义 (少了任何一条取消/失败路径就会漏 socket + 线程):
 * ① 已 `commit` 的条目所有权转移给会话池, `releaseUncommitted` 不得再释放;
 * ② `releaseUncommitted` 幂等 —— 取消分支与 `finally` 各调一次是正常形态;
 * ③ 判定按**身份** (`===`) 而不是 `equals`: `PooledSession` 是 data class, 可变字段
 *    (=`keepAliveJob`) 会让 equals 前后不一致, 用 equals 摘除会漏 (见 createDirectChannel 的同类坑)。
 *
 * 文件放在 tunnel 测试目录只是并行开发时的文件边界约定, 包名仍跟随被测类。
 */
class SessionCleanupRegistryTest {
    /** equals/hashCode 有意义但实例不同 —— 用来锁死"按身份提交"。 */
    private class Handle(
        val name: String,
    ) {
        override fun equals(other: Any?): Boolean = other is Handle && other.name == name

        override fun hashCode(): Int = name.hashCode()
    }

    @Test
    fun `committed sessions are never released`() {
        val released = CopyOnWriteArrayList<String>()
        val registry = SessionCleanupRegistry<String> { released += it }

        registry.track("s0")
        registry.track("s1")
        assertTrue(registry.commit("s0"))
        assertTrue(registry.commit("s1"))

        registry.releaseUncommitted()

        assertEquals("已入池的会话不得被回收 (否则池里刚建好的会话会被断开)", emptyList<String>(), released)
    }

    @Test
    fun `only uncommitted sessions are released`() {
        val released = CopyOnWriteArrayList<Handle>()
        val registry = SessionCleanupRegistry<Handle> { released += it }
        val pooled = Handle("s0")
        val leaked = Handle("s1")
        registry.track(pooled)
        registry.track(leaked)

        // 同 equals 但不同实例: 不得把 pooled 认成"已提交"
        assertFalse("提交必须按身份判定, equals 相等的另一实例不能算提交", registry.commit(Handle("s0")))
        assertTrue(registry.commit(pooled))

        registry.releaseUncommitted()

        assertEquals("只有未提交的那条被释放", listOf(leaked), released)
    }

    @Test
    fun `releaseUncommitted releases every pending session exactly once`() {
        val released = CopyOnWriteArrayList<Handle>()
        val registry = SessionCleanupRegistry<Handle> { released += it }
        val a = Handle("s0")
        val b = Handle("s1")
        registry.track(a)
        registry.track(b)

        registry.releaseUncommitted()
        registry.releaseUncommitted()
        registry.releaseUncommitted()

        assertEquals("取消后全部释放, 且幂等 (重复调用不得二次 disconnect)", listOf(a, b), released)
    }
}
