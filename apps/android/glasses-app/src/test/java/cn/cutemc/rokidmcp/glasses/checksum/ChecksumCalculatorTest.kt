package cn.cutemc.rokidmcp.glasses.checksum

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumCalculatorTest {
    @Test
    fun `sha256 returns lowercase hex digest`() {
        val checksum = ChecksumCalculator().sha256("jpeg-test".encodeToByteArray())

        assertEquals("6f9109e11e5edc0405b468a92162a77f85887d780a9519f5ae5ef852a1a954c3", checksum)
    }
}
