package com.linkbridge.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import com.linkbridge.android.Ui.body
import com.linkbridge.android.Ui.pad
import com.linkbridge.android.Ui.section
import com.linkbridge.android.Ui.title

class MainActivity : ComponentActivity() {
    private lateinit var peersContainer: LinearLayout
    private lateinit var emptyPeersText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var peerStatusDot: View
    private lateinit var pairButton: Button
    private lateinit var sendFileButton: Button
    private lateinit var refreshScanButton: Button
    private lateinit var receiveStatusText: TextView
    private lateinit var activityLogContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var notificationHint: TextView

    private lateinit var identity: DeviceIdentity
    private lateinit var trustedPeerStore: TrustedPeerStore
    private val controlClient = ControlClient()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var selectedPeer: DiscoveredPeer? = null
    private var selectedDevice: DeviceIdentity? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::sendSelectedFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        identity = IdentityStore(this).loadOrCreate()
        trustedPeerStore = TrustedPeerStore(this)

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 34, 24, 28)
        }
        rootScroll.addView(root)

        // Since API 35 the app always draws behind the system bars; keep content clear of them.
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(24 + bars.left, 34 + bars.top, 24 + bars.right, 28 + bars.bottom)
            insets
        }

        // 1. Header View
        root.addView(buildHeaderView())

        // 2. Local device identity, kept compact so the peer and transfer action stay above the fold.
        root.addView(buildIdentityCard())

        // 3. Nearby Mac Peers Section Header
        val nearbyHeaderStack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 10)
        }
        peerStatusDot = Ui.statusDot(this, Ui.WARNING)
        peerStatusDot.layoutParams = LinearLayout.LayoutParams(24, 24).apply {
            rightMargin = 16
        }
        nearbyHeaderStack.addView(peerStatusDot)
        nearbyHeaderStack.addView(TextView(this).apply {
            section("NEARBY MAC PEERS")
        })
        root.addView(nearbyHeaderStack)

        // Connection / Peer Selected Status View
        connectionStatusText = TextView(this).apply {
            body("Searching for a Mac on the same Wi-Fi network…")
            setTextColor(Ui.MUTED)
            pad(20, 14)
            background = Ui.rounded(Ui.SURFACE, radiusDp = 14f, strokeColor = Ui.LINE)
        }
        root.addView(connectionStatusText)

        // Peers List Container
        peersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        root.addView(peersContainer)

        emptyPeersText = TextView(this).apply {
            body("No nearby Mac peer found yet. Keep LinkBridge open on Mac.")
            setTextColor(Ui.MUTED)
            setPadding(0, 8, 0, 12)
        }
        root.addView(emptyPeersText)

        // 4. Action Buttons Stack
        val buttonsStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 16)
        }

        sendFileButton = Button(this).apply {
            text = "Send to Mac"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.primaryButtonDrawable()
            visibility = View.GONE
            setPadding(0, 22, 0, 22)
            setOnClickListener { openFilePicker() }
        }

        pairButton = Button(this).apply {
            text = "Request pairing"
            textSize = 14f
            setTextColor(Ui.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.secondaryButtonDrawable()
            visibility = View.GONE
            setPadding(0, 20, 0, 20)
            setOnClickListener { pairSelectedPeer() }
        }

        refreshScanButton = Button(this).apply {
            text = "Scan again"
            textSize = 14f
            setTextColor(Ui.INK)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.secondaryButtonDrawable()
            setPadding(0, 20, 0, 20)
            setOnClickListener { refreshDiscovery() }
        }

        buttonsStack.addView(sendFileButton)
        buttonsStack.addView(pairButton)
        buttonsStack.addView(LinearLayout(this).apply { setPadding(0, 6, 0, 0) })
        buttonsStack.addView(refreshScanButton)
        root.addView(buttonsStack)

        // Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            setPadding(0, 0, 0, 16)
        }
        root.addView(progressBar)

        // 5. Incoming Status Card
        root.addView(TextView(this).apply {
            section("RECEIVING")
            setPadding(0, 12, 0, 8)
        })

        receiveStatusText = TextView(this).apply {
            body("Ready for private transfers over local Wi-Fi")
            pad(20, 14)
            background = Ui.rounded(Ui.SURFACE, radiusDp = 14f, strokeColor = Ui.LINE)
        }
        root.addView(receiveStatusText)

        notificationHint = TextView(this).apply {
            body("")
            textSize = 13f
            setTextColor(Ui.WARNING)
            setPadding(4, 10, 4, 0)
            visibility = View.GONE
            setOnClickListener { onNotificationHintTapped() }
        }
        root.addView(notificationHint)

        // 6. Activity History Section
        root.addView(TextView(this).apply {
            section("RECENT ACTIVITY")
            setPadding(0, 20, 0, 8)
        })

        activityLogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(activityLogContainer)

        val initialHistoryEmpty = TextView(this).apply {
            body(EMPTY_HISTORY_TEXT)
            setTextColor(Ui.MUTED)
            textSize = 13f
        }
        activityLogContainer.addView(initialHistoryEmpty)

        setContentView(rootScroll)
    }

    override fun onStart() {
        super.onStart()
        // The service owns discovery, receiving and sending so they survive leaving the app.
        LinkBridgeService.start(this)
        LinkBridgeHub.addListener(hubListener)
        updateNotificationPermission(requestIfNeeded = true)
    }

    override fun onResume() {
        super.onResume()
        LinkBridgeHub.foregroundApprover = LinkBridgeHub.Approver(::approveIncomingTransfer)
    }

    override fun onPause() {
        LinkBridgeHub.foregroundApprover = null
        super.onPause()
    }

    override fun onStop() {
        LinkBridgeHub.removeListener(hubListener)
        super.onStop()
    }

    private val hubListener = object : LinkBridgeHub.Listener {
        override fun onPeersChanged(peers: List<DiscoveredPeer>) = renderPeers(peers)

        override fun onReceiveStatus(status: String) {
            receiveStatusText.text = status
        }

        override fun onActivityLogged(message: String, isOutgoing: Boolean) = addActivityLog(message, isOutgoing)

        override fun onSendStateChanged(state: LinkBridgeHub.SendState) = renderSendState(state)
    }

    /**
     * Notifications carry the Accept/Decline prompt when a file arrives in the background.
     * Ask once; after a refusal, show a hint instead of asking again.
     */
    private fun updateNotificationPermission(requestIfNeeded: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val prefs = getSharedPreferences("linkbridge_ui", MODE_PRIVATE)
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                notificationHint.visibility = View.GONE
            }
            shouldShowRequestPermissionRationale(permission) -> {
                notificationHint.text = "Notifications are off, so files sent while LinkBridge is in the background are declined. Tap to turn them on."
                notificationHint.visibility = View.VISIBLE
            }
            requestIfNeeded && !prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false) -> {
                prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
                notificationPermission.launch(permission)
            }
            prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false) -> {
                notificationHint.text = "Notifications are off, so files sent while LinkBridge is in the background are declined. Tap to open settings."
                notificationHint.visibility = View.VISIBLE
            }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateNotificationPermission(requestIfNeeded = false)
    }

    private fun onNotificationHintTapped() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shouldShowRequestPermissionRationale(permission)) {
            notificationPermission.launch(permission)
        } else {
            // Permanently denied: only Settings can turn it back on.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun buildHeaderView(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val appTitle = TextView(this).apply {
            title("LinkBridge")
        }
        val appSubtitle = TextView(this).apply {
            text = "Android Companion"
            textSize = 12f
            setTextColor(Ui.MUTED)
            typeface = Typeface.DEFAULT_BOLD
        }

        titleStack.addView(appTitle)
        titleStack.addView(appSubtitle)
        header.addView(titleStack)

        val statusBadge = TextView(this).apply {
            text = "ONLINE / PRIVATE"
            textSize = 10f
            setTextColor(Ui.SUCCESS)
            typeface = Typeface.DEFAULT_BOLD
            pad(16, 8)
            background = Ui.rounded(Ui.SUCCESS_BG, radiusDp = 10f, strokeColor = Ui.SUCCESS, strokeWidthPx = 2)
        }
        header.addView(statusBadge)

        return header
    }

    private fun buildIdentityCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            pad(22, 18)
            background = Ui.rounded(Ui.SURFACE, radiusDp = 16f, strokeColor = Ui.LINE)
        }

        val headerText = TextView(this).apply {
            section("YOUR ANDROID")
        }
        card.addView(headerText)

        val deviceNameText = TextView(this).apply {
            text = identity.deviceName
            textSize = 16f
            setTextColor(Ui.INK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 4, 0, 2)
        }
        card.addView(deviceNameText)

        val uuidRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val uuidShort = identity.deviceId.toString().let {
            if (it.length > 18) "${it.take(8)}...${it.takeLast(4)}" else it
        }

        val uuidText = TextView(this).apply {
            text = "Device ID  $uuidShort"
            textSize = 12f
            setTextColor(Ui.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        uuidRow.addView(uuidText)

        val copyBtn = TextView(this).apply {
            text = "Copy"
            textSize = 11f
            setTextColor(Ui.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            pad(12, 6)
            isClickable = true
            isFocusable = true
            setOnClickListener { copyDeviceId() }
        }
        uuidRow.addView(copyBtn)

        card.addView(uuidRow)
        return card
    }

    private fun copyDeviceId() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("LinkBridge Device UUID", identity.deviceId.toString())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied Android Device UUID!", Toast.LENGTH_SHORT).show()
    }

    private fun approveIncomingTransfer(request: IncomingTransferRequest, answer: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Accept incoming file?")
            .setMessage("${request.senderName} wants to send ${request.fileName} (${formatBytes(request.bytes)}).")
            .setPositiveButton("Accept") { _, _ -> answer(true) }
            .setNegativeButton("Decline") { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .show()
    }

    private fun renderPeers(peers: List<DiscoveredPeer>) {
        emptyPeersText.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        peersContainer.removeAllViews()

        if (peers.isNotEmpty()) {
            peerStatusDot.background = Ui.rounded(Ui.SUCCESS, radiusDp = 12f)
        } else {
            peerStatusDot.background = Ui.rounded(Ui.WARNING, radiusDp = 12f)
        }

        peers.forEach { peer ->
            val isTrusted = trustedPeerStore.isTrusted(peer.deviceId)
            val statusLabelText = if (isTrusted) "TRUSTED / READY" else "TAP TO CONNECT"

            val peerCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                pad(20, 16)
                background = Ui.rounded(
                    if (isTrusted) Ui.SUCCESS_BG else Ui.SURFACE,
                    radiusDp = 14f,
                    strokeColor = if (isTrusted) Ui.SUCCESS else Ui.LINE
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { connectToPeer(peer) }
            }

            val peerNameView = TextView(this).apply {
                text = peer.deviceName
                textSize = 15f
                setTextColor(Ui.INK)
                typeface = Typeface.DEFAULT_BOLD
            }

            val peerEndpointView = TextView(this).apply {
                text = "${peer.platform ?: "macOS"}  ·  ${peer.endpoint}"
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, 2, 0, 4)
            }

            val peerStatusBadge = TextView(this).apply {
                text = statusLabelText
                textSize = 10f
                setTextColor(if (isTrusted) Ui.SUCCESS else Ui.ACCENT)
                typeface = Typeface.DEFAULT_BOLD
            }

            peerCard.addView(peerNameView)
            peerCard.addView(peerEndpointView)
            peerCard.addView(peerStatusBadge)

            peersContainer.addView(peerCard)
            peersContainer.addView(LinearLayout(this).apply { setPadding(0, 6, 0, 0) })
        }
    }

    private fun connectToPeer(peer: DiscoveredPeer) {
        selectedPeer = peer
        selectedDevice = null
        pairButton.visibility = View.GONE
        sendFileButton.visibility = View.GONE
        connectionStatusText.text = "Connecting to ${peer.deviceName} at ${peer.endpoint}..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING_BG, radiusDp = 14f, strokeColor = Ui.WARNING)

        networkExecutor.execute {
            val result = controlClient.fetchDevice(peer)
            runOnUiThread {
                result
                    .onSuccess { device ->
                        selectedDevice = device
                        val isTrusted = trustedPeerStore.isTrusted(device.deviceId)
                        connectionStatusText.text = "Connected to ${device.deviceName}\nEncrypted transfer channel ready"
                        connectionStatusText.background = Ui.rounded(Ui.SUCCESS_BG, radiusDp = 14f, strokeColor = Ui.SUCCESS)

                        pairButton.visibility = if (isTrusted) View.GONE else View.VISIBLE
                        sendFileButton.visibility = if (isTrusted) View.VISIBLE else View.GONE
                    }
                    .onFailure { error ->
                        connectionStatusText.text = "Could not connect to ${peer.deviceName}\n${error.localizedMessage ?: "Unknown error"}"
                        connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
                        pairButton.visibility = View.GONE
                        sendFileButton.visibility = View.GONE
                    }
            }
        }
    }

    private fun refreshDiscovery() {
        refreshScanButton.isEnabled = false
        selectedPeer = null
        selectedDevice = null
        pairButton.visibility = View.GONE
        sendFileButton.visibility = View.GONE
        peersContainer.removeAllViews()
        emptyPeersText.visibility = View.VISIBLE
        peerStatusDot.background = Ui.rounded(Ui.WARNING, radiusDp = 12f)
        connectionStatusText.text = "Refreshing nearby Mac scan..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING_BG, radiusDp = 14f, strokeColor = Ui.WARNING)

        LinkBridgeService.start(this)
        LinkBridgeHub.rescan()
        refreshScanButton.postDelayed({ refreshScanButton.isEnabled = true }, 500)
    }

    private fun pairSelectedPeer() {
        val peer = selectedPeer ?: return
        val device = selectedDevice ?: return

        pairButton.isEnabled = false
        connectionStatusText.text = "Pairing with ${device.deviceName}..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING_BG, radiusDp = 14f, strokeColor = Ui.WARNING)

        networkExecutor.execute {
            val result = controlClient.beginPairing(peer, identity)
            runOnUiThread {
                result
                    .onSuccess { session -> confirmPairingCode(peer, device, session) }
                    .onFailure { error -> showPairingFailure(device, error) }
            }
        }
    }

    private fun confirmPairingCode(peer: DiscoveredPeer, device: DeviceIdentity, session: PairingSession) {
        val code = PairingCode.display(session.code)
        connectionStatusText.text = "Pairing code: $code\nCheck that ${device.deviceName} shows the same code."
        AlertDialog.Builder(this)
            .setTitle("Pairing code  $code")
            .setMessage("${device.deviceName} should now show this same code. If it matches, tap \"Codes match\" here and Accept on the Mac.\n\nIf the codes differ, cancel: someone may be intercepting the connection.")
            .setCancelable(false)
            .setPositiveButton("Codes match") { _, _ -> finishPairing(peer, device, session) }
            .setNegativeButton("Cancel") { _, _ ->
                pairButton.isEnabled = true
                connectionStatusText.text = "Pairing cancelled"
                connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
            }
            .show()
    }

    private fun finishPairing(peer: DiscoveredPeer, device: DeviceIdentity, session: PairingSession) {
        connectionStatusText.text = "Waiting for you to accept on ${device.deviceName}..."
        networkExecutor.execute {
            val result = controlClient.finalizePairing(peer, identity)
            runOnUiThread {
                result
                    .onSuccess {
                        trustedPeerStore.save(
                            TrustedPeer(
                                deviceId = device.deviceId,
                                deviceName = device.deviceName,
                                platform = device.platform,
                                host = peer.host,
                                port = peer.port,
                                authToken = session.authToken
                            )
                        )
                        pairButton.visibility = View.GONE
                        pairButton.isEnabled = true
                        sendFileButton.visibility = View.VISIBLE
                        connectionStatusText.text = "Trusted connection with ${device.deviceName}\nReady for encrypted file transfer."
                        connectionStatusText.background = Ui.rounded(Ui.SUCCESS_BG, radiusDp = 14f, strokeColor = Ui.SUCCESS)
                    }
                    .onFailure { error -> showPairingFailure(device, error) }
            }
        }
    }

    private fun showPairingFailure(device: DeviceIdentity, error: Throwable) {
        pairButton.isEnabled = true
        connectionStatusText.text = "Pairing failed with ${device.deviceName}\n${error.localizedMessage ?: "Unknown error"}"
        connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
    }

    private fun openFilePicker() {
        pickFile.launch(arrayOf("*/*"))
    }

    private fun sendSelectedFile(uri: Uri) {
        val peer = selectedPeer ?: return
        val trustedPeer = selectedDevice?.let { trustedPeerStore.find(it.deviceId) }
        if (trustedPeer == null) {
            connectionStatusText.text = "Pair with ${peer.deviceName} before sending."
            connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
            return
        }

        // The send runs in LinkBridgeService, so it keeps going if you leave the app.
        if (!LinkBridgeHub.send(peer, trustedPeer, uri)) {
            connectionStatusText.text = "LinkBridge is not running. Reopen the app and try again."
            connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
            return
        }
        sendFileButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
    }

    private fun renderSendState(state: LinkBridgeHub.SendState) {
        when (state) {
            is LinkBridgeHub.SendState.Progress -> {
                sendFileButton.isEnabled = false
                progressBar.visibility = View.VISIBLE
                connectionStatusText.text = "Sending ${state.fileName}\n${formatBytes(state.sent)} / ${formatBytes(state.total)}"
                connectionStatusText.background = Ui.rounded(Ui.WARNING_BG, radiusDp = 14f, strokeColor = Ui.WARNING)
            }
            is LinkBridgeHub.SendState.Done -> {
                sendFileButton.isEnabled = true
                progressBar.visibility = View.GONE
                connectionStatusText.text = "Sent ${state.fileName} (${formatBytes(state.bytes)})\nSaved on ${state.peerName} in Downloads/LinkBridge."
                connectionStatusText.background = Ui.rounded(Ui.SUCCESS_BG, radiusDp = 14f, strokeColor = Ui.SUCCESS)
            }
            is LinkBridgeHub.SendState.Failed -> {
                sendFileButton.isEnabled = true
                progressBar.visibility = View.GONE
                connectionStatusText.text = "Could not send ${state.fileName}\n${state.message}"
                connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
            }
        }
    }

    private fun addActivityLog(message: String, isOutgoing: Boolean) {
        (activityLogContainer.getChildAt(0) as? TextView)?.let { first ->
            if (first.text == EMPTY_HISTORY_TEXT) activityLogContainer.removeView(first)
        }

        val logItem = TextView(this).apply {
            text = (if (isOutgoing) "▲ " else "▼ ") + message
            textSize = 12f
            setTextColor(if (isOutgoing) Ui.ACCENT else Ui.SUCCESS)
            typeface = Typeface.DEFAULT_BOLD
            pad(16, 10)
            background = Ui.rounded(Ui.SURFACE, radiusDp = 10f, strokeColor = Ui.LINE)
        }

        activityLogContainer.addView(logItem, 0)

        // Keep maximum 4 items
        if (activityLogContainer.childCount > 4) {
            activityLogContainer.removeViewAt(activityLogContainer.childCount - 1)
        }
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EMPTY_HISTORY_TEXT = "Your recent transfers will appear here."
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
