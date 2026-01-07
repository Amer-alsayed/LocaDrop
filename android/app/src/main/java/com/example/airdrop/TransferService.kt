package com.example.airdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class TransferService : Service() {

    companion object {
        const val CHANNEL_ID = "service_channel_v2" // Changed to force reset
        const val NOTIFICATION_ID = 1
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var lastUpdateTime = 0L

    override fun onCreate() {
        super.onCreate()
        NetworkManager.initialize(this)
        NetworkManager.start()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Observe changes
        serviceScope.launch {
            NetworkManager.transferStatus.collect { status ->
                updateNotification(status, force = true)
            }
        }
        
        serviceScope.launch {
             NetworkManager.stats.collect { stats ->
                 val status = NetworkManager.transferStatus.value
                 if (status.startsWith("Sending") || status.startsWith("Receiving")) {
                     updateNotification(status, stats, force = false)
                 }
             }
        }
    }
    
    private fun updateNotification(status: String, stats: com.example.airdrop.TransferStats? = null, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Throttle progress updates to ~2Hz, unless forced or finished
        if (!force && stats != null && stats.progress < 1.0f && (now - lastUpdateTime) < 500) {
            return
        }
        lastUpdateTime = now

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, notificationIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val isTransferring = stats != null && (status.startsWith("Sending") || status.startsWith("Receiving"))
        val icon = if (isTransferring) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_data_bluetooth
        val largeIcon = android.graphics.BitmapFactory.decodeResource(resources, android.R.drawable.sym_def_app_icon)
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setLargeIcon(largeIcon) // Custom App Icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Unremovable
            .setOnlyAlertOnce(true)
            
        if (isTransferring) {
             val etaText = if (stats!!.etaSeconds >= 60) {
                 "${stats.etaSeconds / 60}m ${stats.etaSeconds % 60}s"
             } else {
                 "${stats.etaSeconds}s"
             }
             val statsText = "${String.format("%.1f", stats.speedMbps)} Mbps • ETA: $etaText"
             builder.setContentTitle("$status")
             builder.setContentText(statsText)
             builder.setProgress(100, (stats.progress * 100).toInt(), false)
        } else {
             builder.setContentTitle("LocalDrop")
             builder.setContentText(if (status.isEmpty()) "Ready to share" else status)
             builder.setProgress(0, 0, false)
        }
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                "ACTION_ACCEPT" -> NetworkManager.confirmTransfer(true)
                "ACTION_REJECT" -> NetworkManager.confirmTransfer(false)
                "STOP_SERVICE" -> stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel() // Cancel coroutines to prevent leaks/duplication
        NetworkManager.stop()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Transfer Service Channel",
                NotificationManager.IMPORTANCE_LOW // Keep Low to avoid sound/popup on every progress update
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, notificationIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val largeIcon = android.graphics.BitmapFactory.decodeResource(resources, android.R.drawable.sym_def_app_icon)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalDrop")
            .setContentText("Ready to share")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Custom White Status Icon
            .setLargeIcon(largeIcon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
