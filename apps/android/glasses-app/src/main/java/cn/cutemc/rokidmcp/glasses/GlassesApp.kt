package cn.cutemc.rokidmcp.glasses

import android.app.Application
import cn.cutemc.rokidmcp.glasses.gateway.GlassesRuntimeStore
import cn.cutemc.rokidmcp.glasses.logging.GlassesLoggerBootstrap
import cn.cutemc.rokidmcp.glasses.renderer.DisplayStateStore
import cn.cutemc.rokidmcp.glasses.logging.GlassesUiLogStore
import timber.log.Timber

class GlassesApp : Application() {
    val logStore = GlassesUiLogStore()
    val runtimeStore = GlassesRuntimeStore()
    val displayStateStore = DisplayStateStore()

    override fun onCreate() {
        super.onCreate()
        Timber.tag("glasses-app").i("app startup")
        GlassesLoggerBootstrap.initialize(logStore)
    }
}
