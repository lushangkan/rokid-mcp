package cn.cutemc.rokidmcp.phone.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PhoneUiLogStore(
    private val capacity: Int = 200,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow<List<PhoneLogEntry>>(emptyList())
    val entries: StateFlow<List<PhoneLogEntry>> = _entries

    fun append(
        level: PhoneLogLevel,
        tag: String,
        message: String,
        throwableSummary: String? = null,
    ) {
        _entries.value = (_entries.value + PhoneLogEntry(
            level = level,
            tag = tag,
            message = message,
            timestampMs = nowMs(),
            throwableSummary = throwableSummary,
        )).takeLast(capacity)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
