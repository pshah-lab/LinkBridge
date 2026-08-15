package com.linkbridge.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DecimalFormat
import java.util.concurrent.Executors
import com.linkbridge.android.Ui.body
import com.linkbridge.android.Ui.pad
import com.linkbridge.android.Ui.section
import com.linkbridge.android.Ui.title

class MainActivity : Activity() {
    private lateinit var discoveryService: DiscoveryService
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

    private lateinit var identity: DeviceIdentity
    private lateinit var trustedPeerStore: TrustedPeerStore
    private lateinit var receiveServer: ReceiveServer
    private val controlClient = ControlClient()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var selectedPeer: DiscoveredPeer? = null
    private var selectedDevice: DeviceIdentity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        identity = IdentityStore(this).loadOrCreate()
        trustedPeerStore = TrustedPeerStore(this)

        discoveryService = DiscoveryService(this, identity, CONTROL_PORT) { peers ->
            runOnUiThread { renderPeers(peers) }
        }
        discoveryService.start()

        receiveServer = ReceiveServer(this, CONTROL_PORT) { status ->
            runOnUiThread {
                receiveStatusText.text = status
                receiveStatusText.background = Ui.rounded(Ui.SURFACE, radiusDp = 14f, strokeColor = Ui.LINE)
                addActivityLog(status, isOutgoing = false)
            }
        }

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        rootScroll.addView(root)

        // 1. Header View
        root.addView(buildHeaderView())

        // 2. Local Device Identity Card
        root.addView(buildIdentityCard())

