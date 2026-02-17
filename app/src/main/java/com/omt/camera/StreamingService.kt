package com.omt.camera

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process alive while streaming.
 * Without this, Android kills the app when it goes to the background,
 * closing the TCP server and causing RST responses to vMix connections.
 */
class StreamingService : Service() {

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "omt_streaming"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 6500) ?: 6500
        val notification = buildNotification(port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            @SuppressLint("InlinedApi")
            val serviceType = if (hasAudio)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIFICATION_ID, notification, serviceType)
            Log.i(TAG, "Foreground service started for port $port (audio=${hasAudio})")
        } else {
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Foreground service started for port $port")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OMT Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the OMT camera stream active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OMT Camera Streaming")
            .setContentText("Streaming on port $port")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
