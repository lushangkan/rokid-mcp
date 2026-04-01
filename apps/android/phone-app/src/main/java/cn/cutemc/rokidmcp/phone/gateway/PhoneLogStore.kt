package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PhoneLogEntry(
    val tag: String,
    val message: String,
    val timestampMs: Long,
)

class PhoneLogStore(
    private val clock: Clock = SystemClock,
) {
    private val _entries = MutableStateFlow<List<PhoneLogEntry>>(emptyList())
    val entries: StateFlow<List<PhoneLogEntry>> = _entries

    fun append(tag: String, message: String) {
        _entries.value = (_entries.value + PhoneLogEntry(tag = tag, message = message, timestampMs = clock.nowMs())).takeLast(200)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
