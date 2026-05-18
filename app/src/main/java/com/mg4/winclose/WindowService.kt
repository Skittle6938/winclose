package com.mg4.winclose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class WindowService : Service() {

    companion object {
        private const val CHANNEL_ID = "winclose_channel"
        const val PREF_AUTO_CLOSE = "auto_close_on_exit"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WindowService::class.java))
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private var ignitionListener: ((Int) -> Unit)? = null
    private var parkingListener:  (() -> Unit)?    = null
    @Volatile private var prevIgnitionState = -1
    @Volatile private var closeInProgress   = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification(getString(R.string.notif_active)))

        WindowHardware.init(this)

        // ── Trigger principal : porte ouverte + gear=PARK (CarStateClient) ──
        val parkCb: () -> Unit = {
            val autoClose = prefs().getBoolean(PREF_AUTO_CLOSE, false)
            if (autoClose && !closeInProgress) triggerClose()
        }
        parkingListener = parkCb
        WindowHardware.parkingStateCallbacks.add(parkCb)

        // ── Ignition listener : start/stop du speed monitor ──
        val ignCb: (Int) -> Unit = { state ->
            when (state) {
                WindowHardware.CarIgnitionItem.RUN -> {
                    if (prevIgnitionState == WindowHardware.CarIgnitionItem.OFF
                            || prevIgnitionState == -1) {
                        WindowHardware.startSpeedMonitor(applicationContext)
                    }
                }
                WindowHardware.CarIgnitionItem.OFF -> {
                    WindowHardware.stopSpeedMonitor()
                }
            }
            prevIgnitionState = state
        }
        ignitionListener = ignCb
        WindowHardware.registerIgnitionListener(ignCb)
    }

    private fun prefs() = getSharedPreferences("winclose_prefs", MODE_PRIVATE)

    private fun triggerClose() {
        closeInProgress = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WinClose:close")
        wl.acquire(10_000L)
        Thread {
            try {
                WindowHardware.closeAllWindowsPulsed(5000L)
            } finally {
                if (wl.isHeld) wl.release()
                closeInProgress = false
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        WindowHardware.stopSpeedMonitor()
        ignitionListener?.let { WindowHardware.ignitionCallbacks.remove(it) }
        parkingListener?.let  { WindowHardware.parkingStateCallbacks.remove(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .build()
}
