# Android Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Timber`-based Android logging that writes to both Logcat and an in-memory rolling UI log buffer, with `phone-app` rendering the buffer on its main screen and `glasses-app` adopting the same infrastructure without a main-screen log UI yet.

**Architecture:** Each Android app owns its own logging package and bootstrap path. App code writes logs through `Timber`; a per-app `UiLogTree` forwards the same log events into a bounded `StateFlow` buffer for UI consumption. `phone-app` migrates controller logging from direct store appends to `Timber`, while `glasses-app` receives the same infrastructure without wiring the buffer into its main screen.

**Tech Stack:** Kotlin, Android app modules, Jetpack Compose, `StateFlow`, JUnit4, Robolectric-capable local unit tests, Timber

---

### Task 1: Add Timber Dependency Wiring For Both Android Apps

**Files:**
- Modify: `apps/android/gradle/libs.versions.toml`
- Modify: `apps/android/phone-app/build.gradle.kts`
- Modify: `apps/android/glasses-app/build.gradle.kts`

- [ ] **Step 1: Add the Timber version and library alias to the version catalog**

Update `apps/android/gradle/libs.versions.toml`.

Add the new version entry under `[versions]`:

```toml
timber = "5.0.1"
```

Add the new library alias under `[libraries]`:

```toml
timber = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }
```

- [ ] **Step 2: Add Timber to `phone-app`**

Update the `dependencies` block in `apps/android/phone-app/build.gradle.kts`.

Add this line near the other `implementation(...)` dependencies:

```kotlin
implementation(libs.timber)
```

The resulting relevant block should look like:

