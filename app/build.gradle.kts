plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.olcos.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.olcos.app"
        minSdk = 26
        targetSdk = 35
        // Releases are tagged v<versionName>.<versionCode> on Hotelk52339/olcos-android;
        // the app compares the code, so bump it for every release.
        versionCode = 3
        versionName = "3.0"
    }

    // CI signs debug builds with one shared keystore (the DEBUG_KEYSTORE_B64
    // secret), so a new APK installs over the previous one; without the
    // environment variable the usual per-machine debug keystore is used.
    signingConfigs {
        getByName("debug") {
            val shared = System.getenv("OLCOS_DEBUG_KEYSTORE")
            if (shared != null) {
                storeFile = file(shared)
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "olcos"
                keyPassword = "android"
            }
        }
        // Releases are signed with the same shared key, so a release installs
        // over a test build and the other way round.
        create("release") {
            val shared = System.getenv("OLCOS_DEBUG_KEYSTORE")
            if (shared != null) {
                storeFile = file(shared)
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "olcos"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("OLCOS_DEBUG_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // The Go core brings one shared library per ABI; nothing else to merge.
        jniLibs.useLegacyPackaging = false
        // jsch and Bouncy Castle both ship OSGi manifests; neither is read on Android.
        resources.excludes += setOf("META-INF/versions/**/OSGI-INF/MANIFEST.MF", "META-INF/versions/**/module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    // The Go core bound with gomobile (scripts/build-core.sh); built by CI.
    implementation(files("libs/olcoscore.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // The QR scanner: CameraX frames, decoded by the zxing core already here.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // The server install over SSH (core/SSHClient.kt): jsch, with Bouncy
    // Castle for ed25519 keys and the curve25519 exchange.
    implementation(libs.jsch)
    implementation(libs.bouncycastle)
    // The session stores (data/SecurePrefs.kt): encrypted at rest.
    implementation(libs.androidx.security.crypto)

    // The JVM unit tests under app/src/test: the pure parts of the services.
    testImplementation(libs.junit)
}
