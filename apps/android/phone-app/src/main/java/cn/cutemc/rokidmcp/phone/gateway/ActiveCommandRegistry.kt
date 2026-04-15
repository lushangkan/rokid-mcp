package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandDispatchPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchImage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchMessage
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandDispatchPayload

class ActiveCommandRegistry {
    private var activeCommand: ActiveRelayCommand? = null

    fun begin(dispatch: CommandDispatchMessage): ActiveRelayCommand {
        if (activeCommand != null) {
            throw ActiveCommandRegistryException(
                code = LocalProtocolErrorCodes.COMMAND_BUSY,
                message = "another relay command is already active",
            )
        }

        return dispatch.toActiveRelayCommand().also { activeCommand = it }
    }

    fun activeOrNull(): ActiveRelayCommand? = activeCommand

    fun require(requestId: String, action: CommandAction? = null): ActiveRelayCommand {
        val current = activeCommand ?: throw ActiveCommandRegistryException(
            code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
            message = "no active relay command exists",
        )

        if (current.requestId != requestId) {
            throw ActiveCommandRegistryException(
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "active relay command ${current.requestId} does not match $requestId",
            )
        }

        if (action != null && current.action != action) {
            throw ActiveCommandRegistryException(
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "active relay command action ${current.action} does not match $action",
            )
        }

        return current
    }

    fun clear(requestId: String? = null): ActiveRelayCommand? {
        val current = activeCommand ?: return null
        if (requestId != null && current.requestId != requestId) {
            throw ActiveCommandRegistryException(
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "active relay command ${current.requestId} does not match $requestId",
            )
        }

        activeCommand = null
        return current
    }
}

sealed interface ActiveRelayCommand {
    val requestId: String
    val action: CommandAction
    val timeoutMs: Long
}

data class ActiveDisplayTextRelayCommand(
    override val requestId: String,
    override val timeoutMs: Long,
    val text: String,
    val durationMs: Long,
) : ActiveRelayCommand {
    override val action: CommandAction = CommandAction.DISPLAY_TEXT
}

data class ActiveCapturePhotoRelayCommand(
    override val requestId: String,
    override val timeoutMs: Long,
    val quality: cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality?,
    val image: CommandDispatchImage,
) : ActiveRelayCommand {
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO
}

class ActiveCommandRegistryException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)

private fun CommandDispatchMessage.toActiveRelayCommand(): ActiveRelayCommand = when (val payload = payload) {
    is DisplayTextCommandDispatchPayload -> ActiveDisplayTextRelayCommand(
        requestId = requestId,
        timeoutMs = payload.timeoutMs,
        text = payload.params.text,
        durationMs = payload.params.durationMs,
    )

    is CapturePhotoCommandDispatchPayload -> ActiveCapturePhotoRelayCommand(
        requestId = requestId,
        timeoutMs = payload.timeoutMs,
        quality = payload.params.quality,
        image = payload.image,
    )
}
