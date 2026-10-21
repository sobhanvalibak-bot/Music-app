package com.example.musicapp

import android.content.Context
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── Listening log ─────────────────────────

/** One month of listening, ready to draw. */
class MonthStats(
    val label: String,
    val totalMs: Long,
    val prevMs: Long,
    val daily: LongArray,
    /** song, plays, listened ms */
    val topSongs: List<Triple<Song, Long, Long>>,
    /** artist name, listened ms */
    val topArtists: List<Pair<String, Long>>,
    val activeDays: Int
)

class SongPick(val song: Song, val reason: String)

/**
 * Remembers what you listen to: for every day, every song's play count and listened time.
 * A play counts once a song has been heard for 30 seconds. Stays on the device (SharedPreferences "listenlog"),
 * and only the last 100 days are kept. Used by "Your listening this month" and "Song of the day".
 */
class ListenLog(context: Context) {
    private val prefs = context.getSharedPreferences("listenlog", Context.MODE_PRIVATE)

    /** day ("yyyy-MM-dd") -> song id -> [plays, listened ms] */
    private val days = LinkedHashMap<String, HashMap<Long, LongArray>>()

    /** Goes up every time the log is saved, so screens can refresh. */
    var version by mutableIntStateOf(0)
        private set

    private var sessionSong = -1L
    private var sessionMs = 0L
    private var pending = 0

    init {
        try {
            val root = JSONObject(prefs.getString("log", "{}") ?: "{}")
            val dayKeys = root.keys()
            while (dayKeys.hasNext()) {
                val day = dayKeys.next()
                val o = root.getJSONObject(day)
                val m = HashMap<Long, LongArray>()
                val ids = o.keys()
                while (ids.hasNext()) {
                    val k = ids.next()
                    val a = o.getJSONArray(k)
                    m[k.toLong()] = longArrayOf(a.getLong(0), a.getLong(1))
                }
                days[day] = m
            }
        } catch (_: Exception) {
        }
    }

    private fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** Call once a second while [songId] is playing. */
    fun tick(songId: Long, ms: Long = 1000L) {
        val m = days.getOrPut(dayKey(System.currentTimeMillis())) { HashMap() }
        val e = m.getOrPut(songId) { LongArray(2) }
        e[1] += ms
        if (songId != sessionSong) {
            sessionSong = songId
            sessionMs = 0L
        }
        val before = sessionMs
        sessionMs += ms
        if (before < 30_000L && sessionMs >= 30_000L) e[0] += 1L
        pending++
        if (pending >= 15) flush()
    }

    /** A new track (or a repeat of the same one) started: the 30 second counter starts again. */
    fun newTrack() {
        sessionSong = -1L
        sessionMs = 0L
    }

    fun flush() {
        val cutoff = dayKey(System.currentTimeMillis() - 100L * 86_400_000L)
        days.keys.removeAll { it < cutoff }
        val root = JSONObject()
        for ((day, m) in days) {
            val o = JSONObject()
            for ((id, a) in m) o.put(id.toString(), JSONArray().put(a[0]).put(a[1]))
            root.put(day, o)
        }
        prefs.edit().putString("log", root.toString()).apply()
        pending = 0
        version++
    }

    private fun monthKey(cal: Calendar): String =
        String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

