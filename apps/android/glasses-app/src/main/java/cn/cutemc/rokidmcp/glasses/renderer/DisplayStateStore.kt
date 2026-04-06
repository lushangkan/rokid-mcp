package cn.cutemc.rokidmcp.glasses.renderer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DisplayTextState(
    val text: String? = null,
    val visibleUntilMs: Long? = null,
)

class DisplayStateStore {
    private val internalState = MutableStateFlow(DisplayTextState())

    val state: StateFlow<DisplayTextState> = internalState

    fun show(text: String, visibleUntilMs: Long) {
        internalState.value = DisplayTextState(text = text, visibleUntilMs = visibleUntilMs)
    }

    fun clear() {
        internalState.value = DisplayTextState()
    }
}
