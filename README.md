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
- **Playlists** – create playlists in Profile, or open the song menu (three dots or long-press) to add it to one.
- **Settings** (open them from the button in Profile):
  - Theme: system, light, or dark
  - Accent color
  - Sort order: newest, oldest, or A–Z
  - Liquid glass opacity
  - Cover corner roundness
  - Show or hide song duration
- Custom app icon (adaptive and themed).


## Dynamic Island update

- Removed the classic full-width tab dock, the classic glass mini player and the "Classic mode" opaque switch. The app now always uses the floating glass tab bar and the Dynamic Island player.
- **Hold the Dynamic Island**: an iOS-style frosted menu opens under it (Favorite, Add to playlist, Edit info, Share). The app behind it is blurred (Android 12+); tap outside or press Back to close.
- **Hold a song** (or tap its three dots): the same frosted menu opens right under the song row. The row lifts and the app behind it blurs (real window blur on Android 12+, soft dim otherwise). It replaces the old bottom sheet and adds Share.

## New in this version

- **Swipe gestures like iOS** – swipe left or right on the tab bar (the highlight follows your finger and the tab changes live); swipe the mini player left for the next song and right for the previous one.
- **Cover theme** – Settings > Appearance > *Cover*: background, cards, glass and highlights take the colour of the playing song's cover.
- **Album in the full player** – tap the album chip under the artist name to see every song of that album and play any of them.
- **Search** – round search button next to the tab bar; searches title, artist and album.
- **Edit info** – pencil button in the full player, or long-press a song > *Edit info*. Title, artist, album, genre, year and track number are written into the audio file itself (uses jaudiotagger). On Android 10+ the system asks once per edit to allow changing the file.

- **Long-press menus everywhere** – hold an album, an artist, a playlist or a favorite card: the item lifts, the app blurs and the same frosted menu springs open next to it (Play, Shuffle, Play next, Open, Delete playlist, Share). One reusable `MenuTarget` wrapper in `SongSheet.kt` does it.
- **More iOS touches** –
  - *Swipe from the left edge* on album, artist, favorites, playlist and settings pages to go back (the page follows your finger).
  - *Swipe a song row*: right = favorite, left = play next (haptic tick at the threshold).
  - *Pull the Songs tab down* to reveal search; let go past the line to open it.
  - *A–Z index* on the right of the Songs tab when the sort is A–Z (touch or slide, with a letter bubble).
  - *Large title collapses* into a small top bar on Songs, Albums and Artists.
  - *Sheet-style player*: the screen behind it shrinks and rounds while the player is open.
  - *Round green switches* and a *segmented control with a sliding pill* in Settings.
  - *Big pill volume slider* (speaker button in the player, bottom row).
  - *Sleep timer* (moon button in the player): 15 / 30 / 45 / 60 minutes or end of the song; the last 10 seconds fade out.
  - Rubber-band overscroll at the ends of lists comes from Android itself (stretch effect, Android 12+).

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
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets the notification island keep working on phones with strict battery savers |
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


## Latest completion pass (2026-10-19)
- Added persistent Recently Played history (up to 30 tracks) and a horizontal Recently Played section on the Home/music screen.
- History updates when the Media3 session transitions to a track and persists across app restarts.
- Expanded synchronized-lyrics lookup: tries the exact LRCLIB endpoint, then search results and track detail endpoints; parses minute/second timestamps and filters empty lines.
- Smoothed cover-derived theme color transitions to 1.1 seconds with FastOutSlowIn easing.

Build verification: this archive does not include Gradle Wrapper scripts and the current environment has no `gradle` executable, so an Android compile could not be run here. Please run `assembleDebug` in GitHub Actions to validate the full Android toolchain. Existing notification currently relies on Media3's standard media-session controls; custom Shuffle/Repeat/Like notification buttons are not yet implemented in this pass.


## Playback extras (inspired by BitChord, local files only)

- **Crossfade** (Settings > Playback, or the Playback sheet in the full player): 0–12 s. While the next song fades in, a short helper player finishes the old one and fades it out. With crossfade off, ExoPlayer's normal gapless playback is used.
- **Playback speed** 0.5×–2× (Playback sheet). The chosen speed is remembered.
- **Sleep timer**: 15/30/45/60 min or "End of song".
- **Skip silence**: jumps over silent parts inside a song.
- **Equalizer**: opens the system equalizer on this app's audio session.
- **Stats for nerds**: format, sample rate, bit depth (Android 12+), channels, bitrate.
- **Lyrics** (full player > Lyrics): shows a saved `.lrc` for the song, lyrics found inside the audio file, or a `.lrc` you import. Line timing follows the song and tapping a line seeks; enhanced `.lrc` with `<mm:ss.xx>` word times highlights word by word. Lyrics found with *Edit info > Find lyrics* are now also saved on the device so they work offline afterwards.

