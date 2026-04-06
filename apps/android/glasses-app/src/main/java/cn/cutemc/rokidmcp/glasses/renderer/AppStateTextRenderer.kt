package cn.cutemc.rokidmcp.glasses.renderer

import cn.cutemc.rokidmcp.glasses.gateway.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppStateTextRenderer(
    private val displayStateStore: DisplayStateStore,
    private val clock: Clock,
) : TextRenderer {
    private val mutex = Mutex()
    private var activeToken = 0L

    override suspend fun render(text: String, durationMs: Long) {
        val token = mutex.withLock {
            activeToken += 1
            val currentToken = activeToken
            displayStateStore.show(text = text, visibleUntilMs = clock.nowMs() + durationMs)
            currentToken
        }

        delay(durationMs)

        mutex.withLock {
            if (token == activeToken) {
                displayStateStore.clear()
            }
        }
    }
}
