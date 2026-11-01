package com.scrcpy.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SessionService : Service() {

    private val binder = LocalBinder()
    var sessionManager: SessionManager? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): SessionService = this@SessionService
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(applicationContext)
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val channelId = "scrcpy_session_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Scrcpy Mirroring Session",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Scrcpy Android")
            .setContentText("Active screen mirror session")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessionManager?.disconnect()
        super.onDestroy()
    }
}
