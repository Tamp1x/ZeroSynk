package com.buffersynk.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire protocol shared with the PC app. Keep in sync with Protocol.cs. */
@Serializable
data class WsMessage(
    val type: String,
    val deviceName: String? = null,
    val deviceId: String? = null,
    val token: String? = null,
    val pcName: String? = null,
    val ok: Boolean? = null,
    val kind: String? = null,
    val text: String? = null,
    val data: String? = null,
    val hash: String? = null,
    val mime: String? = null,
    val fileId: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val state: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val app: String? = null,
    val action: String? = null,
    val key: String? = null,
    val pkg: String? = null,
    val appName: String? = null,
    val replyable: Boolean? = null,
    val origin: String? = null, // phone | pc
    val ts: Long? = null,
)

val ProtoJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

object Protocol {
    const val VERSION = 1
    const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    fun now(): Long = System.currentTimeMillis() / 1000

    fun hello(deviceName: String, deviceId: String, token: String) = WsMessage(
        type = "hello",
        deviceName = deviceName,
        deviceId = deviceId,
        token = token,
        ts = now(),
    )

    fun ping() = WsMessage(type = "ping", ts = now())

    fun clipboardText(text: String, hash: String) = WsMessage(
        type = "clipboard",
        kind = "text",
        text = text,
        hash = hash,
        ts = now(),
    )

    fun clipboardImage(base64Png: String, hash: String, mime: String) = WsMessage(
        type = "clipboard",
        kind = "image",
        data = base64Png,
        hash = hash,
        mime = mime,
        ts = now(),
    )

    fun fileComplete(fileName: String) = WsMessage(type = "fileComplete", fileName = fileName, ts = now())

    fun mediaControl(action: String) = WsMessage(type = "mediaControl", action = action, ts = now())

    fun notif(key: String, pkg: String, appName: String, title: String, text: String, replyable: Boolean, origin: String) = WsMessage(
        type = "notif",
        key = key,
        pkg = pkg,
        appName = appName,
        title = title,
        text = text,
        replyable = replyable,
        origin = origin,
        ts = now(),
    )

    fun notifReply(key: String, text: String) = WsMessage(
        type = "notifReply",
        key = key,
        text = text,
        ts = now(),
    )

    fun notifRemoved(key: String) = WsMessage(
        type = "notifRemoved",
        key = key,
        ts = now(),
    )

    fun status(state: String) = WsMessage(type = "status", state = state, ts = now())

    fun bye() = WsMessage(type = "bye", ts = now())
}
