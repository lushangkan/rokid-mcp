package cn.cutemc.rokidmcp.glasses.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusiveExecutionGuardTest {
    @Test
    fun `tryAcquire stores the current request when idle`() {
        val guard = ExclusiveExecutionGuard()

        assertTrue(guard.tryAcquire("req_1"))
        assertTrue(guard.isBusy())
        assertEquals("req_1", guard.currentRequestId())
    }

    @Test
    fun `tryAcquire rejects a second active request`() {
        val guard = ExclusiveExecutionGuard()
        guard.tryAcquire("req_1")

        assertFalse(guard.tryAcquire("req_2"))
        assertEquals("req_1", guard.currentRequestId())
    }

    @Test
    fun `release rejects mismatched request ids`() {
        val guard = ExclusiveExecutionGuard()
        guard.tryAcquire("req_1")

        assertThrows(IllegalStateException::class.java) {
            guard.release("req_2")
        }
    }
}
