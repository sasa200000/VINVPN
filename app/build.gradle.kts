plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ffvpn.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ffvpn.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"
    }

    // A fixed key kept in the repo. Every build, local or CI, signs with the
    // same certificate, so a new APK updates over an installed one instead of
    // failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/vinvpn.keystore")
            storePassword = "VinVpn!2026"
            keyAlias = "vinvpn"
            keyPassword = "VinVpn!2026"
        }
    }

    // One APK per CPU family plus a universal one that runs anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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

    composeOptions {
        // Pairs with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // The engine is exec'd from nativeLibraryDir, so the .so files must
            // stay real files on disk instead of being mapped from the APK.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libaether.so",
                "**/libc2tun.so",
                "**/libffvpn.so",
                "**/libfluxcore.so",
                "**/liblantern.so",
                "**/liblyrebird.so",
                "**/libgojni.so",
                "**/libopenrung.so",
                "**/libsstp.so",
                "**/libsunlion.so",
                "**/libsunlionbridge.so",
                "**/libtor.so",
                "**/libtorrunner.so"
            )
        }
    }
}

// amneziawg-android drags okhttp 5.x, whose kotlin-stdlib 2.2.0 carries class
// metadata this project's Kotlin 1.9.24 cannot read (it stops at 2.0) — every
// Kotlin file in the app then fails to compile. The Proton account API is
// written against okhttp 4.x (same API, metadata 1.8, readable), and the DNS
// over HTTPS okhttp is even there for never runs here: this app's configs point
// at plain IPs, so endpoint resolution never enters that path.
configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:4.12.0")
        force("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
    }
}

dependencies {
    // The official Psiphon engine for Android (gomobile psi.Psi), published as a
    // release artifact by Psiphon-Labs/psiphon-tunnel-core-binaries. The engine
    // used to be a vendored .so built on clientlib, whose StartTunnel closes its
    // "connected" channel again when the engine replaces a dead tunnel and takes
    // the whole process down with "panic: close of closed channel".
    implementation(files("libs/ca.psiphon.aar"))

    // AmneziaWG for the Proton core: the tunnel backend (libam-go) and, with it,
    // okhttp (the Proton account API) and the WebView proxy override support.
    // hevtunnel (a transitive) asks for androidx.core 1.17, which wants compile
    // SDK 36 and AGP 8.9 — this project builds on 35 and 8.7, and its own core
    // (1.13.1, above) is what stays in the graph. The tunnel path this app runs
    // never enters hevtunnel's own code.
    implementation("com.zaneschepke:amneziawg-android:2.1.13") {
        exclude(group = "androidx.core", module = "core-ktx")
        exclude(group = "androidx.core", module = "core")
    }
    implementation("androidx.webkit:webkit:1.11.0")
    // Compile against okhttp directly: amneziawg carries it at runtime scope
    // only, and the version is pinned to 4.12 above (metadata story there).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation("androidx.compose.ui:ui:1.6.6")
    implementation("androidx.compose.ui:ui-graphics:1.6.6")
    implementation("androidx.compose.foundation:foundation:1.6.6")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling:1.6.6")
}
