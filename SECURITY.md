<!--
SPDX-FileCopyrightText: 2026 missing-foss

SPDX-License-Identifier: GPL-3.0-or-later
-->

# Security

This documents the Trobar Android client's privacy posture and its at-rest
protection of the pairing token. It complements the server's
[SECURITY.md](https://github.com/missing-foss/trobar-server/blob/main/SECURITY.md).

## Reporting a vulnerability

Preferred: GitHub's **private vulnerability reporting** — "Report a
vulnerability" under this repository's Security tab. Or email
**missing_foss@etik.com** with details and, if possible, a way to reproduce.
Please don't open a public issue for anything exploitable until it's been
addressed.

## No telemetry — the app talks only to your server (and, on request, GitHub)

Trobar phones nothing home. Audited (#62), both statically and on a running
emulator:

- **No analytics / crash / tracking SDK** is present — no Firebase, Crashlytics,
  Sentry, Play Services measurement, or similar (verified against the full
  dependency set).
- **The only outbound destinations the app itself contacts are:**
  1. **Your configured Trobar server** — sync, the device API, and artist images
     (which are served *by your server*, not a third party). This URL is the one
     you paired against; nothing is hard-coded.
  2. **`api.github.com`** — the "check for updates" button on the About screen,
     and **only** when you tap it. Never automatic, never in the background.
- The GitHub / Liberapay links on the About screen open in your **browser** via
  an intent — they are not requests the app makes.
- **Dynamic check:** with the app freshly launched and idle, a packet capture
  (`tcpdump`) on the emulator recorded **zero** outbound DNS/connections from the
  app — no beacons, no background calls.

The permissions the app holds (`INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA` for
QR pairing, `POST_NOTIFICATIONS`, and the sync `FOREGROUND_SERVICE`) are all in
service of those two destinations plus on-device sync; none feed analytics.

## The pairing token at rest

Unlike the desktop client (which keeps the token in plaintext on the card by
design), the Android client wraps the device pairing token in a **hardware-backed
Android Keystore AES-256-GCM key** before persisting it (`TokenCrypto.kt`). The
AES key is generated inside the `AndroidKeyStore` and never leaves it, so the
stored token is ciphertext at rest.
