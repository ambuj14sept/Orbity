import java.time.LocalDateTime
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.orbity.mobile"
    compileSdk = 35
    defaultConfig {
        // Must match the wear module: the Data Layer only connects apps with the same id + signing key.
        applicationId = "com.orbity.remote"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        // Identifies the exact build in the logs.
        buildConfigField("String", "BUILD_TIME", "\"${LocalDateTime.now().withNano(0)}\"")
        // Developer log server reached over Tailscale; set orbity.logServer in local.properties.
        val localProperties = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "LOG_SERVER", "\"${localProperties.getProperty("orbity.logServer", "")}\"")
    }
    signingConfigs {
        // Shared, committed debug key: the phone and watch APKs must be signed with the same key
        // for the Data Layer to connect them, wherever they are built.
        getByName("debug") {
            storeFile = rootProject.file("keystore/orbity-debug.jks")
            storePassword = "android"
            keyAlias = "orbity"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), rootProject.file("proguard-rules.pro"))
            // Same key as debug so phone and watch builds always match.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":shared"))

    val cameraX = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    implementation("androidx.camera:camera-extensions:$cameraX")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Play services pulls in an old Fragment; activity-result APIs need 1.3+.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
