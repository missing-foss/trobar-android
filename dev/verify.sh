#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 missing-foss
#
# SPDX-License-Identifier: GPL-3.0-or-later

# Pre-push verification gate for trobar-android. Run from the repo root:
#   dev/verify.sh
#
# What blocks a merge is the `build` job in .github/workflows/ci.yml. It
# compiles the instrumented tests and does not execute them: running them needs
# an emulator, which that job has no business booting for every push. So an
# androidTest dependency bump or API change is caught there, and a test that
# FAILS is not.
#
# Executing them is the separate `instrumented-tests` job, which boots an
# emulator and runs the suite. It is deliberately NOT a required status check
# (see its own comment), so a failure there does not block a merge by itself --
# read it, do not assume a green merge means those tests passed.
#
# This script does not run them either. Locally the behavioural suite executes
# on demand, on a machine with KVM. A green run here says nothing about whether
# those tests pass.
#
# (An earlier version of this header described a CI arrangement that no longer
# matched the workflow beside it. Recorded because the claim was accurate when
# written and became false without anything editing it: when this paragraph and
# the workflow disagree, the workflow is right.)
#
# Two checks here have no counterpart in the workflow beside it: the leak scan,
# which needs a pattern list that is deliberately not in this repository, and
# the tracker-reference check. Neither can be relied on from a CI result.
#
# Signed release builds are separate and local; the build.gradle.kts guard
# pins the canonical key.
set -uo pipefail
fail=0
step() { echo; echo "== $1 =="; }

step "debug build"
./gradlew assembleDebug && echo ok || fail=1

step "JVM unit tests (app/src/test, no device)"
./gradlew testDebugUnitTest && echo ok || fail=1

step "static analysis (detekt)"
./gradlew detekt && echo ok || fail=1

step "brand colours (generated, WCAG AA)"
# BrandTokens.kt and res/values/colors_trobar.xml are generated from
# brand/trobar-themes.json by dev/gen_brand_tokens.py. Fails when either is
# stale or any theme's derived scheme puts a text/background pair under WCAG AA.
python3 dev/gen_brand_tokens.py --check || fail=1

step "single-colour mark (generated, exact geometry)"
# brand/trobar-mark-mono.svg and the three mono drawables come from
# dev/gen_mono_mark.py; fails when any of them is stale.
python3 dev/gen_mono_mark.py --check || fail=1

step "resource strings fit aapt2 (32,767 bytes)"
# A longer attribute (a vector's pathData, say) compiles to STRING_TOO_LARGE
# with only a warning; the drawable then fails to inflate at runtime. The same
# script runs in the forge's CI.
python3 dev/check_res_attr_size.py || fail=1

step "translations (EN/FR parity — MissingTranslation lint, #52)"
# A new string without a values-fr/ counterpart (or a dangling FR whose EN was
# removed) fails the build — scoped to those two lint checks in build.gradle.kts.
./gradlew lintDebug && echo ok || fail=1

step "call sites the device tests can't reach (source check)"
python3 dev/check_call_sites.py || fail=1

step "leak scan (strings that must never ship)"
# The pattern list is NOT in this repository. A denylist that ships the terms
# it exists to exclude publishes exactly what it is protecting -- which is what
# used to happen here. Supply one via LEAK_PATTERNS to run it; with no
# list configured this reports that it did not run rather than passing.
# A `-f` list has no comment syntax and no blank-line syntax: grep takes every
# line in the file as a pattern, including the ones a human wrote as structure.
# So anything not meant as a pattern is stripped before grep sees the list. If
# stripping leaves nothing the list is malformed, and that fails loudly rather
# than scanning against an empty pattern set and reporting "ok". The count is
# printed on success so a list that quietly shrinks is visible.
# The filtered list is fed to grep through process substitution and never
# written to disk. A 0600 file removed on EXIT is still a copy of a list whose
# one handling rule is that it is not copied, and EXIT does not run on SIGKILL,
# on a full disk, or when a CI container is torn down mid-step. This removes
# the cleanup path rather than managing it.
# ONE grep invocation, deliberately. `git ls-files | xargs grep` may run grep
# several times: a temp file is re-opened by each, but a process substitution
# is a pipe and is drained by the first, so later batches would scan against an
# empty pattern set and report clean -- no error, no change in exit status. The
# file list is passed as arguments instead, in an array so paths containing
# spaces survive. Should that list ever outgrow ARG_MAX, this fails loudly with
# E2BIG rather than quietly under-scanning.
if [ -n "${LEAK_PATTERNS:-}" ] && [ -s "${LEAK_PATTERNS}" ]; then
  mapfile -t leak_files < <(git ls-files)
  leak_n=$(grep -vcE '^[[:space:]]*(#|$)' "${LEAK_PATTERNS}")
  if [ "${#leak_files[@]}" -eq 0 ]; then
    # grep with no file operands reads stdin: it would hang rather than scan,
    # and a scan of zero files reporting "ok" would be vacuous either way.
    echo "FAIL: no tracked files to scan"; fail=1
  elif [ "${leak_n:-0}" -eq 0 ]; then
    echo "FAIL: pattern list has no usable patterns (comments and blanks only)"; fail=1
  else
    # grep exits 0 on match, 1 on no match, and 2 on error. An `if` reads 1 and
    # 2 alike, so a tracked file missing from the working tree -- an ordinary
    # mid-edit state -- turned a found leak into "ok": the match was printed and
    # the step still passed. Branch on the status so an incomplete scan fails
    # loudly instead of reporting a clean tree it never finished reading.
    grep -InE -f <(grep -vE '^[[:space:]]*(#|$)' "${LEAK_PATTERNS}") -- "${leak_files[@]}"
    leak_rc=$?
    case "$leak_rc" in
      0) echo "LEAK: forbidden term(s) above"; fail=1 ;;
      1) echo "ok ($leak_n patterns)" ;;
      *) echo "FAIL: leak scan errored (grep exit $leak_rc) -- scanned set incomplete"; fail=1 ;;
    esac
  fi
