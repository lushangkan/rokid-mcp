package cn.cutemc.rokidmcp.phone.logging

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class PhoneUiLogStore(
    private val capacity: Int = 200,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow<List<PhoneLogEntry>>(emptyList())
    private val nextEntryId = AtomicLong(1L)

    val entries: StateFlow<List<PhoneLogEntry>> = _entries

    fun append(
        level: PhoneLogLevel,
        tag: String,
        message: String,
        throwableSummary: String? = null,
    ) {
        _entries.update { current ->
            (current + PhoneLogEntry(
                id = nextEntryId.getAndIncrement(),
                level = level,
                tag = tag,
                message = message,
                timestampMs = nowMs(),
                throwableSummary = throwableSummary,
            )).takeLast(capacity)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