    /** This month's numbers. [byId] turns song ids into songs (songs that are gone are skipped in the top lists). */
    fun month(byId: Map<Long, Song>): MonthStats {
        val cal = Calendar.getInstance()
        val prefix = monthKey(cal)
        val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val label = SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
        val daily = LongArray(dim)
        val perSong = HashMap<Long, LongArray>()
        var total = 0L
        var active = 0
        val prevCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, -1)
        }
        val prevPrefix = monthKey(prevCal)
        var prev = 0L
        for ((day, m) in days) {
            if (day.startsWith(prevPrefix)) {
                for (a in m.values) prev += a[1]
                continue
            }
            if (!day.startsWith(prefix)) continue
            val di = (day.substring(8, 10).toIntOrNull() ?: 1) - 1
            var dayMs = 0L
            for ((id, a) in m) {
                dayMs += a[1]
                val t = perSong.getOrPut(id) { LongArray(2) }
                t[0] += a[0]
                t[1] += a[1]
            }
            daily[di.coerceIn(0, dim - 1)] += dayMs
            total += dayMs
            if (dayMs > 0L) active++
        }
        val topSongs = perSong.entries
            .mapNotNull { (id, a) -> byId[id]?.let { Triple(it, a[0], a[1]) } }
            .sortedWith(compareByDescending<Triple<Song, Long, Long>> { it.second }.thenByDescending { it.third })
            .take(5)
        val perArtist = HashMap<String, Long>()
        for ((id, a) in perSong) {
            val s = byId[id] ?: continue
            val name = s.artists.firstOrNull() ?: s.artist
            perArtist[name] = (perArtist[name] ?: 0L) + a[1]
        }
        val topArtists = perArtist.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return MonthStats(label, total, prev, daily, topSongs, topArtists, active)
    }

    /** song id -> how many days ago it was last played (0 = today). */
    private fun lastPlayedDaysAgo(): Map<Long, Int> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayMs = fmt.parse(dayKey(System.currentTimeMillis()))?.time ?: return emptyMap()
        val out = HashMap<Long, Int>()
        for (day in days.keys.sorted()) {
            val ms = fmt.parse(day)?.time ?: continue
            val ago = ((todayMs - ms) / 86_400_000.0).roundToInt().coerceAtLeast(0)
            for (id in days[day]!!.keys) out[id] = ago
        }
        return out
    }

    /**
     * One song for today, the same all day. Favorites and songs you haven't played for a while
     * are more likely; never the same song two days in a row.
     */
    fun songOfDay(songs: List<Song>, favSet: Set<Long>): SongPick? {
        val pool = songs.filter { !it.isOnline }
        if (pool.isEmpty()) return null
        val today = dayKey(System.currentTimeMillis())
        val savedDay = prefs.getString("sotd_day", null)
        val savedId = prefs.getLong("sotd_id", -1L)
        val ago = lastPlayedDaysAgo()
        var chosen: Song? = if (savedDay == today) pool.firstOrNull { it.id == savedId } else null
        if (chosen == null) {
            val weights = pool.map { s ->
                var w = 1.0
                val a = ago[s.id]
                w += if (a == null) 3.0 else minOf(a, 60) / 20.0
                if (favSet.contains(s.id)) w += 2.0
                if (s.id == savedId && pool.size > 1) w = 0.0
                w
            }
            val rnd = java.util.Random(today.hashCode().toLong() * 31L + 7L)
            var r = rnd.nextDouble() * weights.sum()
            var pickIndex = pool.lastIndex
            for (i in pool.indices) {
                r -= weights[i]
                if (r <= 0.0) {
                    pickIndex = i
                    break
                }
            }
            chosen = pool[pickIndex]
            prefs.edit().putString("sotd_day", today).putLong("sotd_id", chosen.id).apply()
        }
        val song: Song = chosen
        val a = ago[song.id]
        val reason = when {
            a != null && a >= 60 -> "You haven't played this in ${a / 30} months"
            a != null && a >= 14 -> "You haven't played this in ${a / 7} weeks"
            favSet.contains(song.id) -> "One of your favorites"
            a == null -> "A hidden gem from your library"
            else -> "Picked for you today"
        }
        return SongPick(song, reason)
    }
}

fun formatListening(ms: Long): String {
    val min = ms / 60_000L
    return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
}

// ───────────────────────── Song of the day ─────────────────────────

