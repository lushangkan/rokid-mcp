package cn.cutemc.rokidmcp.phone.config

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneLocalConfigStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: PhoneLocalConfigStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("test_phone_local_config", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PhoneLocalConfigStore(prefs)
    }

    @Test
    fun `load without existing data creates default config with valid deviceId`() {
        val config = store.load()

        assertNotNull(config.deviceId)
        assertTrue(PhoneLocalConfig.isValidDeviceId(config.deviceId))
        assertNull(config.authToken)
        assertNull(config.relayBaseUrl)
        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, config.reconnectDelayMs)
    }

    @Test
    fun `default config uses default reconnect delay`() {
        val config = PhoneLocalConfig.default()

        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, config.reconnectDelayMs)
    }

    @Test
    fun `save then load returns persisted config`() {
        val expected = PhoneLocalConfig(
            deviceId = "phone-1234abcd",
            authToken = "token-abc",
            relayBaseUrl = "https://relay.example.com",
            reconnectDelayMs = 12_345L,
        )

        store.save(expected)
        val loaded = store.load()

        assertEquals(expected, loaded)
    }

    @Test
    fun `save persists auth token under existing authToken key`() {
        store.save(
            PhoneLocalConfig(
                deviceId = "phone-1234abcd",
                authToken = "token-abc",
                relayBaseUrl = "https://relay.example.com",
            ),
        )

        assertEquals("token-abc", prefs.getString("authToken", null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `save rejects invalid deviceId`() {
        store.save(
            PhoneLocalConfig(
                deviceId = "bad id",
                authToken = null,
                relayBaseUrl = null,
            ),
        )
    }

    @Test
    fun `deviceId validation accepts expected format`() {
        assertTrue(PhoneLocalConfig.isValidDeviceId("phone-ab12cd34"))
        assertTrue(PhoneLocalConfig.isValidDeviceId("abc12345"))
        assertTrue(PhoneLocalConfig.isValidDeviceId("node-01-abcdef"))
    }

    @Test
    fun `load recovers with default when stored deviceId is invalid`() {
        prefs.edit().putString("deviceId", "bad id!!").commit()

        val config = store.load()

        assertTrue(PhoneLocalConfig.isValidDeviceId(config.deviceId))
        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, config.reconnectDelayMs)
        val reloaded = store.load()
        assertEquals(config, reloaded)
    }

    @Test
    fun `load recovers with default when deviceId is missing but other fields are present`() {
        prefs.edit()
            .putString("authToken", "token-abc")
            .putString("relayBaseUrl", "https://relay.example.com")
            .commit()

        val config = store.load()

        assertTrue(PhoneLocalConfig.isValidDeviceId(config.deviceId))
        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, config.reconnectDelayMs)
    }

    @Test
    fun `load falls back to default reconnect delay when stored value is missing`() {
        prefs.edit().putString("deviceId", "phone-ab12cd34").commit()

        val config = store.load()

        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, config.reconnectDelayMs)
    }

    @Test
    fun `load falls back to default reconnect delay when stored value is invalid`() {
        prefs.edit()
            .putString("deviceId", "phone-ab12cd34").commit()
        prefs.edit()
            .putString("reconnectDelayMs", "NaN")
            .commit()

        val config = store.load()

        assertEquals(PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS, config.reconnectDelayMs)
    }
}
