package cn.cutemc.rokidmcp.glasses.gateway

class GlassesAppController(
    private val runtimeStore: GlassesRuntimeStore,
) {
    suspend fun start() {
        applyTransportState(GlassesTransportState.LISTENING)
    }

    suspend fun stop(reason: String) {
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.DISCONNECTED,
                lastErrorMessage = null,
            ),
        )
    }

    fun applyTransportState(state: GlassesTransportState) {
        val nextRuntime = when (state) {
            GlassesTransportState.IDLE,
            GlassesTransportState.DISCONNECTED,
            -> GlassesRuntimeState.DISCONNECTED
            GlassesTransportState.LISTENING -> GlassesRuntimeState.CONNECTING
            GlassesTransportState.CONNECTED -> GlassesRuntimeState.CONNECTING
            GlassesTransportState.ERROR -> GlassesRuntimeState.ERROR
        }

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
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.READY,
                lastErrorMessage = null,
            ),
        )
    }

    fun markFailure(errorMessage: String) {
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.ERROR,
                lastErrorMessage = errorMessage,
            ),
        )
    }

    fun markDisconnected() {
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = GlassesRuntimeState.DISCONNECTED,
                lastErrorMessage = null,
            ),
        )
    }
}
