package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PhoneSetupState {
    UNINITIALIZED,
    INITIALIZED,
}

enum class PhoneRuntimeState {
    DISCONNECTED,
    CONNECTING,
    READY,
    BUSY,
    ERROR,
}

enum class PhoneUplinkState {
    OFFLINE,
    CONNECTING,
    ONLINE,
    ERROR,
}

data class PhoneRuntimeSnapshot(
    val setupState: PhoneSetupState = PhoneSetupState.UNINITIALIZED,
    val runtimeState: PhoneRuntimeState = PhoneRuntimeState.DISCONNECTED,
    val uplinkState: PhoneUplinkState = PhoneUplinkState.OFFLINE,
    val activeCommandRequestId: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val lastUpdatedAt: Long = 0L,
)

class PhoneRuntimeStore {
    private val _snapshot = MutableStateFlow(PhoneRuntimeSnapshot())
    val snapshot: StateFlow<PhoneRuntimeSnapshot> = _snapshot

    internal fun replace(next: PhoneRuntimeSnapshot) {
        _snapshot.value = next
    }
}
