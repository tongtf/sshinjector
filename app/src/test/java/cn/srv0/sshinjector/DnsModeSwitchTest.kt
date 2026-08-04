package cn.srv0.sshinjector

import cn.srv0.sshinjector.ui.viewmodel.nextDnsMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 连接模式切换循环逻辑的单元测试
 */
class DnsModeSwitchTest {

    @Test
    fun `nextDnsMode cycles through all four modes`() {
        assertEquals(1, nextDnsMode(0)) // REMOTE -> SYSTEM
        assertEquals(2, nextDnsMode(1)) // SYSTEM -> WHITELIST
        assertEquals(3, nextDnsMode(2)) // WHITELIST -> DOMAIN_SPLIT
        assertEquals(0, nextDnsMode(3)) // DOMAIN_SPLIT -> REMOTE (wrap)
    }

    @Test
    fun `nextDnsMode handles wrap-around for value four`() {
        assertEquals(1, nextDnsMode(4)) // 4 % 4 -> 0, then +1? No: (4+1)%4=1
    }

    @Test
    fun `nextDnsMode is defined for all real mode values`() {
        for (mode in 0..3) {
            val next = nextDnsMode(mode)
            assert(next in 0..3)
        }
    }
}
