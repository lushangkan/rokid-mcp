package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAppControllerTest {
    @Test
    fun `runtime store starts disconnected and offline`() = runTest {
        val store = PhoneRuntimeStore()
        val snapshot = store.snapshot.value

        assertEquals(PhoneSetupState.UNINITIALIZED, snapshot.setupState)
        assertEquals(PhoneRuntimeState.DISCONNECTED, snapshot.runtimeState)
        assertEquals(PhoneUplinkState.OFFLINE, snapshot.uplinkState)
    }

    @Test
    fun `start without required config records startup error and does not run`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneLogStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = logStore,
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "abc12345",
                    authToken = null,
                    relayBaseUrl = null,
                    appVersion = "1.0",
                )
            },
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")

        assertEquals(GatewayRunState.ERROR, controller.runState.value)
        assertEquals("PHONE_CONFIG_INCOMPLETE", runtimeStore.snapshot.value.lastErrorCode)
        assertTrue(logStore.entries.value.any { it.message.contains("missing relay config") })
    }
}
