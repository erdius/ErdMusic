<img align="left" src="logo.svg" width="100" height="100" alt="ErdMusic Logo">

<br clear="all" />

# ErdMusic

A calm, E‑ink‑friendly **local music player** that puts your attention and privacy first.

ErdMusic is a fork of [CalmMusic](https://github.com/davidraywilson/CalmMusic) by David Ray Wilson, stripped down to do one thing well: play the music files already on your device. All streaming functionality (YouTube Music, Apple Music) has been removed — no accounts, no network, no noise.

"Let's make technology useful again."

## What makes ErdMusic different?

ErdMusic is for people who want **less noise and more music** — especially on de‑googled phones and E‑ink devices like the Mudita Kompakt.

- **Local files only**  
  No streaming services, no sign‑ins, no network requests. Your music library is the folders you choose.
- **Built for E‑ink and low‑distraction screens**  
  Large text, high contrast, minimal animations, and layouts that still feel good at slow refresh rates (uses the Mudita Mindful Design library).
- **Mindful by design**  
  No feeds, badges, or engagement tricks — just simple screens that do one job well.
- **Privacy‑respecting**  
  No tracking, no analytics SDKs, no ads. Your listening stays on your device.

## Features

### Your local library

- Choose exactly which folders on your device ErdMusic is allowed to scan.
- The app indexes supported audio files (mp3, m4a, aac, flac, wav, ogg, mp4, opus) and builds a clean library of **songs, albums, artists, and playlists**.
- Everything works fully **offline**.

### Playlists

- Create and edit playlists in the app, with an **artist → album → song** browser for picking tracks.
- **Import M3U playlists**: drop `.m3u`/`.m3u8` files (exported from a desktop player or hand‑written) into any of your scanned music folders and they are imported automatically on the next library scan. Entries are matched by file name, and re‑scanning after editing the file updates the same playlist in place.
- Shuffle any playlist, artist, or album from its detail screen.

### A calm player

- A quiet **Now Playing** screen with big typography and simple controls — easy on the eyes and on E‑ink.
- One queue with **shuffle** and **repeat**, background playback via a media session, and an optional system overlay showing what's playing.
- **Customizable navigation**: reorder or hide the bottom tabs (Playlists, Artists, Songs, Albums) in Settings → Tabs.

### FM radio remote (Mudita Kompakt)

- The Radio tab can remote‑control an external FM radio app on the device via notification access and an accessibility service. This is optional — the app works fine without granting those permissions.

## Getting started

1. **Install ErdMusic** on an Android device (Android 9 / API 28 or newer).
2. **Open the app** — you'll start with an empty library.
3. **Add local music**
   - Go to **Settings → Local**.
   - Enable local music and pick the folders that contain your audio files.
   - ErdMusic will scan and build your library of songs, albums, and artists.
4. *(Optional)* Put `.m3u` playlist files in those folders to have your playlists imported automatically.
5. *(Optional)* Rearrange or hide bottom tabs under **Settings → Tabs**.

## Privacy & data

ErdMusic is designed to stay out of your business:

- **No accounts. No network features at all.**
- **No ads, no analytics, no tracking SDKs.**
- Your settings and library live **only on your device**.

## For developers

If you want to hack on ErdMusic or build your own APK:

- **Requirements**
  - JDK 17
  - Android SDK Platform 35+
  - Android Studio (optional — the Gradle wrapper is enough)
  - Device or emulator running Android 9 (API 28) or newer

- **Quick start**
  1. Clone this repository.
  2. Create `local.properties` pointing at your Android SDK (`sdk.dir=...`), or open the folder in Android Studio and let it do this for you.
  3. Assemble a debug APK: `./gradlew :app:assembleDebug`
  4. The APK lands in `app/build/outputs/apk/debug/`.

- **Other useful Gradle commands**
  - Install debug build on a connected device: `./gradlew :app:installDebug`
  - Run unit tests: `./gradlew :app:testDebugUnitTest`
  - Run Android Lint: `./gradlew :app:lintDebug`

## Credits

ErdMusic is based on [CalmMusic](https://github.com/davidraywilson/CalmMusic) by [David Ray Wilson](https://github.com/davidraywilson), which also supports YouTube Music and Apple Music streaming — if you want those features, use the original. The full commit history of the original project is preserved in this repository.

## License

GPL‑3.0 (see `LICENSE`).
