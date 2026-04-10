package cn.cutemc.rokidmcp.phone.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