```kotlin
dependencies {
    implementation(project(":share"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 3: Add Timber to `glasses-app`**

Update the `dependencies` block in `apps/android/glasses-app/build.gradle.kts`.

Add this line near the other `implementation(...)` dependencies:

```kotlin
implementation(libs.timber)
```

The resulting relevant block should look like:

```kotlin
dependencies {
    implementation(project(":share"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 4: Run compilation to verify the dependency catalog resolves**

Run:

```bash
cd apps/android && ./gradlew :phone-app:compileDebugKotlin :glasses-app:compileDebugKotlin
```

Expected:

- `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit the dependency wiring**

```bash
git add apps/android/gradle/libs.versions.toml apps/android/phone-app/build.gradle.kts apps/android/glasses-app/build.gradle.kts
git commit -m "chore: add timber dependencies for android apps"
```

### Task 2: Build `phone-app` Logging Core And Its Failing Tests First

**Files:**
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneLogLevel.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneLogEntry.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogStore.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogTree.kt`
- Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogStoreTest.kt`
- Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogTreeTest.kt`

- [ ] **Step 1: Write the failing store test**

Create `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogStoreTest.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUiLogStoreTest {
    @Test
    fun `keeps only the most recent entries within capacity`() {
        val store = PhoneUiLogStore(capacity = 2) { 100L }

        store.append(level = PhoneLogLevel.INFO, tag = "one", message = "first")
        store.append(level = PhoneLogLevel.WARN, tag = "two", message = "second")
        store.append(level = PhoneLogLevel.ERROR, tag = "three", message = "third")

        assertEquals(listOf("second", "third"), store.entries.value.map { it.message })
    }

    @Test
    fun `clear removes all entries`() {
        val store = PhoneUiLogStore(capacity = 2) { 200L }

        store.append(level = PhoneLogLevel.INFO, tag = "controller", message = "hello")
        store.clear()

        assertTrue(store.entries.value.isEmpty())
    }
}
```

- [ ] **Step 2: Write the failing tree test**

Create `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogTreeTest.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class PhoneUiLogTreeTest {
    @Test
    fun `forwards timber events into the ui log store`() {
        val store = PhoneUiLogStore(capacity = 5) { 1_717_171_800L }
        val tree = PhoneUiLogTree(store)

        Timber.uprootAll()
        try {
            Timber.plant(tree)

            Timber.tag("controller").e(IllegalStateException("boom"), "startup failed")

            val entry = store.entries.value.single()
            assertEquals(PhoneLogLevel.ERROR, entry.level)
            assertEquals("controller", entry.tag)
            assertEquals("startup failed", entry.message)
            assertEquals(1_717_171_800L, entry.timestampMs)
            assertTrue(entry.throwableSummary!!.contains("boom"))
        } finally {
            Timber.uprootAll()
        }
    }
}
```

- [ ] **Step 3: Run the new tests to verify they fail**

Run:

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStoreTest" --tests "cn.cutemc.rokidmcp.phone.logging.PhoneUiLogTreeTest"
```

Expected:

- FAIL
- Missing production classes under `cn.cutemc.rokidmcp.phone.logging`

- [ ] **Step 4: Implement the minimal logging core for `phone-app`**

Create `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneLogLevel.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.logging

enum class PhoneLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        fun fromPriority(priority: Int): PhoneLogLevel = when (priority) {
            2 -> VERBOSE
            3 -> DEBUG
            4 -> INFO
            5 -> WARN
            else -> ERROR
        }
    }
}
```

Create `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneLogEntry.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.logging

data class PhoneLogEntry(
    val level: PhoneLogLevel,
    val tag: String,
    val message: String,
    val timestampMs: Long,
    val throwableSummary: String? = null,
)
```

Create `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogStore.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PhoneUiLogStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _entries = MutableStateFlow<List<PhoneLogEntry>>(emptyList())
    val entries: StateFlow<List<PhoneLogEntry>> = _entries

    fun append(
        level: PhoneLogLevel,
        tag: String,
        message: String,
        throwableSummary: String? = null,
    ) {
        val next = PhoneLogEntry(
            level = level,
            tag = tag,
            message = message,
            timestampMs = nowMs(),
            throwableSummary = throwableSummary,
        )
        _entries.value = (_entries.value + next).takeLast(capacity)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
```

Create `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneUiLogTree.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.logging

import timber.log.Timber

class PhoneUiLogTree(
    private val store: PhoneUiLogStore,
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        store.append(
            level = PhoneLogLevel.fromPriority(priority),
            tag = tag?.takeIf { it.isNotBlank() } ?: "app",
            message = message,
            throwableSummary = t?.let { throwable ->
                val summary = throwable.message ?: throwable::class.java.simpleName
                "${throwable::class.java.simpleName}: $summary"
            },
        )
    }
}
```

- [ ] **Step 5: Re-run the tests to verify they pass**

Run:

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStoreTest" --tests "cn.cutemc.rokidmcp.phone.logging.PhoneUiLogTreeTest"
```

Expected:

- PASS

- [ ] **Step 6: Commit the `phone-app` logging core**

```bash
git add apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/logging
git commit -m "feat: add phone timber ui log core"
```

### Task 3: Integrate `phone-app` Logging Into Bootstrap, Controller, And Main Screen

**Files:**
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/PhoneApp.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneLoggerBootstrap.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/PhoneMainScreen.kt`
- Modify: `apps/android/phone-app/src/main/AndroidManifest.xml`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/MainActivity.kt`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt`
- Modify: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt`
- Delete: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLogStore.kt`

- [ ] **Step 1: Update the controller test first so it expects Timber-based logging**

Replace `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt` with:

```kotlin
package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogTree
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class PhoneAppControllerTest {
    @Test
    fun `runtime store starts disconnected and offline`() = runTest {
        val store = PhoneRuntimeStore()
        val snapshot = store.snapshot.value

        assertEquals(PhoneSetupState.UNINITIALIZED, snapshot.setupState)
        assertEquals(PhoneRuntimeState.DISCONNECTED, snapshot.runtimeState)
        assertEquals(PhoneUplinkState.OFFLINE, snapshot.uplinkState)
    }

    @Test
    fun `start without required config records startup error and does not run`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore { 1_717_171_800L }

        Timber.uprootAll()
        try {
            Timber.plant(PhoneUiLogTree(logStore))

            val controller = PhoneAppController(
                runtimeStore = runtimeStore,
                logStore = logStore,
                loadConfig = {
                    PhoneGatewayConfig(
                        deviceId = "abc12345",
                        authToken = null,
                        relayBaseUrl = null,
                        appVersion = "1.0",
                    )
                },
            )

            controller.start(targetDeviceAddress = "00:11:22:33:44:55")

            assertEquals(GatewayRunState.ERROR, controller.runState.value)
            assertEquals("PHONE_CONFIG_INCOMPLETE", runtimeStore.snapshot.value.lastErrorCode)
            assertTrue(logStore.entries.value.any { it.message.contains("missing relay config") })
            assertEquals(1_717_171_800L, logStore.entries.value.single().timestampMs)
        } finally {
            Timber.uprootAll()
        }
    }
}
```

- [ ] **Step 2: Run the controller test to verify it fails before migration**

Run:

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneAppControllerTest"
```

