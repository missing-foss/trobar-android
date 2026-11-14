#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 missing-foss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Source checks for call sites no test on the device can reach.

These are not behaviour tests. The instrumented suite runs on the device,
where the sources aren't, and a JVM unit test would need a new test
dependency; so the few call sites that wire a tested helper into code the
suite can't drive (the activity's callbacks, the worker's body) are pinned
here by reading the source. Each rule says what it protects and why.

Exit 0 when every rule holds, 1 (listing the broken ones) otherwise.
"""

import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "app/src/main/java/org/missing_foss/trobar"

# (file, description, pattern, expected number of matches)
RULES = [
    ("SyncWorker.kt",
     "the first promotion goes through ForegroundPromoter, so a refusal doesn't fail the run",
     r"promoter\.promote\(buildForegroundInfo\(applicationContext, 0, 0\)\)", 1),
    ("SyncWorker.kt",
     "no raw setForeground() of a notification outside the promoter",
     r"\bsetForeground\(buildForegroundInfo", 0),
    ("SyncWorker.kt",
     "progress reaches the notification only through the throttle",
     r"if \(throttle\.due\(p\.done, p\.total, p\.scanning, SystemClock\.elapsedRealtime\(\)\)\)", 1),
    ("MainActivity.kt",
     "Unpair goes through SyncWorker.unpair, which cancels the scheduled sync",
     r"onUnpair = \{ lifecycleScope\.launch \{ SyncWorker\.unpair\(this@MainActivity\) \} \}", 1),
    ("MainActivity.kt",
     "Re-enroll goes through SyncWorker.unpair too",
     r"onReEnroll = \{ lifecycleScope\.launch \{ SyncWorker\.unpair\(this@MainActivity\) \} \}", 1),
    ("MainActivity.kt",
     "nothing in the activity clears the pairing behind SyncWorker.unpair's back",
     r"Prefs\.clearPairing\(", 0),
    ("MainActivity.kt",
     "choosing the sync folder asks for the notification permission, once",
     r"SyncWorker\.schedulePeriodic\(this@MainActivity\)\n\s*\}\n\s*askForNotificationsOnce\(\)", 1),
    ("MainActivity.kt",
     "the ask goes through shouldAskForNotifications and records that it asked first",
     r"if \(!shouldAskForNotifications\(Build\.VERSION\.SDK_INT, granted,\s*Prefs\.notificationPromptDone\(this@MainActivity\)\)\) return@launch\s*Prefs\.setNotificationPromptDone\(this@MainActivity\)\s*notificationPermission\.launch\(Manifest\.permission\.POST_NOTIFICATIONS\)", 1),
    ("SyncWorker.kt",
     "provenance rows of tracks that left the device are pruned against the sync's answer",
     r"result\.heldTrackIds\?\.let \{ store\.prune\(it\) \}", 1),
    ("SyncWorker.kt",
     "a sync is counted toward the thank-you card only when it had no error",
     r"if \(result\.error == null\) \{\s*Prefs\.countSuccessfulSync\(applicationContext\)", 1),
    ("SyncWorker.kt",
     "and nowhere else",
     r"Prefs\.countSuccessfulSync\(", 1),
    ("SyncWorker.kt",
     "and reported to the server best-effort, so an older server's 404 never fails the sync",
     r"Prefs\.countSuccessfulSync\(applicationContext\)\s*try \{\s*api\.reportSyncCompleted\(\)\s*\} catch \(ignored: Exception\)", 1),
    ("HomeScreen.kt",
     "full-mode Home shows the thank-you card first, when it is due",
     r"if \(thanksCardShown\) item\(key = \"thanks_card\"\) \{ ThanksCardIfDue\(\) \}\s*items\(", 1),
    ("SimpleShell.kt",
     "simple mode shows it under the sync status card, when it is due",
     r"SyncStatusCard\(pairing, syncStatus\)\s*// [^\n]*\n\s*if \(thanksCardShown\) ThanksCardIfDue\(\)", 1),
    ("SimpleShell.kt",
     "the Simple sync screen passes the server's storage limit, so the over-limit warning can show",
     r"StorageUsage\(stats, limitBytes = limitBytes,", 1),
    ("SimpleShell.kt",
     "and that limit is the one the server holds for the device",
     r"limitBytes = try \{\s*withContext\(Dispatchers\.IO\) \{ ApiClient\([^)]*\)\.getDeviceInfo\(\)\.maxSizeBytes \}", 1),
]


def main() -> int:
    broken = []
    for name, what, pattern, expected in RULES:
        found = len(re.findall(pattern, (SRC / name).read_text(encoding="utf-8")))
        if found != expected:
            broken.append(f"{name}: {what} (expected {expected} match(es) of /{pattern}/, found {found})")
    for line in broken:
        print("FAIL", line)
    if not broken:
        print(f"ok ({len(RULES)} call sites)")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
