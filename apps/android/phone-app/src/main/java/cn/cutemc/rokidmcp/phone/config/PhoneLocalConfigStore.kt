package cn.cutemc.rokidmcp.phone.config

import java.io.File
import java.util.Properties

class PhoneLocalConfigStore(
    private val filesDirProvider: () -> File,
) {
    private val configFileName = "phone-local-config.json"

    fun load(): PhoneLocalConfig {
        val configFile = configFile()
        if (!configFile.exists()) {
            val defaultConfig = PhoneLocalConfig.default()
            save(defaultConfig)
            return defaultConfig
        }

        val properties = Properties().apply {
            configFile.inputStream().use { load(it) }
        }
        val loaded = PhoneLocalConfig(
            deviceId = properties.getProperty("deviceId") ?: "",
            authToken = properties.getProperty("authToken")?.ifBlank { null },
            relayBaseUrl = properties.getProperty("relayBaseUrl")?.ifBlank { null },
        )

        if (!PhoneLocalConfig.isValidDeviceId(loaded.deviceId)) {
            val defaultConfig = PhoneLocalConfig.default()
            save(defaultConfig)
            return defaultConfig
        }

        return loaded
    }

    fun save(config: PhoneLocalConfig) {
        require(PhoneLocalConfig.isValidDeviceId(config.deviceId)) {
            "deviceId format is invalid"
        }

        val properties = Properties().apply {
            setProperty("deviceId", config.deviceId)
            setProperty("authToken", config.authToken ?: "")
            setProperty("relayBaseUrl", config.relayBaseUrl ?: "")
        }
        configFile().outputStream().use { output ->
            properties.store(output, null)
        }
    }

    private fun configFile(): File {
        return File(filesDirProvider(), configFileName)
    }
}
