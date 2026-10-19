package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainSuffixSetTest {
    @Test
    fun `normalize trims lowercases and strips trailing dots`() {
        assertEquals("example.com", DomainSuffixSet.normalize("  Example.COM.  "))
        assertEquals("", DomainSuffixSet.normalize("   "))
        assertEquals("1.2.3.4", DomainSuffixSet.normalize("1.2.3.4."))
    }

    @Test
    fun `exact domain and subdomains match`() {
        val set = setOf("example.com")
        assertTrue(DomainSuffixSet.matches(set, "example.com"))
        assertTrue(DomainSuffixSet.matches(set, "a.example.com"))
        assertTrue(DomainSuffixSet.matches(set, "a.b.example.com"))
        assertTrue(DomainSuffixSet.matches(set, "EXAMPLE.com"))
        assertTrue(DomainSuffixSet.matches(set, "example.com."))
    }

    @Test
    fun `lookalike domains must not match`() {
        val set = setOf("example.com")
        assertFalse(DomainSuffixSet.matches(set, "notexample.com"))
        assertFalse(DomainSuffixSet.matches(set, "example.com.evil.net"))
        assertFalse(DomainSuffixSet.matches(set, "example.co"))
    }

    @Test
    fun `empty set or blank host never matches`() {
        assertFalse(DomainSuffixSet.matches(emptySet(), "example.com"))
        assertFalse(DomainSuffixSet.matches(setOf("example.com"), ""))
        assertFalse(DomainSuffixSet.matches(setOf("example.com"), "   "))
    }

    @Test
    fun `multiple entries and parent-domain entries behave like gfwlist suffixes`() {
        val set = setOf("example.com", "sub.other.net")
        assertTrue(DomainSuffixSet.matches(set, "x.example.com"))
        assertTrue(DomainSuffixSet.matches(set, "sub.other.net"))
        assertFalse(DomainSuffixSet.matches(set, "other.net"))
    }
}
