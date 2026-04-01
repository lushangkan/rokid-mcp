package cn.cutemc.rokidmcp.glasses.logging

enum class GlassesLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        fun fromPriority(priority: Int): GlassesLogLevel = when (priority) {
            2 -> VERBOSE
            3 -> DEBUG
            4 -> INFO
            5 -> WARN
            else -> ERROR
        }
    }
}
