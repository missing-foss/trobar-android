// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // #21: FreeDroidWarn publishes nowhere but JitPack, so adopting it
        // means a third build-time artifact source. Narrowed to the single
        // group it is here for: JitPack builds whatever GitHub coordinate is
        // asked of it, so an unscoped entry would let any `com.github.*`
        // coordinate resolve from it, and would also let it answer for
        // artifacts that must only ever come from the two repositories above.
        // With this filter it is consulted for that one group and nothing
        // else, and it is consulted last.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.woheller69") }
        }
    }
}

rootProject.name = "trobar-android"
include(":app")
