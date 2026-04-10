package cn.cutemc.rokidmcp.glasses.executor

import android.util.Log
import cn.cutemc.rokidmcp.glasses.gateway.FakeClock
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import kotlinx.coroutines.runBlocking
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

        val logs = captureTimberLogs {
            runBlocking {
                val result = executor.execute(
                    requestId = "req_display_1",
                    command = DisplayTextCommand(
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
        }

        logs.assertLog(Log.INFO, "display-text", "display_text execution start requestId=req_display_1 durationMs=3000 textLength=13")
        logs.assertLog(Log.INFO, "display-text", "display_text execution complete requestId=req_display_1 durationMs=3000 textLength=13")
        logs.assertNoSensitiveData()
        assertTrue(logs.none { entry -> entry.message.contains("hello glasses") })
    }

    @Test
    fun `execute maps renderer failures to display failed`() = runTest {
        val executor = DisplayTextExecutor(
            textRenderer = TextRenderer { _, _ -> error("display surface unavailable") },
            clock = FakeClock(1_717_190_200L),
        )

        var failure: DisplayTextExecutionException? = null
        val logs = captureTimberLogs {
            runBlocking {
                failure = kotlin.runCatching {
                    executor.execute(
                        requestId = "req_display_2",
                        command = DisplayTextCommand(
                            timeoutMs = 30_000L,
                            params = DisplayTextCommandParams(
                                text = "hello glasses",
                                durationMs = 3_000L,
                            ),
                        ),
                    )
                }.exceptionOrNull() as DisplayTextExecutionException
            }
        }

        assertEquals(LocalProtocolErrorCodes.DISPLAY_FAILED, failure?.code)
        assertEquals("display surface unavailable", failure?.message)
        logs.assertLog(Log.ERROR, "display-text", "display_text execution failed requestId=req_display_2 durationMs=3000 textLength=13")
        logs.assertNoSensitiveData()
        assertTrue(logs.none { entry -> entry.message.contains("hello glasses") })
    }
}

private data class RenderCall(val text: String, val durationMs: Long)

private class RecordingTextRenderer : TextRenderer {
    val calls: MutableList<RenderCall> = mutableListOf()

    override suspend fun render(text: String, durationMs: Long) {
        calls += RenderCall(text, durationMs)
    }
}
