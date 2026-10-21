package com.example.musicapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val ACTION_OPEN_PLAYER = "com.example.musicapp.OPEN_PLAYER"

/**
 * Owns the player and the MediaSession. This is what gives you the notification,
 * lock-screen controls, Bluetooth/headset buttons and background playback.
 *
 * Extras on top of plain playback (all work on local files only):
 *  - Crossfade (0-12 s): a second, short-lived player keeps playing the end of the old song
 *    while the main player fades the next one in.
 *  - Skip silence, sleep timer (minutes or "end of this song").
 *  - Publishes the audio session so the system equalizer can attach to it.
 * Gapless playback between songs is built into ExoPlayer.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    /** Local files as usual; online songs (npyt://...) get their stream address resolved just in time. */
    private val mediaSourceFactory by lazy {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(OnlineEngine.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), OnlineResolver)
        )
    }

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var tail: ExoPlayer? = null
    private var tailItemId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var fading = false
    private var fadeElapsed = 0L
    private var fadeTotal = 1L
    private var lastTick = 0L
    private var effectSession = 0

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            onTick(p)
            if (p.isPlaying) handler.postDelayed(this, if (fading) 40L else 250L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        YoutubeAuth.init(this)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        p.skipSilenceEnabled = prefs.getBoolean("skip_silence", false)

        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                MusicWidget.pushFromPlayer(this@PlaybackService, p)
                if (isPlaying) {
                    // a timer that ran out while paused is simply forgotten
                    if (SleepTimer.endAtMs > 0L && SystemClock.elapsedRealtime() >= SleepTimer.endAtMs) {
                        SleepTimer.endAtMs = 0L
                    }
                    if (fading) tail?.play()
                    lastTick = SystemClock.elapsedRealtime()
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                } else {
                    handler.removeCallbacks(ticker)
                    if (fading) tail?.pause()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!fading) p.volume = 1f
                MusicWidget.pushFromPlayer(this@PlaybackService, p)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    // "stop after this song" has done its job
                    SleepTimer.afterTrack = false
                    p.pauseAtEndOfMediaItems = false
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                publishAudioSession(audioSessionId)
            }
        })
        publishAudioSession(p.audioSessionId)

        // Tapping the notification / lock-screen card opens the full player.
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, p).setSessionActivity(openPending).build()
    }

    // ───────────────────────── Ticker: settings, sleep timer, crossfade ─────────────────────────

    private fun onTick(p: ExoPlayer) {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick).coerceIn(0L, 400L)
        lastTick = now

        val skip = prefs.getBoolean("skip_silence", false)
        if (p.skipSilenceEnabled != skip) p.skipSilenceEnabled = skip
        if (p.pauseAtEndOfMediaItems != SleepTimer.afterTrack) {
            p.pauseAtEndOfMediaItems = SleepTimer.afterTrack
        }

        val end = SleepTimer.endAtMs
        if (end > 0L && now >= end) {
            SleepTimer.off()
            if (fading) endFade(p)
            p.pause()
            return
        }

        if (fading) {
            fadeElapsed += dt
            val f = (fadeElapsed.toFloat() / fadeTotal.toFloat()).coerceIn(0f, 1f)
            val half = PI.toFloat() / 2f
            p.volume = sin(f * half)
            tail?.volume = cos(f * half)
            if (f >= 1f) endFade(p)
            return
        }
        maybeCrossfade(p)
    }

    private fun maybeCrossfade(p: ExoPlayer) {
        val x = prefs.getInt("crossfade", 0) * 1000L
        if (x <= 0L || SleepTimer.afterTrack) return
        if (p.repeatMode == Player.REPEAT_MODE_ONE || !p.hasNextMediaItem()) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur < x * 2 + 2000L) return
        val item = p.currentMediaItem ?: return
        val pos = p.currentPosition
        val remaining = dur - pos
        // load the end of this song into the helper player a little early so it starts instantly
        if (remaining <= x + 2500L && remaining > x) preloadTail(p, item, dur - x)
        if (remaining <= x && remaining > 120L) startCrossfade(p, item, pos, remaining)
    }

    private fun ensureTail(p: ExoPlayer): ExoPlayer {
        val t = tail ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .build()
            .also { tail = it }
        if (t.audioSessionId != p.audioSessionId) t.audioSessionId = p.audioSessionId
        return t
    }

    private fun preloadTail(p: ExoPlayer, item: MediaItem, atMs: Long) {
        if (tailItemId == item.mediaId) return
        val t = ensureTail(p)
        t.playWhenReady = false
        t.setMediaItem(item, atMs)
        t.prepare()
        tailItemId = item.mediaId
    }

    private fun startCrossfade(p: ExoPlayer, item: MediaItem, pos: Long, remaining: Long) {
        val t = ensureTail(p)
        if (tailItemId != item.mediaId) {
            t.setMediaItem(item, pos)
            t.prepare()
            tailItemId = item.mediaId
        } else {
            t.seekTo(pos)
        }
        t.playbackParameters = p.playbackParameters
        t.volume = 1f
        t.playWhenReady = true

        fading = true
        fadeElapsed = 0L
        fadeTotal = remaining.coerceAtLeast(200L)
        p.volume = 0f
        p.seekToNextMediaItem()
    }

    private fun endFade(p: ExoPlayer) {
        fading = false
        p.volume = 1f
        tail?.let {
            it.stop()
            it.clearMediaItems()
        }
        tailItemId = null
    }

    // ───────────────────────── System equalizer ─────────────────────────

    private fun publishAudioSession(id: Int) {
        if (effectSession != 0 && effectSession != id) closeAudioSession(effectSession)
        PlaybackBridge.audioSessionId = id
        if (id != 0) {
            effectSession = id
            sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                }
            )
        }
    }

    private fun closeAudioSession(id: Int) {
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (effectSession != 0) closeAudioSession(effectSession)
        tail?.release()
        tail = null
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}
