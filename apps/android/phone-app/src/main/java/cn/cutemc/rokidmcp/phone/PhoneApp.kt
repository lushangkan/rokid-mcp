package cn.cutemc.rokidmcp.phone

import android.app.Application
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import cn.cutemc.rokidmcp.phone.gateway.PhoneAppController
import cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneLogStore
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeStore
import cn.cutemc.rokidmcp.phone.logging.PhoneLoggerBootstrap
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore

class PhoneApp : Application() {
    val gatewayAppVersion: String = BuildConfig.VERSION_NAME
    val uiLogStore = PhoneUiLogStore()
    val logStore = PhoneLogStore(uiLogStore)
    val localConfigStore: PhoneLocalConfigStore by lazy {
        PhoneLocalConfigStore(getSharedPreferences("phone_local_config", MODE_PRIVATE))
    }
    val appController: PhoneAppController by lazy {
        createActivePhoneAppController(
            localConfigStore = localConfigStore,
            logStore = logStore,
            gatewayAppVersion = gatewayAppVersion,
        )
    }

    override fun onCreate() {
        super.onCreate()
        PhoneLoggerBootstrap.initialize(uiLogStore)
    }
}

internal fun createActivePhoneAppController(
    localConfigStore: PhoneLocalConfigStore,
    logStore: PhoneLogStore,
    gatewayAppVersion: String,
): PhoneAppController = PhoneAppController(
    runtimeStore = PhoneRuntimeStore(),
    logStore = logStore,
    loadConfig = {
        val local = localConfigStore.load()
        PhoneGatewayConfig(
            deviceId = local.deviceId,
            authToken = local.authToken,
            relayBaseUrl = local.relayBaseUrl,
            appVersion = gatewayAppVersion,
        )
    },
)
