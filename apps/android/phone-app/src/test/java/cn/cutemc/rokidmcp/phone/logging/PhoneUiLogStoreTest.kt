package cn.cutemc.rokidmcp.phone.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUiLogStoreTest {
    @Test
    fun `append keeps only the newest entries within capacity`() {
        var now = 1_700_000_000L
        val store = PhoneUiLogStore(capacity = 3, nowMs = { now++ })

        store.append(level = PhoneLogLevel.DEBUG, tag = "first", message = "one")
        store.append(level = PhoneLogLevel.INFO, tag = "second", message = "two")
        store.append(level = PhoneLogLevel.WARN, tag = "third", message = "three")
        store.append(level = PhoneLogLevel.ERROR, tag = "fourth", message = "four")

        val entries = store.entries.value
        assertEquals(3, entries.size)
        assertEquals(listOf("second", "third", "fourth"), entries.map { it.tag })
        assertEquals(listOf("two", "three", "four"), entries.map { it.message })
        assertEquals(listOf(1_700_000_001L, 1_700_000_002L, 1_700_000_003L), entries.map { it.timestampMs })
    }

    @Test
    fun `clear removes all entries`() {
        val store = PhoneUiLogStore(nowMs = { 42L })

        store.append(level = PhoneLogLevel.INFO, tag = "phone", message = "connected")
        assertTrue(store.entries.value.isNotEmpty())

        store.clear()

        assertTrue(store.entries.value.isEmpty())
    }
}
