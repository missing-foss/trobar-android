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

// Every dependency the build resolves, transitive ones included, is pinned in
// gradle.lockfile: a build that would resolve anything else fails instead of
// quietly picking it up. After changing a dependency, rewrite the lockfiles
// with `./gradlew :app:dependencies :freedroidwarn:dependencies
// buildEnvironment --write-locks` and commit them with the change. Renovate's
// pull requests need the same, which works only if its runner can build the
// app.
dependencyLocking {
    lockAllConfigurations()
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
        versionCode = 51
        versionName = "2.16.0"
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
            // R8 shrinks and obfuscates the release build. The libraries need
            // no keep rules of their own: see proguard-rules.pro for what was
            // checked. Resource shrinking stays off: launcher icons and themes
            // are looked up by name at run time.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only when TROBAR_KEYSTORE is set, as the release builds
            // that ship are. Without it the release build is unsigned, as an
            // F-Droid build needs, and AGP names it app-release-unsigned.apk;
            // the warning below says so on every release build without it.
            if (System.getenv("TROBAR_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // AGP otherwise adds a block of dependency metadata to the APK's signing
    // block, encrypted for Google Play with fresh randomness each time, so no
    // two builds of the same source are byte-identical. Only Google Play
    // reads it, and Trobar is not on Google Play; a reproducible build, which
    // F-Droid needs to ship the APK signed with our own key, needs it gone.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The three-tab shell and its pushed destinations; replaces an eight-way
    // `when` over booleans that had no back stack. Exact pin, Google Maven.
    implementation("androidx.navigation:navigation-compose:2.10.2")
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
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
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
        implementation("androidx.concurrent:concurrent-futures:1.3.0")
        implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
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
    // requirement will stop this app working on certified devices. Vendored from
    // upstream's V1.14 tag as the :freedroidwarn module (freedroidwarn/UPSTREAM.md),
    // so the build takes nothing from JitPack.
    implementation(project(":freedroidwarn"))

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
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")

    // #169: plain JVM unit tests, ./gradlew testDebugUnitTest -- no device, so
    // both CI copies run them on every change. The version is the one the
    // instrumented tests already resolve through androidx.test.ext:junit.
    testImplementation("junit:junit:4.13.2")
}

// A release build without TROBAR_KEYSTORE is unsigned (see buildTypes): say so
// loudly, naming the missing variable, whenever a release package is built.
// Not on debug or test builds, which never sign with it.
gradle.taskGraph.whenReady {
    val packagesARelease = allTasks.any { task ->
        task.project == project && task.name.contains("Release") &&
            (task.name.startsWith("assemble") || task.name.startsWith("bundle") || task.name.startsWith("package"))
    }
    if (packagesARelease && System.getenv("TROBAR_KEYSTORE") == null) {
        logger.warn(
            "WARNING: TROBAR_KEYSTORE is not set, so this release build is UNSIGNED " +
                "(app-release-unsigned.apk). A signed release needs TROBAR_KEYSTORE and its password variables."
        )
    }
}
