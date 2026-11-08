package com.zerosynk.app.design

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.zerosynk.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow

/** Design skin shared with the PC app: zero | onyx | eclipx.
 *  Holds the current selection, persists it, exposes it to Compose and
 *  swaps the launcher icon/label (activity-alias) to match. */
object Design {

    const val ZERO = "zero"
    const val ONYX = "onyx"
    const val ECLIPX = "eclipx"

    /** Compose-observable current design. */
    val flow = MutableStateFlow(ZERO)

    @Volatile
    var current: String = ZERO
        private set

    fun normalize(v: String?): String = when (v) {
        ONYX -> ONYX
        ECLIPX -> ECLIPX
        else -> ZERO
    }

    /** App display name: ZeroSynk | OnyxSynk | Eclipx. */
    fun displayName(design: String = current): String = when (design) {
        ONYX -> "OnyxSynk"
        ECLIPX -> "Eclipx"
        else -> "ZeroSynk"
    }

    /** Notification accent color (ARGB). */
    fun accent(design: String = current): Int = when (design) {
        ONYX -> 0xFFC9A227.toInt()
        ECLIPX -> 0xFFFFFFFF.toInt()
        else -> 0xFF0A84FF.toInt()
    }

    /** Floating dock background (ARGB long for Compose Color). */
    fun dockColor(design: String = current): Long = when (design) {
        ONYX -> 0xFF17140D
        ECLIPX -> 0xFF1A1A1A
        else -> 0xFF1C1C1E
    }

    /** Load from prefs and make sure the launcher icon matches (startup). */
    fun load(context: Context) {
        current = normalize(Prefs.get(context).design)
        flow.value = current
        switchLauncherIcon(context, current)
    }

    /** Persist + apply: theme recomposes via [flow], launcher icon via alias. */
    fun apply(context: Context, design: String) {
        val d = normalize(design)
        val changed = d != current
        current = d
        flow.value = d
        Prefs.get(context).design = d
        if (changed) switchLauncherIcon(context, d)
    }

    private fun switchLauncherIcon(context: Context, design: String) {
        val pm = context.packageManager
        val pkg = context.packageName
        val aliases = listOf(
            ZERO to "LauncherZero",
            ONYX to "LauncherOnyx",
            ECLIPX to "LauncherEclipx",
        )
        for ((key, cls) in aliases) {
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "$pkg.$cls"),
                    if (key == design) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    PackageManager.DONT_KILL_APP,
                )
            } catch (_: Exception) {
            }
        }
    }
}
