// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later

// FreeDroidWarn, vendored from upstream's V1.14 tag so the build needs no
// JitPack: see UPSTREAM.md. Only this build file is ours; src/ is upstream's,
// unchanged apart from the manifest (its package moved to `namespace` below,
// as current AGP requires).
plugins {
    id("com.android.library")
}

android {
    namespace = "org.woheller69.freeDroidWarn"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Locked like the app (CONTRIBUTING.md): freedroidwarn/gradle.lockfile.
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // ContextCompat is all it uses; upstream pulls it in through appcompat.
    implementation("androidx.core:core-ktx:1.19.1")
}
