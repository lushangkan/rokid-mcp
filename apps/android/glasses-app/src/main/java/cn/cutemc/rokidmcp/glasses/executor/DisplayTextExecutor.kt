package cn.cutemc.rokidmcp.glasses.executor

import cn.cutemc.rokidmcp.glasses.gateway.Clock
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextOutcome
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextResult
import kotlinx.coroutines.CancellationException
import timber.log.Timber

private const val DISPLAY_TEXT_TAG = "display-text"

class DisplayTextExecutor(
    private val textRenderer: TextRenderer,
    private val clock: Clock,
) {
    suspend fun execute(requestId: String, command: DisplayTextCommand): DisplayTextResult {
        val textLength = command.params.text.length
        Timber.tag(DISPLAY_TEXT_TAG).i(
            "display_text execution start requestId=$requestId durationMs=${command.params.durationMs} textLength=$textLength",
        )

        try {
            textRenderer.render(command.params.text, command.params.durationMs)
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            Timber.tag(DISPLAY_TEXT_TAG).e(
                error,
                "display_text execution failed requestId=$requestId durationMs=${command.params.durationMs} textLength=$textLength",
            )
            throw DisplayTextExecutionException(
                code = LocalProtocolErrorCodes.DISPLAY_FAILED,
                message = error.message ?: "failed to render display_text command",
                cause = error,
            )
        }

        Timber.tag(DISPLAY_TEXT_TAG).i(
            "display_text execution complete requestId=$requestId durationMs=${command.params.durationMs} textLength=$textLength",
        )
        return DisplayTextResult(
            completedAt = clock.nowMs(),
            result = DisplayTextOutcome(
                displayed = true,
                durationMs = command.params.durationMs,
            ),
        )
    }
}

class DisplayTextExecutionException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
