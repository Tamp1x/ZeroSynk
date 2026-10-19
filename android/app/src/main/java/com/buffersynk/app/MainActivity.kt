package com.buffersynk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.buffersynk.app.ui.BufferSynkRoot
import com.buffersynk.app.ui.theme.ZeroSynkTheme
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val scanResults = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZeroSynkTheme {
                BufferSynkRoot(scanFlow = scanResults)
            }
        }
        maybeEmit(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeEmit(intent)
    }

    private fun maybeEmit(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("zerosynk://") || data.startsWith("buffersynk://")) {
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
}
