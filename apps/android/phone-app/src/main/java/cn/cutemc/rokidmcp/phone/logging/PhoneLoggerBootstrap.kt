package cn.cutemc.rokidmcp.phone.logging

import timber.log.Timber

object PhoneLoggerBootstrap {
    private var isInitialized = false

    @Synchronized
    fun initialize(logStore: PhoneUiLogStore) {
        if (isInitialized) {
            return
        }

        Timber.plant(Timber.DebugTree())
        Timber.plant(PhoneUiLogTree(logStore))
        isInitialized = true
    }
}
