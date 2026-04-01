package cn.cutemc.rokidmcp.glasses.logging

import timber.log.Timber

class GlassesUiLogTree(
    private val store: GlassesUiLogStore,
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        store.append(
            level = GlassesLogLevel.fromPriority(priority),
            tag = tag?.takeUnless { it.isBlank() } ?: "app",
            message = message,
            throwableSummary = t?.toSummary(),
        )
    }

    private fun Throwable.toSummary(): String {
        val throwableName = this::class.simpleName ?: Throwable::class.java.simpleName
        val throwableMessage = message?.takeUnless { it.isBlank() }
        return if (throwableMessage == null) throwableName else "$throwableName: $throwableMessage"
    }
}
