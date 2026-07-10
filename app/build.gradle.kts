plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mfoss.trobar"
    compileSdk = 34

    defaultConfig {
        // Renamed from the pre-Trobar applicationId (gitea#66) — a new id, so
        // this is a fresh app to stores/Obtainium; existing installs don't
        // auto-update and must be reinstalled + re-paired.
        applicationId = "com.mfoss.trobar"
        minSdk = 26
        targetSdk = 34
        versionCode = 36
        versionName = "2.4.1"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("TROBAR_KEYSTORE") ?: "release.keystore"
            val keystorePass = System.getenv("TROBAR_KEYSTORE_PASSWORD") ?: ""
            storeFile = file(keystorePath)
            storePassword = keystorePass
            keyAlias = System.getenv("TROBAR_KEY_ALIAS") ?: "trobar"
            keyPassword = System.getenv("TROBAR_KEY_PASSWORD") ?: keystorePass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
