#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 missing-foss
#
# SPDX-License-Identifier: GPL-3.0-or-later

# Pre-push verification gate for trobar-android. Run from the repo root:
#   dev/verify.sh
# CI (.github/workflows/ci.yml) runs the same checks. Signed release builds
# are separate and local; the build.gradle.kts guard pins the canonical key.
set -uo pipefail
fail=0
step() { echo; echo "== $1 =="; }

step "debug build"
./gradlew assembleDebug && echo ok || fail=1

step "static analysis (detekt)"
./gradlew detekt && echo ok || fail=1

step "translations (EN/FR parity — MissingTranslation lint, #52)"
# A new string without a values-fr/ counterpart (or a dangling FR whose EN was
# removed) fails the build — scoped to those two lint checks in build.gradle.kts.
./gradlew lintDebug && echo ok || fail=1

step "leak scan (household infra must never ship)"
if git ls-files | xargs grep -InE "mphp|soundsync|renoir|192\.168\.50|/nfs/" 2>/dev/null \
     | grep -viE "\.lock$|workflows/ci\.yml|dev/verify\.sh"; then
  echo "LEAK: forbidden term(s) above"; fail=1
else
  echo "ok"
fi

step "gitleaks (secrets)"
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks git --no-banner . && echo ok || fail=1
else
  echo "SKIP (gitleaks not installed) — CI still runs it"
fi

step "REUSE (per-file SPDX licensing, #50)"
# Every file must declare copyright + license (inline SPDX header, or via
# REUSE.toml for binaries / fonts / the vendored Gradle wrapper). A new
# unlicensed file then fails here rather than shipping unattributed.
if command -v reuse >/dev/null 2>&1; then
  if reuse lint >/dev/null 2>&1; then echo ok; else reuse lint | tail -20; fail=1; fi
else
  echo "SKIP (reuse not installed — pipx install reuse) — CI still runs it"
fi

echo
if [ "$fail" -eq 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAILED"; fi
exit "$fail"
