package cn.cutemc.rokidmcp.share.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFrameCodecTest {
    private val codec = DefaultLocalFrameCodec()

    @Test
    fun `encode and decode hello frame`() {
        val encoded = codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.HELLO,
                timestamp = 1_717_171_717L,
                payload = HelloPayload(
                    deviceId = "phone-1",
                    appVersion = "1.0.0",
                    supportedActions = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
                ),
            ),
        )

        val decoded = codec.decode(encoded)
        @Suppress("UNCHECKED_CAST")
        val header = decoded.header as LocalFrameHeader<HelloPayload>

        assertEquals(LocalMessageType.HELLO, header.type)
        assertEquals("phone-1", header.payload.deviceId)
        assertEquals(listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO), header.payload.supportedActions)
    }

    @Test
    fun `encode and decode chunk data frame with binary body`() {
        val body = byteArrayOf(1, 2, 3, 4, 5)

        val encoded = codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.CHUNK_DATA,
                requestId = "req-1",
                transferId = "tx-1",
                timestamp = 1_717_171_718L,
                payload = ChunkDataPayload(
                    index = 0,
                    offset = 0,
                    size = body.size,
                    chunkChecksum = LocalProtocolChecksums.crc32(body),
                ),
            ),
            body = body,
        )

        val decoded = codec.decode(encoded)
        @Suppress("UNCHECKED_CAST")
        val header = decoded.header as LocalFrameHeader<ChunkDataPayload>

        assertEquals(LocalMessageType.CHUNK_DATA, header.type)
        assertEquals(5, header.payload.size)
        assertArrayEquals(body, decoded.body)
    }

    @Test(expected = ProtocolCodecException::class)
    fun `decode rejects tampered header crc`() {
        val encoded = codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.PING,
                timestamp = 1_717_171_719L,
                payload = PingPayload(seq = 7, nonce = "nonce"),
            ),
        ).clone()

        encoded[12] = (encoded[12].toInt() xor 0x01).toByte()

        codec.decode(encoded)
    }
}
