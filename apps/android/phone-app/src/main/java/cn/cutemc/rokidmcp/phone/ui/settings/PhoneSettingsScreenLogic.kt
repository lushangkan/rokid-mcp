package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry

internal fun isStopActionEnabled(runState: GatewayRunState): Boolean {
    return runState == GatewayRunState.STARTING || runState == GatewayRunState.RUNNING
}

internal fun buildPhoneLogText(logs: List<PhoneLogEntry>): String {
    if (logs.isEmpty()) {
        return "No logs yet"
    }

    return buildString {
        logs.forEachIndexed { index, entry ->
            if (index > 0) {
                append("\n\n")
            }
            append(entry.level)
            append(' ')
            append(entry.tag)
            append('\n')
            append(entry.message)
            entry.throwableSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                append('\n')
                append(summary)
            }
        }
    }
}
