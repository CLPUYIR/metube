package com.example

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Rational
import java.io.File
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.MainNavTab
import com.example.ui.YMusicViewModel
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.DownloadFormatDialog
import com.example.ui.components.DownloadFormatOption
import com.example.ui.components.DownloadsView
import com.example.ui.components.MiniPlaybackProgressBar
import com.example.ui.components.NowPlayingSheet
import com.example.ui.components.PlaylistsView
import com.example.ui.components.SleepTimerDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.data.downloader.StreamCaptureRegistry
import com.example.data.downloader.StreamUrlResolver

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    var playbackService by mutableStateOf<MediaPlaybackService?>(null)
        private set
    var isInPiPMode by mutableStateOf(false)
        private set
    private var isBound = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    playbackService?.acquireWakeLock()
                    playbackService?.acquireWifiLock()
                    webView?.evaluateJavascript(
                        """
                        (function() {
                            window._ymusicScreenOff = true;
                            const v = document.querySelector('video') || document.querySelector('audio');
                            if (v && !v.ended && (window._ymusicWasPlaying || window._ymusicShouldBePlaying)) {
                                if (v.paused) {
                                    v.play().catch(function(){});
                                }
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    webView?.evaluateJavascript(
                        "(function() { window._ymusicScreenOff = false; })();",
                        null
                    )
                    if (playbackService?.playbackInfo?.value?.isPlaying == true ||
                        (playbackService?.isPlayingOffline == true && playbackService?.internalPlayer?.isPlaying == true)) {
                        playbackService?.acquireWakeLock()
                        playbackService?.acquireWifiLock()
                    }
                }
            }
        }
    }

    fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val aspectRatio = Rational(16, 9)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        this.isInPiPMode = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = (playbackService?.playbackInfo?.value?.isPlaying == true) ||
                    (playbackService?.isPlayingOffline == true && playbackService?.internalPlayer?.isPlaying == true)
            if (isPlaying) {
                enterPictureInPicture()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaPlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true

            playbackService?.transportActionCallback = { action ->
                when (action) {
                    MediaPlaybackService.ACTION_PLAY -> JsMediaBridge.resumeWebPlayback(webView)
                    MediaPlaybackService.ACTION_PAUSE -> JsMediaBridge.pauseWebPlayback(webView)
                    MediaPlaybackService.ACTION_NEXT -> JsMediaBridge.triggerNext(webView)
                    MediaPlaybackService.ACTION_PREV -> JsMediaBridge.triggerPrevious(webView)
                }
            }

            playbackService?.onPauseWebStreamRequested = {
                JsMediaBridge.pauseWebPlayback(webView)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureCacheDirectories(this)
        enableEdgeToEdge()

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val serviceIntent = Intent(this, MediaPlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                // Request Notification Permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val context = LocalContext.current
                    var hasNotificationPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasNotificationPermission = isGranted
                    }
                    LaunchedEffect(Unit) {
                        if (!hasNotificationPermission) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: YMusicViewModel = viewModel()
                    YMusicRootApp(
                        viewModel = viewModel,
                        serviceProvider = { playbackService },
                        isInPiPMode = isInPiPMode,
                        onEnterPiP = { enterPictureInPicture() },
                        onWebViewCreated = { wv -> webView = wv }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (playbackService?.playbackInfo?.value?.isPlaying == true ||
            (playbackService?.isPlayingOffline == true && playbackService?.internalPlayer?.isPlaying == true)) {
            playbackService?.acquireWakeLock()
            playbackService?.acquireWifiLock()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
        }
        super.onDestroy()
    }

    private fun ensureCacheDirectories(context: Context) {
        try {
            val cache = context.cacheDir ?: return
            if (!cache.exists()) cache.mkdirs()
            val webViewDir = File(cache, "WebView")
            if (!webViewDir.exists()) webViewDir.mkdirs()
            val crashpadDir = File(webViewDir, "Crashpad")
            if (!crashpadDir.exists()) crashpadDir.mkdirs()
            val attachmentsDir = File(crashpadDir, "attachments")
            if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

enum class ActiveStreamMode(val url: String, val title: String) {
    MUSIC("https://music.youtube.com", "YouTube Music"),
    VIDEOS("https://m.youtube.com", "YouTube Videos"),
    LIVE("https://m.youtube.com/live", "YouTube Live")
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YMusicRootApp(
    viewModel: YMusicViewModel,
    serviceProvider: () -> MediaPlaybackService?,
    isInPiPMode: Boolean = false,
    onEnterPiP: () -> Unit = {},
    onWebViewCreated: (WebView) -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(MainNavTab.STREAM) }
    var streamMode by remember { mutableStateOf(ActiveStreamMode.MUSIC) }
    var activeTrack by remember { mutableStateOf(TrackMetadata()) }
    var adsBlockedCount by remember { mutableIntStateOf(0) }
    var pageProgress by remember { mutableIntStateOf(100) }
    var isDesktopUserAgent by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var internalWebView by remember { mutableStateOf<WebView?>(null) }
    var customVideoView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showFullPlayerSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showDownloadFormatDialog by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(false) }
    var webViewRecreateKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    var isRendererCrashed by remember { mutableStateOf(false) }
    var consecutiveCrashes by remember { mutableIntStateOf(0) }
    var lastCrashTime by remember { mutableLongStateOf(0L) }

    val reloadRotation by animateFloatAsState(
        targetValue = if (isReloading) 360f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        finishedListener = { isReloading = false },
        label = "reload_rotation"
    )

    val service = serviceProvider()
    val playlists by viewModel.playlists.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val currentDownloadProgress by viewModel.currentDownloadProgress.collectAsState()
    val downloads by viewModel.downloads.collectAsState()

    val playbackInfo by (service?.playbackInfo?.collectAsState() ?: remember { mutableStateOf(PlaybackInfo()) })
    val sleepTimerSeconds by (service?.sleepTimerSecondsRemaining?.collectAsState() ?: remember { mutableStateOf(null) })

    val isOfflineActive = service?.isPlayingOffline == true && service.internalPlayer.isPlaying && (currentTab != MainNavTab.STREAM || (!activeTrack.isPlaying && activeTrack.title.isEmpty()))
    val displayTitle = if (isOfflineActive) {
        playbackInfo.title.ifEmpty { service?.currentOfflineTitle?.ifEmpty { "Offline Track" } ?: "Offline Track" }
    } else {
        activeTrack.title.ifEmpty { "MeTube Stream" }
    }
    val displayArtist = if (isOfflineActive) {
        "${playbackInfo.artist.ifEmpty { service?.currentOfflineArtist ?: "Local" }} • Offline Storage"
    } else if (activeTrack.artist.isNotEmpty()) {
        if (activeTrack.duration.equals("LIVE", ignoreCase = true)) {
            "${activeTrack.artist} • LIVE STREAM"
        } else {
            "${activeTrack.artist} • ${activeTrack.currentTime} / ${activeTrack.duration}"
        }
    } else {
        if (activeTrack.duration.equals("LIVE", ignoreCase = true)) {
            "LIVE STREAM • Background audio"
        } else {
            "Background audio • Ad-shielded"
        }
    }
    val displayThumbnail = if (isOfflineActive) playbackInfo.thumbnailUrl else activeTrack.thumbnailUrl

    val completedDownloads = remember(downloads) {
        downloads.filter { it.status.equals("COMPLETED", ignoreCase = true) }
    }
    val titleToMatch = activeTrack.title.ifEmpty { displayTitle }
    val urlToMatch = activeTrack.url

    val downloadedFormatsForCurrentTrack = remember(completedDownloads, titleToMatch, urlToMatch) {
        val matching = completedDownloads.filter {
            (urlToMatch.isNotEmpty() && it.sourceUrl == urlToMatch) ||
            (titleToMatch.isNotEmpty() && it.title.equals(titleToMatch, ignoreCase = true)) ||
            (titleToMatch.isNotEmpty() && it.title.startsWith(titleToMatch, ignoreCase = true)) ||
            (titleToMatch.isNotEmpty() && titleToMatch.startsWith(it.title, ignoreCase = true))
        }
        val set = mutableSetOf<DownloadFormatOption>()
        for (d in matching) {
            val path = d.localFilePath.lowercase()
            val t = d.title.lowercase()
            if (path.endsWith(".m4a") || (!path.endsWith(".mp4") && !path.endsWith(".webm") && !t.contains("video"))) {
                if (path.endsWith(".mp3") || t.contains("mp3")) {
                    set.add(DownloadFormatOption.AUDIO_MP3)
                } else {
                    set.add(DownloadFormatOption.AUDIO_M4A)
                }
            }
            if (path.endsWith(".mp4") || path.endsWith(".webm") || t.contains("video") || t.contains("720p") || t.contains("360p")) {
                if (t.contains("360p") || path.contains("360p")) {
                    set.add(DownloadFormatOption.VIDEO_360P)
                } else {
                    set.add(DownloadFormatOption.VIDEO_720P)
                }
            }
        }
        set
    }

    val isTrackDownloaded = isOfflineActive || downloadedFormatsForCurrentTrack.isNotEmpty()

    val offlineFraction = if (playbackInfo.durationMs > 0) {
        (playbackInfo.currentPositionMs.toFloat() / playbackInfo.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val onlineFraction = if (activeTrack.durationSec > 0) {
        (activeTrack.currentTimeSec / activeTrack.durationSec).coerceIn(0f, 1f)
    } else 0f
    val currentProgressFraction = if (isOfflineActive) offlineFraction else onlineFraction
    val currentPositionText = if (isOfflineActive) playbackInfo.positionFormatted else activeTrack.currentTime
    val durationText = if (isOfflineActive) playbackInfo.durationFormatted else activeTrack.duration

    var optimisticIsPlaying by remember { mutableStateOf<Boolean?>(null) }
    val isPlayingNow = optimisticIsPlaying ?: if (isOfflineActive) playbackInfo.isPlaying else activeTrack.isPlaying

    LaunchedEffect(playbackInfo.isPlaying, activeTrack.isPlaying, isOfflineActive) {
        optimisticIsPlaying = null
    }

    val onTogglePlayPause: () -> Unit = {
        val next = !isPlayingNow
        optimisticIsPlaying = next
        if (isOfflineActive && service?.internalPlayer?.isPlaying == true) {
            service.internalPlayer.pause()
        } else if (isOfflineActive && currentTab != MainNavTab.STREAM) {
            service?.internalPlayer?.play()
        } else {
            service?.stopOfflinePlayback()
            if (next) {
                JsMediaBridge.resumeWebPlayback(internalWebView)
            } else {
                JsMediaBridge.pauseWebPlayback(internalWebView)
            }
        }
    }

    val onSeekFraction: (Float) -> Unit = { fraction ->
        if (isOfflineActive) {
            val targetMs = (playbackInfo.durationMs * fraction).toLong()
            service?.seekToPosition(targetMs)
        } else {
            JsMediaBridge.seekToFraction(internalWebView, fraction)
        }
    }

    val onSeekRelative: (Int) -> Unit = { seconds ->
        if (isOfflineActive) {
            service?.seekByOffset(seconds * 1000L)
        } else {
            if (seconds > 0) JsMediaBridge.seekForward(internalWebView, seconds)
            else JsMediaBridge.seekBackward(internalWebView, -seconds)
        }
    }

    // Dedicated Picture-in-Picture Mode Overlay
    if (isInPiPMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (customVideoView != null) {
                AndroidView(
                    factory = {
                        FrameLayout(it).apply {
                            (customVideoView?.parent as? ViewGroup)?.removeView(customVideoView)
                            addView(customVideoView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else if ((streamMode == ActiveStreamMode.VIDEOS || streamMode == ActiveStreamMode.LIVE) && internalWebView != null) {
                AndroidView(
                    factory = { internalWebView!! },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (displayThumbnail.isNotEmpty()) {
                        AsyncImage(
                            model = displayThumbnail,
                            contentDescription = "PiP Artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF311B92),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayTitle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = displayArtist,
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlayingNow) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlayingNow) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }
        return
    }

    val activity = context as? ComponentActivity
    DisposableEffect(customVideoView) {
        if (customVideoView != null) {
            activity?.let { act ->
                val window = act.window
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            activity?.let { act ->
                val window = act.window
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.let { act ->
                val window = act.window
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Handle back button
    BackHandler(enabled = customVideoView != null || showFullPlayerSheet || showSleepTimerDialog || selectedPlaylist != null || (currentTab == MainNavTab.STREAM && canGoBack) || currentTab != MainNavTab.STREAM || isSearchExpanded) {
        if (customVideoView != null) {
            customViewCallback?.onCustomViewHidden()
            customVideoView = null
            customViewCallback = null
        } else if (showFullPlayerSheet) {
            showFullPlayerSheet = false
        } else if (showSleepTimerDialog) {
            showSleepTimerDialog = false
        } else if (selectedPlaylist != null) {
            viewModel.selectPlaylist(null)
        } else if (isSearchExpanded) {
            isSearchExpanded = false
        } else if (currentTab == MainNavTab.STREAM && internalWebView?.canGoBack() == true) {
            internalWebView?.goBack()
        } else if (currentTab != MainNavTab.STREAM) {
            currentTab = MainNavTab.STREAM
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
            if (customVideoView == null && currentTab == MainNavTab.STREAM) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141414))
                        .statusBarsPadding()
                ) {
                    // Top Header Row: Brand & Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Brand
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFE50914),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = "MeTube Logo",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Text(
                                text = "MeTube",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp,
                                modifier = Modifier.testTag("app_brand_title")
                            )
                        }

                        // Right Action Controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Shield Badge
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF1E3A2F),
                                modifier = Modifier.testTag("shield_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = "Shield Active",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "$adsBlockedCount",
                                        color = Color(0xFF4CAF50),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Sleep Timer Button
                            IconButton(
                                onClick = { showSleepTimerDialog = true },
                                modifier = Modifier.size(32.dp).testTag("top_bar_sleep_timer_button")
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (sleepTimerSeconds != null && sleepTimerSeconds!! > 0) {
                                            val mins = (sleepTimerSeconds!! + 59) / 60
                                            Badge(
                                                containerColor = Color(0xFFBB86FC),
                                                contentColor = Color.Black
                                            ) {
                                                Text("${mins}m", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bedtime,
                                        contentDescription = "Sleep Timer",
                                        tint = if (sleepTimerSeconds != null) Color(0xFFBB86FC) else Color.LightGray,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            // Picture in Picture button
                            IconButton(
                                onClick = onEnterPiP,
                                modifier = Modifier.size(32.dp).testTag("top_bar_pip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture in picture",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Search Toggle
                            IconButton(
                                onClick = { isSearchExpanded = !isSearchExpanded },
                                modifier = Modifier.size(32.dp).testTag("search_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Reload Button
                            IconButton(
                                onClick = {
                                    isReloading = true
                                    internalWebView?.reload()
                                },
                                modifier = Modifier.size(32.dp).testTag("reload_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload",
                                    tint = Color.LightGray,
                                    modifier = Modifier
                                        .size(17.dp)
                                        .rotate(reloadRotation)
                                )
                            }
                        }
                    }

                    // Mode Navigation Row: Music | Videos | Live (Full Width, Equal Weight, Perfectly Adaptive!)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tab 1: Music
                        FilterChip(
                            selected = streamMode == ActiveStreamMode.MUSIC,
                            onClick = {
                                if (streamMode != ActiveStreamMode.MUSIC) {
                                    service?.stopOfflinePlayback()
                                    streamMode = ActiveStreamMode.MUSIC
                                    internalWebView?.settings?.userAgentString = AdBlockEngine.DESKTOP_USER_AGENT
                                    internalWebView?.loadUrl(ActiveStreamMode.MUSIC.url)
                                }
                            },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Audiotrack,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Music", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE50914),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White,
                                containerColor = Color(0xFF242424),
                                labelColor = Color.LightGray
                            ),
                            border = null,
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("tab_music")
                        )

                        // Tab 2: Videos (ALWAYS VISIBLE, PROMINENT, NEVER HIDDEN!)
                        FilterChip(
                            selected = streamMode == ActiveStreamMode.VIDEOS,
                            onClick = {
                                if (streamMode != ActiveStreamMode.VIDEOS) {
                                    service?.stopOfflinePlayback()
                                    streamMode = ActiveStreamMode.VIDEOS
                                    internalWebView?.settings?.userAgentString = AdBlockEngine.MOBILE_USER_AGENT
                                    internalWebView?.loadUrl(ActiveStreamMode.VIDEOS.url)
                                }
                            },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Videos", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE50914),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White,
                                containerColor = Color(0xFF242424),
                                labelColor = Color.LightGray
                            ),
                            border = null,
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("tab_videos")
                        )

                        // Tab 3: Live
                        FilterChip(
                            selected = streamMode == ActiveStreamMode.LIVE,
                            onClick = {
                                if (streamMode != ActiveStreamMode.LIVE) {
                                    service?.stopOfflinePlayback()
                                    streamMode = ActiveStreamMode.LIVE
                                    internalWebView?.settings?.userAgentString = AdBlockEngine.MOBILE_USER_AGENT
                                    internalWebView?.loadUrl(ActiveStreamMode.LIVE.url)
                                }
                            },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (streamMode == ActiveStreamMode.LIVE) Color.White else Color(0xFFE50914))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE50914),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF242424),
                                labelColor = Color.LightGray
                            ),
                            border = null,
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("tab_live")
                        )
                    }

                    // Search input
                    AnimatedVisibility(visible = isSearchExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search songs, artists, or enter URL...", fontSize = 13.sp) },
                                singleLine = true,
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear search",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("search_input_field"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF1F1F1F),
                                    unfocusedContainerColor = Color(0xFF1F1F1F),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.LightGray,
                                    focusedBorderColor = Color(0xFFE50914),
                                    unfocusedBorderColor = Color(0xFF333333)
                                )
                            )

                            Button(
                                onClick = {
                                    if (searchQuery.isNotBlank()) {
                                        service?.stopOfflinePlayback()
                                        val targetUrl = if (searchQuery.startsWith("http://") || searchQuery.startsWith("https://")) {
                                            searchQuery
                                        } else {
                                            val base = if (streamMode == ActiveStreamMode.MUSIC) {
                                                "https://music.youtube.com/search?q="
                                            } else {
                                                "https://m.youtube.com/results?search_query="
                                            }
                                            val encodedQuery = java.net.URLEncoder.encode(searchQuery, "UTF-8")
                                            if (streamMode == ActiveStreamMode.LIVE) {
                                                base + encodedQuery + "&sp=CAM%3D"
                                            } else {
                                                base + encodedQuery
                                            }
                                        }
                                        internalWebView?.loadUrl(targetUrl)
                                        isSearchExpanded = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.height(50.dp).testTag("search_submit_button")
                            ) {
                                Text("Go", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Quick Navigation Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { if (canGoBack) internalWebView?.goBack() },
                                enabled = canGoBack,
                                modifier = Modifier.size(32.dp).testTag("back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = if (canGoBack) Color.White else Color.DarkGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = { if (canGoForward) internalWebView?.goForward() },
                                enabled = canGoForward,
                                modifier = Modifier.size(32.dp).testTag("forward_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Forward",
                                    tint = if (canGoForward) Color.White else Color.DarkGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // User Agent Switcher
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                isDesktopUserAgent = !isDesktopUserAgent
                                internalWebView?.settings?.userAgentString = if (isDesktopUserAgent) {
                                    AdBlockEngine.DESKTOP_USER_AGENT
                                } else {
                                    AdBlockEngine.MOBILE_USER_AGENT
                                }
                                internalWebView?.reload()
                            }
                        ) {
                            Icon(
                                imageVector = if (isDesktopUserAgent) Icons.Default.Laptop else Icons.Default.PhoneAndroid,
                                contentDescription = "User Agent",
                                tint = Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isDesktopUserAgent) "Desktop View" else "Mobile View",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Page progress indicator
                    if (pageProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { pageProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = Color(0xFFE50914),
                            trackColor = Color.Transparent
                        )
                    }
                }
            } else {
                // Top status bar padding for Playlists and Downloads
                Spacer(modifier = Modifier.statusBarsPadding())
            }
        },
        bottomBar = {
            if (customVideoView == null) {
                val showMiniPlayer = isOfflineActive || currentTab != MainNavTab.STREAM || streamMode == ActiveStreamMode.MUSIC
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    if (showMiniPlayer) {
                        // Persistent Mini-Player Bar
                        val playPauseInteractionSource = remember { MutableInteractionSource() }
                val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
                val miniPlayPauseScale by animateFloatAsState(
                    targetValue = if (isPlayPausePressed) 0.88f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "mini_play_pause_scale"
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mini_player_container"),
                    color = Color(0xFF161616),
                    tonalElevation = 8.dp
                ) {
                    Column {
                        // Live Scrubbing / Progress Bar on Mini Player
                        MiniPlaybackProgressBar(
                            progressFraction = currentProgressFraction,
                            modifier = Modifier.fillMaxWidth().testTag("mini_player_progress_bar")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tappable Area to expand Full Screen Player (Artwork + Title/Artist)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showFullPlayerSheet = true }
                                    .testTag("mini_player_expand_area"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail / Note
                                if (displayThumbnail.isNotEmpty()) {
                                    AsyncImage(
                                        model = displayThumbnail,
                                        contentDescription = "Artwork",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isOfflineActive) Color(0xFF1B382B) else Color(0xFF242424),
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isOfflineActive) Icons.Default.FileDownloadDone else Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = if (isOfflineActive) Color(0xFF4CAF50) else Color.Gray,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Title & info
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isOfflineActive) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF2E7D32),
                                                modifier = Modifier.padding(end = 4.dp)
                                            ) {
                                                Text(
                                                    text = "OFFLINE",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = displayTitle,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.basicMarquee()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = displayArtist,
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Controls Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                // Add to Playlist button
                                IconButton(
                                    onClick = { showAddToPlaylistDialog = true },
                                    modifier = Modifier.size(40.dp).testTag("mini_player_add_playlist")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlaylistAdd,
                                        contentDescription = "Add to playlist",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Previous Track
                                IconButton(
                                    onClick = {
                                        if (isOfflineActive) {
                                            service?.internalPlayer?.seekTo(0)
                                        } else {
                                            JsMediaBridge.triggerPrevious(internalWebView)
                                        }
                                    },
                                    modifier = Modifier.size(42.dp).testTag("mini_player_prev")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SkipPrevious,
                                        contentDescription = "Previous Track",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // Responsive Animated Play / Pause FAB
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFE50914),
                                    modifier = Modifier
                                        .size(44.dp)
                                        .scale(miniPlayPauseScale)
                                        .clickable(
                                            interactionSource = playPauseInteractionSource,
                                            indication = null,
                                            onClick = onTogglePlayPause
                                        )
                                        .testTag("mini_player_play_pause")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AnimatedContent(
                                            targetState = isPlayingNow,
                                            transitionSpec = {
                                                (fadeIn(animationSpec = tween(140)) + scaleIn(initialScale = 0.8f))
                                                    .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.8f))
                                            },
                                            label = "mini_play_pause_icon_morph"
                                        ) { isPlaying ->
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Pause" else "Play",
                                                tint = Color.White,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                }

                                // Next Track
                                IconButton(
                                    onClick = {
                                        if (isOfflineActive) {
                                            viewModel.playNextInQueue(service)
                                        } else {
                                            JsMediaBridge.triggerNext(internalWebView)
                                        }
                                    },
                                    modifier = Modifier.size(42.dp).testTag("mini_player_next")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SkipNext,
                                        contentDescription = "Next Track",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

                // Main Bottom Navigation Bar
                NavigationBar(
                    containerColor = Color(0xFF141414),
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == MainNavTab.STREAM,
                        onClick = { currentTab = MainNavTab.STREAM },
                        icon = { Icon(Icons.Default.Headphones, contentDescription = "Stream") },
                        label = { Text("Stream", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color(0xFFE50914),
                            indicatorColor = Color(0xFFE50914),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_stream")
                    )

                    NavigationBarItem(
                        selected = currentTab == MainNavTab.PLAYLISTS,
                        onClick = { currentTab = MainNavTab.PLAYLISTS },
                        icon = { Icon(Icons.Default.QueueMusic, contentDescription = "Playlists") },
                        label = { Text("Playlists", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color(0xFFE50914),
                            indicatorColor = Color(0xFFE50914),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_playlists")
                    )

                    NavigationBarItem(
                        selected = currentTab == MainNavTab.DOWNLOADS,
                        onClick = { currentTab = MainNavTab.DOWNLOADS },
                        icon = { Icon(Icons.Default.DownloadDone, contentDescription = "Downloads") },
                        label = { Text("Downloads", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color(0xFFE50914),
                            indicatorColor = Color(0xFFE50914),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        ),
                        modifier = Modifier.testTag("nav_downloads")
                    )
                }
            }
        }
    }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Keep WebView alive in background so audio doesn't stop when browsing playlists/downloads
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (currentTab != MainNavTab.STREAM) {
                            Modifier.alpha(0f).zIndex(-1f)
                        } else {
                            Modifier.alpha(1f).zIndex(1f)
                        }
                    )
            ) {
                if (isRendererCrashed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF141414)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                tint = Color(0xFFE50914),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "Stream Paused",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "The web renderer process paused to conserve resources. Tap below to reload the stream.",
                                color = Color.LightGray,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    consecutiveCrashes = 0
                                    isRendererCrashed = false
                                    webViewRecreateKey++
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Resume Stream", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    key(webViewRecreateKey) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                YMusicCustomWebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        mediaPlaybackRequiresUserGesture = false
                                        javaScriptCanOpenWindowsAutomatically = true
                                        userAgentString = if (streamMode == ActiveStreamMode.MUSIC) {
                                            AdBlockEngine.DESKTOP_USER_AGENT
                                        } else {
                                            AdBlockEngine.MOBILE_USER_AGENT
                                        }
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        allowFileAccess = false
                                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    }

                                    try {
                                        CookieManager.getInstance().setAcceptCookie(true)
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                    try {
                                        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                                this,
                                                AdBlockEngine.BACKGROUND_PLAYBACK_JS,
                                                setOf("*")
                                            )
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            pageProgress = newProgress
                                            canGoBack = view?.canGoBack() ?: false
                                            canGoForward = view?.canGoForward() ?: false
                                            if (newProgress > 40) {
                                                consecutiveCrashes = 0
                                                isRendererCrashed = false
                                            }
                                        }

                                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                            try {
                                                (view?.parent as? ViewGroup)?.removeView(view)
                                                customVideoView = view
                                                customViewCallback = callback
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }

                                        override fun onHideCustomView() {
                                            try {
                                                customViewCallback?.onCustomViewHidden()
                                                customVideoView = null
                                                customViewCallback = null
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    }

                                    webViewClient = YMusicWebViewClient(
                                        context = ctx,
                                        onAdBlocked = { count ->
                                            adsBlockedCount = count
                                        },
                                        onRendererCrashed = {
                                            val now = System.currentTimeMillis()
                                            if (now - lastCrashTime < 8000L) {
                                                consecutiveCrashes++
                                            } else {
                                                consecutiveCrashes = 1
                                            }
                                            lastCrashTime = now

                                            android.util.Log.w("YMusic", "WebView renderer exited. Consecutive: $consecutiveCrashes")
                                            if (consecutiveCrashes >= 5) {
                                                isRendererCrashed = true
                                            } else {
                                                coroutineScope.launch {
                                                    delay(600)
                                                    webViewRecreateKey++
                                                }
                                            }
                                        }
                                    )

                                    val bridge = JsMediaBridge(
                                        onTrackChanged = { track ->
                                            activeTrack = track
                                            if (track.isPlaying || (track.title.isNotEmpty() && currentTab == MainNavTab.STREAM)) {
                                                if (service?.isPlayingOffline == true) {
                                                    service.stopOfflinePlayback()
                                                }
                                            }
                                            service?.syncMetadata(track, force = track.isPlaying || (track.title.isNotEmpty() && currentTab == MainNavTab.STREAM))
                                        },
                                        onPlaybackStateChanged = { isPlaying ->
                                            activeTrack = activeTrack.copy(isPlaying = isPlaying)
                                            if (isPlaying && service?.isPlayingOffline == true) {
                                                service.stopOfflinePlayback()
                                            }
                                            service?.updatePlaybackState(isPlaying)
                                        }
                                    )
                                    addJavascriptInterface(bridge, JsMediaBridge.JAVASCRIPT_NAME)

                                    loadUrl(streamMode.url)
                                    internalWebView = this
                                    onWebViewCreated(this)
                                }
                            },
                            update = { wv ->
                                internalWebView = wv
                            }
                        )
                    }
                }
            }

            // Other Tab Views
            when (currentTab) {
                MainNavTab.PLAYLISTS -> {
                    PlaylistsView(
                        viewModel = viewModel,
                        service = service,
                        onPlayOnlineTrack = { url, _ ->
                            service?.stopOfflinePlayback()
                            currentTab = MainNavTab.STREAM
                            internalWebView?.loadUrl(url)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MainNavTab.DOWNLOADS -> {
                    DownloadsView(
                        viewModel = viewModel,
                        service = service,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MainNavTab.STREAM -> {
                    // Shown in persistent Box above
                }
            }
        }
    }

    // Fullscreen Custom Video Overlay
    if (customVideoView != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .zIndex(99999f)
        ) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        (customVideoView?.parent as? ViewGroup)?.removeView(customVideoView)
                        addView(
                            customVideoView,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showFullPlayerSheet) {
        NowPlayingSheet(
            title = displayTitle,
            artist = displayArtist,
            thumbnailUrl = displayThumbnail,
            isPlaying = isPlayingNow,
            isOffline = isTrackDownloaded,
            currentTimeText = currentPositionText,
            durationText = durationText,
            progressFraction = currentProgressFraction,
            sleepTimerSeconds = sleepTimerSeconds,
            isDownloading = currentDownloadProgress != null,
            downloadProgress = currentDownloadProgress,
            onPlayPauseToggle = onTogglePlayPause,
            onSkipNext = {
                if (isOfflineActive) viewModel.playNextInQueue(service)
                else JsMediaBridge.triggerNext(internalWebView)
            },
            onSkipPrevious = {
                if (isOfflineActive) service?.internalPlayer?.seekTo(0)
                else JsMediaBridge.triggerPrevious(internalWebView)
            },
            onSeekFraction = onSeekFraction,
            onSeekRelative = onSeekRelative,
            onOpenSleepTimer = { showSleepTimerDialog = true },
            onAddToPlaylist = { showAddToPlaylistDialog = true },
            onDownloadTrack = {
                val titleToDownload = activeTrack.title.ifEmpty { displayTitle }
                if (titleToDownload.isNotEmpty() && titleToDownload != "MeTube Stream" && titleToDownload != "Offline Track") {
                    if (activeTrack.duration.equals("LIVE", ignoreCase = true)) {
                        Toast.makeText(context, "Live streams cannot be downloaded until stream ends", Toast.LENGTH_SHORT).show()
                    } else {
                        showDownloadFormatDialog = true
                    }
                } else {
                    Toast.makeText(context, "Play a track first to download", Toast.LENGTH_SHORT).show()
                }
            },
            onEnterPiP = onEnterPiP,
            onDismiss = { showFullPlayerSheet = false }
        )
    }

    if (showDownloadFormatDialog) {
        val titleToDownload = activeTrack.title.ifEmpty { displayTitle }
        val artistToDownload = activeTrack.artist.ifEmpty { displayArtist }
        val matchingDownload = completedDownloads.firstOrNull {
            it.title.equals(titleToDownload, ignoreCase = true) || it.title.startsWith(titleToDownload, ignoreCase = true)
        }
        val resolvedSourceUrl = activeTrack.url.ifEmpty {
            matchingDownload?.sourceUrl?.ifEmpty { internalWebView?.url ?: "" } ?: (internalWebView?.url ?: "")
        }

        DownloadFormatDialog(
            trackTitle = titleToDownload,
            trackArtist = artistToDownload,
            defaultIsVideo = (streamMode == ActiveStreamMode.VIDEOS),
            downloadedOptions = downloadedFormatsForCurrentTrack,
            onDismiss = { showDownloadFormatDialog = false },
            onConfirmDownload = { option ->
                val vid = StreamUrlResolver.extractVideoId(resolvedSourceUrl)
                    ?: StreamUrlResolver.extractVideoId(internalWebView?.url ?: "")
                if (!vid.isNullOrEmpty()) {
                    StreamCaptureRegistry.setActiveVideo(vid, titleToDownload)
                }
                Toast.makeText(
                    context,
                    if (option.isVideo) "Downloading video \"$titleToDownload\" (${option.qualityBadge}) in background..."
                    else "Downloading audio \"$titleToDownload\" (${option.qualityBadge}) in background...",
                    Toast.LENGTH_SHORT
                ).show()

                viewModel.downloadTrack(
                    title = titleToDownload,
                    artist = artistToDownload,
                    duration = activeTrack.duration,
                    sourceUrl = resolvedSourceUrl,
                    thumbnailUrl = displayThumbnail,
                    directStreamUrl = activeTrack.streamUrl,
                    formatOption = option,
                    isVideo = option.isVideo
                ) { success ->
                    if (success) {
                        Toast.makeText(
                            context,
                            if (option.isVideo) "Saved video \"$titleToDownload\" (${option.qualityBadge}) with audio to Gallery & Storage!"
                            else "Saved song \"$titleToDownload\" (${option.qualityBadge}) to Music Library & Storage!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(context, "Download failed - please play the track to ensure stream is active.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            remainingSeconds = sleepTimerSeconds,
            onSetTimerMinutes = { mins -> service?.setSleepTimer(mins) },
            onCancelTimer = { service?.cancelSleepTimer() },
            onDismiss = { showSleepTimerDialog = false }
        )
    }

    if (showAddToPlaylistDialog) {
        val currentTrackData = activeTrack
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showAddToPlaylistDialog = false },
            onSelectPlaylist = { playlistId ->
                if (currentTrackData.title.isNotEmpty()) {
                    viewModel.addTrackToPlaylist(playlistId, currentTrackData)
                    Toast.makeText(context, "Added to playlist!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No track playing to add", Toast.LENGTH_SHORT).show()
                }
                showAddToPlaylistDialog = false
            },
            onCreateNewPlaylist = { name ->
                viewModel.createPlaylist(name) { newId ->
                    if (currentTrackData.title.isNotEmpty()) {
                        viewModel.addTrackToPlaylist(newId, currentTrackData)
                    }
                }
                Toast.makeText(context, "Created and added to playlist!", Toast.LENGTH_SHORT).show()
                showAddToPlaylistDialog = false
            }
        )
    }
    }
}