        // 3. Nearby Mac Peers Section Header
        val nearbyHeaderStack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 10)
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
            body("Searching for nearby LinkBridge Mac devices on local Wi-Fi...")
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
            text = "Send File to Mac"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.primaryButtonDrawable()
            visibility = View.GONE
            setPadding(0, 28, 0, 28)
            setOnClickListener { openFilePicker() }
        }

        pairButton = Button(this).apply {
            text = "Pair with Mac"
            textSize = 14f
            setTextColor(Ui.ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.secondaryButtonDrawable()
            visibility = View.GONE
            setPadding(0, 24, 0, 24)
            setOnClickListener { pairSelectedPeer() }
        }

        refreshScanButton = Button(this).apply {
            text = "Refresh Scan"
            textSize = 14f
            setTextColor(Ui.INK)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.secondaryButtonDrawable()
            setPadding(0, 24, 0, 24)
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
            section("INCOMING RECEIVER")
            setPadding(0, 12, 0, 8)
        })

        receiveStatusText = TextView(this).apply {
            body("Ready to receive Mac files over LAN")
            pad(20, 14)
            background = Ui.rounded(Ui.SURFACE, radiusDp = 14f, strokeColor = Ui.LINE)
        }
        root.addView(receiveStatusText)

        // 6. Activity History Section
        root.addView(TextView(this).apply {
            section("TRANSFER HISTORY")
            setPadding(0, 20, 0, 8)
        })

        activityLogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(activityLogContainer)

        val initialHistoryEmpty = TextView(this).apply {
            body("No transfers recorded in this session.")
            setTextColor(Ui.MUTED)
            textSize = 13f
        }
        activityLogContainer.addView(initialHistoryEmpty)

        setContentView(rootScroll)
        receiveServer.start()
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
            text = "PORT :49153 ACTIVE"
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
            section("THIS DEVICE IDENTITY")
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
            text = "ID: $uuidShort"
            textSize = 12f
            setTextColor(Ui.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        uuidRow.addView(uuidText)

        val copyBtn = TextView(this).apply {
            text = "Copy ID"
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
            val statusLabelText = if (isTrusted) "PAIRED & READY" else "TAP TO CONNECT"

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
                text = "${peer.platform ?: "macOS"} • ${peer.endpoint}"
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
                        connectionStatusText.text = "Connected to ${device.deviceName}\nEndpoint: ${peer.endpoint}"
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

        runCatching { discoveryService.stop() }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                discoveryService = DiscoveryService(this, identity, CONTROL_PORT) { peers ->
                    runOnUiThread { renderPeers(peers) }
                }
                discoveryService.start()
                Toast.makeText(this, "Refreshing mDNS device scan...", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                connectionStatusText.text = "Refresh failed\n${error.localizedMessage ?: "Unknown error"}"
                connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
            }

            refreshScanButton.isEnabled = true
        }, 500)
    }

    private fun pairSelectedPeer() {
        val peer = selectedPeer ?: return
        val device = selectedDevice ?: return

        pairButton.isEnabled = false
        connectionStatusText.text = "Pairing with ${device.deviceName}..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING_BG, radiusDp = 14f, strokeColor = Ui.WARNING)

        networkExecutor.execute {
            val result = controlClient.pair(peer, identity)
            runOnUiThread {
                result
                    .onSuccess {
                        trustedPeerStore.save(
                            TrustedPeer(
                                deviceId = device.deviceId,
                                deviceName = device.deviceName,
                                platform = device.platform,
                                host = peer.host,
                                port = peer.port
                            )
                        )
                        pairButton.visibility = View.GONE
                        pairButton.isEnabled = true
                        sendFileButton.visibility = View.VISIBLE
                        connectionStatusText.text = "Paired with ${device.deviceName}\nReady for file transfer."
                        connectionStatusText.background = Ui.rounded(Ui.SUCCESS_BG, radiusDp = 14f, strokeColor = Ui.SUCCESS)
                    }
                    .onFailure { error ->
                        pairButton.isEnabled = true
                        connectionStatusText.text = "Pairing failed with ${device.deviceName}\n${error.localizedMessage ?: "Unknown error"}"
                        connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
                    }
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    @Deprecated("Deprecated by Android framework; sufficient for this Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        sendSelectedFile(uri)
    }

    private fun sendSelectedFile(uri: Uri) {
        val peer = selectedPeer ?: return
        val fileInfo = fileInfo(uri)

        sendFileButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        connectionStatusText.text = "Sending ${fileInfo.name}..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING_BG, radiusDp = 14f, strokeColor = Ui.WARNING)

        networkExecutor.execute {
            val result = contentResolver.openInputStream(uri)?.let { input ->
                controlClient.uploadFile(peer, fileInfo.name, fileInfo.size, input) { sent, total ->
                    runOnUiThread {
                        connectionStatusText.text = "Sending ${fileInfo.name}\n${formatBytes(sent)} / ${formatBytes(total)}"
                    }
                }
            } ?: Result.failure(IllegalStateException("Could not open selected file"))

            runOnUiThread {
                sendFileButton.isEnabled = true
                progressBar.visibility = View.GONE
                result
                    .onSuccess {
                        val formattedSize = formatBytes(fileInfo.size)
                        connectionStatusText.text = "Successfully sent ${fileInfo.name} ($formattedSize)\nSaved on Mac in Downloads/LinkBridge."
                        connectionStatusText.background = Ui.rounded(Ui.SUCCESS_BG, radiusDp = 14f, strokeColor = Ui.SUCCESS)
                        addActivityLog("Sent ${fileInfo.name} ($formattedSize) to ${peer.deviceName}", isOutgoing = true)
                    }
                    .onFailure { error ->
                        connectionStatusText.text = "File send failed\n${error.localizedMessage ?: "Unknown error"}"
                        connectionStatusText.background = Ui.rounded(Ui.DANGER_BG, radiusDp = 14f, strokeColor = Ui.DANGER)
                    }
            }
        }
    }

    private fun addActivityLog(message: String, isOutgoing: Boolean) {
        if (activityLogContainer.childCount > 0) {
            val first = activityLogContainer.getChildAt(0) as? TextView
            if (first?.text?.contains("No transfers recorded") == true) {
                activityLogContainer.removeView(first)
            }
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

    private fun fileInfo(uri: Uri): SelectedFileInfo {
        var name = "selected-file"
        var size = -1L

        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }

        if (size < 0) {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0) {
                    size = descriptor.length
                }
            }
        }

        return SelectedFileInfo(name = name, size = size)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "unknown"
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }

        return "${DecimalFormat("#,##0.#").format(value)} ${units[unitIndex]}"
    }

    override fun onDestroy() {
        discoveryService.stop()
        receiveServer.stop()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CONTROL_PORT = 49153
        private const val REQUEST_PICK_FILE = 1001
    }
}

private data class SelectedFileInfo(
    val name: String,
    val size: Long
)
