package com.example.data.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.ui.components.DownloadFormatOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android Foreground Service dedicated to offline audio and video downloading.
 * Guarantees uninterrupted downloading even when:
 * 1. The user navigates away or minimizes MeTube
 * 2. The phone screen turns off (via PARTIAL_WAKE_LOCK)
 * 3. The system enters Doze or background battery optimization modes
 */
class OfflineDownloadService : Service() {

    data class DownloadRequest(
        val title: String,
        val artist: String,
        val duration: String,
        val sourceUrl: String,
        val thumbnailUrl: String,
        val directStreamUrl: String,
        val formatOption: DownloadFormatOption,
        val isVideo: Boolean
    )

    data class DownloadTaskStatus(
        val title: String,
        val artist: String,
        val formatOption: DownloadFormatOption,
        val progress: Int,
        val isCompleted: Boolean = false,
        val isFailed: Boolean = false,
        val errorMessage: String? = null
    )

    companion object {
        const val CHANNEL_ID = "metube_offline_downloads"
        const val FOREGROUND_NOTIFICATION_ID = 9001
        const val COMPLETED_NOTIFICATION_BASE_ID = 9100

        private const val ACTION_ENQUEUE_DOWNLOAD = "com.example.action.ENQUEUE_DOWNLOAD"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_ARTIST = "extra_artist"
        private const val EXTRA_DURATION = "extra_duration"
        private const val EXTRA_SOURCE_URL = "extra_source_url"
        private const val EXTRA_THUMBNAIL_URL = "extra_thumbnail_url"
        private const val EXTRA_DIRECT_STREAM_URL = "extra_direct_stream_url"
        private const val EXTRA_FORMAT_OPTION = "extra_format_option"
        private const val EXTRA_IS_VIDEO = "extra_is_video"

        private val _currentDownload = MutableStateFlow<DownloadTaskStatus?>(null)
        val currentDownload: StateFlow<DownloadTaskStatus?> = _currentDownload.asStateFlow()

        fun startDownload(
            context: Context,
            title: String,
            artist: String,
            duration: String,
            sourceUrl: String,
            thumbnailUrl: String,
            directStreamUrl: String = "",
            formatOption: DownloadFormatOption = DownloadFormatOption.AUDIO_M4A,
            isVideo: Boolean = formatOption.isVideo
        ) {
            val intent = Intent(context, OfflineDownloadService::class.java).apply {
                action = ACTION_ENQUEUE_DOWNLOAD
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_DURATION, duration)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
                putExtra(EXTRA_THUMBNAIL_URL, thumbnailUrl)
                putExtra(EXTRA_DIRECT_STREAM_URL, directStreamUrl)
                putExtra(EXTRA_FORMAT_OPTION, formatOption.name)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("OfflineDownloadService", "Failed to startForegroundService: ${e.message}", e)
                try {
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadQueue = ConcurrentLinkedQueue<DownloadRequest>()
    private val queueMutex = Mutex()
    private var isWorkerRunning = false

    private var wakeLock: PowerManager.WakeLock? = null
    private val downloadManager by lazy { OfflineDownloadManager(this) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Immediate startForeground to guarantee compliance with Android 8-15 requirements
        val initialNotification = buildOngoingNotification(
            title = intent?.getStringExtra(EXTRA_TITLE) ?: "Preparing download...",
            formatBadge = "MeTube Downloader",
            progress = 5
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            android.util.Log.w("OfflineDownloadService", "startForeground note: ${e.message}")
        }

        if (intent?.action == ACTION_ENQUEUE_DOWNLOAD) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Track"
            val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
            val duration = intent.getStringExtra(EXTRA_DURATION) ?: "0:00"
            val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: ""
            val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""
            val directStreamUrl = intent.getStringExtra(EXTRA_DIRECT_STREAM_URL) ?: ""
            val optionName = intent.getStringExtra(EXTRA_FORMAT_OPTION) ?: DownloadFormatOption.AUDIO_M4A.name
            val formatOption = try {
                DownloadFormatOption.valueOf(optionName)
            } catch (_: Exception) {
                DownloadFormatOption.AUDIO_M4A
            }
            val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, formatOption.isVideo)

            val request = DownloadRequest(
                title = title,
                artist = artist,
                duration = duration,
                sourceUrl = sourceUrl,
                thumbnailUrl = thumbnailUrl,
                directStreamUrl = directStreamUrl,
                formatOption = formatOption,
                isVideo = isVideo
            )

            downloadQueue.offer(request)
            processQueueIfNeeded()
        }

        return START_NOT_STICKY
    }

    private fun processQueueIfNeeded() {
        serviceScope.launch {
            queueMutex.withLock {
                if (isWorkerRunning) return@launch
                isWorkerRunning = true
            }

            try {
                while (true) {
                    val nextRequest = downloadQueue.poll() ?: break
                    executeDownload(nextRequest)
                }
            } finally {
                queueMutex.withLock {
                    isWorkerRunning = false
                }
                if (downloadQueue.isEmpty()) {
                    releaseWakeLock()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        stopForeground(false)
                    }
                    stopSelf()
                }
            }
        }
    }

