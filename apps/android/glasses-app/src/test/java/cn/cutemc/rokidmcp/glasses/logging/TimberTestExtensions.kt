package cn.cutemc.rokidmcp.glasses.logging

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import timber.log.Timber

/**
 * Test helper for capturing Timber logs.
 *
 * LOGGING POLICY (for reference in tests):
 * Level Matrix:
 * - Timber.v(): high-frequency transport chatter (PING, PONG, CHUNK_DATA, heartbeat)
 * - Timber.d(): non-terminal internal mechanics (read loop, frame encode/decode)
 * - Timber.i(): major milestones (connection accepted, HELLO sent/ack, session ready)
 * - Timber.w(): recoverable anomalies (hello rejected, unmatched pong, permission denied)
 * - Timber.e(): failures/exceptions that abort operations
 *
 * Tag Ownership:
 * - Glasses: rfcomm-server, glasses-session, command-dispatch, capture-photo,
 *            display-text, image-chunk, gateway-service, glasses-controller,
 *            glasses-main, glasses-app
 *
 * Redaction:
 * - Mask Bluetooth MAC to last 2 octets
 * - URL: scheme+host+path only, no query/userinfo
 * - Never log: authToken, raw JSON, HTTP body, image bytes, display text
 * - Log IDs (requestId, sessionId, transferId) as-is
 */
class CapturingTimberTree : Timber.Tree() {
    private val mutableLogs = mutableListOf<LogEntry>()

    val logs: List<LogEntry>
        get() = mutableLogs.toList()

    data class LogEntry(
        val priority: Int,
        val tag: String?,
        val message: String,
        val throwable: Throwable?,
    )

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        mutableLogs.add(
            LogEntry(
                priority = priority,
                tag = tag,
                message = message,
                throwable = t,
            ),
        )
    }

    fun clear() {
        mutableLogs.clear()
    }
}

fun captureTimberLogs(block: (CapturingTimberTree) -> Unit): List<CapturingTimberTree.LogEntry> {
    val tree = CapturingTimberTree()
    Timber.plant(tree)

    return try {
        block(tree)
        tree.logs
    } finally {
        Timber.uproot(tree)
    }
}

fun List<CapturingTimberTree.LogEntry>.assertLog(priority: Int, tag: String, messagePattern: String) {
    val found = any { entry ->
        entry.priority == priority &&
            entry.tag == tag &&
            entry.message.contains(messagePattern)
    }

    if (!found) {
        fail(
            "Expected log with priority=$priority, tag='$tag', containing '$messagePattern'. Got: $this",
        )
    }
}

fun List<CapturingTimberTree.LogEntry>.assertNoSensitiveData() {
    val sensitivePatterns = listOf("authToken", "Authorization", "Bearer ", "imageBytes", "chunkBytes")

    for (entry in this) {
        for (pattern in sensitivePatterns) {
            assertTrue(
                "Log contains sensitive data '$pattern': ${entry.message}",
                !entry.message.contains(pattern),
            )
        }
    }
}