Expected:

- FAIL
- `PhoneAppController` still depends on the old `PhoneLogStore`

- [ ] **Step 3: Add `phone-app` bootstrap and main screen UI**

Create `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/logging/PhoneLoggerBootstrap.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.logging

import timber.log.Timber

object PhoneLoggerBootstrap {
    private var initialized = false

    fun initialize(logStore: PhoneUiLogStore) {
        if (initialized) return
        initialized = true
        Timber.plant(Timber.DebugTree())
        Timber.plant(PhoneUiLogTree(logStore))
    }
}
```

Create `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/PhoneApp.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone

import android.app.Application
import cn.cutemc.rokidmcp.phone.logging.PhoneLoggerBootstrap
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore

class PhoneApp : Application() {
    val logStore: PhoneUiLogStore by lazy { PhoneUiLogStore() }

    override fun onCreate() {
        super.onCreate()
        PhoneLoggerBootstrap.initialize(logStore)
    }
}
```

Create `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/PhoneMainScreen.kt`:

```kotlin
package cn.cutemc.rokidmcp.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry

@Composable
fun PhoneMainScreen(
    logs: List<PhoneLogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Phone Debug Console", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onClearLogs) {
                Text("Clear")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(logs, key = { entry -> "${entry.timestampMs}-${entry.tag}-${entry.message}" }) { entry ->
                Text(
                    text = "[${entry.timestampMs}] ${entry.level} ${entry.tag}: ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
```

- [ ] **Step 4: Migrate the activity and manifest to use the new app-level log store**

Update `apps/android/phone-app/src/main/AndroidManifest.xml` so the `<application>` node includes the custom app class:

```xml
<application
        android:name=".PhoneApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.RokidMCPPhone">
```

Replace `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/MainActivity.kt` with:

```kotlin
package cn.cutemc.rokidmcp.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import cn.cutemc.rokidmcp.phone.ui.PhoneMainScreen
import cn.cutemc.rokidmcp.phone.ui.theme.RokidMCPPhoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val phoneApp = application as PhoneApp

        setContent {
            val logs by phoneApp.logStore.entries.collectAsState()

            RokidMCPPhoneTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhoneMainScreen(
                        logs = logs,
                        onClearLogs = phoneApp.logStore::clear,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Migrate the controller to Timber and delete the old bespoke log store**

Replace `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt` with:

```kotlin
package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

data class PhoneGatewayConfig(
    val deviceId: String,
    val authToken: String?,
    val relayBaseUrl: String?,
    val appVersion: String,
)

enum class GatewayRunState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
}

