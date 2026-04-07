package cn.cutemc.rokidmcp.glasses.gateway

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cn.cutemc.rokidmcp.glasses.GlassesApp
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraXCameraAdapter
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.renderer.AppStateTextRenderer
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GlassesGatewayService : LifecycleService() {
    private var controller: GlassesAppController? = null
    private var session: GlassesLocalLinkSession? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> lifecycleScope.launch { ensureStarted() }
            ACTION_STOP -> lifecycleScope.launch {
                stopGateway(intent.getStringExtra(EXTRA_STOP_REASON) ?: "service-stop")
                stopSelf(startId)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleScope.launch {
            stopGateway("service-destroyed")
        }
        super.onDestroy()
    }

    private suspend fun ensureStarted() {
        if (session != null) {
            return
        }

        val composition = createActiveGlassesGatewayComposition(
            app = application as GlassesApp,
            sessionScope = lifecycleScope,
            cameraAdapter = CameraXCameraAdapter(
                context = applicationContext,
                lifecycleOwner = this,
            ),
        )

        controller = composition.controller
        session = composition.session
        composition.controller.start()
        composition.session.start()
    }

    private suspend fun stopGateway(reason: String) {
        session?.stop(reason)
        session = null
        controller?.stop(reason)
        controller = null
    }

    companion object {
        const val ACTION_START = "cn.cutemc.rokidmcp.glasses.gateway.action.START"
        const val ACTION_STOP = "cn.cutemc.rokidmcp.glasses.gateway.action.STOP"
        const val EXTRA_STOP_REASON = "stopReason"

        fun createStartIntent(context: Context): Intent = Intent(context, GlassesGatewayService::class.java)
            .setAction(ACTION_START)

        fun createStopIntent(context: Context, reason: String = "service-stop"): Intent =
            Intent(context, GlassesGatewayService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_REASON, reason)
    }
}

internal data class ActiveGlassesGatewayComposition(
    val controller: GlassesAppController,
    val session: GlassesLocalLinkSession,
    val commandDispatcher: CommandDispatcher,
)

internal fun createActiveGlassesGatewayComposition(
    app: GlassesApp,
    sessionScope: CoroutineScope,
    cameraAdapter: CameraAdapter,
    transport: RfcommServerTransport = AndroidRfcommServerTransport(),
    clock: Clock = SystemClock,
): ActiveGlassesGatewayComposition {
    val controller = GlassesAppController(app.runtimeStore)
    val frameSender = GlassesFrameSender { header, body -> transport.send(header, body) }
    val captureExecutor = CapturePhotoExecutor(
        cameraAdapter = cameraAdapter,
        checksumCalculator = ChecksumCalculator(),
        imageChunkSender = ImageChunkSender(
            clock = clock,
            frameSender = frameSender,
        ),
        clock = clock,
        frameSender = frameSender,
    )
    val commandDispatcher = CommandDispatcher(
        clock = clock,
        scope = sessionScope,
        frameSender = frameSender,
        exclusiveGuard = ExclusiveExecutionGuard(),
        displayTextExecutor = DisplayTextExecutor(
            textRenderer = AppStateTextRenderer(app.displayStateStore, clock),
            clock = clock,
        ),
        capturePhotoExecutor = captureExecutor,
    )
    val session = GlassesLocalLinkSession(
        transport = transport,
        controller = controller,
        clock = clock,
        sessionScope = sessionScope,
        commandDispatcher = commandDispatcher,
    )

    return ActiveGlassesGatewayComposition(
        controller = controller,
        session = session,
        commandDispatcher = commandDispatcher,
    )
}
