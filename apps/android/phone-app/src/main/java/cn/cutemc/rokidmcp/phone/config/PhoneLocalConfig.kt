package cn.cutemc.rokidmcp.phone.config

import java.util.UUID

data class PhoneLocalConfig(
    val deviceId: String,
    val authToken: String?,
    val relayBaseUrl: String?,
    val reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS,
    val targetDeviceAddress: String = DEFAULT_TARGET_DEVICE_ADDRESS,
) {
    companion object {
        const val DEFAULT_RECONNECT_DELAY_MS: Long = 5000L
        const val DEFAULT_TARGET_DEVICE_ADDRESS: String = "00:11:22:33:44:55"
        private val DEVICE_ID_REGEX = Regex("^[a-z0-9-]{8,40}$")
        private val TARGET_DEVICE_ADDRESS_REGEX = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun default(generateSuffix: () -> String = { UUID.randomUUID().toString().replace("-", "").take(8) }): PhoneLocalConfig {
            val generatedDeviceId = "phone-${generateSuffix().lowercase()}"
            return PhoneLocalConfig(
                deviceId = generatedDeviceId,
                authToken = null,
                relayBaseUrl = null,
                reconnectDelayMs = DEFAULT_RECONNECT_DELAY_MS,
                targetDeviceAddress = DEFAULT_TARGET_DEVICE_ADDRESS,
            )
        }

        fun isValidDeviceId(value: String): Boolean {
            return DEVICE_ID_REGEX.matches(value)
        }

        fun isValidReconnectDelayMs(value: Long): Boolean {
            return value > 0L
        }

        fun normalizeTargetDeviceAddress(value: String): String {
            return value.trim().uppercase()
        }

        fun isValidTargetDeviceAddress(value: String): Boolean {
            return TARGET_DEVICE_ADDRESS_REGEX.matches(normalizeTargetDeviceAddress(value))
        }
    }
}
