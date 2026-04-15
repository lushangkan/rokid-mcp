package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import cn.cutemc.rokidmcp.phone.logging.PhoneLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSettingsScreenLogicTest {
    @Test
    fun `stop action enabled only while gateway is actively starting or running`() {
        assertTrue(isStopActionEnabled(GatewayRunState.STARTING))
        assertTrue(isStopActionEnabled(GatewayRunState.RUNNING))
        assertFalse(isStopActionEnabled(GatewayRunState.IDLE))
        assertFalse(isStopActionEnabled(GatewayRunState.STOPPING))
        assertFalse(isStopActionEnabled(GatewayRunState.STOPPED))
        assertFalse(isStopActionEnabled(GatewayRunState.ERROR))
    }

    @Test
    fun `build phone log text returns empty-state placeholder when there are no logs`() {
        assertEquals("No logs yet", buildPhoneLogText(emptyList()))
    }

    @Test
    fun `build phone log text formats entries into a single readable block`() {
        val logs = listOf(
            PhoneLogEntry(
                id = 1L,
                level = PhoneLogLevel.INFO,
                tag = "controller",
                message = "gateway started",
                timestampMs = 100L,
            ),
            PhoneLogEntry(
                id = 2L,
                level = PhoneLogLevel.ERROR,
                tag = "relay-session",
                message = "relay websocket closed",
                timestampMs = 200L,
                throwableSummary = "java.io.IOException: broken pipe",
            ),
        )

        assertEquals(
            """
            INFO controller
            gateway started

            ERROR relay-session
            relay websocket closed
            java.io.IOException: broken pipe
            """.trimIndent(),
            buildPhoneLogText(logs),
        )
    }
}