else
  echo "SKIP (no LEAK_PATTERNS configured)"
fi

step "gitleaks (secrets)"
if command -v gitleaks >/dev/null 2>&1; then
  _g=$(gitleaks git --no-banner . 2>&1); _rc=$?
  printf '%s\n' "$_g"
  # gitleaks reports how many commits it scanned; zero is a pass over nothing.
  _n=$(printf '%s' "$_g" | sed -n 's/.*[^0-9]\([0-9][0-9]*\) commits scanned.*/\1/p' | tail -1)
  if [ "$_rc" -ne 0 ]; then fail=1
  elif [ -z "$_n" ]; then
    echo "FAIL: gitleaks exited 0 but its commit count could not be read -- output format changed?"; fail=1
  elif [ "$_n" -eq 0 ]; then echo "FAIL: gitleaks scanned 0 commits"; fail=1
  else echo "ok ($_n commits scanned)"; fi
else
  echo "SKIP (gitleaks not installed) — CI still runs it"
fi

step "REUSE (per-file SPDX licensing, #50)"
# Every file must declare copyright + license (inline SPDX header, or via
# REUSE.toml for binaries / fonts / the vendored Gradle wrapper). A new
# unlicensed file then fails here rather than shipping unattributed.
if command -v reuse >/dev/null 2>&1; then
  _r=$(reuse lint 2>&1); _rc=$?
  # reuse reports "Files with copyright information: N / M". A lint over zero
  # files is a pass over nothing, and is reported as such.
  _n=$(printf '%s' "$_r" | sed -n 's/.*Files with copyright information: \([0-9][0-9]*\) \/ [0-9][0-9]*.*/\1/p' | tail -1)
  _d=$(printf '%s' "$_r" | sed -n 's/.*Files with copyright information: [0-9][0-9]* \/ \([0-9][0-9]*\).*/\1/p' | tail -1)
  if [ "$_rc" -ne 0 ]; then printf '%s\n' "$_r" | tail -20; fail=1
  elif [ -z "$_n" ]; then
    echo "FAIL: reuse exited 0 but its file count could not be read -- output format changed?"; fail=1
  elif [ "$_n" -eq 0 ]; then echo "FAIL: reuse checked 0 files"; fail=1
  else echo "ok ($_n/$_d files)"; fi
else
  echo "FAIL: reuse is not installed (pipx install reuse) — the licensing check cannot run"; fail=1
fi

step "vendored guards match their declared versions"
# The guards below are copies of missing-foss/dev-guards. This recomputes each
# one's hash and compares it to the version this repository claims, so a guard
# edited in place -- or left behind when the canonical copy moved on -- is a red
# check rather than a silent fork. Six copies of one guard drifting, with
# nothing reporting it, is why the canonical repository exists.
dev/guards/verify-vendored.sh || fail=1

step "themed icons and launcher components (icon packs)"
python3 dev/guards/check-android-launcher.py || fail=1

step "Tracker references in published prose"
# Public docs must stand alone: an issue number that outlives the tracker it
# points at is worse than no citation. Excludes fenced blocks, inline code, hex
# colours and heading anchors -- a guard that false-positives gets switched
# off, and then protects nothing.
if python3 dev/guards/check-tracker-refs.py; then
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
  # A workflow that reads the pin from .tool-versions, and asserts in its own
  # run that it names no literal (see .github/workflows/ci.yml), can't drift.
  ci_derived=$(grep -lE 'java-version: *\$\{\{ *steps\.tools\.outputs\.java *\}\}' .github/workflows/*.yml 2>/dev/null)
  # Both sides must be READABLE before agreement means anything. The previous
  # form guarded with [ -n "$ci_java" ] and fell through to `ok`, so a mirror
  # workflow that stopped naming a version -- or stopped existing -- turned
  # this into a step that compared nothing and reported success.
  if [ -z "$tv_java" ]; then
    echo "FAIL: .tool-versions exists but names no java pin"; fail=1
  elif [ -z "$ci_java" ] && [ -n "$ci_derived" ]; then
    echo "ok (java $tv_java; .github/workflows derives it from .tool-versions: $(echo $ci_derived))"
  elif [ -z "$ci_java" ]; then
    echo "FAIL: no java-version found in .github/workflows -- nothing to compare against"; fail=1
  elif [ "$(printf '%s' "$ci_java" | wc -l)" -gt 0 ]; then
    echo "FAIL: .github/workflows names more than one java version: $(echo $ci_java)"; fail=1
  elif [ "$tv_java" != "$ci_java" ]; then
    echo "PIN DRIFT: .tool-versions says java $tv_java, mirror CI says $ci_java"; fail=1
  else
    echo "ok (java $tv_java, agrees with .github/workflows)"
  fi

  # A workflow can also take the pin out of the comparison entirely by reading
  # .tool-versions itself and feeding the result to setup-java. That cannot
  # drift and is worth doing, but it moves the risk rather than removing it:
  # replacing the interpolation with a literal -- the obvious thing to do while
  # debugging a runner -- gives that workflow a JDK disagreeing with the pin,
  # and a check comparing literals cannot see it, because there is no longer a
  # literal to compare. Any workflow written that way asserts the property
  # itself, as a step that reads its own definition; a check here could only
  # assert it for files this checkout happens to contain.
else
  echo "SKIP (no .tool-versions in this repo)"
fi

echo
if [ "$fail" -eq 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAILED"; fi
exit "$fail"
