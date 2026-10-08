<!--
SPDX-FileCopyrightText: 2026 missing-foss

SPDX-License-Identifier: GPL-3.0-or-later
-->

# Screenshots

| File | Shows |
|---|---|
| `phone-01-enroll.png` | Enrolment, with the server URL and code typed in |
| `phone-02-home.png` | Home: the Recently added covers |
| `phone-03-library.png` | Library: the artist list |
| `phone-04-playlists.png` | Playlists |
| `phone-05-syncing.png` | Settings while a sync runs |
| `phone-06-themes.png` | Settings → Appearance → Theme |
| `phone-07-home-nuech.png`, `phone-08-home-garriga.png`, `phone-09-home-contraste.png` | Home in three of the five themes |
| `phone-10-simple-mode.png` | Simple mode's sync screen |
| `tablet-01-library.png` | The Library's two panes on a tablet |

Phone shots are 1080×2400, from a Pixel 6 emulator image. The tablet shot is
2560×1600, from a Pixel Tablet image. Both use API 35 `google_apis`.

## How they were made

Everything shown is invented: the artists, albums, playlists, cover art and
artist pictures come from trobar-server's **showcase library**. See
"The showcase library" in that repository's `dev/README.md`. The server
address in the shots is `music.example.org`. `example.org` is reserved for
documentation (RFC 2606), and the emulator's hosts file points this name at
the local server.

1. **Server.** In trobar-server's `dev/`, start the showcase stack on a free
   local port, finish first-run setup, and choose the **Filesystem** provider.
   Then sync playlists once, and set any Last.fm username in your profile.
   Suggestions and Most played need one, and the dev mock answers for any
   name. The shots here show Recently added, which needs neither.
2. **Emulator.** Boot an AVD with `-writable-system`, then run `adb root` and
   `adb remount` (reboot once if it asks). Add
   `127.0.0.1 music.example.org` to `/system/etc/hosts`. Then
   `adb reverse tcp:80 tcp:<server port>`, so the phone reaches the server at
   `http://music.example.org`. The hosts entry survives a reboot; the reverse
   does not.
3. **App.** The debug build allows cleartext only to the emulator host and
   localhost. For the capture build, add
   `<domain includeSubdomains="false">music.example.org</domain>` to
   `app/src/debug/res/xml/network_security_config_debug.xml`. **Don't commit
   it.**
4. **Pair** with **Add a mobile device** → **Generate pairing code** in the
   web app. Then select a few artists, albums and playlists for the device and
   run a sync. In the app's Home widget settings, move Library and Devices
   below the cover widgets.
5. **Status bar.** Use demo mode so the shots carry no real clock or
   notifications:
   `adb shell settings put global sysui_demo_allowed 1`, then broadcast
   `com.android.systemui.demo` with `enter`, `clock -e hhmm 0941`,
   `battery -e level 100 -e plugged false`, `notifications -e visible false`,
   and `network -e mobile hide` / `network -e wifi show -e level 4 -e fully true`.
6. Capture with `adb exec-out screencap -p > file.png`.
