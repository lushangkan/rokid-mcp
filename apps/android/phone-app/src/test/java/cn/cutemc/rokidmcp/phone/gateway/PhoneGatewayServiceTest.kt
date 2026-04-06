package cn.cutemc.rokidmcp.phone.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
