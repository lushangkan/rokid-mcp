package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
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
}
