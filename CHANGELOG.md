<!--
SPDX-FileCopyrightText: 2026 missing-foss

SPDX-License-Identifier: GPL-3.0-or-later
-->

# Changelog

What changed for someone using the Trobar Android app, by version, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) shape. A release's date
is its tag's. Versions before 2.14.0 are in the git log.

## [Unreleased]

## [2.16.0]

### Added

- Playlists made and edited on the phone: New playlist, and for a playlist made
  in Trobar, Rename, Delete (which says its mirrors go too) and reordering.
- Tapping an album opens its page, with the cover, the tracklist and Add to
  basket. A double-tap still stages it.
- The Library groups by album artist, so a compilation shows once, and an
  artist's page lists the albums they also appear on.
- Settings can choose Artist images (Off, Small or Full) on the phone.
- Playlists added from a public Tidal link show Tidal's mark and name and the public-link badge, and a failed fetch names Tidal.
- A sort menu on the Playlists screen: Availability, Most tracks, Newest in
  Trobar or Name, as on the web. The phone remembers the choice.
- An open playlist has a Sync entire playlist button, and tapping a track offers
  to stage just that track or open its artist or album in the Library.
- Tapping a device on Home shows what is selected for it, and each can be removed.
- A Playlists widget on Home: the playlists with the most left to sync to the chosen device.
- A one-time thank-you card on Home, after 50 successful syncs.
- The app asks for the notification permission once, after the folder picker,
  and Settings shows whether it's allowed.
- A device without a mobile radio can choose hotspot sync, and sees its limit.

### Changed

- The Home album widgets (Suggestions, Recently added, Recently released) leave
  out albums already selected for this phone. An empty one says so, naming the
  phone.
- Before the first download a sync says how many tracks, how much space and how
  much is free. After a run, Settings lists what failed and why, up to 50.
- Playlist subscriptions show their provider's mark, and a playlist from a
  public link carries a link badge.
- Enrolment shows the code as eight boxes, with Paste, and continues only with a
  full address and code. With the server away, Settings shows a card at the top
  instead of hiding the whole screen.
- During a sync, Settings leaves the live progress to the bottom indicator.
- Files Trobar writes or removes are reported to the media index, so other
  players see them without a reboot.
- Downloads go to a part file and are renamed into place. A download resumes
  only within a sync.
- Space checks keep a reserve, and count transcoded tracks at an estimate. A
  sync says up front when the downloads can't fit, and stops if the folder fills.
- Sync lists each folder once on a SAF tree.
- Support links go to Ko-fi, with a line saying what the money pays for.
- AIFF (`.aiff`, `.aif`) counts as audio, and `.alac` is dropped.
- A send that empties the basket goes back to the screen the basket was opened
  from, and says what was sent there.

### Fixed

- Duel the bard keeps its size through a game, so the board no longer jumps
  when it ends. The winning line is highlighted, each ending has its own short
  animation (none with Remove animations on), and taps make no sound.
- A refused token says so, isn't retried, and unpairing stops the schedule.
- A refused foreground promotion no longer fails a sync.
- A download that can't resume starts over instead of wedging.

Some of this needs a newer Trobar server. Making and editing playlists, album
pages, the Library by album artist and what is selected for a device need
trobar-server 2.17.0. The Home widgets leaving out this phone's albums, public
Tidal links and choosing Artist images need 2.18.0. With an older server, those
parts keep their 2.15.0 behaviour or say the server needs updating.

## [2.15.0]

Needs Trobar server 2.16.0 for the Playlists tab (App API level 2). With an older
server, the app tells you to update the server.

### Added

- A Playlists tab: browse playlists and their entries, sync with the provider,
  review unmatched entries, request missing albums from Lidarr, share a playlist
  with the household or make it private, and subscribe from a link.
- A Home dashboard with your account's widgets, which you can reorder, remove
  and add back.
- A Library with searched and sorted artists, albums as a list or cover grid,
  artist pictures and similar artists.
- Double-tap an album or playlist to stage it for the chosen device. The Basket
  has one section per device and sends per device.
- Swipe between tabs. Settings replaces the Sync tab, and a sync indicator shows
  across the app.
- App mode: Simple (sync only) or Full, automatic or pinned.
- Pairing can read the QR code from an image.
- Playlists created in the sync folder are sent to the server.
- A language picker, using the system's page on Android 13 and later.
- The Occitan rosette and five themes.
- A warning for sideloading users about Google's developer verification.

### Fixed

- Layout fixes on narrow phones (320 dp): no words broken mid-word, and headers,
  counts and legends stay on one line.
- Cover grids use as many columns as the screen width holds.
- Library search finds album titles, and typographic hyphens and accents.

## [2.14.0]

### Fixed

- Pairing is no longer lost after a reboot. If it was already lost, pair again
  once after upgrading.

### Changed

- The periodic sync can wait for charging and idle.
- French uses vous, not tu.