class PhoneAppController(
    private val runtimeStore: PhoneRuntimeStore,
    private val logStore: PhoneUiLogStore,
    private val loadConfig: () -> PhoneGatewayConfig,
) {
    private val _runState = MutableStateFlow(GatewayRunState.IDLE)
    val runState: StateFlow<GatewayRunState> = _runState
    val snapshot: StateFlow<PhoneRuntimeSnapshot> = runtimeStore.snapshot
    val logs: StateFlow<List<PhoneLogEntry>> = logStore.entries

    suspend fun start(targetDeviceAddress: String) {
        val config = loadConfig()
        runtimeStore.replace(
            PhoneRuntimeSnapshot(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.CONNECTING,
                uplinkState = PhoneUplinkState.OFFLINE,
            ),
        )

        if (config.authToken.isNullOrBlank() || config.relayBaseUrl.isNullOrBlank()) {
            _runState.value = GatewayRunState.ERROR
            Timber.tag("controller").e("missing relay config")
            runtimeStore.replace(
                runtimeStore.snapshot.value.copy(
                    runtimeState = PhoneRuntimeState.ERROR,
                    lastErrorCode = "PHONE_CONFIG_INCOMPLETE",
                    lastErrorMessage = "authToken or relayBaseUrl is missing",
                ),
            )
            return
        }

        _runState.value = GatewayRunState.STARTING
        Timber.tag("controller").i("start requested for $targetDeviceAddress")
    }

    suspend fun stop(reason: String) {
        _runState.value = GatewayRunState.STOPPING
        Timber.tag("controller").i("stop requested: $reason")
        _runState.value = GatewayRunState.STOPPED
    }

    fun clearLogs() {
        logStore.clear()
    }
}
```

Delete `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLogStore.kt`.

- [ ] **Step 6: Run the phone tests and build**

Run:

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug
```

Expected:

- `BUILD SUCCESSFUL`
- `PhoneAppControllerTest` passes using the Timber-to-UI pipeline

- [ ] **Step 7: Commit the `phone-app` integration**

```bash
git add apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone apps/android/phone-app/src/main/AndroidManifest.xml apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt
git add -u apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLogStore.kt
git commit -m "feat: integrate phone timber logging ui"
```

### Task 4: Add The Same Logging Foundation To `glasses-app`

**Files:**
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/GlassesApp.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesLogLevel.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesLogEntry.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogStore.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogTree.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesLoggerBootstrap.kt`
- Modify: `apps/android/glasses-app/src/main/AndroidManifest.xml`
- Test: `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogStoreTest.kt`
- Test: `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogTreeTest.kt`

- [ ] **Step 1: Write the failing `glasses-app` logging tests**

Create `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogStoreTest.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesUiLogStoreTest {
    @Test
    fun `keeps only the most recent entries within capacity`() {
        val store = GlassesUiLogStore(capacity = 2) { 500L }

        store.append(level = GlassesLogLevel.INFO, tag = "one", message = "first")
        store.append(level = GlassesLogLevel.WARN, tag = "two", message = "second")
        store.append(level = GlassesLogLevel.ERROR, tag = "three", message = "third")

        assertEquals(listOf("second", "third"), store.entries.value.map { it.message })
    }

    @Test
    fun `clear removes all entries`() {
        val store = GlassesUiLogStore(capacity = 2) { 500L }

        store.append(level = GlassesLogLevel.INFO, tag = "controller", message = "hello")
        store.clear()

        assertTrue(store.entries.value.isEmpty())
    }
}
```

Create `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogTreeTest.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class GlassesUiLogTreeTest {
    @Test
    fun `forwards timber events into the ui log store`() {
        val store = GlassesUiLogStore(capacity = 5) { 1_717_171_900L }
        val tree = GlassesUiLogTree(store)

        Timber.uprootAll()
        try {
            Timber.plant(tree)

            Timber.tag("glasses-session").w(IllegalArgumentException("bad frame"), "decode failed")

            val entry = store.entries.value.single()
            assertEquals(GlassesLogLevel.WARN, entry.level)
            assertEquals("glasses-session", entry.tag)
            assertEquals("decode failed", entry.message)
            assertEquals(1_717_171_900L, entry.timestampMs)
            assertTrue(entry.throwableSummary!!.contains("bad frame"))
        } finally {
            Timber.uprootAll()
        }
    }
}
```

- [ ] **Step 2: Run the new `glasses-app` tests to verify they fail**

Run:

```bash
cd apps/android && ./gradlew :glasses-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.glasses.logging.GlassesUiLogStoreTest" --tests "cn.cutemc.rokidmcp.glasses.logging.GlassesUiLogTreeTest"
```

Expected:

- FAIL
- Missing production classes under `cn.cutemc.rokidmcp.glasses.logging`

- [ ] **Step 3: Implement the `glasses-app` logging package and bootstrap**

Create `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesLogLevel.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses.logging

