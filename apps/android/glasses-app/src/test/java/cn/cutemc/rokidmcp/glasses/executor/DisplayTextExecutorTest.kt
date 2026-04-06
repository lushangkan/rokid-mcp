package cn.cutemc.rokidmcp.glasses.executor

import cn.cutemc.rokidmcp.glasses.gateway.FakeClock
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextExecutorTest {
    @Test
    fun `execute renders text and returns a protocol result`() = runTest {
        val renderer = RecordingTextRenderer()
        val executor = DisplayTextExecutor(
            textRenderer = renderer,
            clock = FakeClock(1_717_190_100L),
        )

        val result = executor.execute(
            DisplayTextCommand(
                timeoutMs = 30_000L,
                params = DisplayTextCommandParams(
                    text = "hello glasses",
                    durationMs = 3_000L,
                ),
            ),
        )

        assertEquals(listOf(RenderCall("hello glasses", 3_000L)), renderer.calls)
        assertTrue(result.result.displayed)
        assertEquals(3_000L, result.result.durationMs)
        assertEquals(1_717_190_100L, result.completedAt)
    }

    @Test
    fun `execute maps renderer failures to display failed`() = runTest {
        val executor = DisplayTextExecutor(
            textRenderer = TextRenderer { _, _ -> error("display surface unavailable") },
            clock = FakeClock(1_717_190_200L),
        )

        val failure = kotlin.runCatching {
            executor.execute(
                DisplayTextCommand(
                    timeoutMs = 30_000L,
                    params = DisplayTextCommandParams(
                        text = "hello glasses",
                        durationMs = 3_000L,
                    ),
                ),
            )
        }.exceptionOrNull() as DisplayTextExecutionException

        assertEquals(LocalProtocolErrorCodes.DISPLAY_FAILED, failure.code)
        assertEquals("display surface unavailable", failure.message)
    }
}

private data class RenderCall(val text: String, val durationMs: Long)

private class RecordingTextRenderer : TextRenderer {
    val calls: MutableList<RenderCall> = mutableListOf()

    override suspend fun render(text: String, durationMs: Long) {
        calls += RenderCall(text, durationMs)
    }
}
