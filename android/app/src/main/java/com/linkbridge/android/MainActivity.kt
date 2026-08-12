package com.linkbridge.android

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.app.Activity
import android.os.Bundle
import android.provider.OpenableColumns
import java.text.DecimalFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var pairButton: Button
    private lateinit var sendFileButton: Button
    private lateinit var receiveStatusText: TextView
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
            renderPeers(peers)
        }
        discoveryService.start()
        receiveServer = ReceiveServer(this, CONTROL_PORT) { status ->
            runOnUiThread {
                receiveStatusText.text = status
                receiveStatusText.background = Ui.rounded(Ui.SURFACE, strokeColor = Ui.LINE)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 36)
            setBackgroundColor(Ui.BACKGROUND)
        }

        root.addView(TextView(this).apply {
            title("LinkBridge")
        })

        root.addView(TextView(this).apply {
            body("${identity.deviceName}\n${identity.deviceId}")
            setTextColor(Ui.MUTED)
            setPadding(0, 8, 0, 28)
        })

        root.addView(TextView(this).apply {
            section("Connection")
            setPadding(0, 0, 0, 10)
        })

        connectionStatusText = TextView(this).apply {
            body("Tap a nearby Mac to connect.")
            pad()
            background = Ui.rounded(Ui.SURFACE, strokeColor = Ui.LINE)
        }
        root.addView(connectionStatusText)

        pairButton = Button(this).apply {
            text = "Pair"
            setTextColor(Ui.ACCENT)
            visibility = View.GONE
            setOnClickListener { pairSelectedPeer() }
        }
        root.addView(pairButton)

        sendFileButton = Button(this).apply {
            text = "Send File"
            setTextColor(Ui.ACCENT)
            visibility = View.GONE
            setOnClickListener { openFilePicker() }
        }
        root.addView(sendFileButton)

        root.addView(TextView(this).apply {
            section("Incoming")
            setPadding(0, 24, 0, 10)
        })

        receiveStatusText = TextView(this).apply {
            body("Ready to receive Mac files.")
            pad()
            background = Ui.rounded(Ui.SURFACE, strokeColor = Ui.LINE)
        }
        root.addView(receiveStatusText)

        root.addView(TextView(this).apply {
            section("Nearby Devices")
            setPadding(0, 28, 0, 10)
        })

        emptyPeersText = TextView(this).apply {
            body("Searching on local Wi-Fi...")
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        root.addView(emptyPeersText)

        peersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(ScrollView(this).apply {
            addView(peersContainer)
        })

        setContentView(root)
        receiveServer.start()
    }

    private fun renderPeers(peers: List<DiscoveredPeer>) {
        emptyPeersText.visibility = if (peers.isEmpty()) TextView.VISIBLE else TextView.GONE
        peersContainer.removeAllViews()

        peers.forEach { peer ->
            val pairStatus = if (trustedPeerStore.isTrusted(peer.deviceId)) "Paired" else "Tap to connect"
            peersContainer.addView(TextView(this).apply {
                text = "${peer.deviceName}\n${peer.platform ?: "unknown"} - ${peer.endpoint}\n$pairStatus"
                textSize = 15f
                setTextColor(Ui.INK)
                setPadding(24, 20, 24, 20)
                background = Ui.rounded(if (trustedPeerStore.isTrusted(peer.deviceId)) Ui.SUCCESS else Ui.SURFACE, strokeColor = Ui.LINE)
                isClickable = true
                isFocusable = true
                setOnClickListener { connectToPeer(peer) }
            })
        }
    }

    private fun connectToPeer(peer: DiscoveredPeer) {
        selectedPeer = peer
        selectedDevice = null
        pairButton.visibility = View.GONE
        sendFileButton.visibility = View.GONE
        connectionStatusText.text = "Connecting to ${peer.deviceName} at ${peer.endpoint}..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING, strokeColor = Ui.LINE)

        networkExecutor.execute {
            val result = controlClient.fetchDevice(peer)
            runOnUiThread {
                result
                    .onSuccess { device ->
                        selectedDevice = device
                        connectionStatusText.text = "Connected to ${device.deviceName}\nDevice ID: ${device.deviceId}\nFeatures: ${device.features.joinToString(", ")}"
                        connectionStatusText.background = Ui.rounded(Ui.SUCCESS, strokeColor = Ui.LINE)
                        val trusted = trustedPeerStore.isTrusted(device.deviceId)
                        pairButton.visibility = if (trusted) View.GONE else View.VISIBLE
                        sendFileButton.visibility = if (trusted) View.VISIBLE else View.GONE
                    }
                    .onFailure { error ->
                        connectionStatusText.text = "Could not connect to ${peer.deviceName}\n${error.localizedMessage ?: "Unknown error"}"
                        connectionStatusText.background = Ui.rounded(Ui.DANGER, strokeColor = Ui.LINE)
                        pairButton.visibility = View.GONE
                        sendFileButton.visibility = View.GONE
                    }
            }
        }
    }

    private fun pairSelectedPeer() {
        val peer = selectedPeer ?: return
        val device = selectedDevice ?: return

        pairButton.isEnabled = false
        connectionStatusText.text = "Pairing with ${device.deviceName}..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING, strokeColor = Ui.LINE)

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
                        connectionStatusText.background = Ui.rounded(Ui.SUCCESS, strokeColor = Ui.LINE)
                    }
                    .onFailure { error ->
                        pairButton.isEnabled = true
                        connectionStatusText.text = "Pairing failed with ${device.deviceName}\n${error.localizedMessage ?: "Unknown error"}"
                        connectionStatusText.background = Ui.rounded(Ui.DANGER, strokeColor = Ui.LINE)
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

    @Deprecated("Deprecated by Android framework; sufficient for this minimal native Activity.")
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
        connectionStatusText.text = "Sending ${fileInfo.name}..."
        connectionStatusText.background = Ui.rounded(Ui.WARNING, strokeColor = Ui.LINE)

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
                result
                    .onSuccess {
                        connectionStatusText.text = "Sent ${fileInfo.name}\nSaved on Mac in Downloads/LinkBridge."
                        connectionStatusText.background = Ui.rounded(Ui.SUCCESS, strokeColor = Ui.LINE)
                    }
                    .onFailure { error ->
                        connectionStatusText.text = "File send failed\n${error.localizedMessage ?: "Unknown error"}"
                        connectionStatusText.background = Ui.rounded(Ui.DANGER, strokeColor = Ui.LINE)
                    }
            }
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