    private suspend fun executeDownload(req: DownloadRequest) {
        val tag = "OfflineDownloadService"
        android.util.Log.i(tag, "Executing background download for '${req.title}' (${req.formatOption.name})")

        _currentDownload.value = DownloadTaskStatus(
            title = req.title,
            artist = req.artist,
            formatOption = req.formatOption,
            progress = 10
        )
        updateNotification(req.title, req.formatOption.qualityBadge, 10)

        var lastReported = 10
        val result = downloadManager.downloadTrack(
            title = req.title,
            artist = req.artist,
            duration = req.duration,
            sourceUrl = req.sourceUrl,
            thumbnailUrl = req.thumbnailUrl,
            directStreamUrl = req.directStreamUrl,
            formatOption = req.formatOption,
            isVideo = req.isVideo,
            onProgress = { progress ->
                _currentDownload.value = _currentDownload.value?.copy(progress = progress)
                if (progress >= lastReported + 5 || progress == 100) {
                    lastReported = progress
                    updateNotification(req.title, req.formatOption.qualityBadge, progress)
                }
            }
        )

        if (result.isSuccess) {
            android.util.Log.i(tag, "Download SUCCESS for '${req.title}'")
            _currentDownload.value = _currentDownload.value?.copy(progress = 100, isCompleted = true)
            showCompletedNotification(req.title, req.formatOption.qualityBadge, req.isVideo)
        } else {
            val err = result.exceptionOrNull()?.message ?: "Download failed"
            android.util.Log.e(tag, "Download FAILED for '${req.title}': $err")
            _currentDownload.value = _currentDownload.value?.copy(isFailed = true, errorMessage = err)
            showFailedNotification(req.title, err)
        }

        // Small delay to let UI observe completion
        kotlinx.coroutines.delay(1200)
        _currentDownload.value = null
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MeTube:OfflineDownloadWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // 1 hour safety timeout
                }
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            android.util.Log.w("OfflineDownloadService", "WakeLock acquisition: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            android.util.Log.w("OfflineDownloadService", "WakeLock release: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeTube Offline Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress for music and video downloads"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(
        title: String,
        formatBadge: String,
        progress: Int
    ): Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading: $title")
            .setContentText("$formatBadge • $progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, formatBadge: String, progress: Int) {
        try {
            val notif = buildOngoingNotification(title, formatBadge, progress)
            notificationManager?.notify(FOREGROUND_NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            android.util.Log.w("OfflineDownloadService", "updateNotification note: ${e.message}")
        }
    }

    private fun showCompletedNotification(title: String, formatBadge: String, isVideo: Boolean) {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val destination = if (isVideo) "Gallery & Phone Storage" else "Music Library & Phone Storage"
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("\"$title\" ($formatBadge) saved to $destination")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val uniqueId = COMPLETED_NOTIFICATION_BASE_ID + (System.currentTimeMillis() % 1000).toInt()
            notificationManager?.notify(uniqueId, notif)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showFailedNotification(title: String, error: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText("\"$title\": $error")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val uniqueId = COMPLETED_NOTIFICATION_BASE_ID + 500 + (System.currentTimeMillis() % 1000).toInt()
            notificationManager?.notify(uniqueId, notif)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
