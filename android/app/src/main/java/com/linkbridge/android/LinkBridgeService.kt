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
import com.linkbridge.android.presentation.ReceiveState
import java.util.concurrent.Executors
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
    private val center get() = LinkBridgeHub.approvals
    /** The file being sent, so a stop can report it as failed. */
    /** Set in onDestroy; a transfer finishing after that records history but publishes no live state. */
    @Volatile private var stopped = false

    private lateinit var identity: DeviceIdentity
    private lateinit var discovery: DiscoveryService
    private lateinit var receiveServer: ReceiveServer
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        LinkBridgeHub.publishReceiveState(ReceiveState.Starting)
        identity = IdentityStore(this).loadOrCreate()
        discovery = newDiscovery().also { it.start() }
        receiveServer = ReceiveServer(
            context = this,
            port = CONTROL_PORT,
            onListening = { onReceiveEvent(ReceiveEvent.Listening) },
            onProgress = { fileName, received, total ->
                onReceiveEvent(ReceiveEvent.Progress(fileName, received, total))
            },
            onFailed = { reason -> onReceiveEvent(ReceiveEvent.ServerFailed(reason)) },
            onTransferFailed = { fileName, reason -> onReceiveEvent(ReceiveEvent.TransferFailed(fileName, reason)) },
            onTransferCompleted = { transfer -> onReceiveEvent(ReceiveEvent.Completed(transfer)) },
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
                LinkBridgeHub.resolveApproval(id, intent.action == ACTION_ACCEPT)
            }
        }
        ensureForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away stops LinkBridge unless a transfer is still running.
        if (activeSends.get() == 0 && center.pending.value.isEmpty()) stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopped = true
        // Decline while the hub still points here, so each request's notification is cancelled.
        center.declineAll()
        if (LinkBridgeHub.service === this) LinkBridgeHub.service = null
        runCatching { discovery.stop() }
        runCatching { receiveServer.stop() }
        // The send in flight keeps running (its socket I/O isn't interruptible) and reports its
        // real outcome when it ends, so it isn't marked failed here. Queued sends never start:
        // report each one, so the share sheet's tally can complete.
        val dropped = sendExecutor.shutdownNow()
        dropped.forEach { task ->
            val name = (task as? QueuedSend)?.let { queued ->
                runCatching { contentResolver.fileInfo(queued.uri).name }.getOrNull()
            } ?: "file"
            LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Failed(name, STOPPED_MESSAGE))
        }
        LinkBridgeHub.publishPeers(emptyList())
        LinkBridgeHub.publishReceiveState(ReceiveState.Stopped)
        super.onDestroy()
    }

    /** Stops the service from the UI, as the notification's Stop action does. */
    fun stop() {
        main.post { stopEverything() }
    }

    fun cancelApprovalNotification(id: Int) {
        NotificationManagerCompat.from(this).cancel(APPROVAL_NOTIFICATION_BASE + id)
    }

    private fun onReceiveEvent(event: ReceiveEvent) {
        val update = TransferRecords.onReceiveEvent(event, System.currentTimeMillis())
        // An upload can finish after Stop (its I/O isn't interruptible): the file is saved
        // and the Mac got 200, so keep its history entry. Only the live state goes quiet.
        update.history?.let { LinkBridgeHub.recordHistory(this, it) }
        if (!stopped) LinkBridgeHub.publishReceiveState(update.state)
    }

    fun rescan() {
        main.post {
            runCatching { discovery.stop() }
            discovery = newDiscovery().also { it.start() }
        }
    }

    fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, uri: Uri, persistable: Boolean) {
        activeSends.incrementAndGet()
        sendExecutor.execute(QueuedSend(uri) {
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
                    if (stopped) return@uploadFile
                    LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Progress(info.name, sent, total))
                    if (total > 0) updateNotification("Sending ${info.name} to ${peer.deviceName}", progress = (sent * 100 / total).toInt())
                }.onSuccess {
                    LinkBridgeHub.recordHistory(
                        this, TransferHistoryEntry(info.name, info.size, peer.deviceName, isOutgoing = true)
                    )
                    LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Done(info.name, info.size, peer.deviceName))
                }.onFailure { error ->
                    Log.e(TAG, "Send failed", error)
                    val message = error.localizedMessage ?: "Unknown error"
                    LinkBridgeHub.recordHistory(
                        this,
                        TransferRecords.failedSend(
                            info.name, info.size, peer.deviceName, message, uri.toString(), persistable,
                            System.currentTimeMillis()
                        )
                    )
                    LinkBridgeHub.publishSendState(LinkBridgeHub.SendState.Failed(info.name, message))
                }
            } finally {
                activeSends.decrementAndGet()
                updateNotification(READY_TEXT, progress = null)
            }
        })
    }

    /** A queued send that knows its file, so a stop can report it if it never ran. */
    private class QueuedSend(val uri: Uri, private val body: () -> Unit) : Runnable {
        override fun run() = body()
    }

    /** Called on a receive-server thread; blocks until the user answers or 60 s pass. */
    private fun requestApproval(request: IncomingTransferRequest): Boolean {
        val id = center.register(request)
        if (!LinkBridgeHub.uiVisible) {
            if (canPostNotifications()) {
                showApprovalNotification(id, request)
            } else {
                center.declineUnasked(id)
                // No way to ask: the Devices banner already offers to turn notifications on.
                Log.w(TAG, "Declined a background file request: notifications are off")
                return false
            }
        }
        return center.await(id, Timeouts.APPROVAL_TIMEOUT_SECONDS)
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
        private const val STOPPED_MESSAGE = "LinkBridge stopped"
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
