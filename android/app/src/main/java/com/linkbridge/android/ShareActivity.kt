package com.linkbridge.android

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.linkbridge.android.Ui.body
import com.linkbridge.android.Ui.pad
import com.linkbridge.android.Ui.section
import com.linkbridge.android.Ui.title

/**
 * "Share → LinkBridge" target. Exported so other apps can reach it, so it treats the
 * incoming intent as untrusted: only ACTION_SEND / ACTION_SEND_MULTIPLE with content://
 * streams from other apps are accepted, nothing is sent without a tap, and no intent from
 * the caller is ever launched.
 *
 * Stays open until the send finishes: the caller's read grant on the shared URIs lasts only
 * while this activity is alive. "Hide" moves it to the background without finishing it.
 */
class ShareActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var targetsContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var hideButton: Button
    private lateinit var trustedPeerStore: TrustedPeerStore

    private var uris: List<Uri> = emptyList()
    private var sending = false
    private var finishedCount = 0
    private var failedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        trustedPeerStore = TrustedPeerStore(this)
        uris = sharedUris(intent)
        buildUi()

        if (uris.isEmpty()) {
            statusText.text = "LinkBridge can only send files. Share a photo, video or document instead."
            hideButton.text = "Close"
            return
        }
        statusText.text = if (uris.size == 1) {
            "Send ${contentResolver.fileInfo(uris[0]).name} to:"
        } else {
            "Send ${uris.size} files to:"
        }
    }

    override fun onStart() {
        super.onStart()
        LinkBridgeService.start(this)
        LinkBridgeHub.addListener(listener)
    }

    override fun onStop() {
        LinkBridgeHub.removeListener(listener)
        super.onStop()
    }

    private val listener = object : LinkBridgeHub.Listener {
        override fun onPeersChanged(peers: List<DiscoveredPeer>) {
            if (!sending && uris.isNotEmpty()) renderTargets(peers)
        }

        override fun onSendStateChanged(state: LinkBridgeHub.SendState) {
            if (!sending) return
            when (state) {
                is LinkBridgeHub.SendState.Progress ->
                    statusText.text = "Sending ${state.fileName} (${finishedCount + failedCount + 1} of ${uris.size})\n" +
                        "${formatBytes(state.sent)} / ${formatBytes(state.total)}"
                is LinkBridgeHub.SendState.Done -> finishedCount += 1
                is LinkBridgeHub.SendState.Failed -> {
                    failedCount += 1
                    Log.w(TAG, "Share send failed: ${state.message}")
                }
            }
            if (finishedCount + failedCount == uris.size) showFinished()
        }
    }

    private fun renderTargets(peers: List<DiscoveredPeer>) {
        targetsContainer.removeAllViews()
        val targets = peers.mapNotNull { peer -> trustedPeerStore.find(peer.deviceId)?.let { peer to it } }
        if (targets.isEmpty()) {
            targetsContainer.addView(TextView(this).apply {
                body("Looking for a paired Mac on this Wi-Fi… Open LinkBridge on your Mac. If you haven't paired yet, pair in the LinkBridge app first.")
                setTextColor(Ui.MUTED)
                setPadding(0, 12, 0, 12)
            })
            return
        }
        targets.forEach { (peer, trusted) ->
            targetsContainer.addView(Button(this).apply {
                text = peer.deviceName
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = Ui.primaryButtonDrawable()
                setPadding(0, 22, 0, 22)
                setOnClickListener { startSending(peer, trusted) }
            })
            targetsContainer.addView(View(this).apply { minimumHeight = 12 })
        }
    }

    private fun startSending(peer: DiscoveredPeer, trustedPeer: TrustedPeer) {
        sending = true
        targetsContainer.removeAllViews()
        progressBar.visibility = View.VISIBLE
        hideButton.text = "Hide"
        statusText.text = "Preparing ${uris.size} file(s) for ${peer.deviceName}…"
        uris.forEach { uri ->
            if (!LinkBridgeHub.send(peer, trustedPeer, uri, persistable = false)) {
                failedCount += 1
            }
        }
        if (failedCount == uris.size) {
            statusText.text = "LinkBridge is not running. Open the LinkBridge app and try again."
            progressBar.visibility = View.GONE
            hideButton.text = "Close"
            sending = false
        }
    }

    private fun showFinished() {
        sending = false
        progressBar.visibility = View.GONE
        hideButton.text = "Done"
        statusText.text = when {
            failedCount == 0 && finishedCount == 1 -> "Sent. It's in Downloads/LinkBridge on your Mac."
            failedCount == 0 -> "Sent $finishedCount files to Downloads/LinkBridge on your Mac."
            finishedCount == 0 -> "Could not send. Check that your Mac is on the same Wi-Fi with LinkBridge open, then share again."
            else -> "Sent $finishedCount, $failedCount failed. Check the Mac is on the same Wi-Fi and share the failed files again."
        }
    }

    private fun onHideTapped() {
        // Hide keeps the activity (and the caller's URI grant) alive while sending.
        if (sending) moveTaskToBack(true) else finish()
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
        }.take(MAX_FILES)
    }

    private fun isOwnAuthority(uri: Uri): Boolean {
        val authority = uri.authority ?: return true
        val provider = packageManager.resolveContentProvider(authority, 0) ?: return false
        return provider.packageName == packageName
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(24 + bars.left, 34 + bars.top, 24 + bars.right, 28 + bars.bottom)
            insets
        }

        root.addView(TextView(this).apply { title("LinkBridge") })
        root.addView(TextView(this).apply {
            section("SEND TO MAC")
            setPadding(0, 24, 0, 8)
        })
        statusText = TextView(this).apply {
            body("")
            pad(20, 14)
            background = Ui.rounded(Ui.SURFACE, radiusDp = 14f, strokeColor = Ui.LINE)
        }
        root.addView(statusText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progressBar)
        targetsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        root.addView(targetsContainer)
        hideButton = Button(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(Ui.INK)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.secondaryButtonDrawable()
            setPadding(0, 20, 0, 20)
            setOnClickListener { onHideTapped() }
        }
        root.addView(hideButton)
        setContentView(scroll)
    }

    companion object {
        private const val TAG = "LinkBridgeShare"
        private const val MAX_FILES = 50
    }
}
