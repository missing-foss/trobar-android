#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 missing-foss
#
# SPDX-License-Identifier: GPL-3.0-or-later

# Pre-push verification gate for trobar-android. Run from the repo root:
#   dev/verify.sh
# CI (.github/workflows/ci.yml) runs most of these, plus two things this
# script does NOT: it compiles the instrumented tests and runs them on an
# emulator. So a green run here is not a green CI run — that is the one
# direction worth remembering, because it is the one that lets a push fail
# after this script said everything passed.
#
# The other direction: the leak scan, the tracker-reference check and the
# toolchain-pin check run only here.
#
# Signed release builds are separate and local; the build.gradle.kts guard
# pins the canonical key.
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

step "leak scan (strings that must never ship)"
# The pattern list is NOT in this repository. A denylist that ships the terms
# it exists to exclude publishes exactly what it is protecting -- which is what
# used to happen here. Supply one via LEAK_PATTERNS to run it; with no
# list configured this reports that it did not run rather than passing.
if [ -n "${LEAK_PATTERNS:-}" ] && [ -s "${LEAK_PATTERNS}" ]; then
  if git ls-files | xargs grep -InE -f "${LEAK_PATTERNS}" 2>/dev/null; then
    echo "LEAK: forbidden term(s) above"; fail=1
  else
    echo "ok"
  fi
else
  echo "SKIP (no LEAK_PATTERNS configured)"
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
  echo "FAIL: reuse is not installed (pipx install reuse) — the licensing check cannot run"; fail=1
fi

step "Tracker references in published prose"
# Public docs must stand alone: an issue number that outlives the tracker it
# points at is worse than no citation. Excludes fenced blocks, inline code, hex
# colours and heading anchors -- a guard that false-positives gets switched
# off, and then protects nothing.
if python3 dev/check-tracker-refs.py docs README.md SECURITY.md CONTRIBUTING.md; then
  echo ok
else
  fail=1
fi

echo
step "Toolchain pin agrees with CI"
# .tool-versions is the source of truth; CI must not drift from it. Deliberately
# a consistency check rather than making CI read the exact string: setup-java's
# acceptance of a "17.0.20+8" style version is not something this repo can test,
# and a check that both agree catches the drift either way.
if [ -f .tool-versions ]; then
  tv_java=$(awk '/^java /{print $2}' .tool-versions | sed 's/^temurin-//; s/\..*//')
  ci_java=$(grep -hoE 'java-version: *"?[0-9]+' .github/workflows/*.yml 2>/dev/null | grep -oE '[0-9]+' | sort -u)
  if [ -n "$tv_java" ] && [ -n "$ci_java" ] && [ "$tv_java" != "$ci_java" ]; then
    echo "PIN DRIFT: .tool-versions says java $tv_java, CI says $ci_java"; fail=1
  else
    echo ok
  fi
else
  echo "SKIP (no .tool-versions in this repo)"
fi

echo
if [ "$fail" -eq 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAILED"; fi
exit "$fail"
