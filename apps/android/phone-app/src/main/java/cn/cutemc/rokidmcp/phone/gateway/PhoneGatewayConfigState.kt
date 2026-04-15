package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore

class PhoneGatewayConfigState(
    private val localConfigStore: PhoneLocalConfigStore,
    private val gatewayAppVersion: String,
) {
    fun load(): PhoneGatewayConfig = localConfigStore.load().toGatewayConfig()

    fun update(
        deviceId: String,
        authToken: String?,
        relayBaseUrl: String?,
        reconnectDelayMs: Long = localConfigStore.load().reconnectDelayMs,
    ): PhoneGatewayConfig {
        val currentConfig = localConfigStore.load()
        val local = PhoneLocalConfig(
            deviceId = deviceId,
            authToken = authToken?.ifBlank { null },
            relayBaseUrl = relayBaseUrl?.ifBlank { null },
            reconnectDelayMs = normalizeReconnectDelayMs(reconnectDelayMs),
            targetDeviceAddress = currentConfig.targetDeviceAddress,
        )
        localConfigStore.save(local)
        return local.toGatewayConfig()
    }

    fun update(config: PhoneGatewayIntentConfig): PhoneGatewayConfig {
        return update(
            deviceId = config.deviceId,
            authToken = config.authToken,
            relayBaseUrl = config.relayBaseUrl,
            reconnectDelayMs = config.reconnectDelayMs,
        )
    }

    private fun normalizeReconnectDelayMs(value: Long): Long {
        require(PhoneLocalConfig.isValidReconnectDelayMs(value)) {
            "reconnectDelayMs must be positive"
        }

        return value
    }

    private fun PhoneLocalConfig.toGatewayConfig(): PhoneGatewayConfig {
        return PhoneGatewayConfig(
            deviceId = deviceId,
            authToken = authToken,
            relayBaseUrl = relayBaseUrl,
            appVersion = gatewayAppVersion,
            reconnectDelayMs = reconnectDelayMs,
        )
    }
}
