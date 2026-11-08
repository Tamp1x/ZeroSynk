package com.zerosynk.app.net

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
    // hello (phone -> PC) / welcome (PC -> phone): wire protocol version, checked on connect
    val proto: Int? = null,
    val design: String? = null,
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
    // media: timeline in seconds (progress bar / seek); mediaControl: seek target in seconds
    val position: Long? = null,
    val duration: Long? = null,
    // media: album art as base64 (raw thumbnail bytes, sent on track change only)
    val cover: String? = null,
    val key: String? = null,
    val pkg: String? = null,
    val appName: String? = null,
    val location: String? = null,
    val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    val replyable: Boolean? = null,
    val origin: String? = null, // phone | pc
    // clipboard kind="stack": recent PC clipboard entries (history seed)
    val items: List<ClipboardItem>? = null,
    val ts: Long? = null,
)

/** One entry of the PC's clipboard stack. */
@Serializable
data class ClipboardItem(
    val kind: String = "text",
    val text: String,
    val hash: String? = null,
    val ts: Long = 0,
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
        proto = VERSION,
        ts = now(),
    )

    fun ping() = WsMessage(type = "ping", ts = now())

    fun sharedLocation(uri: String) = WsMessage(type = "location", location = uri, ts = now())

    fun phoneStatus(level: Int, charging: Boolean) = WsMessage(
        type = "phoneStatus", batteryLevel = level, charging = charging, ts = now(),
    )

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

    fun clipboardHtml(html: String, hash: String) = WsMessage(
        type = "clipboard",
        kind = "html",
        text = html,
        hash = hash,
        mime = "text/html",
        ts = now(),
    )

    /** Recent PC clipboard texts — recorded into the phone's history, never applied. */
    fun clipboardStack(items: List<ClipboardItem>) = WsMessage(
        type = "clipboard",
        kind = "stack",
        items = items,
        ts = now(),
    )

    fun fileComplete(fileName: String, fileId: String? = null) = WsMessage(
        type = "fileComplete",
        fileName = fileName,
        fileId = fileId,
        ts = now(),
    )

    fun mediaControl(action: String, position: Long? = null) = WsMessage(
        type = "mediaControl",
        action = action,
        position = position,
        ts = now(),
    )

    /** Now playing, sent by the phone (origin="phone") for the PC's player card. */
    fun media(
        state: String,
        title: String,
        artist: String,
        app: String,
        position: Long,
        duration: Long,
        cover: String?,
    ) = WsMessage(
        type = "media",
        origin = "phone",
        state = state,
        title = title,
        artist = artist,
        app = app,
        position = position,
        duration = duration,
        cover = cover,
        ts = now(),
    )

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

    fun design(design: String) = WsMessage(type = "design", design = design, ts = now())

    fun bye() = WsMessage(type = "bye", ts = now())
}
