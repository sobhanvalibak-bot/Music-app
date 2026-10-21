@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.musicapp

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── Screens ─────────────────────────

@Composable
fun MusicApp(
    p: Palette,
    settings: Settings,
    openPlayerSignal: Int = 0,
    onTint: (Color?) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { ProfileStore(context) }
    val listenLog = remember { ListenLog(context) }
    // Player surfaces follow the current cover independently from the app-wide theme.
    var playerTint by remember { mutableStateOf<Color?>(null) }
    val animatedPlayerTint by animateColorAsState(
        targetValue = playerTint ?: p.accent,
        animationSpec = tween(durationMillis = 850, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "playerCoverTint"
    )
    val playerPalette = remember(p, animatedPlayerTint) {
        Palette(p.dark, p.accent, settings.glass, p.corner, animatedPlayerTint)
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var askNoticeAccess by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { rebindNoticeListener(context) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // after the notification permission: ask for access to read other apps' notifications (island)
        if (!isNoticeAccessEnabled(context)) askNoticeAccess = true
    }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (!isNoticeAccessEnabled(context)) askNoticeAccess = true
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        granted = it
        askNotifications() // one dialog after the other, never together
    }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) scope.launch { profile.savePhoto(uri) } }
    val avatarVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val ok = profile.saveVideo(uri, false)
            Toast.makeText(context, if (ok) "Animated profile video saved" else "Video must be MP4 and no larger than 50 MB", Toast.LENGTH_LONG).show()
        }
    }
    val bannerVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val ok = profile.saveVideo(uri, true)
            Toast.makeText(context, if (ok) "Animated banner saved" else "Video must be MP4 and no larger than 50 MB", Toast.LENGTH_LONG).show()
        }
    }

    // Editing song info: tags are written into the audio file, which Android only allows after
    // the user agrees (Android 10+: system dialog, Android 9-: storage permission).
    var songsVersion by remember { mutableIntStateOf(0) }
    var editTarget by remember { mutableStateOf<Song?>(null) }
    var pendingEdit by remember { mutableStateOf<Pair<Song, TagEdit>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }
    var grantAsked by remember { mutableStateOf(false) }
    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            retryTick++
        } else {
            saving = false
            pendingEdit = null
            Toast.makeText(context, "Not allowed, the file was not changed", Toast.LENGTH_SHORT).show()
        }
    }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) {
            retryTick++
        } else {
            saving = false
            pendingEdit = null
            Toast.makeText(context, "Storage permission is needed to edit the file", Toast.LENGTH_SHORT).show()
        }
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // back from the settings page: try again (in place if it was allowed, else the one-file permission)
        retryTick++
    }
    // Downloads go to Music/musicapp. Android 10+ lets an app add its own files there freely;
    // Android 9 and older need the storage permission, so it is asked right when a download starts.
    var pendingDownload by remember { mutableStateOf<Song?>(null) }
    val downloadPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        val s = pendingDownload
        pendingDownload = null
        if (ok && s != null) OnlineDownloader.start(context, s)
        else Toast.makeText(context, "Storage permission is needed to save songs in Music/musicapp", Toast.LENGTH_LONG).show()
    }
    DisposableEffect(Unit) {
        OnlineDownloader.requestWrite = { s ->
            pendingDownload = s
            downloadPermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        onDispose { OnlineDownloader.requestWrite = null }
    }

    // On start: music permission first (if missing), then notifications.
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(audioPermission) else askNotifications()
    }

    if (askNoticeAccess) {
        NoticeAccessDialog(
            p = p,
            onOpenSettings = {
                askNoticeAccess = false
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            onLater = { askNoticeAccess = false }
        )
    }

    var tab by remember { mutableIntStateOf(0) }
    val appHaptic = LocalHapticFeedback.current
    fun favWithHaptic(id: Long) {
        appHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
        profile.toggleFavorite(id)
    }
    var sub by remember { mutableStateOf<Sub>(Sub.None) }
    var addTarget by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    var showPlayer by remember { mutableStateOf(false) }
    var islandExpanded by remember { mutableStateOf(false) }
    // quick menu that opens when the Dynamic Island is held
    var islandMenu by remember { mutableStateOf(false) }
    var showIslandSleep by remember { mutableStateOf(false) }
    LaunchedEffect(showPlayer) { if (showPlayer) { islandExpanded = false; islandMenu = false } }
    var artistPage by remember { mutableStateOf<String?>(null) }
    var albumPage by remember { mutableStateOf<List<Song>?>(null) }
    var barsVisible by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    BackHandler(enabled = searching) { searching = false }
    BackHandler(enabled = tab == 3 && sub != Sub.None) { sub = Sub.None }
    BackHandler(enabled = artistPage != null) { artistPage = null }
    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = islandMenu) { islandMenu = false }

    // Scrolling content down hides the bars, scrolling back up brings them back.
    val barsScroll = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                // reset the sum when the direction changes
                if ((dy < 0f && acc > 0f) || (dy > 0f && acc < 0f)) acc = 0f
                acc += dy
                if (acc < -40f) barsVisible = false
                else if (acc > 40f) barsVisible = true
                return Offset.Zero
            }
        }
    }

    // While typing in search the keyboard needs the space, so the bars slide away too.
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val barsShown = barsVisible

    // Scrolling down: only the tab bar (and search button) fades out, and the mini player glides
    // down into the place the tab bar had. The mini player itself never fades.
    val tabHide by animateFloatAsState(
        if (barsShown) 0f else 1f,
        spring(dampingRatio = 0.85f, stiffness = 380f),
        label = "tabHide"
    )
    // while typing in search the keyboard needs the space: everything slides away
    val imeHide by animateFloatAsState(
        if (searching && imeOpen) 1f else 0f,
        spring(dampingRatio = 0.8f, stiffness = 260f),
        label = "imeHide"
    )
    var tabRowH by remember { mutableIntStateOf(0) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlyphIcon(Glyph.Note, p.accent, Modifier.size(64.dp))
            Spacer(Modifier.height(20.dp))
            Text("Allow access to your music to continue", color = p.text, fontSize = 17.sp)
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.accent)
                    .clickable { launcher.launch(audioPermission) }
                    .padding(horizontal = 36.dp, vertical = 14.dp)
            ) { Text("Allow", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
        return
    }

    val songs = remember(songsVersion, OnlineDownloader.version) { loadSongs(context) }
    remember { MixStore.init(context) } // which songs are mixes + their collage covers
    remember { OnlineRegistry.init(context) } // online songs saved earlier (favorites, playlists, recent)
    // songs on the device plus the online songs the app has seen
    val byId: Map<Long, Song> = remember(songs) { SongIndex(songs.associateBy { it.id }) }
    val sorted = remember(songs, settings.sort) {
        when (settings.sort) {
            1 -> songs.sortedBy { it.dateAdded }
            2 -> songs.sortedBy { it.title.lowercase() }
            else -> songs
        }
    }
    val albums = remember(sorted, settings.sort) {
        val groups = sorted.groupBy { it.albumId }.values
        when (settings.sort) {
            1 -> groups.sortedBy { l -> l.minOf { it.dateAdded } }
            2 -> groups.sortedBy { it.first().album.lowercase() }
            else -> groups.sortedByDescending { l -> l.maxOf { it.dateAdded } }
        }
    }
    val artists = remember(songs) {
        val map = LinkedHashMap<String, Pair<String, MutableList<Song>>>()
        songs.forEach { s ->
            s.artists.forEach { name ->
                map.getOrPut(name.lowercase()) { name to mutableListOf() }.second.add(s)
            }
        }
        map.values.map { ArtistGroup(it.first, it.second) }.sortedBy { it.name.lowercase() }
    }
    val favSet = profile.favorites.toSet()
    val songOfDay = remember(songs) { listenLog.songOfDay(songs, favSet) }

    // Home > Categories: a new random order every time the app is opened
    var categoryMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var categorySeed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(System.nanoTime()) }
    val categories = remember(songs, favSet, categorySeed) { buildCategories(songs, favSet, categorySeed) }

    // Playback runs in PlaybackService (media session + notification + lock screen).
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var current by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) { PlaybackBridge.isPlayingNow = isPlaying }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var position by remember { mutableLongStateOf(0L) }
    var pendingOpen by remember { mutableStateOf(false) }
    var audioInfo by remember { mutableStateOf<AudioInfo?>(null) }
    var queue by remember { mutableStateOf<List<QueueEntry>>(emptyList()) }
    var currentWindow by remember { mutableIntStateOf(-1) }
    // true while the current (online) song is playing with its picture
    var hasVideo by remember { mutableStateOf(false) }
    var onlineRetryId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
            } catch (e: Exception) {
                controller = null
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    DisposableEffect(controller, byId) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            isPlaying = c.isPlaying
            shuffle = c.shuffleModeEnabled
            repeatMode = c.repeatMode
            current = c.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
            audioInfo = audioInfoFrom(c.currentTracks)
            hasVideo = c.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            queue = buildQueue(c, byId)
            currentWindow = c.currentMediaItemIndex
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    audioInfo = audioInfoFrom(tracks)
                    hasVideo = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                }
                override fun onPlayerError(error: PlaybackException) {
                    // an online song that cannot be loaded: try once more with a fresh stream address;
                    // if YouTube blocks it, look for the same song on SoundCloud; else say what went wrong.
                    // It never jumps to a song from the phone by itself.
                    val mid = c.currentMediaItem?.mediaId?.toLongOrNull()
                    val song = mid?.let { byId[it] } ?: current
                    if (song == null || !song.isOnline) return
                    val page = song.remoteId.orEmpty()
                    val reason = generateSequence<Throwable>(error) { it.cause }
                        .map { it.message.orEmpty() }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("Source error") && !it.startsWith("androidx.media3") }
                        ?: "Could not play this online song"
                    val blocked = OnlineEngine.isBlocked(reason)
                    if (!blocked && onlineRetryId != song.id) {
                        onlineRetryId = song.id
                        OnlineEngine.forget(page)
                        c.prepare()
                        c.play()
                        return
                    }
                    onlineRetryId = null
                    if (song.canWatch) {
                        Toast.makeText(context, "Looking for this song on SoundCloud...", Toast.LENGTH_SHORT).show()
                        val ui = android.os.Handler(android.os.Looper.getMainLooper())
                        Thread {
                            val alt = OnlineEngine.findAlternative(song.title, song.artist, song.durationMs)
                            ui.post {
                                if (alt != null && c.currentMediaItem?.mediaId == song.id.toString()) {
                                    val altSong = alt.toSong()
                                    OnlineRegistry.remember(altSong)
                                    val idx = c.currentMediaItemIndex
                                    c.replaceMediaItem(idx, altSong.toMediaItem())
                                    c.seekTo(idx, 0L)
                                    c.prepare()
                                    c.play()
                                    Toast.makeText(context, "Playing from ${altSong.sourceName}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, reason.take(140), Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    } else {
                        Toast.makeText(context, reason.take(140), Toast.LENGTH_LONG).show()
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; if (playing) onlineRetryId = null }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    shuffle = shuffleModeEnabled
                    queue = buildQueue(c, byId)
                }
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    queue = buildQueue(c, byId)
                    currentWindow = c.currentMediaItemIndex
                }
                override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                        reason != Player.DISCONTINUITY_REASON_SKIP &&
                        reason != Player.DISCONTINUITY_REASON_SEEK
                    ) return
                    if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
                    val oldItem = oldPosition.mediaItem ?: return
                    if (oldItem.mediaMetadata.extras?.getBoolean("upnext") != true) return
                    val oldIdx = oldPosition.mediaItemIndex
                    // a "Play next" song is used once: drop it so repeat / the next lap does not play it again
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (oldIdx in 0 until c.mediaItemCount && oldIdx != c.currentMediaItemIndex) {
                            val it2 = c.getMediaItemAt(oldIdx)
                            if (it2.mediaId == oldItem.mediaId && it2.mediaMetadata.extras?.getBoolean("upnext") == true) c.removeMediaItem(oldIdx)
                        }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentWindow = c.currentMediaItemIndex
                    run {
                        val id = mediaItem?.mediaId?.toLongOrNull()
                        val why = when (reason) {
                            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
                            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek/skip"
                            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
                            else -> "list changed"
                        }
                        val t = id?.let { byId[it] }
                    }
                    // new song: the bar starts again from the real position, not from the old song's
                    position = c.currentPosition
                    listenLog.newTrack()
                    current = mediaItem?.mediaId?.toLongOrNull()?.let { id -> byId[id]?.also { profile.recordRecentlyPlayed(id) } }
                }
            }
            c.addListener(listener)
            onDispose { c.removeListener(listener) }
        }
    }

    LaunchedEffect(controller) { controller?.setPlaybackSpeed(settings.speed) }

    // listening log: one tick per second of playback (feeds "Your listening this month" and "Song of the day")
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val playing = current
            if (isPlaying && playing != null) listenLog.tick(playing.id)
        }
    }
    LaunchedEffect(isPlaying) { if (!isPlaying) listenLog.flush() }

    fun playQueue(queue: List<Song>, index: Int) {
        val c = controller ?: return
        if (queue.isEmpty()) return
        c.setMediaItems(queue.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    /**
     * Plays just this song right now, without turning the album / artist / playlist it was tapped in
     * into the queue: it goes in right after the current song, and the queue you already had carries on after it.
     * Only the "Play" / "Shuffle" buttons of a page (and the album's own play menu) queue the whole page.
     */
    fun playOne(song: Song, video: Boolean = false) {
        val c = controller ?: return
        OnlineRegistry.remember(song)
        val item = song.toMediaItem(video)
        if (c.mediaItemCount == 0) {
            c.setMediaItem(item)
        } else {
            val at = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
            c.addMediaItem(at, item)
            c.seekTo(at, 0L)
        }
        c.prepare()
        c.play()
    }

    /**
     * "Play next" state: the list index of the last song that is already waiting as "Play next"
     * (or the current song if none) and those waiting songs in the order they will play.
     */
    fun upNextState(c: Player): Pair<Int, List<MediaItem>> {
        val t = c.currentTimeline
        val cur = c.currentMediaItemIndex
        if (t.isEmpty || cur < 0) return cur to emptyList()
        val sh = c.shuffleModeEnabled
        var last = cur
        val waiting = ArrayList<MediaItem>()
        var i = t.getNextWindowIndex(cur, Player.REPEAT_MODE_OFF, sh)
        while (i != androidx.media3.common.C.INDEX_UNSET) {
            val item = c.getMediaItemAt(i)
            if (item.mediaMetadata.extras?.getBoolean("upnext") != true) break
            waiting.add(item)
            last = i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, sh)
        }
        return last to waiting
    }

    // "Play next" always goes right after the current song (and after the songs already waiting as "Play next"):
    // it does not matter which page / category the queue came from, or whether shuffle or repeat is on.
    fun insertUpNext(c: Player, list: List<Song>) {
        list.forEach { OnlineRegistry.remember(it) }
        val insertAt = upNextState(c).first + 1
        val ended = c.playbackState == Player.STATE_ENDED
        c.addMediaItems(insertAt, list.map { it.toMediaItem(upNext = true) })
        if (c.repeatMode == Player.REPEAT_MODE_ONE) {
            // repeat one would play the current song forever and never reach the next one
            c.repeatMode = Player.REPEAT_MODE_ALL
            Toast.makeText(context, "Repeat one turned into repeat all so your next song can play", Toast.LENGTH_SHORT).show()
        }
        if (ended) { c.seekTo(insertAt, 0L); c.play() }
    }

    fun queueNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { playOne(song); return }
        insertUpNext(c, listOf(song))
        Toast.makeText(context, "Will play next: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    // At the very end of the queue.
    fun queueEnd(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { playOne(song); return }
        OnlineRegistry.remember(song)
        val last = c.currentTimeline.getLastWindowIndex(c.shuffleModeEnabled)
            .takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: (c.mediaItemCount - 1)
        c.addMediaItem(last + 1, song.toMediaItem())
        Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // Shuffle / repeat of a category: first make the category the queue (the playing song and the
    // "Play next" songs stay), so "repeat album" repeats that album and shuffle shuffles only it.
    fun adoptCategory(cat: SongCategory) {
        val c = controller ?: return
        val now = current ?: return
        val idx = cat.songs.indexOfFirst { it.id == now.id }
        if (idx < 0) return
        val ids = cat.songs.map { it.id.toString() }
        val plain = (0 until c.mediaItemCount)
            .map { c.getMediaItemAt(it) }
            .filter { it.mediaMetadata.extras?.getBoolean("upnext") != true }
            .map { it.mediaId }
        if (plain == ids) return
        val waiting = upNextState(c).second
        val pos = c.currentPosition
        val wasPlaying = c.isPlaying
        c.setMediaItems(cat.songs.map { it.toMediaItem() }, idx, pos)
        if (waiting.isNotEmpty()) c.addMediaItems(idx + 1, waiting)
        c.prepare()
        if (wasPlaying) c.play()
    }

    // Online song: switch between sound only and picture + sound, keeping the position.
    fun toggleVideo() {
        val c = controller ?: return
        val song = current ?: return
        if (!song.isOnline) return
        val idx = c.currentMediaItemIndex
        val pos = c.currentPosition
        c.replaceMediaItem(idx, song.toMediaItem(!hasVideo))
        c.seekTo(idx, pos)
    }

    // Whole list goes right after the current song.
    fun queueNextAll(list: List<Song>) {
        val c = controller ?: return
        if (list.isEmpty()) return
        if (c.mediaItemCount == 0) { playQueue(list, 0); return }
        insertUpNext(c, list)
        Toast.makeText(context, "Will play next (${list.size} songs)", Toast.LENGTH_SHORT).show()
    }

    // ── Sleep timer: stops the music after a time (with a fade) or at the end of the song ──
    var sleepEndAt by remember { mutableStateOf<Long?>(null) }
    var sleepEndOfSong by remember { mutableStateOf(false) }
    var sleepLeftMs by remember { mutableLongStateOf(0L) }
    fun resetVolume() { try { controller?.volume = 1f } catch (_: Exception) { } }
    fun startSleep(minutes: Int) {
        resetVolume()
        when {
            minutes == 0 -> {
                sleepEndAt = null
                sleepEndOfSong = false
                Toast.makeText(context, "Sleep timer off", Toast.LENGTH_SHORT).show()
            }
            minutes < 0 -> {
                sleepEndAt = null
                sleepEndOfSong = true
                Toast.makeText(context, "Music will stop after this song", Toast.LENGTH_SHORT).show()
            }
            else -> {
                sleepEndOfSong = false
                sleepLeftMs = minutes * 60_000L
                sleepEndAt = SystemClock.elapsedRealtime() + sleepLeftMs
                Toast.makeText(context, "Sleep timer: $minutes min", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(sleepEndAt) {
        val end = sleepEndAt ?: return@LaunchedEffect
        while (true) {
            val left = end - SystemClock.elapsedRealtime()
            sleepLeftMs = left.coerceAtLeast(0L)
            if (left <= 0L) break
            // the last 10 seconds fade out
            if (left < 10_000L) {
                try { controller?.volume = (left / 10_000f).coerceIn(0f, 1f) } catch (_: Exception) { }
            }
            delay(if (left < 10_000L) 100L else 500L)
        }
        controller?.pause()
        resetVolume()
        sleepEndAt = null
        Toast.makeText(context, "Sleep timer finished", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(sleepEndOfSong) {
        if (!sleepEndOfSong) return@LaunchedEffect
        while (true) {
            val c = controller
            if (c != null && c.isPlaying) {
                val d = c.duration
                if (d > 0 && d - c.currentPosition <= 700L) {
                    c.pause()
                    sleepEndOfSong = false
                    break
                }
            }
            delay(250)
        }
    }

    // Derive the player palette from the playing cover even when the app-wide theme is fixed.
    LaunchedEffect(current?.id) {
        val tint = current?.let { songTint(context, it) }
        playerTint = tint
        onTint(tint)
    }

    // Writes the edited tags into the song file (and asks Android for permission if needed).
    LaunchedEffect(pendingEdit, retryTick) {
        val (song, edit) = pendingEdit ?: return@LaunchedEffect
        saving = true
        when (val r = TagEditor.save(context, song, edit)) {
            is SaveResult.Ok -> {
                Toast.makeText(context, "Saved into the song file", Toast.LENGTH_SHORT).show()
                pendingEdit = null
                saving = false
                editTarget = null
                songsVersion++
            }
            is SaveResult.NeedsGrant -> {
                if (grantAsked) {
                    Toast.makeText(context, "Android did not allow changing this file", Toast.LENGTH_LONG).show()
                    pendingEdit = null
                    saving = false
                } else {
                    grantAsked = true
                    grantLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                }
            }
            is SaveResult.NeedsAllFilesAccess -> {
                Toast.makeText(context, "Allow \"All files access\" to edit songs in their own folder", Toast.LENGTH_LONG).show()
                try {
                    allFilesLauncher.launch(r.intent)
                } catch (_: Exception) {
                    retryTick++
                }
            }
            is SaveResult.NeedsStoragePermission -> {
                if (grantAsked) {
                    Toast.makeText(context, "Storage permission is needed to edit the file", Toast.LENGTH_LONG).show()
                    pendingEdit = null
                    saving = false
                } else {
                    grantAsked = true
                    storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            is SaveResult.Error -> {
                Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                pendingEdit = null
                saving = false
            }
        }
    }

    // Tapping the playback notification opens the full player for the current song.
    LaunchedEffect(openPlayerSignal) {
        if (openPlayerSignal > 0) {
            pendingOpen = true
            delay(5000)
            pendingOpen = false
        }
    }
    LaunchedEffect(pendingOpen, current) {
        if (pendingOpen && current != null) {
            showPlayer = true
            pendingOpen = false
        }
    }

    // Progress for the full player's seek bar.
    // (also feeds the Dynamic Island, so it runs whenever a controller exists)
    LaunchedEffect(controller) {
        val c = controller
        if (c != null) {
            while (true) {
                position = c.currentPosition
                delay(if (showPlayer || islandExpanded) 120 else 500)
            }
        }
    }

    val topPad = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 8.dp
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        104.dp
    val sortSubtitle = SortSubtitles[settings.sort.coerceIn(0, 2)]
    val showDuration = settings.showDuration

    // while the island menu is open the app behind it is blurred (Android 12+), like iOS
    val menuOpen = islandMenu && current != null && !showPlayer
    val menuBlur by animateDpAsState(if (menuOpen) 18.dp else 0.dp, tween(260), label = "menuBlur")
    val blurMod = if (menuBlur > 0.dp) Modifier.blur(menuBlur) else Modifier

    // while the full player is open, the screen behind it shrinks and rounds, like an iOS sheet
    val sheetOn = showPlayer && current != null
    val sheetScale by animateFloatAsState(
        if (sheetOn) 0.92f else 1f, spring(dampingRatio = 0.85f, stiffness = 350f), label = "sheetScale"
    )
    val sheetCorner by animateDpAsState(if (sheetOn) 24.dp else 0.dp, tween(260), label = "sheetCorner")
    val sheetMod = Modifier.graphicsLayer {
        scaleX = sheetScale
        scaleY = sheetScale
        shape = RoundedCornerShape(sheetCorner)
        clip = sheetScale < 0.999f
    }

    Box(Modifier.fillMaxSize().nestedScroll(barsScroll)) {
        Crossfade(targetState = tab, modifier = blurMod.then(sheetMod), label = "tabs") { t ->
            if (t == 3) {
                when (val s = sub) {
                    is Sub.None -> ProfileTab(
                        profile = profile,
                        byId = byId,
                        totalSongs = songs.size,
                        p = p,
                        topPad = topPad,
                        bottomPad = bottomPad,
                        onEdit = { showEdit = true },
                        onSettings = { sub = Sub.Settings },
                        onFavorites = { sub = Sub.Favorites },
                        onOpenPlaylist = { sub = Sub.PlaylistPage(it) },
                        onNewPlaylist = { showNewPlaylist = true },
                        onMix = { sub = Sub.Mix },
                        onPlayFavorites = { list, i -> list.getOrNull(i)?.let { playOne(it) } },
                        onPlayList = { playQueue(it, 0) },
                        onPlayNextList = { queueNextAll(it) },
                        onShuffleList = { playQueue(it.shuffled(), 0) },
                        onSongPlayNext = { queueNext(it) },
                        listenLog = listenLog,
                        onRecap = { sub = Sub.Recap }
                    )
                    is Sub.Mix -> Box(Modifier.fillMaxSize().swipeBack { sub = Sub.None }.background(p.bgBrush)) {
                        MixPage(
                            songs = songs,
                            p = p,
                            topPad = topPad,
                            bottomPad = bottomPad,
                            onBack = { sub = Sub.None },
                            onPlay = { m -> playQueue(listOf(byId[m.id] ?: m), 0) },
                            onAddToPlaylist = { m -> addTarget = byId[m.id] ?: m },
                            render = { list, cover ->
                                try {
                                    val made = MixEngine.create(context, list, cover)
                                    // the finished mix is one song, saved in the "Mixes" playlist
                                    val pl = profile.playlists.firstOrNull { it.name == "Mixes" } ?: profile.createPlaylist("Mixes")
                                    profile.addToPlaylist(pl.id, made.id)
                                    songsVersion++
                                    made
                                } catch (e: Exception) {
                                    android.util.Log.e("MixEngine", "mix failed", e)
                                    Toast.makeText(context, "Mix failed: ${e.message ?: "unknown error"}".take(140), Toast.LENGTH_LONG).show()
                                    null
                                }
                            }
                        )
                    }
                    is Sub.Recap -> Box(Modifier.fillMaxSize().swipeBack { sub = Sub.None }.background(p.bgBrush)) {
                        RecapPage(
                            stats = remember(listenLog.version, byId) { listenLog.month(byId) },
                            p = p,
                            topPad = topPad,
                            bottomPad = bottomPad,
                            onBack = { sub = Sub.None },
                            onPlay = { playOne(it) }
                        )
                    }
                    is Sub.Settings -> Box(Modifier.fillMaxSize().swipeBack { sub = Sub.None }.background(p.bgBrush)) { SettingsTab(
                        settings = settings,
                        p = p,
                        topPad = topPad,
                        bottomPad = bottomPad,
                        profile = profile,
                        onPickPhoto = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onPickAvatarVideo = { avatarVideoPicker.launch("video/mp4") },
                        onPickBannerVideo = { bannerVideoPicker.launch("video/mp4") },
                        onBack = { sub = Sub.None }
                    ) }
                    is Sub.Favorites -> {
                        val favSongs = profile.favorites.reversed().mapNotNull { byId[it] }
                        Box(Modifier.fillMaxSize().swipeBack { sub = Sub.None }.background(p.bgBrush)) {
                        SongListPage(
                            title = "Favorites",
                            subtitle = "${favSongs.size} songs",
                            songs = favSongs,
                            current = current,
                            favSet = favSet,
                            p = p,
                            showDuration = showDuration,
                            topPad = topPad,
                            bottomPad = bottomPad,
                            onBack = { sub = Sub.None },
                            onPlay = { i -> favSongs.getOrNull(i)?.let { playOne(it) } },
                            onPlayAll = { playQueue(favSongs, 0) },
                            onShuffleAll = { playQueue(favSongs.shuffled(), 0) },
                            onToggleFav = { favWithHaptic(it.id) },
                            onLong = { addTarget = it },
                            onRemove = null,
                            onPlayNext = { queueNext(it) },
                            onAddToQueue = { queueEnd(it) },
                            onEditInfo = { editTarget = it }
                        )
                        }
                    }
                    is Sub.PlaylistPage -> {
                        val pl = profile.playlists.firstOrNull { it.id == s.id }
                        if (pl != null) {
                            val plSongs = pl.songIds.mapNotNull { byId[it] }
                            Box(Modifier.fillMaxSize().swipeBack { sub = Sub.None }.background(p.bgBrush)) {
                            PlaylistPage(
                                playlist = pl,
                                songs = plSongs,
                                current = current,
                                favSet = favSet,
                                p = p,
                                showDuration = showDuration,
                                topPad = topPad,
                                bottomPad = bottomPad,
                                onBack = { sub = Sub.None },
                                onPlay = { i -> plSongs.getOrNull(i)?.let { playOne(it) } },
                                onPlayAll = { playQueue(plSongs, 0) },
                                onToggleFav = { favWithHaptic(it.id) },
                                onRemove = { profile.removeFromPlaylist(pl.id, it.id) },
                                onPlayNext = { queueNextAll(plSongs) },
                                onSongPlayNext = { queueNext(it) },
                                onMove = { from, to -> profile.movePlaylistSong(pl.id, from, to) },
                                onDelete = {
                                    sub = Sub.None
                                    profile.deletePlaylist(pl.id)
                                }
                            )
                            }
                        }
                    }
                }
            } else if (songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No songs found on this device", color = p.secondary)
                }
            } else when (t) {
                0 -> SongsTab(
                    songs = sorted,
                    songOfDay = songOfDay,
                    recentlyPlayed = profile.recentlyPlayed.mapNotNull { byId[it] },
                    current = current,
                    favSet = favSet,
                    p = p,
                    showDuration = showDuration,
                    sortSubtitle = sortSubtitle,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    onPlay = { i -> playQueue(sorted, i) },
                    onToggleFav = { favWithHaptic(it.id) },
                    onLong = { addTarget = it },
                    onPlayNext = { queueNext(it) },
                    onAddToQueue = { queueEnd(it) },
                    onEditInfo = { editTarget = it },
                    showIndex = settings.sort == 2,
                    onOpenSearch = { searching = true },
                    categories = categories,
                    categoryMode = categoryMode,
                    onCategoryMode = { categoryMode = it },
                    onReroll = { categorySeed = System.nanoTime() },
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    onPlayCategory = { cat, i -> playQueue(cat.songs, i) },
                    onCategoryShuffle = { cat -> adoptCategory(cat); toggleShuffle() },
                    onCategoryRepeat = { cat -> adoptCategory(cat); cycleRepeat() }
                )
                1 -> AlbumsTab(
                    albums, p, sortSubtitle, topPad, bottomPad,
                    onPlayAll = { playQueue(it, 0) },
                    onPlayNext = { queueNextAll(it) },
                    onShuffle = { playQueue(it.shuffled(), 0) },
                    columns = settings.albumColumns,
                    onColumns = { settings.changeAlbumColumns(it) }
                ) { list -> albumPage = list }
                else -> ArtistsTab(
                    artists, p, topPad, bottomPad,
                    onPlayAll = { playQueue(it, 0) },
                    onPlayNext = { queueNextAll(it) },
                    onShuffle = { playQueue(it.shuffled(), 0) }
                ) { g -> artistPage = g.name }
            }
        }

        // Album detail: opening an album browses its tracks without starting playback.
        albumPage?.let { list ->
            Box(Modifier.fillMaxSize().swipeBack { albumPage = null }.then(sheetMod).background(p.bgBrush)) {
                SongListPage(
                    title = list.firstOrNull()?.album ?: "Album",
                    stretchHeader = true,
                    subtitle = "${list.size} songs · ${list.firstOrNull()?.artist.orEmpty()}",
                    songs = list, current = current, favSet = favSet, p = p,
                    showDuration = showDuration, topPad = topPad, bottomPad = bottomPad,
                    onBack = { albumPage = null },
                    onPlay = { i -> list.getOrNull(i)?.let { playOne(it) } },
                    onPlayAll = { playQueue(list, 0) },
                    onShuffleAll = { playQueue(list.shuffled(), 0) },
                    onToggleFav = { favWithHaptic(it.id) },
                    onLong = { addTarget = it }, onRemove = null, backLabel = "Albums",
                    onPlayNext = { queueNext(it) }, onAddToQueue = { queueEnd(it) },
                    onEditInfo = { editTarget = it }
                )
            }
        }

        // Artist page: all songs by one artist
        artistPage?.let { name ->
            val list = artists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.songs
                ?: emptyList()
            Box(
                Modifier
                    .fillMaxSize()
                    .swipeBack { artistPage = null }
                    .then(sheetMod)
                    .background(p.bgBrush)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                SongListPage(
                    title = name,
                    stretchHeader = true,
                    subtitle = "${list.size} songs",
                    songs = list,
                    current = current,
                    favSet = favSet,
                    p = p,
                    showDuration = showDuration,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    onBack = { artistPage = null },
                    onPlay = { i -> list.getOrNull(i)?.let { playOne(it) } },
                    onPlayAll = { playQueue(list, 0) },
                    onShuffleAll = { playQueue(list.shuffled(), 0) },
                    onToggleFav = { favWithHaptic(it.id) },
                    onLong = { addTarget = it },
                    onRemove = null,
                    backLabel = "Back",
                    onPlayNext = { queueNext(it) },
                    onAddToQueue = { queueEnd(it) },
                    onEditInfo = { editTarget = it }
                )
            }
        }

        // Search page: rises from the bottom like an iOS sheet
        AnimatedVisibility(
            visible = searching,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180))
        ) {
            SearchScreen(
                songs = songs,
                current = current,
                favSet = favSet,
                p = p,
                showDuration = showDuration,
                query = query,
                onQuery = { query = it },
                topPad = topPad,
                bottomPad = bottomPad,
                onClose = { searching = false },
                onPlay = { song, video ->
                    // a search result is a single pick, not a playlist
                    playOne(song, video)
                    if (video) showPlayer = true
                },
                onPlayNext = { queueNext(it) },
                onAddToQueue = { queueEnd(it) },
                onEditInfo = { editTarget = it },
                onToggleFav = { OnlineRegistry.remember(it); favWithHaptic(it.id) },
                onLong = { OnlineRegistry.remember(it); addTarget = it }
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .then(blurMod)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tabsHidden = !barsShown
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .onSizeChanged { tabRowH = it.height }
                    .graphicsLayer {
                        // the floating tab bar fades away when you scroll down
                        alpha = (1f - tabHide) * (1f - imeHide)
                        val sc = 1f - 0.05f * tabHide
                        scaleX = sc
                        scaleY = sc
                        translationY = imeHide * (size.height + 120.dp.toPx())
                    }
                    // a faded tab bar must not take touches
                    .pointerInput(tabsHidden) {
                        if (tabsHidden) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassTabBar(
                    modifier = Modifier.weight(1f),
                    selected = if (searching) -1 else tab,
                    onSelect = {
                        searching = false
                        sub = Sub.None
                        artistPage = null
                        barsVisible = true
                        tab = it
                    },
                    p = p,
                    photo = profile.photo,
                    profileName = profile.name
                )
            }
        }

        // Touch anywhere outside the expanded Dynamic Island: it shrinks back.
        if (islandExpanded && !showPlayer && current != null) {
            Box(
                Modifier.fillMaxSize().zIndex(1.5f)
                    .pointerInput(Unit) { detectTapGestures { islandExpanded = false } }
            )
        }

        // Notification island on top; the music Dynamic Island is pushed down below it.
        val notice = NoticeBus.current
        var shownNotice by remember { mutableStateOf<Notice?>(null) }
        LaunchedEffect(notice) { if (notice != null) shownNotice = notice }

        val islandDensity = LocalDensity.current
        val islandStatusH = with(islandDensity) { WindowInsets.statusBarsIgnoringVisibility.getTop(islandDensity).toDp() }
        val islandTop = ((islandStatusH - settings.islandHeight.dp) / 2).coerceAtLeast(4.dp) + settings.islandOffsetY.dp
        Column(
            Modifier.align(Alignment.TopCenter).zIndex(2f).fillMaxWidth().padding(top = islandTop),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = notice != null && settings.noticeEnabled,
                enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 300f)) { -it * 2 } +
                    fadeIn() + expandVertically(spring(dampingRatio = 0.8f, stiffness = 320f)),
                exit = slideOutVertically { -it * 2 } + fadeOut() +
                    shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 380f))
            ) {
                shownNotice?.let {
                    NoticeIsland(
                        notice = it,
                        onDismiss = { NoticeBus.dismiss() },
                        panelSize = settings.noticeSize,
                        tint = settings.noticeTint,
                        accent = p.accent,
                        islandWidthDp = settings.islandWidth,
                        islandHeightDp = settings.islandHeight,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            if (current != null) {
                current?.let { song ->
                    AnimatedVisibility(visible = !showPlayer, enter = fadeIn(), exit = fadeOut()) {
                        TopIsland(
                            song = song,
                            p = p,
                            isPlaying = isPlaying,
                            isFav = favSet.contains(song.id),
                            shuffle = shuffle,
                            repeatMode = repeatMode,
                            onToggle = { appHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                            onNext = { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } },
                            onPrev = { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } },
                            onFav = { favWithHaptic(song.id) },
                            onShuffle = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                            onRepeat = { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } },
                            islandWidthDp = settings.islandWidth,
                            islandHeightDp = settings.islandHeight,
                            position = position,
                            reserveTop = false,
                            onSeek = { ms ->
                                controller?.seekTo(ms)
                                position = ms
                            },
                            onOpenPlayer = { showPlayer = true },
                            expanded = islandExpanded,
                            onExpandedChange = { islandExpanded = it },
                            onLongPress = { islandExpanded = false; islandMenu = true },
                            sleepActive = sleepEndAt != null || sleepEndOfSong
                        )
                    }
                }
            }
        }

        // Hold the Dynamic Island: iOS style quick menu under it, app behind it blurred.
        AnimatedVisibility(
            visible = menuOpen,
            modifier = Modifier.fillMaxSize().zIndex(3f),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120))
        ) {
            val menuSong = current
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = if (p.dark) 0.28f else 0.10f))
                    .pointerInput(Unit) { detectTapGestures { islandMenu = false } }
            ) {
                if (menuSong != null) {
                    val menuTop = ((WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() - settings.islandHeight.dp) / 2)
                        .coerceAtLeast(4.dp) + settings.islandOffsetY.dp + settings.islandHeight.dp + 12.dp
                    val isFavNow = favSet.contains(menuSong.id)
                    AnimatedVisibility(
                        visible = menuOpen,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = menuTop),
                        enter = fadeIn(tween(160)) + scaleIn(
                            spring(dampingRatio = 0.72f, stiffness = 420f),
                            initialScale = 0.82f,
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        ),
                        exit = fadeOut(tween(100)) + scaleOut(
                            tween(120), targetScale = 0.9f,
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        )
                    ) {
                        IslandContextMenu(
                            p = p,
                            groups = listOf(
                                listOf(
                                    MenuAction(
                                        if (isFavNow) "Remove favorite" else "Favorite",
                                        if (isFavNow) Glyph.Heart else Glyph.HeartOutline
                                    ) { favWithHaptic(menuSong.id); islandMenu = false },
                                    MenuAction("Add to playlist", Glyph.PlusCircle) { addTarget = menuSong; islandMenu = false }
                                ),
                                buildList<MenuAction> {
                                    if (menuSong.isOnline) {
                                        add(MenuAction("Download", Glyph.Download) {
                                            islandMenu = false
                                            OnlineDownloader.start(context, menuSong)
                                        })
                                    }
                                    add(MenuAction("Edit info", Glyph.Edit) { if (!menuSong.isOnline) editTarget = menuSong; islandMenu = false })
                                    add(MenuAction("Share", Glyph.Share) {
                                        islandMenu = false
                                        shareSong(context, menuSong)
                                    })
                                },
                                listOf(
                                    MenuAction(
                                        when {
                                            sleepEndOfSong -> "Sleep timer · end of song"
                                            sleepEndAt != null -> "Sleep timer · ${formatTime(sleepLeftMs)}"
                                            else -> "Sleep timer"
                                        },
                                        Glyph.Moon,
                                        if (sleepEndAt != null || sleepEndOfSong) Color(0xFF0A84FF) else null
                                    ) { islandMenu = false; showIslandSleep = true }
                                )
                            )
                        )
                    }
                }
            }
        }

        // Full player
        AnimatedVisibility(
            visible = showPlayer && current != null,
            modifier = Modifier
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            current?.let { song ->
                // every song of the album that is playing, in track order
                val albumSongs = remember(song.albumId, songs) {
                    songs.filter { it.albumId == song.albumId }
                        .sortedWith(compareBy<Song>({ it.track % 1000 }, { it.title.lowercase() }))
                }
                FullPlayer(
                    song = song,
                    isPlaying = isPlaying,
                    isFav = favSet.contains(song.id),
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    position = position,
                    p = playerPalette,
                    onClose = { showPlayer = false },
                    onToggle = {
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onPrev = {
                        controller?.let {
                            if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0)
                            else it.seekToPreviousMediaItem()
                        }
                    },
                    onNext = {
                        controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
                    },
                    onSeek = { ms ->
                        controller?.seekTo(ms)
                        position = ms
                    },
                    onShuffle = {
                        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                    },
                    onRepeat = {
                        controller?.let {
                            it.repeatMode = when (it.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }
                    },
                    onFav = { favWithHaptic(song.id) },
                    onAddToPlaylist = { addTarget = song },
                    onArtist = { name ->
                        showPlayer = false
                        artistPage = name
                    },
                    albumSongs = albumSongs,
                    onPlayAlbumSong = { i -> albumSongs.getOrNull(i)?.let { playOne(it) } },
                    onEdit = {
                        if (song.isOnline) Toast.makeText(context, "Online songs have no file to edit", Toast.LENGTH_SHORT).show()
                        else editTarget = song
                    },
                    hasVideo = hasVideo,
                    videoPlayer = controller,
                    onToggleVideo = if (song.canWatch) ({ toggleVideo() }) else null,
                    settings = settings,
                    speed = settings.speed,
                    onSpeed = { v ->
                        settings.changeSpeed(v)
                        controller?.setPlaybackSpeed(v)
                    },
                    audioInfo = audioInfo,
                    queue = queue,
                    currentWindow = currentWindow,
                    onPlayQueueItem = { w ->
                        controller?.let {
                            it.seekTo(w, 0L)
                            it.prepare()
                            it.play()
                        }
                    },
                    onRemoveQueueItem = { w ->
                        controller?.let { if (w != it.currentMediaItemIndex) it.removeMediaItem(w) }
                    },
                    onMoveQueueItem = { from, to -> controller?.moveMediaItem(from, to) },
                    onClearUpcoming = {
                        controller?.let { c ->
                            val order = buildQueue(c, byId)
                            val pos = order.indexOfFirst { it.index == c.currentMediaItemIndex }
                            if (pos >= 0) {
                                order.drop(pos + 1).map { it.index }.sortedDescending()
                                    .forEach { c.removeMediaItem(it) }
                            }
                        }
                    },
                    sleepActive = sleepEndAt != null,
                    sleepEndOfSong = sleepEndOfSong,
                    sleepLeftMs = sleepLeftMs,
                    onSleepPick = { startSleep(it) }
                )
            }
        }
    }

    // song menus (long-press / three dots) get a "Sleep timer" entry that opens this sheet
    SideEffect {
        SleepMenuBridge.open = { showIslandSleep = true }
        SleepMenuBridge.active = sleepEndAt != null || sleepEndOfSong
        SleepMenuBridge.label = when {
            sleepEndOfSong -> "Sleep timer · end of song"
            sleepEndAt != null -> "Sleep timer · ${formatTime(sleepLeftMs)}"
            else -> "Sleep timer"
        }
    }
    DisposableEffect(Unit) { onDispose { SleepMenuBridge.open = null } }

    if (showIslandSleep) {
        SleepTimerSheet(
            active = sleepEndAt != null,
            endOfSong = sleepEndOfSong,
            leftMs = sleepLeftMs,
            onPick = { startSleep(it) },
            onDismiss = { showIslandSleep = false }
        )
    }

    addTarget?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = profile.playlists,
            p = p,
            onPick = { pl ->
                profile.addToPlaylist(pl.id, song.id)
                Toast.makeText(context, "Added to ${pl.name}", Toast.LENGTH_SHORT).show()
                addTarget = null
            },
            onCreate = { name ->
                val pl = profile.createPlaylist(name, song.id)
                Toast.makeText(context, "Added to ${pl.name}", Toast.LENGTH_SHORT).show()
                addTarget = null
            },
            onDismiss = { addTarget = null },
            onEdit = {
                editTarget = song
                addTarget = null
            }
        )
    }
    editTarget?.takeIf { !it.isOnline }?.let { song ->
        EditSongDialog(
            song = song,
            p = p,
            saving = saving,
            onSave = { edit ->
                grantAsked = false
                pendingEdit = song to edit
            },
            onDismiss = { if (!saving) editTarget = null }
        )
    }
    if (showNewPlaylist) {
        NewPlaylistDialog(
            p = p,
            onCreate = { name -> profile.createPlaylist(name); showNewPlaylist = false },
            onDismiss = { showNewPlaylist = false }
        )
    }
    if (showEdit) {
        EditProfileDialog(
            profile = profile,
            p = p,
            onDismiss = { showEdit = false }
        )
    }
}
