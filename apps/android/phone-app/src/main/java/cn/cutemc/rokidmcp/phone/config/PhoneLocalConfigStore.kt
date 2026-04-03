package cn.cutemc.rokidmcp.phone.config

import android.content.SharedPreferences

class PhoneLocalConfigStore(
    private val prefs: SharedPreferences,
) {
    fun load(): PhoneLocalConfig {
        val deviceId = prefs.getString("deviceId", null)
        val authToken = prefs.getString("authToken", null)
        val relayBaseUrl = prefs.getString("relayBaseUrl", null)

        val isLoadedValid = deviceId != null && PhoneLocalConfig.isValidDeviceId(deviceId)

        if (isLoadedValid) {
            return PhoneLocalConfig(
                deviceId = deviceId!!,
                authToken = authToken?.ifBlank { null },
                relayBaseUrl = relayBaseUrl?.ifBlank { null },
            )
        }

        val defaultConfig = PhoneLocalConfig.default()
        save(defaultConfig)
        return defaultConfig
    }

    fun save(config: PhoneLocalConfig) {
        require(PhoneLocalConfig.isValidDeviceId(config.deviceId)) {
            "deviceId format is invalid"
        }

        prefs.edit()
            .putString("deviceId", config.deviceId)
            .putString("authToken", config.authToken ?: "")
            .putString("relayBaseUrl", config.relayBaseUrl ?: "")
            .apply()
    }
}
