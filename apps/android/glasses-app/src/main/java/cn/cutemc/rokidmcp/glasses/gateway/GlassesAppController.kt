package cn.cutemc.rokidmcp.glasses.gateway

import timber.log.Timber

class GlassesAppController(
    private val runtimeStore: GlassesRuntimeStore,
) {
    suspend fun start() {
        Timber.tag("glasses-controller").i("controller start")
        applyTransportState(GlassesTransportState.LISTENING)
    }

    suspend fun stop(reason: String) {
        Timber.tag("glasses-controller").i("controller stop reason=%s", reason)
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.DISCONNECTED,
                lastErrorMessage = null,
            ),
        )
    }

    fun applyTransportState(state: GlassesTransportState) {
        val currentRuntime = runtimeStore.snapshot.value.runtimeState
        val nextRuntime = when (state) {
            GlassesTransportState.IDLE,
            GlassesTransportState.DISCONNECTED,
            -> GlassesRuntimeState.DISCONNECTED
            GlassesTransportState.LISTENING -> GlassesRuntimeState.CONNECTING
            GlassesTransportState.CONNECTED -> GlassesRuntimeState.CONNECTING
            GlassesTransportState.ERROR -> GlassesRuntimeState.ERROR
        }

        Timber.tag("glasses-controller").d("transport state %s -> %s", currentRuntime, nextRuntime)

        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = nextRuntime,
                lastErrorMessage = if (nextRuntime == GlassesRuntimeState.ERROR) {
                    runtimeStore.snapshot.value.lastErrorMessage
                } else {
                    null
                },
            ),
        )
    }

    fun markHelloAccepted() {
        Timber.tag("glasses-controller").i("hello accepted; runtime ready")
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.READY,
                lastErrorMessage = null,
            ),
        )
    }

    fun markFailure(errorMessage: String) {
        Timber.tag("glasses-controller").w("runtime failure: %s", errorMessage)
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.ERROR,
                lastErrorMessage = errorMessage,
            ),
        )
    }

    fun markDisconnected() {
        Timber.tag("glasses-controller").w("runtime disconnected")
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.DISCONNECTED,
                lastErrorMessage = null,
            ),
        )
    }
}