enum class GlassesLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        fun fromPriority(priority: Int): GlassesLogLevel = when (priority) {
            2 -> VERBOSE
            3 -> DEBUG
            4 -> INFO
            5 -> WARN
            else -> ERROR
        }
    }
}
```

Create `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesLogEntry.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses.logging

data class GlassesLogEntry(
    val level: GlassesLogLevel,
    val tag: String,
    val message: String,
    val timestampMs: Long,
    val throwableSummary: String? = null,
)
```

Create `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogStore.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GlassesUiLogStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _entries = MutableStateFlow<List<GlassesLogEntry>>(emptyList())
    val entries: StateFlow<List<GlassesLogEntry>> = _entries

    fun append(
        level: GlassesLogLevel,
        tag: String,
        message: String,
        throwableSummary: String? = null,
    ) {
        val next = GlassesLogEntry(
            level = level,
            tag = tag,
            message = message,
            timestampMs = nowMs(),
            throwableSummary = throwableSummary,
        )
        _entries.value = (_entries.value + next).takeLast(capacity)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
```

Create `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesUiLogTree.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses.logging

import timber.log.Timber

class GlassesUiLogTree(
    private val store: GlassesUiLogStore,
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        store.append(
            level = GlassesLogLevel.fromPriority(priority),
            tag = tag?.takeIf { it.isNotBlank() } ?: "app",
            message = message,
            throwableSummary = t?.let { throwable ->
                val summary = throwable.message ?: throwable::class.java.simpleName
                "${throwable::class.java.simpleName}: $summary"
            },
        )
    }
}
```

Create `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/logging/GlassesLoggerBootstrap.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses.logging

import timber.log.Timber

object GlassesLoggerBootstrap {
    private var initialized = false

    fun initialize(logStore: GlassesUiLogStore) {
        if (initialized) return
        initialized = true
        Timber.plant(Timber.DebugTree())
        Timber.plant(GlassesUiLogTree(logStore))
    }
}
```

Create `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/GlassesApp.kt`:

```kotlin
package cn.cutemc.rokidmcp.glasses

import android.app.Application
import cn.cutemc.rokidmcp.glasses.logging.GlassesLoggerBootstrap
import cn.cutemc.rokidmcp.glasses.logging.GlassesUiLogStore

class GlassesApp : Application() {
    val logStore: GlassesUiLogStore by lazy { GlassesUiLogStore() }

    override fun onCreate() {
        super.onCreate()
        GlassesLoggerBootstrap.initialize(logStore)
    }
}
```

- [ ] **Step 4: Register the `glasses-app` application class in the manifest**

Update `apps/android/glasses-app/src/main/AndroidManifest.xml` so the `<application>` node includes:

```xml
<application
        android:name=".GlassesApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.RokidMCPGlasses">
```

- [ ] **Step 5: Re-run the `glasses-app` tests and build**

Run:

```bash
cd apps/android && ./gradlew :glasses-app:testDebugUnitTest :glasses-app:assembleDebug
```

Expected:

- `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit the `glasses-app` logging scaffold**

```bash
git add apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses apps/android/glasses-app/src/main/AndroidManifest.xml apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/logging
git commit -m "feat: scaffold glasses timber logging"
```

### Task 5: Run Final Android Logging Verification

**Files:**
- Verify only: existing files from Tasks 1-4

- [ ] **Step 1: Run the full Android logging verification suite**

Run:

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest :phone-app:assembleDebug :glasses-app:assembleDebug
```

Expected:

- `BUILD SUCCESSFUL`

- [ ] **Step 2: Check worktree status**

Run:

```bash
git status --short
```

Expected:

- no unexpected staged or modified files
- generated directories like `apps/android/.kotlin/` remain untracked and unstaged if present

- [ ] **Step 3: Sanity-check the user-visible outcome manually**

Verify these behaviors in the built apps:

1. `phone-app` launches to a main screen with a rolling log list and a `Clear` action.
2. Triggering controller logging produces entries in both Logcat and the on-screen rolling list.
3. `glasses-app` builds with the same Timber/bootstrap infrastructure in place.

- [ ] **Step 4: Commit any final integration-only adjustments if needed**

If no additional changes were needed after verification, do not create another commit.