New files: `Extras.kt` (sleep timer state, LRC parser, lyrics store, audio info), `PlayerExtras.kt` (lyrics panel and Playback sheet). `PlaybackService.kt` was rewritten to host crossfade, sleep timer and the equalizer session.

## Motion update (iOS-style animations)

- **Living player background** – soft colour blobs from the cover colour drift slowly behind the full player (`AnimatedCoverBackground`).
- **Cover breathes** – the cover is big while playing and shrinks with a spring when paused.
- **3D tilt** – the full-player cover tilts with the phone (accelerometer, only while the player is open).
- **Albums fly in** – album cards rise and fade in one after another (`staggerIn`).
- **Stretchy header** – on album and artist pages, pull the list down and the big cover stretches, then springs back.
- **Equalizer bars** – the playing song shows dancing bars on its cover; they rest when paused.
- **Seek bar grows** – the progress line gets thicker while you touch it.
- **Heart pulse** – liking a song pops the heart and sends out a ring.
- **Island swells** – the Dynamic Island pill briefly widens when the song changes.
- **Shimmer** – covers show a light sweep while they load.
- Already there and kept: lyrics with the active line bright and the others blurred, and the spring-sliding tab bar highlight.
- All new helpers live in `Motion.kt`.

## iOS touches, round 2 (v1.2)

- **Cover flies into the player** – opening the full player makes the cover rise and grow from the mini-player corner with a spring (`Player.kt`).
- **Fine scrubbing** – drag the seek bar, then move your finger away from it: the further down (or up) you go, the slower the seek (1x, 1/2, 1/4, 1/10). A small label and a haptic tick show the speed.
- **Haptics** – favorite (heart) and play/pause now give a tap, on top of the tab bar, menus and swipes that already had one.
- **Pinch on Albums** – spread two fingers for bigger covers, pinch for more columns (1–4). The choice is remembered (`Settings.albumColumns`). Scrolling is not affected.
- **Home-screen widget** – long-press the home screen > Widgets > Music App. Cover, title, artist, previous / play-pause / next. Colour follows the cover (translucent tint with a light rim; Android widgets cannot blur what is behind them). Tap the cover or the title to open the player. Files: `MusicWidget.kt`, `res/layout/widget_player.xml`, `res/xml/music_widget_info.xml`.
- **Search** – filter chips (All / Songs / Artists / Albums) and the last 8 searches, with *Clear*.
- **Now-playing share card** – the share button in the player now sends a picture (cover colour, cover, title, artist, a small sound-wave) plus the text. Files: `ShareCard.kt`, `res/xml/file_paths.xml` (FileProvider).
- Already in the app and kept: the play queue (drag to reorder, remove, clear upcoming) and playlist reordering by long-press.

Build verification: not compiled in the authoring environment (no Gradle/Android SDK). Run **Build APK** in GitHub Actions to validate.

## Press effects everywhere (v1.2)

- **Switches** (`IosSwitch`, used in every Settings switch and the Playback sheet): the knob stretches while a finger is on it, and you can now also drag the knob to the other side.
- **Segmented controls** (`Segmented`: Theme, Sort, notification size and tint): while pressed the pill grows, lifts with a shadow and follows your finger across the options, with a haptic tick on every change.
- **Option pills** (`OptionPill`: playback options and the new Search filter chips): shrink slightly under the finger and spring back.


## Search, queue and online update

- **Search page** – long-press or use the three dots on a result: *Play next*, *Add to queue*, *Add to playlist*, *Favorite*, *Edit info*, *Share* (swipe left on a row = play next, as everywhere else).
- **Online search** – on the Search page switch **On this device / Online**. Results come from YouTube (no account or API key, via NewPipeExtractor). Tap a result to listen, tap **Video** (or the menu > *Watch video*) to watch. In the full player an online song has an **Audio only / Watch video** switch that keeps the position. Online songs can be favorited, added to playlists and queued like local ones (they are remembered in `online_songs` prefs; the stream address is looked up again each time, so old favorites keep working). Online songs cannot be edited (no file) and need an internet connection.
- **Tapping a song inside a collection plays only that song** – in an album, artist, playlist, favorites, the Search results and the player's album list, a tap puts the song right after the current one and keeps the queue you already had. Only the new **Play** / **Shuffle** buttons at the top of album, artist and favorites pages (Play on a playlist, and the long-press menus on albums/artists) queue the whole collection. The Songs tab still plays the whole list from the song you tap.

Files: `Online.kt` (search, stream lookup, online song registry, video surface). `PlaybackService.kt` resolves `npyt://` addresses through a `ResolvingDataSource`. Needs the `NewPipeExtractor` (JitPack) and `okhttp` dependencies and core library desugaring (see `app/build.gradle.kts`). Not compiled in the authoring environment: run **Build APK** in GitHub Actions. If JitPack cannot find tag `v0.24.8`, use the newest tag from the NewPipeExtractor releases page.
