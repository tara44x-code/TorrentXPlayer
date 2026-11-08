<p align="center"><img src="assets/brand/app-icon.png" width="160" alt="Torrent X Player icon"></p>

# Torrent X Player

A neon-themed **Android** app that streams video and audio straight from BitTorrent: open a magnet
link or a `.torrent` file and playback starts in seconds, with near-instant seeking.

Torrent X Player is a mobile port of the open-source
[TorrentPlayer](https://github.com/torrentplayer/torrentplayer) desktop app (MIT or Apache-2.0).
The streaming engine (Rust) is TorrentPlayer's; this fork adds the Android build, a touch-friendly
layout with a bottom tab bar, and a dark neon look.

## Download

Open the [latest release](../../releases/latest) on your phone and download
`TorrentXPlayer-arm64.apk`, then open it to install (allow installs from your browser if Android
asks). It runs on arm64 phones with Android 7.0 or newer. There is no iPhone version.

## What differs from the desktop app

- Downloads are kept in the app's own storage (Android does not let apps write to the shared
  Downloads folder without extra permissions). Uninstalling the app removes them.
- Videos play in a built-in player powered by libVLC (any format, Dolby/DTS audio, embedded
  subtitles), which opens by itself when a stream starts.
- WireGuard keys and the proxy password are stored in a private file inside the app's folder.
- Links to indexer web pages cannot be opened from the app.

## Building

Every push to `main` builds the APK on GitHub Actions (`.github/workflows/android.yml`) and
publishes it as a release. To build locally you need Rust (see `rust-toolchain.toml`), Node.js 22,
JDK 21, and the Android SDK and NDK (`ANDROID_HOME`, `NDK_HOME`):

```sh
rustup target add aarch64-linux-android
cd ui && npm ci
npm run tauri -- android init --ci
npm run tauri -- icon assets/brand/app-icon.png
../scripts/android-prepare.sh
npm run tauri -- android build --apk --target aarch64
```

The release APK is unsigned; sign it with `apksigner`, as the workflow does. To keep one signing
key across builds, add the repository secrets `ANDROID_KEYSTORE_B64` (a base64 `.jks`) and
`ANDROID_KEYSTORE_PASSWORD`.

## License

MIT or Apache-2.0, like TorrentPlayer. See `LICENSE-MIT`, `LICENSE-APACHE` and
`THIRD_PARTY_NOTICES.md`. The original README is in `docs/TorrentPlayer-README.md`.
