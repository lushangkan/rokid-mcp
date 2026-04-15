package cn.cutemc.rokidmcp.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.cutemc.rokidmcp.glasses.gateway.GlassesGatewayService
import cn.cutemc.rokidmcp.glasses.gateway.GlassesRuntimeSnapshot
import cn.cutemc.rokidmcp.glasses.gateway.GlassesRuntimeState
import cn.cutemc.rokidmcp.glasses.ui.theme.RokidMCPGlassesTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            Timber.tag("glasses-main").i("bluetooth permission granted")
            startGatewayService()
        } else {
            Timber.tag("glasses-main").w("bluetooth permission denied")
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            Timber.tag("glasses-main").i("camera permission granted")
        } else {
            Timber.tag("glasses-main").w("camera permission denied")
        }
    }

    private val glassesApp: GlassesApp
        get() = application as GlassesApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.tag("glasses-main").i("activity created")
        enableEdgeToEdge()
        ensureBluetoothPermissionAndStartGateway()
        ensureCameraPermissionRequested()
        setContent {
            RokidMCPGlassesTheme {
                val displayState by glassesApp.displayStateStore.state.collectAsStateWithLifecycle()
                val runtimeSnapshot by glassesApp.runtimeStore.snapshot.collectAsStateWithLifecycle()
                val displayText = displayState.text?.takeIf { it.isNotBlank() }
                val scrollState = rememberScrollState()

                LaunchedEffect(displayText) {
                    scrollState.scrollTo(0)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(innerPadding)

                    if (displayText == null) {
                        ConnectionStatusPanel(
                            statusText = runtimeSnapshot.toStatusText(),
                            statusColor = runtimeSnapshot.toStatusColor(),
                            modifier = contentModifier,
                        )
                    } else {
                        DisplayTextPanel(
                            text = displayText,
                            scrollState = scrollState,
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }

    private fun ensureBluetoothPermissionAndStartGateway() {
        if (BluetoothPermission.hasRequiredPermission(this)) {
            Timber.tag("glasses-main").i("bluetooth permission already granted")
            startGatewayService()
            return
        }

        Timber.tag("glasses-main").d("requesting bluetooth permission")
        requestBluetoothPermission.launch(BluetoothPermission.requiredPermission)
    }

    private fun startGatewayService() {
        Timber.tag("glasses-main").d("starting gateway service")
        startService(GlassesGatewayService.createStartIntent(this))
    }

    private fun ensureCameraPermissionRequested() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Timber.tag("glasses-main").d("camera permission already granted")
            return
        }

        Timber.tag("glasses-main").d("requesting camera permission")
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }
}

@Composable
private fun ConnectionStatusPanel(
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = statusText,
            modifier = Modifier.fillMaxWidth(),
            color = statusColor,
            textAlign = TextAlign.Center,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun DisplayTextPanel(
    text: String,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            textAlign = TextAlign.Start,
            fontSize = 30.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

private fun GlassesRuntimeSnapshot.toStatusText(): String = when (runtimeState) {
    GlassesRuntimeState.DISCONNECTED -> "Waiting for phone"
    GlassesRuntimeState.CONNECTING -> "Connecting..."
    GlassesRuntimeState.READY -> "Connected"
    GlassesRuntimeState.ERROR -> {
        val details = lastErrorMessage?.takeIf { it.isNotBlank() }
        if (details == null) {
            "Connection error"
        } else {
            "Connection error\n$details"
        }
    }
}

private fun GlassesRuntimeSnapshot.toStatusColor(): Color = when (runtimeState) {
    GlassesRuntimeState.DISCONNECTED -> Color(0xFFD0D7DE)
    GlassesRuntimeState.CONNECTING -> Color(0xFFFFE082)
    GlassesRuntimeState.READY -> Color(0xFF81C784)
    GlassesRuntimeState.ERROR -> Color(0xFFEF9A9A)
}
