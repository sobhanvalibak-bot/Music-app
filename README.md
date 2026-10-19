# Music App

A local music player for Android with an iOS-style look: floating liquid-glass tab bar and mini player, large titles, and cover art everywhere. Built with Kotlin and Jetpack Compose.

## Features

- **Songs** – every track on your device with cover art, title, artist, and duration. Sorted newest first by default.
- **Albums and Artists** – cover grid and artist list. Tap to play.
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
│   ├── MainActivity.kt        UI, settings, profile, favorites, playlists
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
