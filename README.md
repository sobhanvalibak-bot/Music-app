# Music App

A local music player for Android with an iOS-style look: floating liquid-glass tab bar and mini player, large titles, and cover art everywhere. Built with Kotlin and Jetpack Compose.

## Features

- **Songs** – every track on your device with cover art, title, artist, and duration. Sorted newest first by default.
- **Albums and Artists** – cover grid and artist list. Tap to play.
- **Full player** – tap the mini player (or the playback notification) to open it: big cover, seek bar, previous/next, shuffle, repeat, favorite and add to playlist. Swipe down to close.
- **Artists** – names are split on `,`, `&` and `_`; tap an artist (in the Artists tab or the full player) to see all their songs.
- **Compact tab bar** – tap a tab, or press and hold the bar and slide left/right to switch tabs. The bars fade out when you scroll down and return when you scroll up.
- **Media session** – background playback, notification controls, lock-screen controls, and headset or Bluetooth buttons (Media3 `MediaSessionService`).
- **Profile** – your name, username, bio, and photo, stored on the device. The Profile tab shows your photo.
- **Favorites** – tap the heart on any song or in the mini player.
- **Playlists** – create playlists in Profile, or long-press any song to add it to one.
- **Settings** (open them from the button in Profile):
  - Theme: system, light, or dark
  - Accent color
  - Sort order: newest, oldest, or A–Z
  - Liquid glass opacity
  - Cover corner roundness
  - Show or hide song duration
- Custom app icon (adaptive and themed).


## New in this version

- **Swipe gestures like iOS** – swipe left or right on the tab bar (the highlight follows your finger and the tab changes live); swipe the mini player left for the next song and right for the previous one.
- **Cover theme** – Settings > Appearance > *Cover*: background, cards, glass and highlights take the colour of the playing song's cover.
- **Album in the full player** – tap the album chip under the artist name to see every song of that album and play any of them.
- **Search** – round search button next to the tab bar; searches title, artist and album.
- **Edit info** – pencil button in the full player, or long-press a song > *Edit info*. Title, artist, album, genre, year and track number are written into the audio file itself (uses jaudiotagger). On Android 10+ the system asks once per edit to allow changing the file.

## Build

### GitHub Actions (no setup)

1. Push this project to a GitHub repository.
2. Open the **Actions** tab and run **Build APK** (it also runs on every push).
3. When it finishes, download `MusicApp-apk` from **Artifacts** and unzip it to get `app-debug.apk`.

### Locally

Requires JDK 17 and the Android SDK (compileSdk 35).

```
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Permissions

| Permission | Why |
| --- | --- |
| `READ_MEDIA_AUDIO` (Android 13+) or `READ_EXTERNAL_STORAGE` (older) | Find the music on your device |
| `POST_NOTIFICATIONS` (Android 13+) | Show the playback notification |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep music playing in the background |

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/musicapp/
│   ├── MainActivity.kt        Activity, theme and system bars
│   ├── MusicApp.kt            Root screen, navigation, playback wiring
│   ├── Screens.kt             Songs, Albums, Artists, Profile, Playlist pages
│   ├── SettingsScreen.kt      Settings page
│   ├── Dialogs.kt             Edit profile, new playlist, add to playlist
│   ├── Bars.kt                Glass mini player and tab bar
│   ├── Player.kt              Full player screen
│   ├── Theme.kt               Palette, liquid glass, cover art, icons
│   ├── Data.kt                Song model, settings, profile store
│   └── PlaybackService.kt     ExoPlayer and MediaSession
└── res/                       app icon
```

## Where data is stored

Everything stays on the device and nothing is uploaded anywhere.

- Settings: `SharedPreferences` file `settings`
- Profile, favorites, playlists: `SharedPreferences` file `profile`
- Profile photo: `profile_photo.jpg` in the app's private storage

## Notes

- The glass effect uses translucency, a light rim, and a soft shadow. It does not blur the content behind it.
- Playlists and favorites refer to songs by their media ID, so a song that is deleted from the device disappears from them.

## Tech

Kotlin 2.0, Jetpack Compose (Material 3), AndroidX Media3 (ExoPlayer and Session), minSdk 24, targetSdk 35.
