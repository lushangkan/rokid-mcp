package cn.cutemc.rokidmcp.glasses.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import timber.log.Timber

class GlassesUiLogTreeTest {
    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `tree forwards logs into store`() {
        val store = GlassesUiLogStore(nowMs = { 1234L })
        Timber.plant(GlassesUiLogTree(store))

        Timber.tag("gateway").d("hello")

        assertEquals(
            listOf(
                GlassesLogEntry(
                    level = GlassesLogLevel.DEBUG,
                    tag = "gateway",
                    message = "hello",
                    timestampMs = 1234L,
                    throwableSummary = null,
                ),
            ),
            store.entries.value,
        )
    }

    @Test
    fun `tree normalizes blank tags and derives throwable summary`() {
        val store = GlassesUiLogStore(nowMs = { 5678L })
        Timber.plant(GlassesUiLogTree(store))

        val failure = IllegalStateException("boom")

        Timber.tag("").e(failure, "failed")

        val entry = store.entries.value.single()
        assertEquals(GlassesLogLevel.ERROR, entry.level)
        assertEquals("app", entry.tag)
        assertEquals("failed\n${failure.stackTraceToString()}", entry.message)
        assertEquals(5678L, entry.timestampMs)
        assertEquals("IllegalStateException: boom", entry.throwableSummary)
    }

    @Test
    fun `tree keeps throwable summary null when absent`() {
        val store = GlassesUiLogStore(nowMs = { 999L })
        Timber.plant(GlassesUiLogTree(store))

        Timber.i("ready")

        assertNull(store.entries.value.single().throwableSummary)
    }
}
