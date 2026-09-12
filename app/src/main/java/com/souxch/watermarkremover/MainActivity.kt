package com.souxch.watermarkremover

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.souxch.watermarkremover.ui.EditorViewModel
import com.souxch.watermarkremover.ui.WatermarkRemoverApp
import com.souxch.watermarkremover.ui.theme.WatermarkRemoverTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatermarkRemoverTheme {
                WatermarkRemoverApp(viewModel)
            }
        }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Supports "Share to" / "Open with" from the gallery. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let(viewModel::openVideo)
    }
}
