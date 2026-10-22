package com.linkbridge.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.linkbridge.android.ui.share.ShareActions
import com.linkbridge.android.ui.share.ShareSheet
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import kotlinx.coroutines.Dispatchers

/**
 * "Share → LinkBridge" target. Exported so other apps can reach it, so it treats the
 * incoming intent as untrusted: only ACTION_SEND / ACTION_SEND_MULTIPLE with content://
 * streams from other apps are accepted, nothing is sent without a tap, and no intent from
 * the caller is ever launched.
 *
 * A Compose sheet over a translucent window. Stays open until the send finishes: the
 * caller's read grant on the shared URIs lasts only while this activity is alive. "Hide"
 * moves it to the background without finishing it.
 */
class ShareActivity : ComponentActivity() {
    private var uris: List<Uri> = emptyList()

    private val viewModel: ShareViewModel by viewModels {
        viewModelFactory {
            initializer { ShareViewModel(LinkBridgeSharePort(applicationContext, uris), uris.size, Dispatchers.IO) }
        }
    }

    /** Bumped when the activity comes back, so a sheet swiped away by Hide shows again. */
    private val sheetGeneration = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        uris = sharedUris(intent)
        val actions = ShareActions(
            onSend = { id -> viewModel.send(id) },
            onHide = ::onHideTapped,
            onOpenApp = ::openApp,
        )
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LinkBridgeTheme {
                key(sheetGeneration.intValue) { ShareSheet(state, actions) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LinkBridgeService.start(this)
    }

    override fun onRestart() {
        super.onRestart()
        sheetGeneration.intValue += 1
    }

    private fun onHideTapped() {
        // Hide keeps the activity (and the caller's URI grant) alive while sending.
        if (viewModel.state.value.isSending) moveTaskToBack(true) else finish()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** Content URIs from another app only; rejects file:// and our own package's content. */
    private fun sharedUris(intent: Intent?): List<Uri> {
        val candidates = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        return candidates.filter { uri ->
            val allowed = uri.scheme == ContentResolver.SCHEME_CONTENT && !isOwnAuthority(uri)
            if (!allowed) Log.w(TAG, "Rejected shared URI with scheme ${uri.scheme}")
            allowed
        }.take(SendBatch.MAX_FILES)
    }

    private fun isOwnAuthority(uri: Uri): Boolean {
        val authority = uri.authority ?: return true
        val provider = packageManager.resolveContentProvider(authority, 0) ?: return false
        return provider.packageName == packageName
    }

    companion object {
        private const val TAG = "LinkBridgeShare"
    }
}
