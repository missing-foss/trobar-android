import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.mfoss.trobar"
    compileSdk = 37

    defaultConfig {
        // Renamed from the pre-Trobar applicationId — a new id, so
        // this is a fresh app to stores/Obtainium; existing installs don't
        // auto-update and must be reinstalled + re-paired.
        applicationId = "com.mfoss.trobar"
        minSdk = 26
        targetSdk = 34
        versionCode = 44
        versionName = "2.10.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("TROBAR_KEYSTORE") ?: "release.keystore"
            val keystorePass = System.getenv("TROBAR_KEYSTORE_PASSWORD") ?: ""
            val alias = System.getenv("TROBAR_KEY_ALIAS") ?: "trobar"
            storeFile = file(keystorePath)
            storePassword = keystorePass
            keyAlias = alias
            keyPassword = System.getenv("TROBAR_KEY_PASSWORD") ?: keystorePass
            // Guard: every release must be signed with the canonical Trobar key.
            // If TROBAR_KEYSTORE is ever pointed at the archived legacy key (or
            // any other), the fingerprint won't match and the build fails loudly
            // rather than shipping an unupdatable APK. Only runs when a keystore
            // password is present (i.e. a real release build); debug/CI skip it.
            // The fingerprint is public (published in the README) — not a secret.
            if (keystorePass.isNotEmpty() && file(keystorePath).exists()) {
                val expected = "674aea874ab6996a0447102939715707e95ddb00ed043e2b4e22837307f0f376"
                val ks = KeyStore.getInstance("PKCS12")
                file(keystorePath).inputStream().use { ks.load(it, keystorePass.toCharArray()) }
                val cert = ks.getCertificate(alias) as? X509Certificate
                    ?: throw GradleException("Alias '$alias' not found in $keystorePath")
                val fp = MessageDigest.getInstance("SHA-256")
                    .digest(cert.encoded)
                    .joinToString("") { b -> "%02x".format(b) }
                if (fp != expected) {
                    throw GradleException(
                        "Release keystore fingerprint $fp does not match the canonical " +
                        "Trobar signing key ($expected). Refusing to build — point " +
                        "TROBAR_KEYSTORE at the current key, not the archived legacy one.")
                }
            }
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
    buildFeatures {
        compose = true
    }
    lint {
        // #52: EN/FR string parity is a build gate. A new string with no FR
        // translation (MissingTranslation) — or a dangling FR whose EN was
        // removed (ExtraTranslation) — fails the build. Scoped to just those
        // two so `lintDebug` stays a fast, focused i18n check, not the full
        // lint suite (the app's other lint findings aren't gated here).
        checkOnly += listOf("MissingTranslation", "ExtraTranslation")
        error += listOf("MissingTranslation", "ExtraTranslation")
        abortOnError = true
    }
}

detekt {
    // Repo-relative override on top of detekt's own recommended defaults —
    // same shape as the server's pyproject.toml scoped ignores for mypy.
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // org.json is part of the Android platform (bootclasspath, since API 1), so
    // JSONObject/JSONArray resolve without an explicit dependency. We deliberately
    // do NOT declare org.json:json — that artifact is under the GPL-incompatible
    // "JSON License" ("...used for Good, not Evil"), and as an `implementation`
    // dep it would be packaged into this GPL-3.0 APK. See #28.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
