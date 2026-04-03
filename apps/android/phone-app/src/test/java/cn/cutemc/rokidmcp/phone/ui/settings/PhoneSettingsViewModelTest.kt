package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import cn.cutemc.rokidmcp.phone.gateway.PhoneAppController
import cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneLogStore
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeStore
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

class PhoneSettingsViewModelTest {
    @Test
    fun `init loads config and exposes valid save state`() = runTest {
        val tempDir = Files.createTempDirectory("phone-settings-vm-test").toFile()
        val configStore = PhoneLocalConfigStore(filesDirProvider = { tempDir })
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
    }

    @Test
    fun `invalid deviceId disables save`() = runTest {
        val tempDir = Files.createTempDirectory("phone-settings-vm-test").toFile()
        val configStore = PhoneLocalConfigStore(filesDirProvider = { tempDir })
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
        val tempDir = Files.createTempDirectory("phone-settings-vm-test").toFile()
        val configStore = PhoneLocalConfigStore(filesDirProvider = { tempDir })
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
        assertTrue(viewModel.save())
        scope.testScheduler.runCurrent()

        val loaded = configStore.load()
        assertEquals("phone-ab12cd34", loaded.deviceId)
        assertEquals("token-123", loaded.authToken)
        assertEquals("https://relay.example.com", loaded.relayBaseUrl)
    }

    @Test
    fun `start does nothing when required start fields are missing`() = runTest {
        val tempDir = Files.createTempDirectory("phone-settings-vm-test").toFile()
        val configStore = PhoneLocalConfigStore(filesDirProvider = { tempDir })
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

        assertEquals(cn.cutemc.rokidmcp.phone.gateway.GatewayRunState.IDLE, controller.runState.value)
    }

    @Test
    fun `save failure sets error message instead of success`() = runTest {
        val tempDir = Files.createTempDirectory("phone-settings-vm-test").toFile()
        val configStore = PhoneLocalConfigStore(filesDirProvider = { tempDir })
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

        val configPath = File(tempDir, "phone-local-config.properties")
        configPath.delete()
        configPath.mkdirs()

        viewModel.onDeviceIdChanged("phone-ab12cd34")
        assertTrue(viewModel.save())
        scope.testScheduler.runCurrent()

        assertEquals("Save failed", viewModel.uiState.value.saveMessage)
    }
}
