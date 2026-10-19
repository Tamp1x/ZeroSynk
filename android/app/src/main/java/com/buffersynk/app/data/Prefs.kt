package com.buffersynk.app.data

import android.content.Context
import android.provider.Settings

class Prefs private constructor(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("buffersynk", Context.MODE_PRIVATE)
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

    val deviceId: String
        get() = sp.getString("deviceId", null)
            ?: Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                .also { sp.edit().putString("deviceId", it).apply() }

    fun savePairing(host: String, port: Int, token: String, pcName: String?) {
        sp.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .putString("pcName", pcName)
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
