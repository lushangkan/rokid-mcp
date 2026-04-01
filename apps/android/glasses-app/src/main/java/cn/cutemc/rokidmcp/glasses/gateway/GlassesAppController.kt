package cn.cutemc.rokidmcp.glasses.gateway

class GlassesAppController(
    private val runtimeStore: GlassesRuntimeStore,
) {
    suspend fun start() = Unit

    suspend fun stop(reason: String) = Unit
}
