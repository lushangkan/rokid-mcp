plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.cutemc.rokidmcp.integration"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 32
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":share"))
    testImplementation(project(":share"))
    testImplementation(project(":phone-app"))
    testImplementation(project(":glasses-app"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp)
    testImplementation(libs.robolectric)
}
