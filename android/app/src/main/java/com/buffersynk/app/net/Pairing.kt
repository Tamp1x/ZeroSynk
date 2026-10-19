package com.buffersynk.app.net

import android.content.Context
import android.net.Uri
import com.buffersynk.app.data.Prefs
import com.buffersynk.app.service.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Pairing flows: QR payload (buffersynk://pair?...) and manual PIN. */
object PairingCtl {

    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data object Success : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    data class Payload(
        val hosts: List<String>,
        val port: Int,
        val token: String?,
        val pcName: String?,
    )

    fun reset() {
        _status.value = Status.Idle
    }

    fun parseUri(raw: String): Payload? {
        val uri = Uri.parse(raw) ?: return null
        if (uri.scheme !in setOf("zerosynk", "buffersynk") || uri.host != "pair") return null
        val hosts = uri.getQueryParameter("h")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (hosts.isEmpty()) return null
        val port = uri.getQueryParameter("p")?.toIntOrNull() ?: 8765
        val token = uri.getQueryParameter("t")
        val pcName = uri.getQueryParameter("n")
        return Payload(hosts, port, token, pcName)
    }

    suspend fun pairWithUri(context: Context, payload: Payload) {
        _status.value = Status.Working
        val token = payload.token
        if (token.isNullOrEmpty()) {
            _status.value = Status.Error("В QR-коде нет токена — введите PIN вручную")
            return
        }
        for (host in payload.hosts) {
            val ping = Api.ping(host, payload.port)
            if (ping != null) {
                save(context, host, payload.port, token, payload.pcName ?: ping.pcName)
                return
            }
        }
        _status.value = Status.Error(
            "ПК не найден. Проверьте, что ПК и телефон в одной сети, а ZeroSynk запущен на ПК.",
        )
    }

    suspend fun pairWithPin(context: Context, host: String, port: Int, pin: String) {
        _status.value = Status.Working
        val result = Api.pair(host, port, pin)
        when {
            result.ok && result.token != null ->
                save(context, host, port, result.token, result.pcName)

            result.error == "bad_pin" ->
                _status.value = Status.Error("Неверный PIN-код")

            result.error == "too_many_attempts" ->
                _status.value = Status.Error("Слишком много попыток — подождите минуту")

            else ->
                _status.value = Status.Error("Не удалось подключиться: ${result.error ?: "сеть недоступна"}")
        }
    }

    private fun save(context: Context, host: String, port: Int, token: String, pcName: String?) {
        Prefs.get(context).savePairing(host, port, token, pcName)
        SyncService.start(context)
        _status.value = Status.Success
    }
}
