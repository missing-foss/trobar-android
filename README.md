# Trobar for Android

The Android client of [Trobar](https://github.com/missing-foss/trobar-server)
— self-hosted music library sync. Pick artists, albums, or playlists in the
web app; this client keeps them offline on the phone, tablet, watch, or
Android-based DAP, in original quality (or transcoded, if the device is set
up that way server-side).

## Install

Grab the APK from Releases (tags `android-vX.Y.Z`) — or add this repository
to [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates.

## Pair

Create a device in the Trobar web app and scan the QR code with the app's
pairing screen, then pick a sync folder. Full walkthrough:
[docs/clients.md](https://github.com/missing-foss/trobar-server/blob/main/docs/clients.md)
in the server repository.

## Build

Standard Gradle project: `./gradlew assembleDebug`. Release builds are
signed with the maintainer's key; to sign your own, set the
`TROBAR_KEYSTORE*` environment variables (see the server repository's
`docs/troubleshooting.md`) — and keep using the same keystore forever,
Android refuses updates across a signature change.

## License

`GPL-3.0-or-later` — see [LICENSE](LICENSE). Contributions are welcome
under the same license; see [CONTRIBUTING.md](CONTRIBUTING.md).
