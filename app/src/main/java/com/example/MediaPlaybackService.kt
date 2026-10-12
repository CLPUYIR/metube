package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackInfo(
    val isPlaying: Boolean = false,
    val isOffline: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val thumbnailUrl: String = "",
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val durationFormatted: String = "0:00",
    val positionFormatted: String = "0:00"
)

/**
 * Foreground MediaPlaybackService managing persistent background audio,
 * Android 13+ system media controls, offline local file playback, and Audio Focus.
 */
class MediaPlaybackService : MediaSessionService(), AudioManager.OnAudioFocusChangeListener {

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null
    lateinit var internalPlayer: ExoPlayer
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var releaseLockJob: Job? = null

    fun acquireWakeLock() {
        releaseLockJob?.cancel()
        releaseLockJob = null
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YMusic::MediaPlaybackWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max safe timeout
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun acquireWifiLock() {
        releaseLockJob?.cancel()
        releaseLockJob = null
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "YMusic::WifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YMusic::WifiLock")
                }
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scheduleReleaseLocks(delayMs: Long = 60_000L) {
        releaseLockJob?.cancel()
        releaseLockJob = serviceScope.launch {
            delay(delayMs)
            releaseWakeLock()
            releaseWifiLock()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null

    private val _playbackInfo = MutableStateFlow(PlaybackInfo())
    val playbackInfo: StateFlow<PlaybackInfo> = _playbackInfo.asStateFlow()

    private val _sleepTimerSecondsRemaining = MutableStateFlow<Int?>(null)
    val sleepTimerSecondsRemaining: StateFlow<Int?> = _sleepTimerSecondsRemaining.asStateFlow()

    var isPlayingOffline = false
        private set

    var currentOfflineTitle = ""
        private set

    var currentOfflineArtist = ""
        private set

    var transportActionCallback: ((action: String) -> Unit)? = null
    var onOfflineTrackEnded: (() -> Unit)? = null
    var onPauseWebStreamRequested: (() -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "ymusic_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.example.ymusic.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.ymusic.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.ymusic.ACTION_NEXT"
        const val ACTION_PREV = "com.example.ymusic.ACTION_PREV"

        fun formatMs(ms: Long): String {
            if (ms <= 0) return "0:00"
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format("%d:%02d", m, s)
        }
    }

    private val serviceScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    if (_playbackInfo.value.isPlaying || (isPlayingOffline && internalPlayer.isPlaying)) {
                        acquireWakeLock()
                        acquireWifiLock()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannel()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(serviceScreenReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        internalPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, internalPlayer)
            .setSessionActivity(activityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                                .add(Player.COMMAND_PLAY_PAUSE)
                                .add(Player.COMMAND_SEEK_TO_NEXT)
                                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                                .build()
                        )
                        .build()
                }
            })
            .build()

        internalPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && isPlayingOffline) {
                    if (onOfflineTrackEnded != null) {
                        onOfflineTrackEnded?.invoke()
                    } else {
                        stopOfflinePlayback()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlayingOffline) {
                    if (isPlaying) acquireWakeLock() else releaseWakeLock()
                    _playbackInfo.value = _playbackInfo.value.copy(isPlaying = isPlaying)
                    updateNotification(currentOfflineTitle, currentOfflineArtist, isPlaying)
                    if (isPlaying) {
                        startProgressTracking()
                    } else {
                        stopProgressTracking()
                    }
                }
            }
        })

        val initialNotification = buildNotification("MeTube Active", "Ready for playback", false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> handlePlayAction()
            ACTION_PAUSE -> handlePauseAction()
            ACTION_NEXT -> handleNextAction()
            ACTION_PREV -> handlePrevAction()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handlePlayAction() {
        if (isPlayingOffline) {
            internalPlayer.play()
        } else {
            transportActionCallback?.invoke(ACTION_PLAY)
        }
    }

    private fun handlePauseAction() {
        if (isPlayingOffline) {
            internalPlayer.pause()
        } else {
            transportActionCallback?.invoke(ACTION_PAUSE)
        }
    }

    private fun handleNextAction() {
        if (isPlayingOffline) {
            onOfflineTrackEnded?.invoke()
        } else {
            transportActionCallback?.invoke(ACTION_NEXT)
        }
    }

    private fun handlePrevAction() {
        if (isPlayingOffline) {
            internalPlayer.seekTo(0)
        } else {
            transportActionCallback?.invoke(ACTION_PREV)
        }
    }

    /**
     * Plays local audio file completely offline using ExoPlayer
     */
    fun playOfflineMedia(filePath: String, title: String, artist: String, thumbnailUrl: String = "") {
        try {
            onPauseWebStreamRequested?.invoke()
            requestAudioFocus()
            isPlayingOffline = true
            currentOfflineTitle = title
            currentOfflineArtist = artist

            val targetFile = if (File(filePath).exists()) {
                File(filePath)
            } else {
                val fileName = File(filePath).name
                val fallback = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), fileName)
                if (fallback.exists()) fallback else File(filePath)
            }
            if (!targetFile.exists()) {
                android.util.Log.e("MediaPlaybackService", "Offline media file not found: $filePath")
                return
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(if (thumbnailUrl.isNotEmpty()) Uri.parse(thumbnailUrl) else null)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(targetFile))
                .setMediaMetadata(metadata)
                .build()

            internalPlayer.stop()
            internalPlayer.setMediaItem(mediaItem)
            internalPlayer.prepare()
            internalPlayer.play()
            acquireWakeLock()

            _playbackInfo.value = PlaybackInfo(
                isPlaying = true,
                isOffline = true,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl
            )
            startProgressTracking()

            updateNotification(title, artist, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopOfflinePlayback() {
        if (isPlayingOffline) {
            internalPlayer.stop()
            isPlayingOffline = false
            stopProgressTracking()
            _playbackInfo.value = PlaybackInfo()
            releaseWakeLock()
        }
    }

    fun seekToPosition(positionMs: Long) {
        if (isPlayingOffline) {
            internalPlayer.seekTo(positionMs)
            _playbackInfo.value = _playbackInfo.value.copy(
                currentPositionMs = positionMs,
                positionFormatted = formatMs(positionMs)
            )
        }
    }

    fun seekByOffset(offsetMs: Long) {
        if (isPlayingOffline) {
            val dur = internalPlayer.duration.coerceAtLeast(0L)
            val target = (internalPlayer.currentPosition + offsetMs).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
            internalPlayer.seekTo(target)
            _playbackInfo.value = _playbackInfo.value.copy(
                currentPositionMs = target,
                positionFormatted = formatMs(target)
            )
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive && isPlayingOffline) {
                if (internalPlayer.isPlaying) {
                    val curPos = internalPlayer.currentPosition
                    val duration = internalPlayer.duration.coerceAtLeast(0L)
                    _playbackInfo.value = _playbackInfo.value.copy(
                        currentPositionMs = curPos,
                        durationMs = duration,
                        positionFormatted = formatMs(curPos),
                        durationFormatted = formatMs(duration)
                    )
                }
                delay(300)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerSecondsRemaining.value = null
            return
        }

        var remainingSeconds = minutes * 60
        _sleepTimerSecondsRemaining.value = remainingSeconds

        sleepTimerJob = serviceScope.launch {
            while (isActive && remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
                _sleepTimerSecondsRemaining.value = remainingSeconds
            }
            // Timer expired: stop all playback!
            _sleepTimerSecondsRemaining.value = null
            if (isPlayingOffline) {
                internalPlayer.pause()
                _playbackInfo.value = _playbackInfo.value.copy(isPlaying = false)
            } else {
                onPauseWebStreamRequested?.invoke()
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerSecondsRemaining.value = null
    }

    /**
     * Updates notification and state for online web streaming
     */
    fun syncMetadata(track: TrackMetadata, force: Boolean = false) {
        if (isPlayingOffline) {
            if (track.isPlaying || !internalPlayer.isPlaying || force) {
                stopOfflinePlayback()
            } else {
                return // keep playing offline audio
            }
        }

        val displayTitle = track.title.ifEmpty { "MeTube Stream" }
        val displaySubtitle = if (track.artist.isNotEmpty()) {
            if (track.duration.equals("LIVE", ignoreCase = true)) {
                "${track.artist} • LIVE STREAM"
            } else {
                "${track.artist} • ${track.currentTime}/${track.duration}"
            }
        } else {
            if (track.duration.equals("LIVE", ignoreCase = true)) {
                "LIVE STREAM • Background playback active"
            } else {
                "Background playback active"
            }
        }

        _playbackInfo.value = PlaybackInfo(
            isPlaying = track.isPlaying,
            isOffline = false,
            title = track.title,
            artist = track.artist,
            album = track.album,
            thumbnailUrl = track.thumbnailUrl,
            positionFormatted = track.currentTime,
            durationFormatted = track.duration
        )

        if (track.isPlaying) {
            acquireWakeLock()
            acquireWifiLock()
        } else if (!isPlayingOffline) {
            scheduleReleaseLocks(60_000L)
        }

        updateNotification(displayTitle, displaySubtitle, track.isPlaying)
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        if (isPlaying) {
            requestAudioFocus()
            acquireWakeLock()
            acquireWifiLock()
            if (isPlayingOffline) {
                stopOfflinePlayback()
            }
        } else {
            if (!isPlayingOffline) {
                scheduleReleaseLocks(60_000L)
            }
        }
        if (!isPlayingOffline) {
            _playbackInfo.value = _playbackInfo.value.copy(isPlaying = isPlaying)
        }
    }

    private fun updateNotification(title: String, subtitle: String, isPlaying: Boolean) {
        try {
            val notification = buildNotification(title, subtitle, isPlaying)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attr)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this)
                    .build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (isPlayingOffline) internalPlayer.pause() else transportActionCallback?.invoke(ACTION_PAUSE)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // For transient losses (e.g. navigation prompt), pause temporarily if offline
                if (isPlayingOffline) internalPlayer.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isPlayingOffline) internalPlayer.play() else transportActionCallback?.invoke(ACTION_PLAY)
            }
        }
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeTube Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background playback controls"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, subtitle: String, isPlaying: Boolean): android.app.Notification {
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "Pause",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PAUSE },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "Play",
                PendingIntent.getService(
                    this, 2,
                    Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PLAY },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
        }

        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next,
            "Next",
            PendingIntent.getService(
                this, 3,
                Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_NEXT },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Previous",
            PendingIntent.getService(
                this, 4,
                Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PREV },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        mediaSession?.let { session ->
            try {
                builder.setStyle(
                    androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
                        .setShowActionsInCompactView(0, 1, 2)
                )
            } catch (_: Throwable) {}
        }

        return builder.build()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(serviceScreenReceiver)
        } catch (_: Exception) {}
        try {
            releaseWakeLock()
            releaseWifiLock()
            internalPlayer.release()
            mediaSession?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}
