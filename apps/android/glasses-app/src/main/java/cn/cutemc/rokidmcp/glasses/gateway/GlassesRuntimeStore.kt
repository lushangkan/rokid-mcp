package cn.cutemc.rokidmcp.glasses.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GlassesRuntimeState {
    DISCONNECTED,
    CONNECTING,
    READY,
    ERROR,
}

data class GlassesRuntimeSnapshot(
    val runtimeState: GlassesRuntimeState = GlassesRuntimeState.DISCONNECTED,
    val lastErrorMessage: String? = null,
    val lastUpdatedAt: Long = 0L,
)

class GlassesRuntimeStore {
    private val _snapshot = MutableStateFlow(GlassesRuntimeSnapshot())
    val snapshot: StateFlow<GlassesRuntimeSnapshot> = _snapshot

    internal fun replace(next: GlassesRuntimeSnapshot) {
        _snapshot.value = next
    }
}
