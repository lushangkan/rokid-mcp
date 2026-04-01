package cn.cutemc.rokidmcp.phone.gateway

class FakeClock(private var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs

    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}
