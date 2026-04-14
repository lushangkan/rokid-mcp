package cn.cutemc.rokidmcp.glasses.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.cutemc.rokidmcp.glasses.GlassesApp
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.Camera2CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraXCameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.FallbackCameraAdapter
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.renderer.AppStateTextRenderer
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

class GlassesGatewayService : LifecycleService() {
    private var controller: GlassesAppController? = null
    private var session: GlassesLocalLinkSession? = null
    private var recoveryJob: Job? = null
    private var autoRestartEnabled: Boolean = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Timber.tag("gateway-service").i("service start command action=%s", intent?.action ?: "none")
        when (intent?.action) {
            ACTION_START -> lifecycleScope.launch {
                autoRestartEnabled = true
                if (!hasBluetoothConnectPermission()) {
                    Timber.tag("gateway-service").w("bluetooth connect permission denied")
                    stopSelf(startId)
                    return@launch
                }

                ensureStarted()
            }
            ACTION_STOP -> lifecycleScope.launch {
                autoRestartEnabled = false
                recoveryJob?.cancel()
                Timber.tag("gateway-service").i(
                    "service stop command reason=%s",
                    intent.getStringExtra(EXTRA_STOP_REASON) ?: "service-stop",
                )
                stopGateway(intent.getStringExtra(EXTRA_STOP_REASON) ?: "service-stop")
                stopSelf(startId)
            }
        }

        return if (intent?.action == ACTION_START && !hasBluetoothConnectPermission()) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onDestroy() {
        Timber.tag("gateway-service").i("service destroyed")
        autoRestartEnabled = false
        recoveryJob?.cancel()
        lifecycleScope.launch {
            stopGateway("service-destroyed")
        }
        super.onDestroy()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensureStarted() {
        if (session != null) {
            Timber.tag("gateway-service").d("gateway composition already started")
            return
        }

        Timber.tag("gateway-service").d("starting gateway composition")
        val composition = createActiveGlassesGatewayComposition(
            app = application as GlassesApp,
            sessionScope = lifecycleScope,
            onLocalLinkFailure = ::requestGatewayRecovery,
            cameraAdapter = FallbackCameraAdapter(
                primary = CameraXCameraAdapter(
                    context = applicationContext,
                    lifecycleOwner = this,
                ),
                fallback = Camera2CameraAdapter(
                    context = applicationContext,
                ),
            ),
        )

        startActiveGlassesGatewayComposition(composition)
        controller = composition.controller
        session = composition.session
        Timber.tag("gateway-service").i("gateway composition ready")
    }

    private suspend fun stopGateway(reason: String) {
        Timber.tag("gateway-service").d("stopping gateway composition reason=%s", reason)
        val activeSession = session
        val activeController = controller
        session = null
        controller = null
        runCatching {
            activeSession?.stop(reason)
        }.onFailure { error ->
            Timber.tag("gateway-service").w(error, "failed to stop glasses session reason=%s", reason)
        }
        runCatching {
            activeController?.stop(reason)
        }.onFailure { error ->
            Timber.tag("gateway-service").w(error, "failed to stop glasses controller reason=%s", reason)
        }
        Timber.tag("gateway-service").i("gateway composition stopped")
    }

    private fun requestGatewayRecovery(reason: String, cause: Throwable) {
        if (!autoRestartEnabled) {
            Timber.tag("gateway-service").d(
                "ignoring gateway recovery because auto restart is disabled reason=%s",
                reason,
            )
            return
        }

        if (recoveryJob?.isActive == true) {
            Timber.tag("gateway-service").d(
                "gateway recovery already in progress reason=%s",
                reason,
            )
            return
        }

        recoveryJob = lifecycleScope.launch {
            try {
                Timber.tag("gateway-service").w(
                    cause,
                    "resetting gateway after local link failure reason=%s",
                    reason,
                )
                stopGateway("local-link-failure:$reason")

                if (!autoRestartEnabled) {
                    Timber.tag("gateway-service").d(
                        "skipping gateway restart because auto restart is disabled reason=%s",
                        reason,
                    )
                    return@launch
                }

                if (!hasBluetoothConnectPermission()) {
                    Timber.tag("gateway-service").w(
                        "gateway recovery aborted because bluetooth connect permission is denied",
                    )
                    stopSelf()
                    return@launch
                }

                ensureStarted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Timber.tag("gateway-service").e(
                    error,
                    "failed to recover gateway after local link failure reason=%s",
                    reason,
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (recoveryJob === job) {
                    recoveryJob = null
                }
            }
        }
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
    transport: RfcommServerTransport = AndroidRfcommServerTransport(app.applicationContext),
    clock: Clock = SystemClock,
    onLocalLinkFailure: (reason: String, cause: Throwable) -> Unit = { _, _ -> },
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
        onLocalLinkFailure = onLocalLinkFailure,
    )

    return ActiveGlassesGatewayComposition(
        controller = controller,
        session = session,
        commandDispatcher = commandDispatcher,
    )
}

internal suspend fun startActiveGlassesGatewayComposition(composition: ActiveGlassesGatewayComposition) {
    try {
        Timber.tag("gateway-service").d("starting active glasses gateway composition")
        composition.controller.start()
        composition.session.start()
        Timber.tag("gateway-service").i("active glasses gateway composition started")
    } catch (error: Throwable) {
        if (error !is CancellationException) {
            Timber.tag("gateway-service").e(error, "failed to start glasses gateway composition")
        }
        rollbackFailedGatewayStart(composition)
        throw error
    }
}

private suspend fun rollbackFailedGatewayStart(composition: ActiveGlassesGatewayComposition) {
    runCatching {
        composition.session.stop("startup-failed")
    }.onFailure { error ->
        Timber.tag("gateway-service").w(error, "failed to stop glasses session after startup failure")
    }

    runCatching {
        composition.controller.stop("startup-failed")
    }.onFailure { error ->
        Timber.tag("gateway-service").w(error, "failed to stop glasses controller after startup failure")
    }
}
