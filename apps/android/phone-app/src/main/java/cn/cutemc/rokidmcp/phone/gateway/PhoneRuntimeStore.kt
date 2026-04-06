package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.RuntimeState
import cn.cutemc.rokidmcp.share.protocol.constants.SetupState
import cn.cutemc.rokidmcp.share.protocol.constants.UplinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PhoneRuntimeSnapshot(
    val setupState: SetupState = SetupState.UNINITIALIZED,
    val runtimeState: RuntimeState = RuntimeState.DISCONNECTED,
    val uplinkState: UplinkState = UplinkState.OFFLINE,
    val lastSeenAt: Long? = null,
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