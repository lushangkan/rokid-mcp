package cn.cutemc.rokidmcp.glasses.gateway

class FakeClock(private var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs

    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}
