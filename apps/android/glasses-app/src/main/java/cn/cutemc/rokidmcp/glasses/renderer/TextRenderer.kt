package cn.cutemc.rokidmcp.glasses.renderer

fun interface TextRenderer {
    suspend fun render(text: String, durationMs: Long)
}
