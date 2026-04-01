package cn.cutemc.rokidmcp.phone.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

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

    @Test
    fun `load recovers with default when config content is corrupted`() {
        val store = PhoneLocalConfigStore(filesDirProvider = { tempDir })
        val corruptedFile = File(tempDir, "phone-local-config.properties")
        corruptedFile.writeText("deviceId=phone-ab12cd34\\u12")

        val config = store.load()

        assertTrue(PhoneLocalConfig.isValidDeviceId(config.deviceId))
        val reloaded = store.load()
        assertEquals(config, reloaded)
    }

    @Test
    fun `load supports legacy json filename with properties content`() {
        val store = PhoneLocalConfigStore(filesDirProvider = { tempDir })
        val legacyFile = File(tempDir, "phone-local-config.json")
        Properties().apply {
            setProperty("deviceId", "phone-ab12cd34")
            setProperty("authToken", "token-legacy")
            setProperty("relayBaseUrl", "https://relay.legacy")
            legacyFile.outputStream().use { store(it, null) }
        }

        val loaded = store.load()

        assertEquals("phone-ab12cd34", loaded.deviceId)
        assertEquals("token-legacy", loaded.authToken)
        assertEquals("https://relay.legacy", loaded.relayBaseUrl)
    }
}
