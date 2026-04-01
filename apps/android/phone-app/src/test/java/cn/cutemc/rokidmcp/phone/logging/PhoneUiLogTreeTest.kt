package cn.cutemc.rokidmcp.phone.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import timber.log.Timber

class PhoneUiLogTreeTest {
    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `tree forwards logs into store`() {
        val store = PhoneUiLogStore(nowMs = { 1234L })
        Timber.plant(PhoneUiLogTree(store))

        Timber.tag("gateway").d("hello")

        assertEquals(
            listOf(
                PhoneLogEntry(
                    level = PhoneLogLevel.DEBUG,
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
        val store = PhoneUiLogStore(nowMs = { 5678L })
        Timber.plant(PhoneUiLogTree(store))

        val failure = IllegalStateException("boom")

        Timber.tag("").e(failure, "failed")

        val entry = store.entries.value.single()
        assertEquals(PhoneLogLevel.ERROR, entry.level)
        assertEquals("app", entry.tag)
        assertEquals("failed\n${failure.stackTraceToString()}", entry.message)
        assertEquals(5678L, entry.timestampMs)
        assertEquals("IllegalStateException: boom", entry.throwableSummary)
    }

    @Test
    fun `tree keeps throwable summary null when absent`() {
        val store = PhoneUiLogStore(nowMs = { 999L })
        Timber.plant(PhoneUiLogTree(store))

        Timber.i("ready")

        assertNull(store.entries.value.single().throwableSummary)
    }
}
