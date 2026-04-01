package cn.cutemc.rokidmcp.glasses.gateway

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesAppControllerTest {
    @Test
    fun `runtime store starts disconnected`() = runTest {
        val store = GlassesRuntimeStore()
        val snapshot = store.snapshot.value

        assertEquals(GlassesRuntimeState.DISCONNECTED, snapshot.runtimeState)
    }
}
