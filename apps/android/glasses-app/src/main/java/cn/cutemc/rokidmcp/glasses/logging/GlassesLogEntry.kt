package cn.cutemc.rokidmcp.glasses.logging

data class GlassesLogEntry(
    val level: GlassesLogLevel,
    val tag: String,
    val message: String,
    val timestampMs: Long,
    val throwableSummary: String? = null,
)
