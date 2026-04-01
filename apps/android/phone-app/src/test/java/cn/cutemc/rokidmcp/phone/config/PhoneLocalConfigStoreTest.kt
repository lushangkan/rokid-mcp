package cn.cutemc.rokidmcp.phone.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneLocalConfigStoreTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("phone-local-config-store-test").toFile()
    }

    @Test
    fun `load without existing file creates default config with valid deviceId`() {
        val store = PhoneLocalConfigStore(filesDirProvider = { tempDir })

        val config = store.load()

        assertNotNull(config.deviceId)
        assertTrue(PhoneLocalConfig.isValidDeviceId(config.deviceId))
        assertNull(config.authToken)
        assertNull(config.relayBaseUrl)
    }

    @Test
    fun `save then load returns persisted config`() {
        val store = PhoneLocalConfigStore(filesDirProvider = { tempDir })
        val expected = PhoneLocalConfig(
            deviceId = "phone-1234abcd",
            authToken = "token-abc",
            relayBaseUrl = "https://relay.example.com",
        )

        store.save(expected)
        val loaded = store.load()

        assertEquals(expected, loaded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `save rejects invalid deviceId`() {
        val store = PhoneLocalConfigStore(filesDirProvider = { tempDir })

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
}
