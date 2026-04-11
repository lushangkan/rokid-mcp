package cn.cutemc.rokidmcp.phone.gateway

private val bearerSecretPattern = Regex("""(?i)\bBearer\s+[^\s,;]+""")
private val namedSecretPattern = Regex("""(?i)\b(authToken|uploadToken)\b\s*[:=]\s*[^\s,;]+""")
private val secretLabelPattern = Regex("""(?i)\b(authToken|uploadToken)\b""")

internal fun String.redactRelaySecrets(): String =
    replace(bearerSecretPattern, "<redacted-credential>")
        .replace(namedSecretPattern, "credential=<redacted>")
        .replace(secretLabelPattern, "credential")

internal fun Throwable.redactRelaySecrets(): Throwable {
    val safeThrowable = IllegalStateException((message ?: javaClass.simpleName).redactRelaySecrets())
    safeThrowable.stackTrace = stackTrace
    return safeThrowable
}
