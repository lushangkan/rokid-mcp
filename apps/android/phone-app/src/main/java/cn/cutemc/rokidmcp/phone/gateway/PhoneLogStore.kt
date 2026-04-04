package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore
import kotlinx.coroutines.flow.StateFlow

class PhoneLogStore(
    private val delegate: PhoneUiLogStore = PhoneUiLogStore(),
) {
    val entries: StateFlow<List<PhoneLogEntry>> = delegate.entries

    fun clear() {
        delegate.clear()
    }
}
