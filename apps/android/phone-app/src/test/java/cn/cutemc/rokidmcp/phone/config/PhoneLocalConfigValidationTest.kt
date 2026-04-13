package cn.cutemc.rokidmcp.phone.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalConfigValidationTest {
    @Test
    fun `target device address validation accepts normalized bluetooth mac values`() {
        assertTrue(PhoneLocalConfig.isValidTargetDeviceAddress("AA:BB:CC:DD:EE:FF"))
        assertTrue(PhoneLocalConfig.isValidTargetDeviceAddress(" aa:bb:cc:dd:ee:ff "))
    }

    @Test
    fun `target device address validation rejects malformed values`() {
        assertFalse(PhoneLocalConfig.isValidTargetDeviceAddress("AA-BB-CC-DD-EE-FF"))
        assertFalse(PhoneLocalConfig.isValidTargetDeviceAddress("AA:BB:CC:DD:EE"))
        assertFalse(PhoneLocalConfig.isValidTargetDeviceAddress(""))
    }

    @Test
    fun `normalize target device address trims and uppercases`() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            PhoneLocalConfig.normalizeTargetDeviceAddress(" aa:bb:cc:dd:ee:ff "),
        )
    }
}
