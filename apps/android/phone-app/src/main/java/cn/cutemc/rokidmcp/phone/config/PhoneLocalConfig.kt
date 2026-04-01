package cn.cutemc.rokidmcp.phone.config

import java.util.UUID

data class PhoneLocalConfig(
    val deviceId: String,
    val authToken: String?,
    val relayBaseUrl: String?,
) {
    companion object {
        private val DEVICE_ID_REGEX = Regex("^[a-z0-9-]{8,40}$")

        fun default(generateSuffix: () -> String = { UUID.randomUUID().toString().replace("-", "").take(8) }): PhoneLocalConfig {
            val generatedDeviceId = "phone-${generateSuffix().lowercase()}"
            return PhoneLocalConfig(
                deviceId = generatedDeviceId,
                authToken = null,
                relayBaseUrl = null,
            )
        }

        fun isValidDeviceId(value: String): Boolean {
            return DEVICE_ID_REGEX.matches(value)
        }
    }
}
