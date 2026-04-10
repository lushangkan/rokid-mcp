package cn.cutemc.rokidmcp.phone.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.cutemc.rokidmcp.phone.PhoneApp
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import kotlinx.coroutines.launch

class PhoneGatewayService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> lifecycleScope.launch {
                if (!hasBluetoothConnectPermission()) {
                    stopSelf(startId)
                    return@launch
                }

                val targetDeviceAddress = requireNotNull(intent.getStringExtra(EXTRA_TARGET_DEVICE_ADDRESS)) {
                    "phone gateway start intent requires targetDeviceAddress"
                }
                val providedConfig = intent.toGatewayConfigOrNull()
                val phoneApp = application as PhoneApp
                providedConfig?.let { config ->
                    phoneApp.localConfigStore.save(
                        PhoneLocalConfig(
                            deviceId = config.deviceId,
                            authToken = config.authToken,
                            relayBaseUrl = config.relayBaseUrl,
                        ),
                    )
                }
                phoneApp.appController.start(
                    targetDeviceAddress = targetDeviceAddress,
                    preloadedConfig = providedConfig?.toGatewayRuntimeConfig(appVersion = phoneApp.gatewayAppVersion),
                )
            }

            ACTION_STOP -> lifecycleScope.launch {
                val reason = intent.getStringExtra(EXTRA_STOP_REASON) ?: "service-stop"
                (application as PhoneApp).appController.stop(reason)
                stopSelf(startId)
            }
        }

        return if (intent?.action == ACTION_START && !hasBluetoothConnectPermission()) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onDestroy() {
        lifecycleScope.launch {
            (application as PhoneApp).appController.stop("service-destroyed")
        }
        super.onDestroy()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_START = "cn.cutemc.rokidmcp.phone.gateway.action.START"
        const val ACTION_STOP = "cn.cutemc.rokidmcp.phone.gateway.action.STOP"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_AUTH_TOKEN = "authToken"
        const val EXTRA_RELAY_BASE_URL = "relayBaseUrl"
        const val EXTRA_TARGET_DEVICE_ADDRESS = "targetDeviceAddress"
        const val EXTRA_STOP_REASON = "stopReason"

        fun createStartIntent(
            context: Context,
            targetDeviceAddress: String,
            config: PhoneGatewayIntentConfig? = null,
        ): Intent = Intent(context, PhoneGatewayService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_DEVICE_ADDRESS, targetDeviceAddress)
            .apply {
                config?.deviceId?.let { putExtra(EXTRA_DEVICE_ID, it) }
                config?.authToken?.let { putExtra(EXTRA_AUTH_TOKEN, it) }
                config?.relayBaseUrl?.let { putExtra(EXTRA_RELAY_BASE_URL, it) }
            }

        fun createStopIntent(context: Context, reason: String = "service-stop"): Intent =
            Intent(context, PhoneGatewayService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_REASON, reason)
    }
}

data class PhoneGatewayIntentConfig(
    val deviceId: String,
    val authToken: String,
    val relayBaseUrl: String,
)

internal fun Intent.toGatewayConfigOrNull(): PhoneGatewayIntentConfig? {
    return gatewayConfigFromExtras(
        deviceId = getStringExtra(PhoneGatewayService.EXTRA_DEVICE_ID),
        authToken = getStringExtra(PhoneGatewayService.EXTRA_AUTH_TOKEN),
        relayBaseUrl = getStringExtra(PhoneGatewayService.EXTRA_RELAY_BASE_URL),
    )
}

internal fun gatewayConfigFromExtras(
    deviceId: String?,
    authToken: String?,
    relayBaseUrl: String?,
): PhoneGatewayIntentConfig? {
    if (deviceId.isNullOrBlank() || authToken.isNullOrBlank() || relayBaseUrl.isNullOrBlank()) {
        return null
    }

    return PhoneGatewayIntentConfig(
        deviceId = deviceId,
        authToken = authToken,
        relayBaseUrl = relayBaseUrl,
    )
}

private fun PhoneGatewayIntentConfig.toGatewayRuntimeConfig(appVersion: String): PhoneGatewayConfig = PhoneGatewayConfig(
    deviceId = deviceId,
    authToken = authToken,
    relayBaseUrl = relayBaseUrl,
    appVersion = appVersion,
)
