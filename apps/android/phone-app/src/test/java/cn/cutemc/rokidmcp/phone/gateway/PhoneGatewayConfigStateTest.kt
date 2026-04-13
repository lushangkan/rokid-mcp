package cn.cutemc.rokidmcp.phone.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneGatewayConfigStateTest {
    private fun makeState(name: String): PhoneGatewayConfigState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return PhoneGatewayConfigState(
            localConfigStore = PhoneLocalConfigStore(prefs),
            gatewayAppVersion = "1.0",
        )
    }

    @Test
    fun `load exposes persisted config as gateway config`() {
        val state = makeState("gateway_config_state_load")

        val config = state.load()

        assertTrue(config.deviceId.startsWith("phone-"))
        assertNull(config.authToken)
        assertNull(config.relayBaseUrl)
        assertEquals("1.0", config.appVersion)
        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, config.reconnectDelayMs)
    }

    @Test
    fun `update persists normalized config and returns runtime config`() {
        val state = makeState("gateway_config_state_update")

        val saved = state.update(
            deviceId = "phone-ab12cd34",
            authToken = "token-123",
            relayBaseUrl = "https://relay.example.com",
        )

        assertEquals("phone-ab12cd34", saved.deviceId)
        assertEquals("token-123", saved.authToken)
        assertEquals("https://relay.example.com", saved.relayBaseUrl)
        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, saved.reconnectDelayMs)
        assertEquals(saved, state.load())
    }

    @Test
    fun `update clears blank auth and relay values before persisting`() {
        val state = makeState("gateway_config_state_blank_normalization")

        state.update(
            deviceId = "phone-ab12cd34",
            authToken = "",
            relayBaseUrl = " ",
        )

        val loaded = state.load()
        assertEquals("phone-ab12cd34", loaded.deviceId)
        assertNull(loaded.authToken)
        assertNull(loaded.relayBaseUrl)
        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, loaded.reconnectDelayMs)
    }

    @Test
    fun `update from intent config persists values`() {
        val state = makeState("gateway_config_state_intent")

        val saved = state.update(
            PhoneGatewayIntentConfig(
                deviceId = "phone-ab12cd34",
                authToken = "token-123",
                relayBaseUrl = "https://relay.example.com",
            ),
        )

        assertEquals(saved, state.load())
    }

    @Test
    fun `update preserves persisted target device address`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("gateway_config_state_target", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = PhoneLocalConfigStore(prefs)
        store.save(
            PhoneLocalConfig(
                deviceId = "phone-ab12cd34",
                authToken = "token-123",
                relayBaseUrl = "https://relay.example.com",
                reconnectDelayMs = 9_000L,
                targetDeviceAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        val state = PhoneGatewayConfigState(
            localConfigStore = store,
            gatewayAppVersion = "1.0",
        )

        state.update(
            deviceId = "phone-deadbeef",
            authToken = "token-456",
            relayBaseUrl = "https://relay.example.org",
            reconnectDelayMs = 10_000L,
        )

        assertEquals("AA:BB:CC:DD:EE:FF", store.load().targetDeviceAddress)
    }
}
