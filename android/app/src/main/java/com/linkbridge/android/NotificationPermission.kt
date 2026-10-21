package com.linkbridge.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Notifications carry the Accept/Decline prompt when a file arrives in the background.
 * Ask once on first start; after a refusal the UI shows a banner instead of asking again,
 * and tapping it asks again (rationale allowed) or opens app settings (permanently denied).
 *
 * Construct during activity initialisation: it registers an activity-result launcher.
 * [onChanged] receives whether notifications are allowed after every check.
 */
class NotificationPermission(
    private val activity: ComponentActivity,
    private val onChanged: (allowed: Boolean) -> Unit,
) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh(requestIfNeeded = false)
    }

    private val prefs get() = activity.getSharedPreferences("linkbridge_ui", Context.MODE_PRIVATE)

    /** Reports the current state; with [requestIfNeeded], asks the first time only. */
    fun refresh(requestIfNeeded: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onChanged(true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(activity, PERMISSION) == PackageManager.PERMISSION_GRANTED
        onChanged(granted)
        if (!granted && requestIfNeeded && !prefs.getBoolean(KEY_ASKED, false) &&
            !activity.shouldShowRequestPermissionRationale(PERMISSION)
        ) {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            launcher.launch(PERMISSION)
        }
    }

    /** The banner's Turn on: ask again if Android allows it, else open the app's settings. */
    fun request() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity.shouldShowRequestPermissionRationale(PERMISSION)) {
            launcher.launch(PERMISSION)
        } else {
            // Permanently denied: only Settings can turn it back on.
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
            )
        }
    }

    private companion object {
        const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS
        const val KEY_ASKED = "asked_notifications"
    }
}
