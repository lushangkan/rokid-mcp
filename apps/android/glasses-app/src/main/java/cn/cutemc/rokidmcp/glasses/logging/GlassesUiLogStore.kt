package cn.cutemc.rokidmcp.glasses.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GlassesUiLogStore(
    private val capacity: Int = 200,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow<List<GlassesLogEntry>>(emptyList())
    val entries: StateFlow<List<GlassesLogEntry>> = _entries

    fun append(
        level: GlassesLogLevel,
        tag: String,
        message: String,
        throwableSummary: String? = null,
    ) {
        _entries.value = (_entries.value + GlassesLogEntry(
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
