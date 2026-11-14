// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later

// The plugins' own classpath is locked too (buildscript-gradle.lockfile), so a
// plugin's transitive dependencies can't move between two builds either.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}
