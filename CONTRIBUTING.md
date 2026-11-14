<!--
SPDX-FileCopyrightText: 2026 missing-foss

SPDX-License-Identifier: GPL-3.0-or-later
-->

# Contributing

This is the Android client of Trobar — the contribution guidelines,
issue tracker conventions, and dev-environment notes live in the main
server repository's `CONTRIBUTING.md` and `docs/`. Short version: open an
issue before large PRs, AI-assisted patches are fine if you say so in the PR,
`./gradlew assembleDebug` must pass, contributions are `GPL-3.0-or-later`
like the client itself.

Before opening a pull request, run `dev/verify.sh` from the repository root.
It builds the debug APK and runs detekt, the generated brand colours and
single-colour mark, the resource size limit, the EN/FR translation check,
the licensing (REUSE) and secret scans, and the repository's guards. It ends
in `VERIFY OK` or `VERIFY FAILED`; a step that prints `SKIP` did not run, so
read the step list rather than the last line. The instrumented tests need an
emulator and are run separately (`./gradlew connectedDebugAndroidTest`).

Every dependency is locked, transitive ones included (`app/gradle.lockfile`,
`freedroidwarn/gradle.lockfile`, `buildscript-gradle.lockfile`). If you add or
change one, rewrite the lockfiles with
`./gradlew :app:dependencies :freedroidwarn:dependencies buildEnvironment --write-locks` and commit
them with your change. Otherwise the build fails, naming what isn't locked.

Public issues and PRs live here on GitHub.

Pull requests opened here are reviewed and then shipped by the maintainers
rather than merged in place, so your commits may arrive under a release
commit rather than your own. Your pull request is closed with a note
crediting you when the change lands. Keep changes focused — a small pull
request with a clear rationale is much easier to take than a large one.

Contributing a translation? See [Translating Trobar](https://missing-foss.github.io/trobar-server/project/translations/).

Security issues: not in the public tracker — missing_foss@etik.com.
