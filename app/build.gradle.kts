import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing (your Google Play upload key): put it in keystore.properties at the project root
// (storeFile, storePassword, keyAlias, keyPassword; never commit it) or in SNAPSTACK_* environment
// variables / CI secrets. Without a key, release APKs are signed with the local debug key so they can
// still be sideloaded, but a Play bundle (bundleRelease) refuses to build.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)
val releaseKeystore: String? = signingValue("storeFile", "SNAPSTACK_KEYSTORE")

// Play bundles: Play builds per-device APKs itself, and AGP can't bundle while ABI splits are on
// (issuetracker.google.com/402800800). Play also rejects debug-signed bundles, so stop right away.
val buildingBundle = gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("bundle") }
if (buildingBundle && gradle.startParameter.taskNames.any { it.contains("Release") } && releaseKeystore == null) {
    throw GradleException(
        "bundleRelease needs your upload key: create keystore.properties (see docs/play-store/RELEASE.md) " +
            "or set SNAPSTACK_KEYSTORE, SNAPSTACK_KEYSTORE_PASSWORD, SNAPSTACK_KEY_ALIAS and SNAPSTACK_KEY_PASSWORD."
    )
}

android {
    namespace = "app.tcgportfolio"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tcgportfolio"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.12.1"
    }

    // ML Kit's on-device OCR is native code (~11 MB per ABI). Build a small APK per ABI plus a
    // universal one; 32-bit x86 (no real phones) is dropped entirely.
    splits {
        abi {
            isEnable = !buildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("storePassword", "SNAPSTACK_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SNAPSTACK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SNAPSTACK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Symbol tables for native crash stack traces in Play Console (ML Kit's prebuilt libraries
            // ship stripped, so Play may still note that some symbols are missing).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        jniLibs.excludes += setOf("lib/x86/**")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
