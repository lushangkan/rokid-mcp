package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.logging.PhoneLogLevel
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogTree
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber

class PhoneAppControllerTest {
    @After
    fun tearDown() {
        Timber.uprootAll()
    }

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
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val controllerLogStore = PhoneLogStore(logStore)
        Timber.plant(PhoneUiLogTree(logStore))
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = controllerLogStore,
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
        assertEquals(1_717_171_800L, logStore.entries.value.single().timestampMs)
        assertEquals(PhoneLogLevel.ERROR, logStore.entries.value.single().level)
        assertEquals("controller", logStore.entries.value.single().tag)
    }

    @Test
    fun `start uses preloaded config when provided`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                fail("loadConfig should not be called when preloaded config is provided")
                PhoneGatewayConfig(
                    deviceId = "phone-ab12cd34",
                    authToken = null,
                    relayBaseUrl = null,
                    appVersion = "1.0",
                )
            },
        )

        controller.start(
            targetDeviceAddress = "00:11:22:33:44:55",
            preloadedConfig = PhoneGatewayConfig(
                deviceId = "phone-ab12cd34",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
        )

        assertEquals(GatewayRunState.STARTING, controller.runState.value)
    }
}
