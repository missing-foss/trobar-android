# SPDX-FileCopyrightText: 2026 missing-foss
#
# SPDX-License-Identifier: GPL-3.0-or-later

# R8 rules for the release build, on top of proguard-android-optimize.txt and
# the rules AAPT writes for everything the manifest names (activities, the
# launcher aliases' target, services, receivers, the zxing CaptureActivity).
#
# No rules are needed today. What was checked, with the bytecode of the
# versions in app/build.gradle.kts:
#   - zxing-android-embedded and Vico (compose, compose-m3) make no reflective
#     calls (no Class.forName, getDeclared*, newInstance), and ship no consumer
#     rules.
#   - zxing core calls Charset.forName only, a charset lookup, not a class.
#   - JSON goes through the platform's org.json, which R8 doesn't touch.
#   - WorkManager ships its own rules for workers it creates by name.
# A minified build of the app launched, showed the FreeDroidWarn notice,
# rendered enrolment, and opened the QR scanner without a crash.
#
# Add a rule here only with the failure it fixes, and the class it keeps.
