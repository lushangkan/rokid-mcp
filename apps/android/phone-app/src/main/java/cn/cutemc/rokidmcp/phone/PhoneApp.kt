package cn.cutemc.rokidmcp.phone

import android.app.Application
import cn.cutemc.rokidmcp.phone.logging.PhoneLoggerBootstrap
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore

class PhoneApp : Application() {
    val logStore = PhoneUiLogStore()

    override fun onCreate() {
        super.onCreate()
        PhoneLoggerBootstrap.initialize(logStore)
    }
}
