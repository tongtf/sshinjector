package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsSuggestionStoreTest {
    private val note = SuggestionNote(connCount = 128, downBytes = 4096L, failedConnCount = 0)
    private val otherNote = SuggestionNote(connCount = 1, downBytes = 0L, failedConnCount = 1)

    @Test
    fun `toggle adds add-direction suggestion when not in list`() {
        val store = StatsSuggestionStore()
        assertTrue(store.toggle("a.com", SuggestionTarget.AD_BLOCK, currentlyInList = false, note = note))
        val only = store.state.value.single()
        assertEquals("a.com", only.domain)
        assertEquals(SuggestionTarget.AD_BLOCK, only.target)
        assertEquals(SuggestionDirection.ADD, only.direction)
        assertEquals(note, only.note)
    }

    @Test
    fun `toggle adds remove-direction suggestion when already in list`() {
        val store = StatsSuggestionStore()
        assertTrue(store.toggle("a.com", SuggestionTarget.AD_BLOCK, currentlyInList = true, note = note))
        val only = store.state.value.single()
        assertEquals(SuggestionDirection.REMOVE, only.direction)
    }

    @Test
    fun `toggle again cancels the pending suggestion`() {
        val store = StatsSuggestionStore()
        store.toggle("a.com", SuggestionTarget.AD_BLOCK, currentlyInList = false, note = note)
        assertFalse(store.toggle("a.com", SuggestionTarget.AD_BLOCK, currentlyInList = false, note = note))
        assertTrue(store.state.value.isEmpty())
    }

    @Test
    fun `same domain can be pending for both targets independently`() {
        val store = StatsSuggestionStore()
        store.toggle("a.com", SuggestionTarget.AD_BLOCK, currentlyInList = false, note = note)
        store.toggle("a.com", SuggestionTarget.DOMAIN_LIST, currentlyInList = true, note = note)
        assertEquals(2, store.state.value.size)
        assertEquals(SuggestionDirection.ADD, store.pendingDirection("a.com", SuggestionTarget.AD_BLOCK))
        assertEquals(SuggestionDirection.REMOVE, store.pendingDirection("a.com", SuggestionTarget.DOMAIN_LIST))
    }

    @Test
    fun `put replaces the existing entry for the same pair`() {
        val store = StatsSuggestionStore()
        store.put("a.com", SuggestionTarget.DOMAIN_LIST, SuggestionDirection.ADD, note)
        store.put("a.com", SuggestionTarget.DOMAIN_LIST, SuggestionDirection.REMOVE, otherNote)
        val only = store.state.value.single()
        assertEquals(SuggestionDirection.REMOVE, only.direction)
        assertEquals(otherNote, only.note)
    }

    @Test
    fun `remove and clear work per pair`() {
        val store = StatsSuggestionStore()
        store.put("a.com", SuggestionTarget.AD_BLOCK, SuggestionDirection.ADD, note)
        store.put("b.com", SuggestionTarget.AD_BLOCK, SuggestionDirection.ADD, note)
        store.remove("a.com", SuggestionTarget.AD_BLOCK)
        assertEquals(listOf("b.com"), store.state.value.map { it.domain })
        store.clear()
        assertTrue(store.state.value.isEmpty())
    }

    @Test
    fun `domains are normalized and blank domains ignored`() {
        val store = StatsSuggestionStore()
        store.toggle("  Example.COM.  ", SuggestionTarget.AD_BLOCK, currentlyInList = false, note = note)
        val only = store.state.value.single()
        assertEquals("example.com", only.domain)
        // 规范化后再点一次 = 取消 (键一致)
        assertFalse(store.toggle("example.com", SuggestionTarget.AD_BLOCK, currentlyInList = false, note = note))
        assertFalse(store.toggle("   ", SuggestionTarget.AD_BLOCK, currentlyInList = false, note = note))
        assertTrue(store.state.value.isEmpty())
    }

    @Test
    fun `pendingDirection returns null when nothing pending`() {
        val store = StatsSuggestionStore()
        assertNull(store.pendingDirection("a.com", SuggestionTarget.AD_BLOCK))
        store.put("a.com", SuggestionTarget.AD_BLOCK, SuggestionDirection.ADD, note)
        assertNull(store.pendingDirection("a.com", SuggestionTarget.DOMAIN_LIST))
        assertNull(store.pendingDirection("b.com", SuggestionTarget.AD_BLOCK))
    }
}
