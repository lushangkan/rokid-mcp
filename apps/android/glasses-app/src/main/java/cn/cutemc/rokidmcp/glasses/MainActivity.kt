package cn.cutemc.rokidmcp.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.cutemc.rokidmcp.glasses.gateway.GlassesGatewayService
import cn.cutemc.rokidmcp.glasses.ui.theme.RokidMCPGlassesTheme

class MainActivity : ComponentActivity() {
    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val allGranted = requiredBluetoothPermissions().all { permission ->
            grantResults[permission] == true || hasPermission(permission)
        }
        if (allGranted) {
            startService(GlassesGatewayService.createStartIntent(this))
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val glassesApp: GlassesApp
        get() = application as GlassesApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureBluetoothPermissionRequested()
        ensureCameraPermissionRequested()
        setContent {
            RokidMCPGlassesTheme {
                val displayState by glassesApp.displayStateStore.state.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = displayState.text ?: "Rokid MCP ready",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            fontSize = if (displayState.text == null) 24.sp else 36.sp,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                }
            }
        }
    }

    private fun ensureBluetoothPermissionRequested() {
        val missingPermissions = requiredBluetoothPermissions().filterNot(::hasPermission)
        if (missingPermissions.isEmpty()) {
            startService(GlassesGatewayService.createStartIntent(this))
            return
        }

        requestBluetoothPermissions.launch(missingPermissions.toTypedArray())
    }

    private fun ensureCameraPermissionRequested() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            return
        }

        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
