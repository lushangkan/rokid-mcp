package cn.cutemc.rokidmcp.phone.logging

enum class PhoneLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        fun fromPriority(priority: Int): PhoneLogLevel = when (priority) {
            2 -> VERBOSE
            3 -> DEBUG
            4 -> INFO
            5 -> WARN
            else -> ERROR
        }
    }
}
