// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "org.missing_foss.trobar"
    compileSdk = 37

    defaultConfig {
        // Renamed from the pre-Trobar applicationId — a new id, so
        // this is a fresh app to stores/Obtainium; existing installs don't
        // auto-update and must be reinstalled + re-paired.
        applicationId = "org.missing_foss.trobar"
        minSdk = 26
        targetSdk = 34
        versionCode = 50
        versionName = "2.15.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" // #61
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
                val expected = "435a96631d400c88ecaf635aeadd1f5bf71f6260d29a614947362d27edf3f52e"
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
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The three-tab shell and its pushed destinations; replaces an eight-way
    // `when` over booleans that had no back stack. Exact pin, Google Maven.
    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // ProcessLifecycleOwner: the launcher icon changes when the app leaves the
    // foreground. Already in the build transitively; declared for that use.
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    // State holders for the App API screens (basket, library, dashboard):
    // server state survives rotation and tab switches. Same family and
    // version as the runtime above; approved by the maintainer on the
    // client issue. Exact pin, Google Maven.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // Charts for the Home dashboard's library widget (by format, by decade).
    // Chosen for being Compose-native with a Material 3 theme provider, so
    // the colours follow the app's theme in dark mode and under dynamic
    // colour; published on Maven Central, no extra repository. The
    // charting dependency itself was approved by the maintainer on the
    // epic. Exact pin; `compose` is its core and is pinned to the same
    // version so a transitive bump cannot split them.
    implementation("com.patrykandpatrick.vico:compose:3.3.1")
    implementation("com.patrykandpatrick.vico:compose-m3:3.3.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // Covers and artist pictures in the library and Home screens. Coil 3 on
    // its OkHttp fetcher, so images ride the app's one shared client (and its
    // credential interceptor) rather than a second HTTP stack; cache-control
    // so the disk cache honours the server's max-age instead of keeping a
    // cover forever. Exact pin, Maven Central, Apache-2.0.
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-cache-control:3.6.3")
    constraints {
        // work-runtime brings androidx.concurrent 1.1.0 into the app and the
        // instrumented-test runner needs 1.2.0; the Android plugin holds the
        // test classpath to the app's versions, so the two must agree. Coil's
        // arrival (its profileinstaller asks for the same module) is what
        // first made the disagreement fatal. Meet at 1.2.0 in the app.
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    }
    // org.json is part of the Android platform (bootclasspath, since API 1), so
    // JSONObject/JSONArray resolve without an explicit dependency. We deliberately
    // do NOT declare org.json:json — that artifact is under the GPL-incompatible
    // "JSON License" ("...used for Good, not Evil"), and as an `implementation`
    // dep it would be packaged into this GPL-3.0 APK.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // #21: warns sideloading users that Google's developer-verification
    // requirement will stop this app working on certified devices. Pinned to an
    // exact tag, NOT upstream's suggested `V1.+` — a floating coordinate from a
    // build-on-demand repository decides at build time what ends up in a signed
    // release. Resolves only from the narrowed JitPack entry in
    // settings.gradle.kts. Its own appcompat floor is 1.5.1, below the 1.7.1
    // above, so it adds no constraint of its own.
    implementation("com.github.woheller69:FreeDroidWarn:V1.14")

    // #61: instrumented Compose UI tests — ./gradlew connectedAndroidTest (needs
    // a device/emulator). ui-test-manifest lets the harness host composables.
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Same version as the app's own OkHttp dep (line 112) — a real local HTTP
    // server standing in for trobar-server, so EnrollmentWizard/SettingsScreen/
    // StatusScreen's actual ApiClient/redeemEnrollment network calls hit
    // something real rather than needing a mocking seam neither has today.
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
