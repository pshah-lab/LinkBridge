package com.linkbridge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.linkbridge.android.ui.LinkBridgeApp
import com.linkbridge.android.ui.theme.LinkBridgeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory(application) }

    private val notifications = NotificationPermission(this) { allowed -> viewModel.notificationsChanged(allowed) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LinkBridgeTheme {
                LinkBridgeApp(viewModel, onRequestNotifications = notifications::request)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The service owns discovery, receiving and sending so they survive leaving the app.
        LinkBridgeService.start(this)
        LinkBridgeHub.uiVisible = true
        notifications.refresh(requestIfNeeded = true)
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the permission in Settings meanwhile.
        notifications.refresh(requestIfNeeded = false)
    }

    override fun onStop() {
        LinkBridgeHub.uiVisible = false
        super.onStop()
    }
}
