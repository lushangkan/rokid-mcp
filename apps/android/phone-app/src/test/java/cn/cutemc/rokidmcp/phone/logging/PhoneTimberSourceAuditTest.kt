package cn.cutemc.rokidmcp.phone.logging

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTimberSourceAuditTest {
    @Test
    fun `phone timber calls avoid sensitive payload markers`() {
        val offenders = findUnsafeTimberCalls(
            sourceRoot = resolveSourceRoot("phone-app", "src", "main"),
            forbiddenPatterns = listOf(
                "authToken",
                "Authorization",
                "Bearer ",
                "imageBytes",
                "chunkBytes",
                "sendText(text)",
                "displayed text=",
            ),
        )

        assertTrue(
            "Unsafe Timber calls found:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }
}

private fun resolveSourceRoot(vararg relativeSegments: String): Path {
    val cwd = Paths.get("").toAbsolutePath().normalize()
    val direct = cwd.resolve(Paths.get(relativeSegments.first(), *relativeSegments.drop(1).toTypedArray()))
    if (Files.exists(direct)) {
        return direct
    }

    val repoRelative = cwd.resolve(Paths.get("apps", "android", relativeSegments.first(), *relativeSegments.drop(1).toTypedArray()))
    if (Files.exists(repoRelative)) {
        return repoRelative
    }

    error("Could not resolve source root for ${relativeSegments.joinToString("/")}")
}

private fun findUnsafeTimberCalls(sourceRoot: Path, forbiddenPatterns: List<String>): List<String> {
    return Files.walk(sourceRoot).use { paths ->
        paths.asSequence()
            .filter { path -> path.isRegularFile() && path.toString().endsWith(".kt") }
            .flatMap { path ->
                extractTimberCalls(Files.readAllLines(path))
                    .mapNotNull { call ->
                        val matchedPattern = forbiddenPatterns.firstOrNull(call::contains) ?: return@mapNotNull null
                        "${sourceRoot.relativize(path)} -> $matchedPattern :: ${call.lineSequence().first().trim()}"
                    }
                    .asSequence()
            }
            .toList()
    }
}

private fun extractTimberCalls(lines: List<String>): List<String> {
    val calls = mutableListOf<String>()
    val current = StringBuilder()
    var balance = 0
    var capturing = false

    for (line in lines) {
        if (!capturing) {
            val timberIndex = line.indexOf("Timber.")
            if (timberIndex < 0) {
                continue
            }

            capturing = true
            val timberCallStart = line.substring(timberIndex)
            current.appendLine(timberCallStart)
            balance = parenthesisBalance(timberCallStart)
        } else {
            current.appendLine(line)
            balance += parenthesisBalance(line)
        }

        if (capturing && balance <= 0) {
            calls += current.toString()
            current.clear()
            balance = 0
            capturing = false
        }
    }

    return calls
}

private fun parenthesisBalance(text: String): Int = text.count { it == '(' } - text.count { it == ')' }
