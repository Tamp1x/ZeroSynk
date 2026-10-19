package com.buffersynk.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Observable connection state for the UI. */
sealed interface Conn {
    data object Disconnected : Conn
    data class Connecting(val attempt: Int) : Conn
    data class Connected(val pcName: String) : Conn
    data object AuthFailed : Conn
}

object ServiceState {
    private val _conn = MutableStateFlow<Conn>(Conn.Disconnected)
    val conn: StateFlow<Conn> = _conn

    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent

    fun setConn(c: Conn) {
        _conn.value = c
    }

    fun setEvent(s: String?) {
        _lastEvent.value = s
    }
}
