package com.zerosynk.app.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.net.Protocol
import com.zerosynk.app.notif.NotifListenerService
import com.zerosynk.app.service.SyncService
import java.io.ByteArrayOutputStream

/**
 * Reverse media control (PC -> phone): watches the phone's active MediaSessions and
 * pushes "now playing" to the PC, which shows a player card and answers with
 * mediaControl commands (prev/toggle/seek/volume).
 *
 * Needs the same notification-access permission as notification mirroring —
 * MediaSessionManager.getActiveSessions only works with a granted listener.
 */
object PhoneMedia {

    private const val COVER_MAX_PX = 360
    private const val TICK_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())
    private val labels = HashMap<String, String>()

    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controller: MediaController? = null
    private var ticker: Runnable? = null
    private var prefs: Prefs? = null

    @Volatile private var started = false
    @Volatile private var activePushed = false
    @Volatile private var coverKey: String? = null
    @Volatile private var cover: String? = null
    @Volatile private var coverFetching = false

    @Volatile
    private var appContext: Context? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            push()
            scheduleTicker()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            // New track: album art changes too — refetch it below in push().
            push()
            scheduleTicker()
        }
    }

    /** Registers the session listener. Safe to call when access is missing (feature stays off). */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        appContext = app
        prefs = Prefs.get(app)
        component = ComponentName(app, NotifListenerService::class.java)
        manager = try {
            app.getSystemService(MediaSessionManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        val l = MediaSessionManager.OnActiveSessionsChangedListener { pickSession() }
        listener = l
        try {
            manager?.addOnActiveSessionsChangedListener(l, component)
        } catch (_: Exception) {
            // Notification access not granted — mirror both directions stay off.
            listener = null
            return
        }
        started = true
        pickSession()
    }

    @Synchronized
    fun stop() {
        started = false
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        try { listener?.let { manager?.removeOnActiveSessionsChangedListener(it) } } catch (_: Exception) {}
        listener = null
        controller?.unregisterCallback(callback)
        controller = null
        activePushed = false
        coverKey = null
        cover = null
    }

    /** Push the current state right away (called on WS connect so the PC card fills in). */
    fun pushNow() = pickSession()

    /** Execute a mediaControl command coming from the PC. */
    fun handleAction(action: String, position: Long?) {
        val c = controller ?: return
        try {
            when (action) {
                "prev" -> c.transportControls.skipToPrevious()
                "next" -> c.transportControls.skipToNext()
                "toggle" -> if (isPlaying()) c.transportControls.pause() else c.transportControls.play()
                "play" -> c.transportControls.play()
                "pause" -> c.transportControls.pause()
                // MediaSession.stop() is honoured by very few players — pause is the
                // reliable "stop playback" for music apps.
                "stop" -> c.transportControls.pause()
                "seekBack" -> seekBy(c, -10_000L)
                "seekFwd" -> seekBy(c, 10_000L)
                "seek" -> if (position != null && position >= 0) c.transportControls.seekTo(position * 1000)
                "volUp" -> adjustVolume(c, AudioManager.ADJUST_RAISE)
                "volDown" -> adjustVolume(c, AudioManager.ADJUST_LOWER)
            }
        } catch (_: Exception) {}
        // The state callback follows anyway — push now so the PC card reacts fast.
        push()
    }

    // ---- internals ----

    private fun isPlaying(): Boolean =
        controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun seekBy(c: MediaController, deltaMs: Long) {
        val st = c.playbackState ?: return
        val cur = currentPositionMs(st)
        val max = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var target = cur + deltaMs
        if (target < 0) target = 0
        if (max > 0 && target > max) target = max
        c.transportControls.seekTo(target)
    }

    private fun adjustVolume(c: MediaController, direction: Int) {
        try { c.adjustVolume(direction, 0) } catch (_: Exception) {}
    }

    /** PlaybackState.position is as of lastPositionUpdateTime — extrapolate while playing. */
    private fun currentPositionMs(st: PlaybackState): Long {
        val elapsed = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
        val speed = st.playbackSpeed
        return if (st.state == PlaybackState.STATE_PLAYING && elapsed in 0L..5000L && speed > 0f) {
            st.position + (elapsed * speed).toLong()
        } else {
            st.position
        }
    }

    private fun pickSession() {
        val mgr = manager ?: return
        val sessions = try {
            mgr.getActiveSessions(component)
        } catch (_: Exception) {
            emptyList()
        }

        val withTitle = sessions.filter { titleOf(it) != null }
        val chosen = withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: withTitle.firstOrNull()

        val current = controller
        if (chosen != null && (current == null || current.sessionToken != chosen.sessionToken)) {
            try { current?.unregisterCallback(callback) } catch (_: Exception) {}
            controller = chosen
            try { chosen.registerCallback(callback, handler) } catch (_: Exception) {}
        } else if (chosen == null && current != null) {
            try { current.unregisterCallback(callback) } catch (_: Exception) {}
            controller = null
            coverKey = null
            cover = null
        }
        push()
        scheduleTicker()
    }

    private fun titleOf(c: MediaController): String? =
        c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }

    private fun scheduleTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        val c = controller
        if (c == null || c.playbackState?.state != PlaybackState.STATE_PLAYING || !enabled()) return
        val r = Runnable {
            push()
            scheduleTicker()
        }
        ticker = r
        handler.postDelayed(r, TICK_MS)
    }

    private fun enabled(): Boolean {
        val p = prefs ?: return false
        return started && p.syncEnabled && p.mediaControls
    }

    /** Sends the current now-playing state to the PC (1/s while playing). */
    private fun push() {
        if (!enabled()) {
            if (activePushed) sendStopped()
            return
        }

        val c = controller ?: run { if (activePushed) sendStopped(); return }
        val st = c.playbackState ?: run { if (activePushed) sendStopped(); return }
        val title = titleOf(c) ?: run { if (activePushed) sendStopped(); return }
        when (st.state) {
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> {
                if (activePushed) sendStopped()
                return
            }
        }

        val playing = st.state == PlaybackState.STATE_PLAYING ||
            st.state == PlaybackState.STATE_BUFFERING
        val artist = c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val durationMs = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L
        val positionMs = currentPositionMs(st)

        // Album art rides along only when the track changes; ticks stay tiny.
        val key = "$title|$artist"
        if (key != coverKey) {
            coverKey = key
            cover = null
            fetchCover(c, key)
        }

        activePushed = true
        SyncService.sendJson(
            Protocol.media(
                state = if (playing) "playing" else "paused",
                title = title,
                artist = artist,
                app = appLabel(c.packageName),
                position = positionMs / 1000,
                duration = durationMs / 1000,
                cover = cover,
            ),
        )
    }

    /** Clears the PC's player card once (nothing is playing anymore). */
    private fun sendStopped() {
        activePushed = false
        SyncService.sendJson(Protocol.media("stopped", "", "", "", 0, 0, null))
    }

    private fun appLabel(pkg: String): String {
        labels[pkg]?.let { return it }
        val label = try {
            val pm = appContext?.packageManager
            if (pm != null) pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() else pkg
        } catch (_: Exception) {
            pkg
        }
        labels[pkg] = label
        return label
    }

    /** Decodes album art off the main thread and re-pushes with the cover attached. */
    private fun fetchCover(c: MediaController, key: String) {
        if (coverFetching) return
        val meta = c.metadata ?: return
        coverFetching = true
        Thread {
            var b64: String? = null
            try {
                val raw = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                if (raw != null) {
                    val bmp = scaleToFit(raw, COVER_MAX_PX)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    if (bmp !== raw) bmp.recycle()
                }
            } catch (_: Exception) {
            } finally {
                coverFetching = false
            }
            val result = b64
            handler.post {
                if (result != null && coverKey == key) {
                    cover = result
                    push()
                }
            }
        }.start()
    }

    private fun scaleToFit(src: Bitmap, max: Int): Bitmap {
        if (src.width <= max && src.height <= max) return src
        val ratio = minOf(max.toFloat() / src.width, max.toFloat() / src.height)
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
