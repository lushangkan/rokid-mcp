package cn.cutemc.rokidmcp.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import cn.cutemc.rokidmcp.phone.gateway.PhoneAppController
import cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeStore
import cn.cutemc.rokidmcp.phone.ui.PhoneMainScreen
import cn.cutemc.rokidmcp.phone.ui.theme.RokidMCPPhoneTheme

class MainActivity : ComponentActivity() {
    private val phoneApp: PhoneApp
        get() = application as PhoneApp

    private val controller by lazy {
        PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = phoneApp.logStore,
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = packageName,
                    authToken = null,
                    relayBaseUrl = null,
                    appVersion = "1.0",
                )
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RokidMCPPhoneTheme {
                val logs by phoneApp.logStore.entries.collectAsState()
                PhoneMainScreen(
                    logs = logs,
                    onClearLogs = controller::clearLogs,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
