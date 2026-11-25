package cn.srv0.sshinjector.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除服务器后的默认服务器提升与开机自启清理。 */
class ServerDeletionPlanTest {
    @Test
    fun `deleting the active server promotes the first remaining one`() {
        val plan = serverDeletionPlan(wasActive = true, remainingIds = listOf(3, 4), lastServerId = 2, deletedId = 2)
        assertEquals(3L, plan.nextActiveId)
        assertTrue(plan.clearLastServerId)
    }

    @Test
    fun `deleting a non active server keeps lastServerId`() {
        val plan = serverDeletionPlan(wasActive = false, remainingIds = listOf(1), lastServerId = 2, deletedId = 5)
        assertNull(plan.nextActiveId)
        assertFalse(plan.clearLastServerId)
    }

    @Test
    fun `deleting the last remaining server clears lastServerId and has no successor`() {
        val plan = serverDeletionPlan(wasActive = true, remainingIds = emptyList(), lastServerId = 7, deletedId = 7)
        assertNull(plan.nextActiveId)
        assertTrue(plan.clearLastServerId)
    }
}
