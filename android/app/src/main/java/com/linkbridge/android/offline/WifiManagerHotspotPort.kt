package com.linkbridge.android.offline

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/** Real [HotspotPort]. Needs NEARBY_WIFI_DEVICES (API 33+) granted at runtime before [start]. */
class WifiManagerHotspotPort(context: Context) : HotspotPort {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Suppress("DEPRECATION") // SoftApConfiguration.ssid: the String form is what the Wi-Fi join flow needs.
    @SuppressLint("MissingPermission") // The caller checks NEARBY_WIFI_DEVICES; a SecurityException is mapped upstream.
    override fun start(callback: HotspotPortCallback) {
        wifi.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    this@WifiManagerHotspotPort.reservation = reservation
                    val config = reservation.softApConfiguration
                    val ssid = config.ssid
                    val passphrase = config.passphrase
                    if (ssid == null || passphrase == null) {
                        reservation.close()
                        this@WifiManagerHotspotPort.reservation = null
                        callback.onFailed(ERROR_GENERIC)
                        return
                    }
                    callback.onStarted(ssid, passphrase)
                }

                override fun onStopped() {
                    reservation = null
                    callback.onStopped()
                }

                override fun onFailed(reason: Int) = callback.onFailed(reason)
            },
            Handler(Looper.getMainLooper()),
        )
    }

    override fun stop() {
        reservation?.close()
        reservation = null
    }
}
