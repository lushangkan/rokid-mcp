package cn.cutemc.rokidmcp.phone.config

import android.content.SharedPreferences

class PhoneLocalConfigStore(
    private val prefs: SharedPreferences,
) {
    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_AUTH_TOKEN = "authToken"
        const val KEY_RELAY_BASE_URL = "relayBaseUrl"
        const val KEY_RECONNECT_DELAY_MS = "reconnectDelayMs"
    }

    fun load(): PhoneLocalConfig {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null)
        val relayBaseUrl = prefs.getString(KEY_RELAY_BASE_URL, null)
        val reconnectDelayResolution = loadReconnectDelayMs()

        val isLoadedValid = deviceId != null && PhoneLocalConfig.isValidDeviceId(deviceId)

        if (isLoadedValid) {
            val normalizedConfig = PhoneLocalConfig(
                deviceId = deviceId!!,
                authToken = authToken?.ifBlank { null },
                relayBaseUrl = relayBaseUrl?.ifBlank { null },
                reconnectDelayMs = reconnectDelayResolution.value,
            )

            if (reconnectDelayResolution.shouldPersist) {
                save(normalizedConfig)
            }

            return normalizedConfig
        }

        val defaultConfig = PhoneLocalConfig.default()
        save(defaultConfig)
        return defaultConfig
    }

    fun save(config: PhoneLocalConfig) {
        require(PhoneLocalConfig.isValidDeviceId(config.deviceId)) {
            "deviceId format is invalid"
        }

        require(PhoneLocalConfig.isValidReconnectDelayMs(config.reconnectDelayMs)) {
            "reconnectDelayMs must be positive"
        }

        prefs.edit()
            .putString(KEY_DEVICE_ID, config.deviceId)
            .putString(KEY_AUTH_TOKEN, config.authToken ?: "")
            .putString(KEY_RELAY_BASE_URL, config.relayBaseUrl ?: "")
            .putLong(KEY_RECONNECT_DELAY_MS, config.reconnectDelayMs)
            .apply()
    }

    private fun loadReconnectDelayMs(): ReconnectDelayResolution {
        val rawValue = prefs.all[KEY_RECONNECT_DELAY_MS]
        val parsedValue = when (rawValue) {
            is Long -> rawValue
            is Int -> rawValue.toLong()
            is Short -> rawValue.toLong()
            is Byte -> rawValue.toLong()
            is Float -> if (rawValue.isFinite()) rawValue.toLong() else null
            is Double -> if (rawValue.isFinite()) rawValue.toLong() else null
            is String -> rawValue.toLongOrNull()
            else -> null
        }

        val normalizedValue = parsedValue?.takeIf(PhoneLocalConfig::isValidReconnectDelayMs)
            ?: PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS

        return ReconnectDelayResolution(
            value = normalizedValue,
            shouldPersist = rawValue == null || normalizedValue != parsedValue,
        )
    }

    private data class ReconnectDelayResolution(
        val value: Long,
        val shouldPersist: Boolean,
    )
}
