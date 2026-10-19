package com.example.musicapp

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
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

    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf<Sub>(Sub.None) }
    var addTarget by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    var showPlayer by remember { mutableStateOf(false) }
    var artistPage by remember { mutableStateOf<String?>(null) }
    var barsVisible by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    BackHandler(enabled = searching) { searching = false }
    BackHandler(enabled = tab == 3 && sub != Sub.None) { sub = Sub.None }
    BackHandler(enabled = artistPage != null) { artistPage = null }
    BackHandler(enabled = showPlayer) { showPlayer = false }

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
    val barsShown = !(searching && imeOpen)

    // Mini player and tab bar slide down off the screen and come back up with a spring.
    val miniHide by animateFloatAsState(
        if (barsShown) 0f else 1f,
        spring(dampingRatio = 0.8f, stiffness = 260f),
        label = "miniHide"
    )
    val tabHide by animateFloatAsState(
        if (barsShown) 0f else 1f,
        spring(dampingRatio = 0.85f, stiffness = 380f),
        label = "tabHide"
    )

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

    val songs = remember(songsVersion) { loadSongs(context) }
    val byId = remember(songs) { songs.associateBy { it.id } }
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

    // Playback runs in PlaybackService (media session + notification + lock screen).
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var current by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var position by remember { mutableLongStateOf(0L) }
    var pendingOpen by remember { mutableStateOf(false) }

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
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffle = shuffleModeEnabled }
                override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    current = mediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
                }
            }
            c.addListener(listener)
            onDispose { c.removeListener(listener) }
        }
    }

    fun playQueue(queue: List<Song>, index: Int) {
        val c = controller ?: return
        if (queue.isEmpty()) return
        c.setMediaItems(queue.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun queueNext(song: Song) {
        val c = controller ?: return
        val insertAt = (c.currentMediaItemIndex + 1).coerceAtLeast(0)
        c.addMediaItem(insertAt, song.toMediaItem())
        Toast.makeText(context, "Will play next: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    // "Cover" theme: take the colour from the cover of the song that is playing.
    LaunchedEffect(current?.id, settings.theme) {
        if (settings.theme == 3) onTint(current?.let { songTint(context, it) })
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
    LaunchedEffect(controller, showPlayer) {
        val c = controller
        if (c != null && showPlayer) {
            while (true) {
                position = c.currentPosition
                delay(400)
            }
        }
    }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (current != null) 150.dp else 96.dp)
    val sortSubtitle = SortSubtitles[settings.sort.coerceIn(0, 2)]
    val showDuration = settings.showDuration

    Box(Modifier.fillMaxSize().nestedScroll(barsScroll)) {
        Crossfade(targetState = tab, label = "tabs") { t ->
            if (t == 3) {
                when (val s = sub) {
                    is Sub.None -> ProfileTab(
                        profile = profile,
                        byId = byId,
                        totalSongs = songs.size,
                        p = p,
                        topPad = topPad,
                        bottomPad = bottomPad,
                        onPickPhoto = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickAvatarVideo = { avatarVideoPicker.launch("video/mp4") },
                        onPickBannerVideo = { bannerVideoPicker.launch("video/mp4") },
                        onEdit = { showEdit = true },
                        onSettings = { sub = Sub.Settings },
                        onFavorites = { sub = Sub.Favorites },
                        onOpenPlaylist = { sub = Sub.PlaylistPage(it) },
                        onNewPlaylist = { showNewPlaylist = true },
                        onPlayFavorites = { list, i -> playQueue(list, i) }
                    )
                    is Sub.Settings -> SettingsTab(settings, p, topPad, bottomPad) { sub = Sub.None }
                    is Sub.Favorites -> {
                        val favSongs = profile.favorites.reversed().mapNotNull { byId[it] }
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
                            onPlay = { i -> playQueue(favSongs, i) },
                            onToggleFav = { profile.toggleFavorite(it.id) },
                            onLong = { addTarget = it },
                            onRemove = null,
                            onPlayNext = { queueNext(it) },
                            onEditInfo = { editTarget = it }
                        )
                    }
                    is Sub.PlaylistPage -> {
                        val pl = profile.playlists.firstOrNull { it.id == s.id }
                        if (pl != null) {
                            val plSongs = pl.songIds.mapNotNull { byId[it] }
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
                                onPlay = { i -> playQueue(plSongs, i) },
                                onToggleFav = { profile.toggleFavorite(it.id) },
                                onRemove = { profile.removeFromPlaylist(pl.id, it.id) },
                                onDelete = {
                                    sub = Sub.None
                                    profile.deletePlaylist(pl.id)
                                }
                            )
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
                    current = current,
                    favSet = favSet,
                    p = p,
                    showDuration = showDuration,
                    sortSubtitle = sortSubtitle,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    onPlay = { i -> playQueue(sorted, i) },
                    onToggleFav = { profile.toggleFavorite(it.id) },
                    onLong = { addTarget = it },
                    onPlayNext = { queueNext(it) },
                    onEditInfo = { editTarget = it }
                )
                1 -> AlbumsTab(albums, p, sortSubtitle, topPad, bottomPad) { list -> playQueue(list, 0) }
                else -> ArtistsTab(artists, p, topPad, bottomPad) { g -> artistPage = g.name }
            }
        }

        // Artist page: all songs by one artist
        artistPage?.let { name ->
            val list = artists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.songs
                ?: emptyList()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(p.bgBrush)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                SongListPage(
                    title = name,
                    subtitle = "${list.size} songs",
                    songs = list,
                    current = current,
                    favSet = favSet,
                    p = p,
                    showDuration = showDuration,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    onBack = { artistPage = null },
                    onPlay = { i -> playQueue(list, i) },
                    onToggleFav = { profile.toggleFavorite(it.id) },
                    onLong = { addTarget = it },
                    onRemove = null,
                    backLabel = "Back",
                    onPlayNext = { queueNext(it) },
                    onEditInfo = { editTarget = it }
                )
            }
        }

        // Search page
        if (searching) {
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
                onPlay = { list, i -> playQueue(list, i) },
                onToggleFav = { profile.toggleFavorite(it.id) },
                onLong = { addTarget = it }
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            current?.let { song ->
                Box(
                    Modifier.graphicsLayer {
                        translationY = miniHide * (size.height + 160.dp.toPx())
                        alpha = 1f
                    }
                ) {
                    MiniPlayer(
                        song = song,
                        isPlaying = isPlaying,
                        isFav = favSet.contains(song.id),
                        p = p,
                        onOpen = { showPlayer = true },
                        onToggle = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                        onNext = {
                            controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
                        },
                        onPrev = {
                            controller?.let {
                                if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0)
                            }
                        },
                        canNext = { controller?.hasNextMediaItem() == true },
                        onFav = { profile.toggleFavorite(song.id) }
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .graphicsLayer {
                        translationY = tabHide * (size.height + 120.dp.toPx())
                        alpha = 1f - tabHide * 0.5f
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
                SearchButton(active = searching, p = p) {
                    barsVisible = true
                    searching = true
                }
            }
        }

        // Full player
        AnimatedVisibility(
            visible = showPlayer && current != null,
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
                    p = p,
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
                    onFav = { profile.toggleFavorite(song.id) },
                    onAddToPlaylist = { addTarget = song },
                    onArtist = { name ->
                        showPlayer = false
                        artistPage = name
                    },
                    albumSongs = albumSongs,
                    onPlayAlbumSong = { i -> playQueue(albumSongs, i) },
                    onEdit = { editTarget = song }
                )
            }
        }
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
    editTarget?.let { song ->
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
