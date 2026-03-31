package cn.cutemc.rokidmcp.share.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `encode and decode accepted hello ack frame`() {
        val encoded = codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.HELLO_ACK,
                timestamp = 1_717_171_720L,
                payload = HelloAckPayload(
                    accepted = true,
                    glassesInfo = GlassesInfo(
                        model = "Rokid Max",
                        appVersion = "2.0.0",
                    ),
                    capabilities = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
                    runtimeState = LocalRuntimeState.READY,
                ),
            ),
        )

        val decoded = codec.decode(encoded)
        @Suppress("UNCHECKED_CAST")
        val header = decoded.header as LocalFrameHeader<HelloAckPayload>

        assertEquals(LocalMessageType.HELLO_ACK, header.type)
        assertEquals(true, header.payload.accepted)
        assertEquals("Rokid Max", header.payload.glassesInfo?.model)
        assertEquals("2.0.0", header.payload.glassesInfo?.appVersion)
        assertEquals(listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO), header.payload.capabilities)
        assertEquals(LocalRuntimeState.READY, header.payload.runtimeState)
        assertNull(header.payload.error)
    }

    @Test
    fun `encode and decode rejected hello ack frame`() {
        val encoded = codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.HELLO_ACK,
                timestamp = 1_717_171_721L,
                payload = HelloAckPayload(
                    accepted = false,
                    error = HelloError(code = "UNSUPPORTED_PROTOCOL", message = "version mismatch"),
                ),
            ),
        )

        val decoded = codec.decode(encoded)
        @Suppress("UNCHECKED_CAST")
        val header = decoded.header as LocalFrameHeader<HelloAckPayload>

        assertEquals(LocalMessageType.HELLO_ACK, header.type)
        assertEquals(false, header.payload.accepted)
        assertEquals("UNSUPPORTED_PROTOCOL", header.payload.error?.code)
        assertEquals("version mismatch", header.payload.error?.message)
    }

    @Test
    fun `encode and decode ping frame`() {
        val encoded = codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.PING,
                timestamp = 1_717_171_722L,
                payload = PingPayload(seq = 42, nonce = "ping-nonce"),
            ),
        )

        val decoded = codec.decode(encoded)
        @Suppress("UNCHECKED_CAST")
        val header = decoded.header as LocalFrameHeader<PingPayload>

        assertEquals(LocalMessageType.PING, header.type)
        assertEquals(42L, header.payload.seq)
        assertEquals("ping-nonce", header.payload.nonce)
    }

    @Test
    fun `encode and decode pong frame`() {
        val encoded = codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.PONG,
                timestamp = 1_717_171_723L,
                payload = PongPayload(seq = 42, nonce = "pong-nonce"),
            ),
        )

        val decoded = codec.decode(encoded)
        @Suppress("UNCHECKED_CAST")
        val header = decoded.header as LocalFrameHeader<PongPayload>

        assertEquals(LocalMessageType.PONG, header.type)
        assertEquals(42L, header.payload.seq)
        assertEquals("pong-nonce", header.payload.nonce)
    }

    @Test(expected = ProtocolCodecException::class)
    fun `encode rejects command status without request id`() {
        codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.COMMAND_STATUS,
                timestamp = 1_717_171_724L,
                payload = CommandStatusPayload(
                    action = LocalAction.DISPLAY_TEXT,
                    status = LocalCommandStatus.DISPLAYING,
                    statusAt = 1_717_171_724L,
                ),
            ),
        )
    }

    @Test(expected = ProtocolCodecException::class)
    fun `encode rejects chunk data without transfer id`() {
        val body = byteArrayOf(9, 8, 7)
        codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.CHUNK_DATA,
                requestId = "req-2",
                timestamp = 1_717_171_725L,
                payload = ChunkDataPayload(
                    index = 1,
                    offset = 3,
                    size = body.size,
                    chunkChecksum = LocalProtocolChecksums.crc32(body),
                ),
            ),
            body = body,
        )
    }

    @Test(expected = ProtocolCodecException::class)
    fun `encode rejects chunk data without binary body`() {
        val body = byteArrayOf(6, 5, 4)
        codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.CHUNK_DATA,
                requestId = "req-3",
                transferId = "tx-3",
                timestamp = 1_717_171_726L,
                payload = ChunkDataPayload(
                    index = 2,
                    offset = 6,
                    size = body.size,
                    chunkChecksum = LocalProtocolChecksums.crc32(body),
                ),
            ),
        )
    }

    @Test(expected = ProtocolCodecException::class)
    fun `encode rejects body on non chunk message`() {
        codec.encode(
            header = LocalFrameHeader(
                type = LocalMessageType.PING,
                timestamp = 1_717_171_727L,
                payload = PingPayload(seq = 99, nonce = "unexpected-body"),
            ),
            body = byteArrayOf(1, 2),
        )
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
