package cn.cutemc.rokidmcp.phone.logging

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class TimberTestExtensionsTest {
    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `captureTimberLogs records tagged entries and helpers assert them`() {
        val failure = IllegalStateException("boom")

        val logs = captureTimberLogs {
            Timber.tag("relay-session").i("session ready requestId=req-123")
            Timber.tag("relay-command").e(failure, "command failed requestId=req-123")
        }

        assertEquals(2, logs.size)
        assertEquals(Log.INFO, logs[0].priority)
        assertEquals("relay-session", logs[0].tag)
        assertEquals("session ready requestId=req-123", logs[0].message)
        assertNull(logs[0].throwable)
        assertEquals(Log.ERROR, logs[1].priority)
        assertEquals(failure, logs[1].throwable)

        logs.assertLog(Log.INFO, "relay-session", "session ready")
        logs.assertLog(Log.ERROR, "relay-command", "command failed")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `captureTimberLogs supports clearing current tree state`() {
        val logs = captureTimberLogs { tree ->
            Timber.tag("phone-service").d("discard me")
            tree.clear()
            Timber.tag("phone-service").d("keep me")
        }

        assertEquals(1, logs.size)
        assertEquals("keep me", logs.single().message)
    }

    @Test
    fun `assertNoSensitiveData checks known secrets`() {
        val logs = captureTimberLogs {
            Timber.tag("phone-app").w("relay url=https://example.com/upload sessionId=session-1")
        }

        assertTrue(logs.all { entry -> "authToken" !in entry.message })
        logs.assertNoSensitiveData()
    }
}
