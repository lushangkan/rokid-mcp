package cn.cutemc.rokidmcp.glasses.logging

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
        val failure = IllegalStateException("camera unavailable")

        val logs = captureTimberLogs {
            Timber.tag("glasses-session").i("session ready sessionId=session-1")
            Timber.tag("capture-photo").e(failure, "capture failed requestId=req-5")
        }

        assertEquals(2, logs.size)
        assertEquals(Log.INFO, logs[0].priority)
        assertEquals("glasses-session", logs[0].tag)
        assertEquals("session ready sessionId=session-1", logs[0].message)
        assertNull(logs[0].throwable)
        assertEquals(Log.ERROR, logs[1].priority)
        assertEquals(failure, logs[1].throwable)

        logs.assertLog(Log.INFO, "glasses-session", "session ready")
        logs.assertLog(Log.ERROR, "capture-photo", "capture failed")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `captureTimberLogs supports clearing current tree state`() {
        val logs = captureTimberLogs { tree ->
            Timber.tag("gateway-service").d("discard me")
            tree.clear()
            Timber.tag("gateway-service").d("keep me")
        }

        assertEquals(1, logs.size)
        assertEquals("keep me", logs.single().message)
    }

    @Test
    fun `assertNoSensitiveData checks known secrets`() {
        val logs = captureTimberLogs {
            Timber.tag("glasses-app").w("photo upload path=/capture requestId=req-5")
        }

        assertTrue(logs.all { entry -> "Bearer " !in entry.message })
        logs.assertNoSensitiveData()
    }
}
