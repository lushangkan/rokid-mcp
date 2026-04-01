package cn.cutemc.rokidmcp.phone.config

import java.io.File
import java.util.Properties

class PhoneLocalConfigStore(
    private val filesDirProvider: () -> File,
) {
    private val configFileName = "phone-local-config.properties"
    private val legacyConfigFileName = "phone-local-config.json"

    fun load(): PhoneLocalConfig {
        val configFile = configFile()
        if (!configFile.exists()) {
            val legacyFile = legacyConfigFile()
            if (legacyFile.exists()) {
                return loadFromFile(legacyFile, migrateToCurrent = true)
            }
            val defaultConfig = PhoneLocalConfig.default()
            save(defaultConfig)
            return defaultConfig
        }

        return loadFromFile(configFile)
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

    private fun legacyConfigFile(): File {
        return File(filesDirProvider(), legacyConfigFileName)
    }

    private fun loadFromFile(sourceFile: File, migrateToCurrent: Boolean = false): PhoneLocalConfig {
        val loaded = runCatching {
            val properties = Properties().apply {
                sourceFile.inputStream().use { load(it) }
            }
            PhoneLocalConfig(
                deviceId = properties.getProperty("deviceId") ?: "",
                authToken = properties.getProperty("authToken")?.ifBlank { null },
                relayBaseUrl = properties.getProperty("relayBaseUrl")?.ifBlank { null },
            )
        }.getOrNull()

        val isLoadedValid = loaded != null && PhoneLocalConfig.isValidDeviceId(loaded.deviceId)
        val recovered = if (isLoadedValid) {
            loaded
        } else {
            PhoneLocalConfig.default()
        }

        if (migrateToCurrent || !isLoadedValid) {
            save(recovered)
        }

        return recovered
    }
}
