package cn.cutemc.rokidmcp.phone.gateway

import android.util.Log
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IncomingImageAssemblerTest {
    @Test
    fun `assembles ordered image chunks`() {
        val bytes = "jpeg-bytes".encodeToByteArray()
        val assembler = IncomingImageAssembler()

        lateinit var result: AssembledImage
        val logs = runBlocking {
            captureTimberLogs {
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
                    payload = ChunkData(
                        index = 0,
                        offset = 0L,
                        size = 4,
                        chunkChecksum = LocalProtocolChecksums.crc32(bytes.copyOfRange(0, 4)),
                    ),
                    body = bytes.copyOfRange(0, 4),
                )
                assembler.append(
                    requestId = "req_capture_1",
                    transferId = "trf_test_1",
                    payload = ChunkData(
                        index = 1,
                        offset = 4L,
                        size = bytes.size - 4,
                        chunkChecksum = LocalProtocolChecksums.crc32(bytes.copyOfRange(4, bytes.size)),
                    ),
                    body = bytes.copyOfRange(4, bytes.size),
                )

                result = assembler.finish(
                    requestId = "req_capture_1",
                    transferId = "trf_test_1",
                    payload = ChunkEnd(totalChunks = 2, totalSize = bytes.size.toLong(), sha256 = bytes.sha256Hex()),
                )
            }
        }

        assertEquals(bytes.size.toLong(), result.size)
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertEquals(bytes.sha256Hex(), result.sha256)
        assertArrayEquals(bytes, result.bytes)
        logs.assertLog(Log.INFO, "image-assembler", "starting image assembly requestId=req_capture_1 transferId=trf_test_1 totalSize=${bytes.size}")
        logs.assertLog(Log.VERBOSE, "image-assembler", "received image chunk requestId=req_capture_1 transferId=trf_test_1 chunkIndex=0 writtenBytes=4")
        logs.assertLog(Log.VERBOSE, "image-assembler", "received image chunk requestId=req_capture_1 transferId=trf_test_1 chunkIndex=1 writtenBytes=${bytes.size}")
        logs.assertLog(Log.INFO, "image-assembler", "assembled image successfully requestId=req_capture_1 transferId=trf_test_1 totalChunks=2 totalSize=${bytes.size}")
        logs.assertNoSensitiveData()
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
            payload = ChunkData(
                index = 0,
                offset = 0L,
                size = bytes.size,
                chunkChecksum = LocalProtocolChecksums.crc32(bytes),
            ),
            body = bytes,
        )

        lateinit var error: ImageAssemblyException
        val logs = runBlocking {
            captureTimberLogs {
                error = assertThrows(ImageAssemblyException::class.java) {
                    assembler.finish(
                        requestId = "req_capture_1",
                        transferId = "trf_test_1",
                        payload = ChunkEnd(totalChunks = 1, totalSize = bytes.size.toLong(), sha256 = "deadbeef"),
                    )
                }
            }
        }

        assertEquals(LocalProtocolErrorCodes.IMAGE_CHECKSUM_MISMATCH, error.code)
        logs.assertLog(Log.WARN, "image-assembler", "image checksum mismatch requestId=req_capture_1 transferId=trf_test_1 code=IMAGE_CHECKSUM_MISMATCH")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `append rejects chunk body size mismatch`() {
        val bytes = "jpeg-bytes".encodeToByteArray()
        val assembler = IncomingImageAssembler()
        assembler.start(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkStart(totalSize = bytes.size.toLong()),
        )

        lateinit var error: ImageAssemblyException
        val logs = runBlocking {
            captureTimberLogs {
                error = assertThrows(ImageAssemblyException::class.java) {
                    assembler.append(
                        requestId = "req_capture_1",
                        transferId = "trf_test_1",
                        payload = ChunkData(
                            index = 0,
                            offset = 0L,
                            size = bytes.size + 1,
                            chunkChecksum = LocalProtocolChecksums.crc32(bytes),
                        ),
                        body = bytes,
                    )
                }
            }
        }

        assertEquals(LocalProtocolErrorCodes.PROTOCOL_INVALID_PAYLOAD, error.code)
        logs.assertLog(Log.ERROR, "image-assembler", "image assembly failed requestId=req_capture_1 transferId=trf_test_1 code=PROTOCOL_INVALID_PAYLOAD")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `append rejects chunk checksum mismatch`() {
        val bytes = "jpeg-bytes".encodeToByteArray()
        val assembler = IncomingImageAssembler()
        assembler.start(
            requestId = "req_capture_1",
            transferId = "trf_test_1",
            payload = ChunkStart(totalSize = bytes.size.toLong()),
        )

        lateinit var error: ImageAssemblyException
        val logs = runBlocking {
            captureTimberLogs {
                error = assertThrows(ImageAssemblyException::class.java) {
                    assembler.append(
                        requestId = "req_capture_1",
                        transferId = "trf_test_1",
                        payload = ChunkData(
                            index = 0,
                            offset = 0L,
                            size = bytes.size,
                            chunkChecksum = "deadbeef",
                        ),
                        body = bytes,
                    )
                }
            }
        }

        assertEquals(LocalProtocolErrorCodes.IMAGE_CHECKSUM_MISMATCH, error.code)
        logs.assertLog(Log.WARN, "image-assembler", "image checksum mismatch requestId=req_capture_1 transferId=trf_test_1 code=IMAGE_CHECKSUM_MISMATCH chunkIndex=0")
        logs.assertNoSensitiveData()
    }
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
