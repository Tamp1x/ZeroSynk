package com.zerosynk.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Observable connection state for the UI. */
sealed interface Conn {
    data object Disconnected : Conn
    data class Connecting(val attempt: Int) : Conn
    data class Connected(val pcName: String) : Conn
    data object AuthFailed : Conn
}

/**
 * What the PC is playing right now (pushed over the "media" message).
 * [coverKey] ("title|artist") lets the UI keep the last cover when a position tick
 * arrives without album art.
 */
data class MediaInfo(
    val state: String,
    val title: String,
    val artist: String,
    val app: String,
    val position: Long,
    val duration: Long,
    val coverKey: String,
    val cover: String?,
)

object ServiceState {
    private val _conn = MutableStateFlow<Conn>(Conn.Disconnected)
    val conn: StateFlow<Conn> = _conn

    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent

    private val _media = MutableStateFlow<MediaInfo?>(null)
    /** PC -> phone "now playing" for the home-screen card. */
    val media: StateFlow<MediaInfo?> = _media

    /** Bumped whenever the clipboard history changes — lets the home screen refresh. */
    private val _clipRev = MutableStateFlow(0)
    val clipRev: StateFlow<Int> = _clipRev

    fun bumpClipRevision() {
        _clipRev.value++
    }

    /** Bumped whenever a PC notification is stored — refreshes the home-screen card. */
    private val _notifRev = MutableStateFlow(0)
    val notifRev: StateFlow<Int> = _notifRev

    fun bumpNotifRevision() {
        _notifRev.value++
    }

    fun setConn(c: Conn) {
        _conn.value = c
    }

    fun setEvent(s: String?) {
        _lastEvent.value = s
    }

    fun setMedia(info: MediaInfo?) {
        _media.value = info
    }
}
