package cn.cutemc.rokidmcp.glasses.renderer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextRendererTest {
    @Test
    fun `recording renderer replaces visible text immediately`() = runTest {
        val renderer = RecordingReplacementTextRenderer()

        renderer.render("first", 1_000L)
        renderer.render("second", 2_000L)

        assertEquals(2, renderer.history.size)
        assertEquals("second", renderer.currentText)
        assertEquals(2_000L, renderer.currentDurationMs)
    }

    @Test
    fun `recording renderer can clear the current text`() {
        val renderer = RecordingReplacementTextRenderer()
        renderer.currentText = "hello"
        renderer.currentDurationMs = 500L

        renderer.clear()

        assertNull(renderer.currentText)
        assertNull(renderer.currentDurationMs)
    }
}

private class RecordingReplacementTextRenderer : TextRenderer {
    val history: MutableList<Pair<String, Long>> = mutableListOf()
    var currentText: String? = null
    var currentDurationMs: Long? = null

    override suspend fun render(text: String, durationMs: Long) {
        history += text to durationMs
        currentText = text
        currentDurationMs = durationMs
    }

    fun clear() {
        currentText = null
        currentDurationMs = null
    }
}
