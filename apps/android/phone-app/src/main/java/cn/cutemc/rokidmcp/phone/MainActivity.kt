package cn.cutemc.rokidmcp.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsScreen
import cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsViewModel
import cn.cutemc.rokidmcp.phone.ui.theme.RokidMCPPhoneTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private var pendingBluetoothStart: (() -> Unit)? = null

    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val allGranted = requiredBluetoothPermissions().all { permission ->
            grantResults[permission] == true || hasPermission(permission)
        }
        if (allGranted) {
            Timber.tag("phone-main").i("bluetooth permission result granted")
        } else {
            Timber.tag("phone-main").w("bluetooth permission result denied")
        }
        val pendingStart = pendingBluetoothStart
        pendingBluetoothStart = null
        if (allGranted) {
            pendingStart?.invoke()
        }
    }

    private val phoneApp: PhoneApp
        get() = application as PhoneApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.tag("phone-main").i("activity created savedInstanceState=%s", savedInstanceState != null)
        enableEdgeToEdge()
        setContent {
            RokidMCPPhoneTheme {
                val viewModel = remember {
                    PhoneSettingsViewModel(
                        controller = phoneApp.appController,
                        localConfigStore = phoneApp.localConfigStore,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                }
                DisposableEffect(Unit) {
                    onDispose { viewModel.close() }
                }
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val runState by viewModel.runState.collectAsStateWithLifecycle()
                val runtimeSnapshot by viewModel.runtimeSnapshot.collectAsStateWithLifecycle()
                val logs by viewModel.logs.collectAsStateWithLifecycle()

                PhoneSettingsScreen(
                    state = uiState,
                    runState = runState,
                    runtimeSnapshot = runtimeSnapshot,
                    logs = logs,
                    onDeviceIdChanged = viewModel::onDeviceIdChanged,
                    onAuthTokenChanged = viewModel::onAuthTokenChanged,
                    onRelayBaseUrlChanged = viewModel::onRelayBaseUrlChanged,
                    onTargetDeviceAddressChanged = viewModel::onTargetDeviceAddressChanged,
                    onSave = viewModel::save,
                    onStart = {
                        ensureBluetoothPermissions(viewModel::startGateway)
                    },
                    onStop = viewModel::stopGateway,
                    onClearLogs = viewModel::clearLogs,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun ensureBluetoothPermissions(onGranted: () -> Unit) {
        val missingPermissions = requiredBluetoothPermissions().filterNot(::hasPermission)
        if (missingPermissions.isEmpty()) {
            Timber.tag("phone-main").i("bluetooth permissions already granted")
            onGranted()
            return
        }

        Timber.tag("phone-main").i("requesting bluetooth permissions count=%d", missingPermissions.size)
        pendingBluetoothStart = onGranted
        requestBluetoothPermissions.launch(missingPermissions.toTypedArray())
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
