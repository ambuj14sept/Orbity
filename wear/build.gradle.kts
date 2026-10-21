import java.time.LocalDateTime

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.orbity.wear"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.orbity.remote"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        // Identifies the exact build in the logs.
        buildConfigField("String", "BUILD_TIME", "\"${LocalDateTime.now().withNano(0)}\"")
    }
    // dev: forwards its logs to the phone app for upload. prod: no diagnostics.
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            buildConfigField("Boolean", "DIAGNOSTICS", "true")
        }
        create("prod") {
            dimension = "channel"
            buildConfigField("Boolean", "DIAGNOSTICS", "false")
        }
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

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
