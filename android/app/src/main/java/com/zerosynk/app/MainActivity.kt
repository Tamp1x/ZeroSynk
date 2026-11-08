package com.zerosynk.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.design.Design
import com.zerosynk.app.ui.ZeroSynkRoot
import com.zerosynk.app.ui.theme.ZeroSynkTheme
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val scanResults = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestLegacyWritePermission()
        val design = Design.flow
        setContent {
            val current by design.collectAsState()
            ZeroSynkTheme(current) {
                ZeroSynkRoot(scanFlow = scanResults)
            }
        }
        maybeEmit(intent)
    }

    /**
     * API 26–28: files land in Download/ZeroSynk through the legacy File API, which
     * needs WRITE_EXTERNAL_STORAGE — declared in the manifest but never requested at
     * runtime, so writes could fail with a SecurityException. One attempt per install;
     * API 29+ uses MediaStore/SAF and doesn't need it.
     */
    private fun maybeRequestLegacyWritePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Build.VERSION.SDK_INT > 28) return
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = Prefs.get(this)
        if (prefs.writePermAsked) return
        prefs.writePermAsked = true
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeEmit(intent)
    }

    private fun maybeEmit(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("zerosynk://")) {
            scanResults.tryEmit(data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        val contents = result?.contents
        if (!contents.isNullOrEmpty()) {
            scanResults.tryEmit(contents)
        }
    }

    companion object {
        private const val REQ_WRITE_STORAGE = 42
    }
}
