package cn.cutemc.rokidmcp.glasses.logging

import timber.log.Timber

object GlassesLoggerBootstrap {
    private var isInitialized = false

    @Synchronized
    fun initialize(logStore: GlassesUiLogStore) {
        if (isInitialized) {
            return
        }

        Timber.plant(Timber.DebugTree())
        Timber.plant(GlassesUiLogTree(logStore))
        isInitialized = true
    }
}
