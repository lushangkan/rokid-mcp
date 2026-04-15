package cn.cutemc.rokidmcp.phone.gateway

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import cn.cutemc.rokidmcp.phone.PhoneApp
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PhoneApp::class, sdk = [36])
class PhoneGatewayServiceTest {
    @Test
    fun `extracts runtime config from explicit extras`() {
        val config = gatewayConfigFromExtras(
            deviceId = "rokid_glasses_01",
            authToken = "token",
            relayBaseUrl = "http://10.0.2.2:3000",
        )

        requireNotNull(config)
        assertEquals("rokid_glasses_01", config.deviceId)
        assertEquals("token", config.authToken)
        assertEquals("http://10.0.2.2:3000", config.relayBaseUrl)
    }

    @Test
    fun `returns null when required runtime config extras are omitted`() {
        assertNull(
            gatewayConfigFromExtras(
                deviceId = "rokid_glasses_01",
                authToken = null,
                relayBaseUrl = null,
            ),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `logs start stop and destroy lifecycle milestones`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = Robolectric.buildService(PhoneGatewayService::class.java).create().get()

        val logs = captureTimberLogs {
            service.onStartCommand(
                PhoneGatewayService.createStartIntent(
                    context = context,
                    targetDeviceAddress = "AA:BB:CC:DD:EE:FF",
                ),
                0,
                1,
            )
            service.onStartCommand(
                PhoneGatewayService.createStopIntent(context = context, reason = "unit-test"),
                0,
                2,
            )
            service.onDestroy()
        }

        logs.assertLog(Log.INFO, "phone-service", "service start command action=")
        logs.assertLog(Log.WARN, "phone-service", "bluetooth connect permission denied")
        logs.assertLog(Log.INFO, "phone-service", "service stop requested reason=unit-test")
        logs.assertLog(Log.INFO, "phone-service", "service destroyed")
    }
}