@Composable
fun SongOfDayCard(
    pick: SongPick,
    isFav: Boolean,
    p: Palette,
    onPlay: () -> Unit,
    onFav: () -> Unit,
    modifier: Modifier = Modifier
) {
    val date = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()).uppercase() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.30f), p.card)))
            .padding(14.dp)
    ) {
        Text(
            "SONG OF THE DAY · $date",
            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.accent, letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(pick.song, Modifier.size(96.dp), 16.dp, p, 400)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pick.song.title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    pick.song.artist, color = p.secondary, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    pick.reason, color = p.text.copy(alpha = 0.75f), fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .bounceClick(onPlay)
                    .clip(RoundedCornerShape(50))
                    .background(p.accent),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphIcon(Glyph.Play, Color.White, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .size(42.dp)
                    .bounceClick(onFav)
                    .clip(CircleShape)
                    .background(p.selectedPill),
                contentAlignment = Alignment.Center
            ) {
                GlyphIcon(
                    if (isFav) Glyph.Heart else Glyph.HeartOutline,
                    if (isFav) p.accent else p.text,
                    Modifier.size(20.dp)
                )
            }
        }
    }
}

// ───────────────────────── Your listening this month ─────────────────────────

@Composable
private fun DailyBars(daily: LongArray, p: Palette) {
    val max = (daily.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        daily.forEach { ms ->
            val frac = (ms.toFloat() / max).coerceIn(0f, 1f)
            val peak = ms > 0L && ms >= max * 0.8f
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(maxOf(frac, 0.05f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (peak) p.accent else p.secondary.copy(alpha = 0.35f))
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("1", color = p.secondary, fontSize = 10.sp)
        Text("15", color = p.secondary, fontSize = 10.sp)
        Text("${daily.size}", color = p.secondary, fontSize = 10.sp)
    }
}

private fun compareText(stats: MonthStats): Pair<String, Boolean> =
    if (stats.prevMs > 0L) {
        val pct = ((stats.totalMs - stats.prevMs) * 100.0 / stats.prevMs).roundToInt()
        if (pct >= 0) "$pct% more than last month" to true else "${-pct}% less than last month" to false
    } else {
        "Your first month tracked" to false
    }

@Composable
private fun StatTile(label: String, title: String, detail: String, song: Song?, p: Palette, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(p.selectedPill).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (song != null) {
            Cover(song, Modifier.size(44.dp), 10.dp, p, 200)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = p.secondary, fontSize = 11.sp)
            Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = p.secondary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
fun ListeningCard(stats: MonthStats, p: Palette, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val (cmp, up) = compareText(stats)
    Column(
        modifier
            .fillMaxWidth()
            .bounceClick(onOpen)
            .clip(RoundedCornerShape(20.dp))
            .background(p.card)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Your listening this month", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.clip(RoundedCornerShape(50)).background(p.selectedPill).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(stats.label, color = p.text, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (stats.totalMs < 60_000L) {
            Text("Nothing yet", color = p.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Play some music and your month shows up here.", color = p.secondary, fontSize = 12.sp)
            return@Column
        }
        Text(formatListening(stats.totalMs), color = p.text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text(cmp, color = if (up) Color(0xFF30D158) else p.secondary, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        DailyBars(stats.daily, p)
        val top = stats.topSongs.firstOrNull()
        val artist = stats.topArtists.firstOrNull()
        if (top != null && artist != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Top song", top.first.title, "${top.second} plays", top.first, p, Modifier.weight(1f))
                StatTile("Top artist", artist.first, formatListening(artist.second), null, p, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("See full recap", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("›", color = p.accent, fontSize = 16.sp)
        }
    }
}

@Composable
fun RecapPage(
    stats: MonthStats,
    p: Palette,
    topPad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit
) {
    val (cmp, up) = compareText(stats)
    LazyColumn(contentPadding = PaddingValues(top = topPad, bottom = bottomPad)) {
        item { BackBar("Profile", p, onBack) }
        item { LargeTitle("${stats.label} recap", "${stats.activeDays} days with music", p) }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)
            ) {
                Text("Total listening time", color = p.secondary, fontSize = 12.sp)
                Text(formatListening(stats.totalMs), color = p.text, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                Text(cmp, color = if (up) Color(0xFF30D158) else p.secondary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                DailyBars(stats.daily, p)
            }
        }
        item { SectionHeader("Top songs", "", p) {} }
        if (stats.topSongs.isEmpty()) {
            item { Text("Nothing yet.", color = p.secondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        itemsIndexed(stats.topSongs) { i, t ->
            Row(
                Modifier.fillMaxWidth().bounceClick { onPlay(t.first) }.padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1}", color = p.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Cover(t.first, Modifier.size(52.dp), 10.dp, p, 200)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.first.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.first.artist, color = p.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${t.second} plays · ${formatListening(t.third)}", color = p.secondary, fontSize = 12.sp)
            }
        }
        item { SectionHeader("Top artists", "", p) {} }
        itemsIndexed(stats.topArtists) { i, a ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1}", color = p.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Text(a.first, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatListening(a.second), color = p.secondary, fontSize = 12.sp)
            }
        }
    }
}

// ───────────────────────── Swipe cover ─────────────────────────

/** Drag state of the full player's cover: the covers of the previous and next song follow your finger. */
class SwipeDeckState {
    var dragX by mutableFloatStateOf(0f)
    var step by mutableFloatStateOf(1f)

    /** The song the cover is already showing after a swipe, until the player reports the new song. */
    var settling by mutableStateOf<Song?>(null)
}

/**
 * Horizontal swipe on the cover: left = next song, right = previous song. Past about a quarter of the cover
 * the swipe commits ([onNext] / [onPrev]), otherwise the cover springs back. At the ends of the queue it rubber-bands.
 */
fun Modifier.swipeDeck(
    state: SwipeDeckState,
    enabled: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val prevOk by rememberUpdatedState(hasPrev)
    val nextOk by rememberUpdatedState(hasNext)
    val prevCb by rememberUpdatedState(onPrev)
    val nextCb by rememberUpdatedState(onNext)
    var job by remember { mutableStateOf<Job?>(null) }

    fun finish() {
        val threshold = state.step * 0.28f
        val target = when {
            state.dragX < -threshold && nextOk -> -state.step
            state.dragX > threshold && prevOk -> state.step
            else -> 0f
        }
        job?.cancel()
        job = scope.launch {
            animate(state.dragX, target, animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) { v, _ ->
                state.dragX = v
            }
            if (target < 0f) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                nextCb()
                state.dragX = 0f
            } else if (target > 0f) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                prevCb()
                state.dragX = 0f
            }
        }
    }

    this
        .onSizeChanged { state.step = (minOf(it.width, it.height) * 0.88f).coerceAtLeast(1f) }
        .then(
            if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { job?.cancel() },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                    onHorizontalDrag = { _, d ->
                        val next = state.dragX + d
                        val blocked = (next < 0f && !nextOk) || (next > 0f && !prevOk)
                        val moved = state.dragX + (if (blocked) d * 0.35f else d)
                        state.dragX = moved.coerceIn(-state.step, state.step)
                    }
                )
            }
        )
}

/** The dimmed cover of the previous ([dir] = -1) or next ([dir] = 1) song, sliding toward the middle as you swipe. */
@Composable
fun DeckNeighbor(song: Song, dir: Int, deck: SwipeDeckState, p: Palette, appear: Float) {
    Cover(
        song,
        Modifier
            .aspectRatio(1f)
            .graphicsLayer {
                val s = deck.step
                val x = dir * s + deck.dragX
                val centered = (1f - abs(x) / s).coerceIn(0f, 1f)
                translationX = x
                val sc = 0.78f + 0.22f * centered
                scaleX = sc
                scaleY = sc
                alpha = (0.5f + 0.5f * centered) * appear.coerceIn(0f, 1f)
                shape = RoundedCornerShape(16.dp)
                clip = false
            },
        16.dp, p, 600
    )
}
