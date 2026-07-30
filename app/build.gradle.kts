plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cn.wty5.term"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "cn.wty5.term"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 26073101
        versionName = "v1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per ABI: binaries live flat under src/<flavor>/assets/
    // (bash, coreutils, curl) — no runtime ABI folder lookup needed.
    // Both flavors share the same versionCode / versionName base; only the
    // versionNameSuffix differs so sideload installs stay distinguishable.
    // Flavor sourceSets apply to ALL build types automatically, so the four
    // variants are: arm64Debug, arm64Release, x86_64Debug, x86_64Release.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
            versionNameSuffix = "-arm64"
        }
        create("x86_64") {
            dimension = "abi"
            ndk {
                abiFilters.clear()
                abiFilters.add("x86_64")
            }
            versionNameSuffix = "-x86_64"
        }
    }

    buildTypes {
        // debug uses AGP defaults (debuggable, no minify). Flavor assets +
        // ndk.abiFilters from productFlavors above are merged in for free —
        // no extra debug-specific ABI config is required.
        debug {
            // Keep package id identical so debug installs replace each other
            // cleanly when switching flavors on the same device/emulator.
            // applicationIdSuffix is intentionally NOT set.
        }
        release {
            isCrunchPngs = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    ndkVersion = "28.2.13676358"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
    implementation(libs.androidx.appcompat)
    // implementation(libs.androidx.camera.camera2)
    // implementation(libs.androidx.camera.core)
    // implementation(libs.androidx.camera.lifecycle)
    // implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.drawerlayout)
    // implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp)
}
