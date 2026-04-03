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
    val uiLogStore = PhoneUiLogStore()
    val logStore = PhoneLogStore(uiLogStore)
    val localConfigStore: PhoneLocalConfigStore by lazy {
        PhoneLocalConfigStore(getSharedPreferences("phone_local_config", MODE_PRIVATE))
    }
    val appController: PhoneAppController by lazy {
        PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = logStore,
            loadConfig = {
                val local = localConfigStore.load()
                PhoneGatewayConfig(
                    deviceId = local.deviceId,
                    authToken = local.authToken,
                    relayBaseUrl = local.relayBaseUrl,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        PhoneLoggerBootstrap.initialize(uiLogStore)
    }
}
