package cn.cutemc.rokidmcp.share.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DecodedFrame(
    val header: LocalFrameHeader<*>,
    val body: ByteArray?,
)

interface LocalFrameCodec {
    fun encode(header: LocalFrameHeader<*>, body: ByteArray? = null): ByteArray
    fun decode(bytes: ByteArray): DecodedFrame
}

class ProtocolCodecException(message: String) : IllegalArgumentException(message)

class DefaultLocalFrameCodec(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    },
) : LocalFrameCodec {
    override fun encode(header: LocalFrameHeader<*>, body: ByteArray?): ByteArray {
        validateHeaderForEncode(header, body)

        val payloadJson = encodePayload(header.type, header.payload)
        val rawHeader = RawFrameHeader(
            version = header.version,
            type = header.type,
            requestId = header.requestId,
            transferId = header.transferId,
            timestamp = header.timestamp,
            payload = payloadJson,
        )
        val headerBytes = json.encodeToString(RawFrameHeader.serializer(), rawHeader).toByteArray(StandardCharsets.UTF_8)
        if (headerBytes.size > LocalProtocolConstants.FRAME_HEADER_MAX_BYTES) {
            throw ProtocolCodecException("Header exceeds ${LocalProtocolConstants.FRAME_HEADER_MAX_BYTES} bytes")
        }

        val payloadBody = body ?: ByteArray(0)
        val frame = ByteBuffer.allocate(16 + headerBytes.size + payloadBody.size)
        frame.putInt(LocalProtocolConstants.FRAME_MAGIC)
        frame.putInt(headerBytes.size)
        frame.putInt(payloadBody.size)
        frame.putInt(LocalProtocolChecksums.crc32(headerBytes).toLong(16).toInt())
        frame.put(headerBytes)
        frame.put(payloadBody)
        return frame.array()
    }

    override fun decode(bytes: ByteArray): DecodedFrame {
        if (bytes.size < 16) {
            throw ProtocolCodecException("Frame is shorter than fixed header")
        }

        val frame = ByteBuffer.wrap(bytes)
        val magic = frame.int
        val headerLength = frame.int
        val bodyLength = frame.int
        val expectedCrc = frame.int.toUInt().toLong()

        if (magic != LocalProtocolConstants.FRAME_MAGIC) {
            throw ProtocolCodecException("Unexpected frame magic: 0x${magic.toUInt().toString(16)}")
        }
        if (headerLength !in 1..LocalProtocolConstants.FRAME_HEADER_MAX_BYTES) {
            throw ProtocolCodecException("Invalid header length: $headerLength")
        }
        if (bodyLength < 0 || bodyLength.toLong() > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
            throw ProtocolCodecException("Invalid body length: $bodyLength")
        }
        if (bytes.size != 16 + headerLength + bodyLength) {
            throw ProtocolCodecException("Frame size does not match declared header/body lengths")
        }

        val headerBytes = ByteArray(headerLength)
        frame.get(headerBytes)
        val actualCrc = LocalProtocolChecksums.crc32(headerBytes).toLong(16)
        if (actualCrc != expectedCrc) {
            throw ProtocolCodecException("Header CRC32 mismatch")
        }

        val body = if (bodyLength == 0) {
            null
        } else {
            ByteArray(bodyLength).also(frame::get)
        }

        val rawHeader = json.decodeFromString(RawFrameHeader.serializer(), headerBytes.toString(StandardCharsets.UTF_8))
        val payload = decodePayload(rawHeader.type, rawHeader.payload)
        val header = LocalFrameHeader(
            version = rawHeader.version,
            type = rawHeader.type,
            requestId = rawHeader.requestId,
            transferId = rawHeader.transferId,
            timestamp = rawHeader.timestamp,
            payload = payload,
        )
        validateHeaderForDecode(header, body)
        return DecodedFrame(header = header, body = body)
    }

    private fun validateHeaderForEncode(header: LocalFrameHeader<*>, body: ByteArray?) {
        if (header.version != LocalProtocolConstants.PROTOCOL_VERSION) {
            throw ProtocolCodecException("Unsupported protocol version: ${header.version}")
        }
        if (header.timestamp <= 0) {
            throw ProtocolCodecException("Timestamp must be positive")
        }
        if (header.type.requiresRequestId() && header.requestId.isNullOrBlank()) {
            throw ProtocolCodecException("${header.type} requires requestId")
        }
        if (header.type.requiresTransferId() && header.transferId.isNullOrBlank()) {
            throw ProtocolCodecException("${header.type} requires transferId")
        }

        when (val payload = header.payload) {
            is CapturePhotoCommandPayload -> {
                if (payload.transfer.maxBytes > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
                    throw ProtocolCodecException("Capture photo maxBytes exceeds protocol limit")
                }
            }
            is ChunkStartPayload -> {
                if (payload.totalSize <= 0 || payload.totalSize > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
                    throw ProtocolCodecException("Chunk start totalSize is out of range")
                }
            }
            is ChunkDataPayload -> {
                val frameBody = body ?: throw ProtocolCodecException("chunk_data requires a binary body")
                if (payload.size != frameBody.size) {
                    throw ProtocolCodecException("chunk_data size does not match binary body length")
                }
                if (payload.chunkChecksum != LocalProtocolChecksums.crc32(frameBody)) {
                    throw ProtocolCodecException("chunk_data checksum does not match binary body")
                }
            }
            else -> {
                if (body != null && header.type != LocalMessageType.CHUNK_DATA) {
                    throw ProtocolCodecException("${header.type} must not include a binary body")
                }
            }
        }
    }

    private fun validateHeaderForDecode(header: LocalFrameHeader<*>, body: ByteArray?) {
        validateHeaderForEncode(header, body)

        if (header.type == LocalMessageType.CHUNK_DATA && body == null) {
            throw ProtocolCodecException("chunk_data body cannot be empty")
        }
    }

    private fun encodePayload(type: LocalMessageType, payload: Any): JsonElement {
        @Suppress("UNCHECKED_CAST")
        val serializer = serializerForEncode(type, payload) as KSerializer<Any>
        return json.encodeToJsonElement(serializer, payload)
    }

    private fun decodePayload(type: LocalMessageType, payload: JsonElement): Any = when (type) {
        LocalMessageType.HELLO -> json.decodeFromJsonElement(HelloPayload.serializer(), payload)
        LocalMessageType.HELLO_ACK -> json.decodeFromJsonElement(HelloAckPayload.serializer(), payload)
        LocalMessageType.PING -> json.decodeFromJsonElement(PingPayload.serializer(), payload)
        LocalMessageType.PONG -> json.decodeFromJsonElement(PongPayload.serializer(), payload)
        LocalMessageType.COMMAND -> decodeCommandPayload(payload)
        LocalMessageType.COMMAND_ACK -> json.decodeFromJsonElement(CommandAckPayload.serializer(), payload)
        LocalMessageType.COMMAND_STATUS -> json.decodeFromJsonElement(CommandStatusPayload.serializer(), payload)
        LocalMessageType.COMMAND_RESULT -> decodeCommandResultPayload(payload)
        LocalMessageType.COMMAND_ERROR -> json.decodeFromJsonElement(CommandErrorPayload.serializer(), payload)
        LocalMessageType.CHUNK_START -> json.decodeFromJsonElement(ChunkStartPayload.serializer(), payload)
        LocalMessageType.CHUNK_DATA -> json.decodeFromJsonElement(ChunkDataPayload.serializer(), payload)
        LocalMessageType.CHUNK_END -> json.decodeFromJsonElement(ChunkEndPayload.serializer(), payload)
    }

    private fun serializerForEncode(type: LocalMessageType, payload: Any): KSerializer<*> = when (type) {
        LocalMessageType.HELLO -> requirePayload<HelloPayload>(type, payload, HelloPayload.serializer())
        LocalMessageType.HELLO_ACK -> requirePayload<HelloAckPayload>(type, payload, HelloAckPayload.serializer())
        LocalMessageType.PING -> requirePayload<PingPayload>(type, payload, PingPayload.serializer())
        LocalMessageType.PONG -> requirePayload<PongPayload>(type, payload, PongPayload.serializer())
        LocalMessageType.COMMAND -> when (payload) {
            is DisplayTextCommandPayload -> DisplayTextCommandPayload.serializer()
            is CapturePhotoCommandPayload -> CapturePhotoCommandPayload.serializer()
            else -> throw ProtocolCodecException("Unsupported command payload: ${payload::class.simpleName}")
        }
        LocalMessageType.COMMAND_ACK -> requirePayload<CommandAckPayload>(type, payload, CommandAckPayload.serializer())
        LocalMessageType.COMMAND_STATUS -> requirePayload<CommandStatusPayload>(type, payload, CommandStatusPayload.serializer())
        LocalMessageType.COMMAND_RESULT -> when (payload) {
            is DisplayTextCommandResultPayload -> DisplayTextCommandResultPayload.serializer()
            is CapturePhotoCommandResultPayload -> CapturePhotoCommandResultPayload.serializer()
            else -> throw ProtocolCodecException("Unsupported command result payload: ${payload::class.simpleName}")
        }
        LocalMessageType.COMMAND_ERROR -> requirePayload<CommandErrorPayload>(type, payload, CommandErrorPayload.serializer())
        LocalMessageType.CHUNK_START -> requirePayload<ChunkStartPayload>(type, payload, ChunkStartPayload.serializer())
        LocalMessageType.CHUNK_DATA -> requirePayload<ChunkDataPayload>(type, payload, ChunkDataPayload.serializer())
        LocalMessageType.CHUNK_END -> requirePayload<ChunkEndPayload>(type, payload, ChunkEndPayload.serializer())
    }

    private fun decodeCommandPayload(payload: JsonElement): Any = when (payload.actionValue()) {
        LocalAction.DISPLAY_TEXT.serialName -> json.decodeFromJsonElement(DisplayTextCommandPayload.serializer(), payload)
        LocalAction.CAPTURE_PHOTO.serialName -> json.decodeFromJsonElement(CapturePhotoCommandPayload.serializer(), payload)
        else -> throw ProtocolCodecException("Unknown command action in payload")
    }

    private fun decodeCommandResultPayload(payload: JsonElement): Any = when (payload.actionValue()) {
        LocalAction.DISPLAY_TEXT.serialName -> json.decodeFromJsonElement(DisplayTextCommandResultPayload.serializer(), payload)
        LocalAction.CAPTURE_PHOTO.serialName -> json.decodeFromJsonElement(CapturePhotoCommandResultPayload.serializer(), payload)
        else -> throw ProtocolCodecException("Unknown command result action in payload")
    }

    private fun JsonElement.actionValue(): String =
        jsonObject["action"]?.jsonPrimitive?.content
            ?: throw ProtocolCodecException("Payload is missing action")

    private inline fun <reified T : Any> requirePayload(
        type: LocalMessageType,
        payload: Any,
        serializer: KSerializer<T>,
    ): KSerializer<T> {
        if (payload !is T) {
            throw ProtocolCodecException("${type.name} received unexpected payload ${payload::class.simpleName}")
        }
        return serializer
    }
}

@Serializable
private data class RawFrameHeader(
    val version: String,
    val type: LocalMessageType,
    val requestId: String? = null,
    val transferId: String? = null,
    val timestamp: Long,
    val payload: JsonElement,
)

private val LocalMessageType.chunkTypes: Set<LocalMessageType>
    get() = setOf(LocalMessageType.CHUNK_START, LocalMessageType.CHUNK_DATA, LocalMessageType.CHUNK_END)

private fun LocalMessageType.requiresRequestId(): Boolean = this in setOf(
    LocalMessageType.COMMAND,
    LocalMessageType.COMMAND_ACK,
    LocalMessageType.COMMAND_STATUS,
    LocalMessageType.COMMAND_RESULT,
    LocalMessageType.COMMAND_ERROR,
    LocalMessageType.CHUNK_START,
    LocalMessageType.CHUNK_DATA,
    LocalMessageType.CHUNK_END,
)

private fun LocalMessageType.requiresTransferId(): Boolean = this in chunkTypes

private val LocalAction.serialName: String
    get() = when (this) {
        LocalAction.DISPLAY_TEXT -> "display_text"
        LocalAction.CAPTURE_PHOTO -> "capture_photo"
    }
