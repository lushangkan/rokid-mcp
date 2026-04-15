package cn.cutemc.rokidmcp.glasses.gateway

import android.util.Log
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesAppControllerTest {
    @Test
    fun `controller logs milestone transitions`() {
        val store = GlassesRuntimeStore()
        val controller = GlassesAppController(store)

        val logs = captureTimberLogs {
            runBlocking {
                controller.start()
                assertEquals(GlassesRuntimeState.CONNECTING, store.snapshot.value.runtimeState)

                controller.markHelloAccepted()
                assertEquals(GlassesRuntimeState.READY, store.snapshot.value.runtimeState)

                controller.applyTransportState(GlassesTransportState.ERROR)
                assertEquals(GlassesRuntimeState.ERROR, store.snapshot.value.runtimeState)

                controller.markFailure("transport failed")
                assertEquals(GlassesRuntimeState.ERROR, store.snapshot.value.runtimeState)

                controller.markDisconnected()
                assertEquals(GlassesRuntimeState.DISCONNECTED, store.snapshot.value.runtimeState)

                controller.stop("service-stop")
                assertEquals(GlassesRuntimeState.DISCONNECTED, store.snapshot.value.runtimeState)
            }
        }

        logs.assertLog(Log.INFO, "glasses-controller", "controller start")
        logs.assertLog(Log.DEBUG, "glasses-controller", "transport state DISCONNECTED -> CONNECTING")
        logs.assertLog(Log.INFO, "glasses-controller", "hello accepted; runtime ready")
        logs.assertLog(Log.DEBUG, "glasses-controller", "transport state READY -> ERROR")
        logs.assertLog(Log.WARN, "glasses-controller", "runtime failure: transport failed")
        logs.assertLog(Log.WARN, "glasses-controller", "runtime disconnected")
        logs.assertLog(Log.INFO, "glasses-controller", "controller stop reason=service-stop")
        logs.assertNoSensitiveData()
    }
}
