package cn.cutemc.rokidmcp.glasses.logging

import cn.cutemc.rokidmcp.glasses.BuildConfig
import timber.log.Timber

object GlassesLoggerBootstrap {
    private var isInitialized = false

    @Synchronized
    fun initialize(logStore: GlassesUiLogStore) {
        if (isInitialized) {
            return
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(GlassesUiLogTree(logStore))
        isInitialized = true
    }
}
