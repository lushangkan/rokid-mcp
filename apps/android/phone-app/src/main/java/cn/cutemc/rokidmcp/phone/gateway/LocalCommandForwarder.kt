package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.CaptureTransfer
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType

fun interface LocalFrameSender {
    suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?)
}

class LocalCommandForwarder(
    private val clock: Clock,
    private val sender: LocalFrameSender,
) {
    suspend fun forward(command: ActiveRelayCommand) {
        sender.send(command.toLocalFrame(clock.nowMs()), null)
    }
}

private fun ActiveRelayCommand.toLocalFrame(timestamp: Long): LocalFrameHeader<*> = when (this) {
    is ActiveDisplayTextRelayCommand -> LocalFrameHeader(
        type = LocalMessageType.COMMAND,
        requestId = requestId,
        timestamp = timestamp,
        payload = DisplayTextCommand(
            timeoutMs = timeoutMs,
            params = DisplayTextCommandParams(
                text = text,
                durationMs = durationMs,
            ),
        ),
    )

    is ActiveCapturePhotoRelayCommand -> LocalFrameHeader(
        type = LocalMessageType.COMMAND,
        requestId = requestId,
        timestamp = timestamp,
        payload = CapturePhotoCommand(
            timeoutMs = timeoutMs,
            params = CapturePhotoCommandParams(quality = quality),
            transfer = CaptureTransfer(
                transferId = image.transferId,
                mediaType = image.contentType,
                maxBytes = image.maxSizeBytes,
            ),
        ),
    )
}
