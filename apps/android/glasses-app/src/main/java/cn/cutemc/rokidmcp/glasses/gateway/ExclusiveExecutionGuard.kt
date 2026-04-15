package cn.cutemc.rokidmcp.glasses.gateway

class ExclusiveExecutionGuard {
    private var activeRequestId: String? = null

    @Synchronized
    fun tryAcquire(requestId: String): Boolean {
        if (activeRequestId != null) {
            return false
        }

        activeRequestId = requestId
        return true
    }

    @Synchronized
    fun release(requestId: String) {
        val current = activeRequestId ?: return
        check(current == requestId) {
            "active command $current does not match $requestId"
        }
        activeRequestId = null
    }

    @Synchronized
    fun isBusy(): Boolean = activeRequestId != null

    @Synchronized
    fun currentRequestId(): String? = activeRequestId
}
