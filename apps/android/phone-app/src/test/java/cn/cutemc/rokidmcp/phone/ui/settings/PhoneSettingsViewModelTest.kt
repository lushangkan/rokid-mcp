package cn.cutemc.rokidmcp.phone.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
import cn.cutemc.rokidmcp.phone.gateway.PhoneAppController
import cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneLogStore
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalSessionEvent
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeStore
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneSettingsViewModelTest {
    private fun makeConfigStore(name: String = "test_settings_vm"): PhoneLocalConfigStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return PhoneLocalConfigStore(prefs)
    }

    @Test
    fun `init loads config and exposes valid save state`() = runTest {
        val configStore = makeConfigStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )

        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )

        assertEquals("", viewModel.uiState.value.deviceId)
        scope.testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.deviceId.startsWith("phone-"))
        assertTrue(viewModel.uiState.value.canSave)
        assertFalse(viewModel.uiState.value.canStart)
        assertEquals("5000", viewModel.uiState.value.reconnectDelayMs)
        assertEquals(PhoneLocalConfig.DEFAULT_TARGET_DEVICE_ADDRESS, viewModel.uiState.value.targetDeviceAddress)
    }

    @Test
    fun `init loads persisted target device address`() = runTest {
        val configStore = makeConfigStore("persisted_target_device_address")
        configStore.save(
            PhoneLocalConfig(
                deviceId = "phone-ab12cd34",
                authToken = "token-123",
                relayBaseUrl = "https://relay.example.com",
                reconnectDelayMs = 8_000L,
                targetDeviceAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )

        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )

        scope.testScheduler.runCurrent()

        assertEquals("AA:BB:CC:DD:EE:FF", viewModel.uiState.value.targetDeviceAddress)
    }

    @Test
    fun `invalid deviceId disables save`() = runTest {
        val configStore = makeConfigStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )
        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )
        scope.testScheduler.runCurrent()

        viewModel.onDeviceIdChanged("bad id")
        scope.testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.canSave)
        assertFalse(viewModel.save())
    }

    @Test
    fun `save persists edited config`() = runTest {
        val configStore = makeConfigStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )
        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )
        scope.testScheduler.runCurrent()

        viewModel.onDeviceIdChanged("phone-ab12cd34")
        viewModel.onAuthTokenChanged("token-123")
        viewModel.onRelayBaseUrlChanged("https://relay.example.com")
        viewModel.onReconnectDelayMsChanged("12000")
        viewModel.onTargetDeviceAddressChanged("aa:bb:cc:dd:ee:ff")
        assertTrue(viewModel.save())
        scope.testScheduler.runCurrent()

        val loaded = configStore.load()
        assertEquals("phone-ab12cd34", loaded.deviceId)
        assertEquals("token-123", loaded.authToken)
        assertEquals("https://relay.example.com", loaded.relayBaseUrl)
        assertEquals(12_000L, loaded.reconnectDelayMs)
        assertEquals("AA:BB:CC:DD:EE:FF", loaded.targetDeviceAddress)
    }

    @Test
    fun `invalid target device address disables save and start`() = runTest {
        val configStore = makeConfigStore("invalid_target_device_address")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )
        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )
        scope.testScheduler.runCurrent()

        viewModel.onDeviceIdChanged("phone-ab12cd34")
        viewModel.onAuthTokenChanged("token-123")
        viewModel.onRelayBaseUrlChanged("https://relay.example.com")
        viewModel.onTargetDeviceAddressChanged("invalid-address")
        scope.testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.canSave)
        assertFalse(viewModel.uiState.value.canStart)
    }

    @Test
    fun `reconnect delay validation requires a positive number`() = runTest {
        val configStore = makeConfigStore("reconnect_delay_validation")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )
        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )
        scope.testScheduler.runCurrent()

        viewModel.onReconnectDelayMsChanged("2500")

        assertEquals("2500", viewModel.uiState.value.reconnectDelayMs)
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `invalid reconnect delay disables start`() = runTest {
        val configStore = makeConfigStore("invalid_reconnect_delay")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )
        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )
        scope.testScheduler.runCurrent()

        viewModel.onDeviceIdChanged("phone-ab12cd34")
        viewModel.onAuthTokenChanged("token-123")
        viewModel.onRelayBaseUrlChanged("https://relay.example.com")
        viewModel.onReconnectDelayMsChanged("0")
        scope.testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.canSave)
        assertFalse(viewModel.uiState.value.canStart)

        viewModel.onReconnectDelayMsChanged("abc")
        scope.testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.canStart)
    }

    @Test
    fun `start does nothing when required start fields are missing`() = runTest {
        val configStore = makeConfigStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )
        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )
        scope.testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.canStart)
        viewModel.startGateway()
        scope.testScheduler.runCurrent()

        assertEquals(GatewayRunState.IDLE, controller.runState.value)
    }

    @Test
    fun `recoverable local link failure keeps start available for retry`() = runTest {
        val configStore = makeConfigStore("retry_after_local_failure")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val logs = PhoneUiLogStore()
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = PhoneLogStore(logs),
            loadConfig = {
                val config = configStore.load()
                PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = "1.0",
                )
            },
        )
        val viewModel = PhoneSettingsViewModel(
            controller = controller,
            localConfigStore = configStore,
            scope = scope,
            ioDispatcher = dispatcher,
        )
        scope.testScheduler.runCurrent()

        viewModel.onDeviceIdChanged("phone-ab12cd34")
        viewModel.onAuthTokenChanged("token-123")
        viewModel.onRelayBaseUrlChanged("https://relay.example.com")
        scope.testScheduler.runCurrent()

        controller.handleLocalSessionEvent(
            PhoneLocalSessionEvent.SessionFailed(
                code = "BLUETOOTH_PONG_TIMEOUT",
                message = "pong not received in time",
            ),
        )
        scope.testScheduler.runCurrent()

        assertEquals(GatewayRunState.STOPPED, controller.runState.value)
        assertTrue(viewModel.uiState.value.canStart)
    }
}
