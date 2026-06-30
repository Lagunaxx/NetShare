package com.example.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.ui.FileSharingViewModel
import kotlinx.coroutines.*

class FileTransferService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    companion object {
        const val CHANNEL_ID = "file_transfer_channel"
        const val NOTIFICATION_ID = 4851
        
        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_RESUME = "com.example.action.RESUME"
        const val ACTION_CANCEL = "com.example.action.CANCEL"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startForegroundNotification()
            observeProgress()
        } else if (action == ACTION_PAUSE) {
            FileSharingViewModel.isPaused.value = true
            updateNotification()
        } else if (action == ACTION_RESUME) {
            FileSharingViewModel.isPaused.value = false
            updateNotification()
        } else if (action == ACTION_CANCEL) {
            FileSharingViewModel.isCancelled.value = true
            FileSharingViewModel.isPaused.value = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildNotification(
            fileName = FileSharingViewModel.currentFileName.value,
            progress = FileSharingViewModel.currentFileProgress.value,
            overallStr = FileSharingViewModel.overallProgressStr.value,
            isPaused = FileSharingViewModel.isPaused.value
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeProgress() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            coroutineScope {
                launch {
                    FileSharingViewModel.currentFileProgress.collect { updateNotification() }
                }
                launch {
                    FileSharingViewModel.overallProgressStr.collect { updateNotification() }
                }
                launch {
                    FileSharingViewModel.currentFileName.collect { updateNotification() }
                }
                launch {
                    FileSharingViewModel.isPaused.collect { updateNotification() }
                }
            }
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(
            fileName = FileSharingViewModel.currentFileName.value,
            progress = FileSharingViewModel.currentFileProgress.value,
            overallStr = FileSharingViewModel.overallProgressStr.value,
            isPaused = FileSharingViewModel.isPaused.value
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        fileName: String?,
        progress: Float?,
        overallStr: String?,
        isPaused: Boolean
    ): Notification {
        val clickIntent = Intent(this, MainActivity::class.java)
        val pendingClick = PendingIntent.getActivity(
            this, 0, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPaused) "Копирование приостановлено" else "Копирование файлов..."
        
        var text = overallStr ?: "Выполняется передача..."
        if (fileName != null) {
            text += " - $fileName"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingClick)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (progress != null) {
            val progressPercent = (progress * 100).toInt()
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(100, 0, true)
        }

        val pauseIntent = Intent(this, FileTransferService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pendingPause = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseActionText = if (isPaused) "Продолжить" else "Пауза"
        builder.addAction(android.R.drawable.ic_media_pause, pauseActionText, pendingPause)

        val cancelIntent = Intent(this, FileTransferService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pendingCancel = PendingIntent.getService(
            this, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", pendingCancel)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Перенос файлов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отображает ход переноса файлов"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        updateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
