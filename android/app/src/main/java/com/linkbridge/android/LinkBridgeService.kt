package com.linkbridge.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps LinkBridge reachable while the app is in the background: advertises this phone,
 * discovers Macs, receives files and runs sends. Runs as a `connectedDevice` foreground
 * service with an ongoing notification; stops from the notification's Stop action or when
 * the app is swiped away while idle.
 */
class LinkBridgeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val controlClient = ControlClient()
    private val activeSends = AtomicInteger(0)
    private val nextRequestId = AtomicInteger(1)
    private val pendingApprovals = ConcurrentHashMap<Int, ArrayBlockingQueue<Boolean>>()

    private lateinit var identity: DeviceIdentity
    private lateinit var discovery: DiscoveryService
    private lateinit var receiveServer: ReceiveServer
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        identity = IdentityStore(this).loadOrCreate()
        discovery = newDiscovery().also { it.start() }
        receiveServer = ReceiveServer(
            context = this,
            port = CONTROL_PORT,
            // Task 7 maps these to ReceiveState; until then they keep today's status text.
            onListening = {
                LinkBridgeHub.publishReceiveStatus("Ready to receive files (encrypted) on port $CONTROL_PORT.")
            },
            onProgress = { fileName, received, total ->
                LinkBridgeHub.publishReceiveStatus("Receiving $fileName\n${formatBytes(received)} / ${formatBytes(total)}")
            },
            onFailed = { reason -> LinkBridgeHub.publishReceiveStatus("Receive server failed: $reason") },
            onTransferFailed = { _, reason ->
                LinkBridgeHub.publishReceiveStatus(
                    if (reason == ReceiveServer.INSUFFICIENT_STORAGE_REASON) reason else "Receive failed\n$reason"
                )
            },
            onTransferCompleted = { transfer ->
                LinkBridgeHub.publishReceiveStatus("Received ${transfer.fileName}\nSaved to Downloads/LinkBridge.")
                LinkBridgeHub.publishActivity("Received ${transfer.fileName}", isOutgoing = false)
                TransferHistoryStore(this).add(
                    TransferHistoryEntry(transfer.fileName, transfer.bytes, transfer.senderName, isOutgoing = false)
                )
                LinkBridgeHub.publishActivity("Received ${transfer.fileName} (${formatBytes(transfer.bytes)}) from ${transfer.senderName}", isOutgoing = false)
            },
            onApprovalRequested = ::requestApproval
        ).also { it.start() }
        LinkBridgeHub.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_ACCEPT, ACTION_DECLINE -> {
                val id = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
                pendingApprovals[id]?.offer(intent.action == ACTION_ACCEPT)
            }
        }
        ensureForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away stops LinkBridge unless a transfer is still running.
        if (activeSends.get() == 0 && pendingApprovals.isEmpty()) stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (LinkBridgeHub.service === this) LinkBridgeHub.service = null
        runCatching { discovery.stop() }
        runCatching { receiveServer.stop() }
        sendExecutor.shutdownNow()
        pendingApprovals.values.forEach { it.offer(false) }
        LinkBridgeHub.publishPeers(emptyList())
        LinkBridgeHub.publishReceiveStatus("LinkBridge is stopped. Open the app to receive files.")
        super.onDestroy()
    }

    fun rescan() {
        main.post {
            runCatching { discovery.stop() }
            discovery = newDiscovery().also { it.start() }
        }
    }

    fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, uri: Uri) {
        activeSends.incrementAndGet()
        sendExecutor.execute {
            val info = runCatching { contentResolver.fileInfo(uri) }.getOrElse { SelectedFileInfo("file", -1) }
            try {
                updateNotification("Sending ${info.name} to ${peer.deviceName}", progress = 0)
                LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Progress(info.name, 0, info.size))
                controlClient.uploadFile(
                    peer = peer,
                    fileName = info.name,
                    size = info.size,
                    localIdentity = identity,
                    trustedPeer = trustedPeer,
                    openInputStream = { contentResolver.openInputStream(uri) }
                ) { sent, total ->
                    LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Progress(info.name, sent, total))
                    if (total > 0) updateNotification("Sending ${info.name} to ${peer.deviceName}", progress = (sent * 100 / total).toInt())
                }.onSuccess {
                    TransferHistoryStore(this).add(
                        TransferHistoryEntry(info.name, info.size, peer.deviceName, isOutgoing = true)
                    )
                    LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Done(info.name, info.size, peer.deviceName))
                    LinkBridgeHub.publishActivity("Sent ${info.name} (${formatBytes(info.size)}) to ${peer.deviceName}", isOutgoing = true)
                }.onFailure { error ->
                    Log.e(TAG, "Send failed", error)
                    LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Failed(info.name, error.localizedMessage ?: "Unknown error"))
                }
            } finally {
                activeSends.decrementAndGet()
                updateNotification(READY_TEXT, progress = null)
            }
        }
    }

    /** Called on a receive-server thread; blocks until the user answers or 60 s pass. */
    private fun requestApproval(request: IncomingTransferRequest): Boolean {
        val id = nextRequestId.getAndIncrement()
        val answer = ArrayBlockingQueue<Boolean>(1)
        pendingApprovals[id] = answer
        try {
            val approver = LinkBridgeHub.foregroundApprover
            when {
                approver != null -> main.post { approver.approve(request) { answer.offer(it) } }
                canPostNotifications() -> showApprovalNotification(id, request)
                else -> {
                    LinkBridgeHub.publishReceiveStatus(
                        "Declined ${request.fileName} from ${request.senderName}: turn on notifications to accept files while LinkBridge is in the background."
                    )
                    return false
                }
            }
            return answer.poll(Timeouts.APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: false
        } finally {
            pendingApprovals.remove(id)
            NotificationManagerCompat.from(this).cancel(APPROVAL_NOTIFICATION_BASE + id)
        }
    }

    private fun ensureForeground() {
        if (isForeground) return
        try {
            ServiceCompat.startForeground(
                this,
                STATUS_NOTIFICATION_ID,
                statusNotification(READY_TEXT, progress = null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            isForeground = true
        } catch (error: Exception) {
            // Still works while the app is open; only background operation is lost.
            Log.e(TAG, "Could not enter the foreground", error)
        }
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun newDiscovery() = DiscoveryService(this, identity, CONTROL_PORT) { peers ->
        LinkBridgeHub.publishPeers(peers)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // canPostNotifications() checks POST_NOTIFICATIONS first.
    private fun updateNotification(text: String, progress: Int?) {
        if (!isForeground || !canPostNotifications()) return
        runCatching { NotificationManagerCompat.from(this).notify(STATUS_NOTIFICATION_ID, statusNotification(text, progress)) }
    }

    private fun statusNotification(text: String, progress: Int?): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LinkBridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_linkbridge)
            .setContentTitle("LinkBridge")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (progress != null) setProgress(100, progress, false) }
            .addAction(0, "Stop", stop)
            .build()
    }

    @Suppress("MissingPermission") // Checked by canPostNotifications() before calling.
    private fun showApprovalNotification(id: Int, request: IncomingTransferRequest) {
        fun action(action: String) = PendingIntent.getService(
            this, id * 2 + if (action == ACTION_ACCEPT) 0 else 1,
            Intent(this, LinkBridgeService::class.java).setAction(action).putExtra(EXTRA_REQUEST_ID, id),
            PendingIntent.FLAG_IMMUTABLE
        )
        // What the lock screen shows: no sender, file name or size.
        val lockScreenVersion = NotificationCompat.Builder(this, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_linkbridge)
            .setContentTitle("Incoming file")
            .setContentText("Unlock to see it")
            .build()
        // Accepting needs an unlocked phone, so someone holding it locked can't take a file in.
        val accept = NotificationCompat.Action.Builder(0, "Accept", action(ACTION_ACCEPT))
            .setAuthenticationRequired(true)
            .build()
        val notification = NotificationCompat.Builder(this, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_linkbridge)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenVersion)
            .setContentTitle("Accept file from ${request.senderName}?")
            .setContentText("${request.fileName} (${formatBytes(request.bytes)})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent())
            .setTimeoutAfter(Timeouts.APPROVAL_TIMEOUT_SECONDS * 1000)
            .setAutoCancel(true)
            .addAction(accept)
            .addAction(0, "Decline", action(ACTION_DECLINE))
            .build()
        NotificationManagerCompat.from(this).notify(APPROVAL_NOTIFICATION_BASE + id, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while LinkBridge is ready to send and receive"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REQUESTS, "Incoming files", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks before saving a file sent from your Mac"
            }
        )
    }

    companion object {
        const val CONTROL_PORT = 49153
        private const val TAG = "LinkBridgeService"
        private const val READY_TEXT = "Ready to send and receive on this Wi-Fi"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_REQUESTS = "requests"
        private const val STATUS_NOTIFICATION_ID = 1
        private const val APPROVAL_NOTIFICATION_BASE = 1000
        private const val ACTION_STOP = "com.linkbridge.android.action.STOP"
        private const val ACTION_ACCEPT = "com.linkbridge.android.action.ACCEPT"
        private const val ACTION_DECLINE = "com.linkbridge.android.action.DECLINE"
        private const val EXTRA_REQUEST_ID = "request_id"

        /**
         * Call while the app is in the foreground. A plain startService (not
         * startForegroundService) means a failed startForeground degrades to "works while
         * open" instead of crashing the app.
         */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, LinkBridgeService::class.java)) }
                .onFailure { Log.e(TAG, "Could not start LinkBridgeService", it) }
        }
    }
}
