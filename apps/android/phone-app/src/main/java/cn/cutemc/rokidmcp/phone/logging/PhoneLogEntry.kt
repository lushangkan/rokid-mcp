package cn.cutemc.rokidmcp.phone.logging

data class PhoneLogEntry(
    val level: PhoneLogLevel,
    val tag: String,
    val message: String,
    val timestampMs: Long,
    val throwableSummary: String? = null,
)
