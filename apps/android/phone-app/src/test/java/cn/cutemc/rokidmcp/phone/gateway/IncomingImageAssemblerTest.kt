package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IncomingImageAssemblerTest {
    @Test
    fun `assembles ordered image chunks`() {
        val bytes = "jpeg-bytes".encodeToByteArray()
        val assembler = IncomingImageAssembler()

        assembler.start(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkStart(
                totalSize = bytes.size.toLong(),
                width = 1280,
                height = 720,
                sha256 = bytes.sha256Hex(),
            ),
        )
        assembler.append(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkData(index = 0, offset = 0L, size = 4, chunkChecksum = "ignored"),
            body = bytes.copyOfRange(0, 4),
        )
        assembler.append(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkData(index = 1, offset = 4L, size = bytes.size - 4, chunkChecksum = "ignored"),
            body = bytes.copyOfRange(4, bytes.size),
        )

        val result = assembler.finish(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkEnd(totalChunks = 2, totalSize = bytes.size.toLong(), sha256 = bytes.sha256Hex()),
        )

        assertEquals(bytes.size.toLong(), result.size)
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertEquals(bytes.sha256Hex(), result.sha256)
        assertArrayEquals(bytes, result.bytes)
    }

    @Test
    fun `finish rejects checksum mismatch`() {
        val bytes = "jpeg-bytes".encodeToByteArray()
        val assembler = IncomingImageAssembler()
        assembler.start(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkStart(totalSize = bytes.size.toLong()),
        )
        assembler.append(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkData(index = 0, offset = 0L, size = bytes.size, chunkChecksum = "ignored"),
            body = bytes,
        )

        val error = assertThrows(ImageAssemblyException::class.java) {
            assembler.finish(
                requestId = "req_capture_1",
                transferId = "trf_test_1",
                payload = ChunkEnd(totalChunks = 1, totalSize = bytes.size.toLong(), sha256 = "deadbeef"),
            )
        }

        assertEquals(LocalProtocolErrorCodes.IMAGE_CHECKSUM_MISMATCH, error.code)
    }
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
