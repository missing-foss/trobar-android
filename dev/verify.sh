#!/usr/bin/env bash
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

echo
if [ "$fail" -eq 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAILED"; fi
exit "$fail"
