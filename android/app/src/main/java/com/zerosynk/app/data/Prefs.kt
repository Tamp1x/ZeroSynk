package com.zerosynk.app.data

import android.content.Context
import android.provider.Settings

class Prefs private constructor(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("ZeroSynk", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var host: String?
        get() = sp.getString("host", null)
        set(v) { sp.edit().putString("host", v).apply() }

    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) { sp.edit().putInt("port", v).apply() }

    var token: String?
        get() = sp.getString("token", null)
        set(v) { sp.edit().putString("token", v).apply() }

    var pcName: String?
        get() = sp.getString("pcName", null)
        set(v) { sp.edit().putString("pcName", v).apply() }

    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) { sp.edit().putBoolean("paired", v).apply() }

    var autoApplyClipboard: Boolean
        get() = sp.getBoolean("autoApplyClip", true)
        set(v) { sp.edit().putBoolean("autoApplyClip", v).apply() }

    var autoDownloadFiles: Boolean
        get() = sp.getBoolean("autoDownload", true)
        set(v) { sp.edit().putBoolean("autoDownload", v).apply() }

    /** SAF tree URI of the folder where files from the PC land (null = Download/ZeroSynk). */
    var saveTreeUri: String?
        get() = sp.getString("saveTreeUri", null)
        set(v) { sp.edit().putString("saveTreeUri", v).apply() }

    /** Human-readable folder label picked with the tree (cached, no SAF query on startup). */
    var saveTreeName: String?
        get() = sp.getString("saveTreeName", null)
        set(v) { sp.edit().putString("saveTreeName", v).apply() }

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", true)
        set(v) { sp.edit().putBoolean("startOnBoot", v).apply() }

    var syncEnabled: Boolean
        get() = sp.getBoolean("syncEnabled", true)
        set(v) { sp.edit().putBoolean("syncEnabled", v).apply() }

    var syncNotifs: Boolean
        get() = sp.getBoolean("syncNotifs", false)
        set(v) { sp.edit().putBoolean("syncNotifs", v).apply() }

    var mediaControls: Boolean
        get() = sp.getBoolean("mediaControls", true)
        set(v) { sp.edit().putBoolean("mediaControls", v).apply() }

    var mirrorWinNotifs: Boolean
        get() = sp.getBoolean("mirrorWinNotifs", false)
        set(v) { sp.edit().putBoolean("mirrorWinNotifs", v).apply() }

    /** Notification filter mode (p. 22): all | important | block (blacklist) | allow (whitelist). */
    var notifMode: String
        get() = sp.getString("notifMode", "all") ?: "all"
        set(v) { sp.edit().putString("notifMode", v).apply() }

    /** Comma-separated package names for block/allow modes (p. 22). */
    var notifApps: String
        get() = sp.getString("notifApps", "") ?: ""
        set(v) { sp.edit().putString("notifApps", v).apply() }

    var design: String
        get() = sp.getString("design", "zero") ?: "zero"
        set(v) { sp.edit().putString("design", v).apply() }

    /** Session password from the PC (p. 18): sent with every request when set. */
    var transferPassword: String?
        get() = sp.getString("pw", null)
        set(v) { sp.edit().putString("pw", v).apply() }

    var featureTourShown: Boolean
        get() = sp.getBoolean("featureTourShown", false)
        set(v) { sp.edit().putBoolean("featureTourShown", v).apply() }

    var screenshotSync: Boolean
        get() = sp.getBoolean("screenshotSync", false)
        set(v) { sp.edit().putBoolean("screenshotSync", v).apply() }

    var lastWhatsNewVersion: String
        get() = sp.getString("lastWhatsNewVersion", "") ?: ""
        set(v) { sp.edit().putString("lastWhatsNewVersion", v).apply() }

    var language: String
        get() = sp.getString("language", "ru") ?: "ru"
        set(v) { sp.edit().putString("language", if (v == "en") "en" else "ru").apply() }

    /** One-shot guard: WRITE_EXTERNAL_STORAGE was already requested (API 26–28). */
    var writePermAsked: Boolean
        get() = sp.getBoolean("writePermAsked", false)
        set(v) { sp.edit().putBoolean("writePermAsked", v).apply() }

    val deviceId: String
        get() = sp.getString("deviceId", null)
            ?: Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                .also { sp.edit().putString("deviceId", it).apply() }

    fun savePairing(host: String, port: Int, token: String, pcName: String?, pw: String? = null) {
        sp.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .putString("pcName", pcName)
            .putString("pw", pw)
            .putBoolean("paired", true)
            .apply()
    }

    fun clear() = sp.edit().clear().apply()

    companion object {
        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context).also { instance = it }
        }
    }
}
