package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.share.protocol.local.IncrementalFrameExtractor
import kotlin.math.min

/**
 * Configures how loopback helpers emit bytes in each direction.
 *
 * Exact-frame delivery remains the default so existing tests only opt into
 * streaming semantics when they explicitly need them.
 */
internal data class LoopbackStreamConfig(
    val clientToServer: LoopbackByteDeliveryMode = LoopbackByteDeliveryMode.ExactFrame,
    val serverToClient: LoopbackByteDeliveryMode = LoopbackByteDeliveryMode.ExactFrame,
)

internal sealed interface LoopbackByteDeliveryMode {
    data object ExactFrame : LoopbackByteDeliveryMode

    /**
     * Splits each emitted payload into the requested leading chunk sizes and then emits any tail as
     * the final chunk.
     */
    data class Fragmented(val chunkSizes: List<Int>) : LoopbackByteDeliveryMode {
        init {
            require(chunkSizes.isNotEmpty()) { "chunkSizes must not be empty" }
            require(chunkSizes.all { it > 0 }) { "chunkSizes must be positive" }
        }
    }

    /**
     * Buffers whole frames until [framesPerEmission] are available, then emits the coalesced bytes.
     * Any configured [chunkSizes] are applied after coalescing so tests can combine batching and
     * fragmentation in the same direction.
     */
    data class Coalesced(
        val framesPerEmission: Int,
        val chunkSizes: List<Int> = emptyList(),
    ) : LoopbackByteDeliveryMode {
        init {
            require(framesPerEmission > 0) { "framesPerEmission must be positive" }
            require(chunkSizes.all { it > 0 }) { "chunkSizes must be positive" }
        }
    }
}

internal fun fragmentFrame(frame: ByteArray, vararg chunkSizes: Int): List<ByteArray> {
    return frame.splitIntoByteChunks(chunkSizes.toList())
}

internal fun coalesceFrames(vararg frames: ByteArray): ByteArray = coalesceFrames(frames.asList())

internal fun coalesceFrames(frames: Iterable<ByteArray>): ByteArray {
    val copiedFrames = frames.map { it.copyOf() }
    val totalSize = copiedFrames.sumOf { it.size }
    val merged = ByteArray(totalSize)
    var offset = 0
    for (frame in copiedFrames) {
        frame.copyInto(merged, destinationOffset = offset)
        offset += frame.size
    }
    return merged
}

internal fun ByteArray.splitIntoByteChunks(chunkSizes: List<Int>): List<ByteArray> {
    if (isEmpty()) {
        return listOf(ByteArray(0))
    }
    if (chunkSizes.isEmpty()) {
        return listOf(copyOf())
    }
    require(chunkSizes.all { it > 0 }) { "chunkSizes must be positive" }

    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    for (requestedChunkSize in chunkSizes) {
        if (offset >= size) {
            break
        }
        val endExclusive = min(offset + requestedChunkSize, size)
        chunks += copyOfRange(offset, endExclusive)
        offset = endExclusive
    }
    if (offset < size) {
        chunks += copyOfRange(offset, size)
    }
    return chunks
}

/**
 * Applies the configured delivery behavior to encoded frames before forwarding bytes to the peer.
 */
internal class LoopbackByteStream(private val deliveryMode: LoopbackByteDeliveryMode) {
    private val pendingFrames = ArrayDeque<ByteArray>()

    suspend fun emitFrame(frame: ByteArray, emitBytes: suspend (ByteArray) -> Unit) {
        pendingFrames.addLast(frame.copyOf())
        emitReadyBytes(emitBytes)
    }

    suspend fun flush(emitBytes: suspend (ByteArray) -> Unit) {
        if (pendingFrames.isEmpty()) {
            return
        }

        when (deliveryMode) {
            LoopbackByteDeliveryMode.ExactFrame,
            is LoopbackByteDeliveryMode.Fragmented,
            -> emitReadyBytes(emitBytes)

            is LoopbackByteDeliveryMode.Coalesced -> {
                emitBytesForPayload(coalesceFrames(pendingFrames), emitBytes)
                pendingFrames.clear()
            }
        }
    }

    private suspend fun emitReadyBytes(emitBytes: suspend (ByteArray) -> Unit) {
        when (deliveryMode) {
            LoopbackByteDeliveryMode.ExactFrame,
            is LoopbackByteDeliveryMode.Fragmented,
            -> {
                while (pendingFrames.isNotEmpty()) {
                    emitBytesForPayload(pendingFrames.removeFirst(), emitBytes)
                }
            }

            is LoopbackByteDeliveryMode.Coalesced -> {
                while (pendingFrames.size >= deliveryMode.framesPerEmission) {
                    val emissionFrames = MutableList(deliveryMode.framesPerEmission) {
                        pendingFrames.removeFirst()
                    }
                    emitBytesForPayload(coalesceFrames(emissionFrames), emitBytes)
                }
            }
        }
    }

    private suspend fun emitBytesForPayload(payload: ByteArray, emitBytes: suspend (ByteArray) -> Unit) {
        val chunks = when (deliveryMode) {
            LoopbackByteDeliveryMode.ExactFrame -> listOf(payload)
            is LoopbackByteDeliveryMode.Fragmented -> payload.splitIntoByteChunks(deliveryMode.chunkSizes)
            is LoopbackByteDeliveryMode.Coalesced -> payload.splitIntoByteChunks(deliveryMode.chunkSizes)
        }
        for (chunk in chunks) {
            emitBytes(chunk)
        }
    }
}

/**
 * Mirrors the transport-side frame reassembly step for tests that want arbitrary byte delivery on
 * the client-to-server path.
 */
internal class LoopbackFrameReassembler {
    private val extractor = IncrementalFrameExtractor()

    fun append(bytes: ByteArray): List<ByteArray> = extractor.append(bytes)

    fun reset() {
        extractor.reset()
    }
}
