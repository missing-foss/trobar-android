<!--
SPDX-FileCopyrightText: 2026 missing-foss

SPDX-License-Identifier: GPL-3.0-or-later
-->

# FreeDroidWarn, vendored

- **Upstream:** https://github.com/woheller69/FreeDroidWarn
- **Version:** 1.14, tag `V1.14`
- **Archive:** `https://github.com/woheller69/FreeDroidWarn/archive/refs/tags/V1.14.tar.gz`,
  sha256 `e37df4de550a1e37aa6a90e813eb10960a78b915ba474d0c3186a236dc9b85e0`
- **Licence:** Apache-2.0, `LICENSES/Apache-2.0.txt`; copyright woheller69.

Vendored so that a build of this app takes nothing from JitPack, a
build-on-demand repository that builds whatever GitHub coordinate it is asked
for. (F-Droid's inclusion policy does list JitPack among its trusted
repositories, so this isn't what an F-Droid build needs; it's one fewer
source of build-time code.)

**What is upstream's:** `src/main/java/` and `src/main/res/`, copied from
`library/src/main/` in the archive above, byte for byte, with one exception.
Upstream's `res/values-in` is a symlink to `values-id` (`in` is Android's legacy
code for Indonesian). Here it's a real copy of `values-id`, so the repository
holds no symlinks.

**What is ours:** `build.gradle.kts` (upstream's is Groovy, for an older AGP),
and `src/main/AndroidManifest.xml`, which drops upstream's
`package="org.woheller69.freeDroidWarn"` attribute because AGP now takes it as
`namespace` in the build file.

**To update:** download the new tag's archive, check it, replace
`src/main/java/` and `src/main/res/` with its `library/src/main/` ones, and
update the version and sha256 here and in the app's third-party notices.
