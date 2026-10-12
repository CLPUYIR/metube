package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    title: String,
    artist: String,
    thumbnailUrl: String,
    isPlaying: Boolean,
    isOffline: Boolean,
    currentTimeText: String,
    durationText: String,
    progressFraction: Float,
    sleepTimerSeconds: Int?,
    isDownloading: Boolean = false,
    downloadProgress: Int? = null,
    onPlayPauseToggle: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSeekRelative: (seconds: Int) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownloadTrack: () -> Unit,
    onEnterPiP: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var isLiked by remember { mutableStateOf(false) }
    var isShuffleActive by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(0) } // 0 = off, 1 = all, 2 = one
    var isLocallyDownloading by remember { mutableStateOf(false) }

    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            isLocallyDownloading = false
        }
    }
    val effectiveDownloading = isDownloading || isLocallyDownloading

    val playPauseInteractionSource = remember { MutableInteractionSource() }
    val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlayPausePressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "play_pause_button_scale"
    )

    // Ambient glow rotation while playing
    val infiniteTransition = rememberInfiniteTransition(label = "disc_rotation")
    val ambientAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambient_angle"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF13091B),
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = Color(0xFFB388FF).copy(alpha = 0.45f),
                    modifier = Modifier.size(width = 38.dp, height = 4.dp)
                ) {}
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF280E38),
                            Color(0xFF160921),
                            Color(0xFF0D0414)
                        )
                    )
                )
        ) {
            // Ambient colored light orb in top right / center
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 60.dp, y = (-40).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF9C27B0).copy(alpha = 0.35f),
                                Color(0xFFE040FB).copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header Row: Collapse button, Title, Heart / Options
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("full_player_collapse_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse Player",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Mode Pill
                    val isLiveStream = !isOffline && durationText.equals("LIVE", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (isOffline) Color(0xFF69F0AE) else if (isLiveStream) Color(0xFFE50914) else Color(0xFFE040FB),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = if (isOffline) "OFFLINE" else if (isLiveStream) "LIVE STREAM" else "STREAMING",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Picture in picture button
                        IconButton(
                            onClick = onEnterPiP,
                            modifier = Modifier.size(36.dp).testTag("full_player_pip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Picture in picture",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Sleep Timer button with badge
                        IconButton(
                            onClick = onOpenSleepTimer,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("full_player_sleep_timer_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (sleepTimerSeconds != null && sleepTimerSeconds > 0) {
                                        val mins = (sleepTimerSeconds + 59) / 60
                                        Badge(
                                            containerColor = Color(0xFFB388FF),
                                            contentColor = Color.Black
                                        ) {
                                            Text("${mins}m", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bedtime,
                                    contentDescription = "Sleep Timer",
                                    tint = if (sleepTimerSeconds != null) Color(0xFFB388FF) else Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Title & Artist on Left + Like Heart on Right (matching reference style)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.ifEmpty { "MeTube" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = artist.ifEmpty { if (isOffline) "Local Storage" else "Streaming Audio" },
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Like / Heart Outline & Filled
                    IconButton(
                        onClick = { isLiked = !isLiked },
                        modifier = Modifier
                            .size(46.dp)
                            .testTag("full_player_like_button")
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Like track",
                            tint = if (isLiked) Color(0xFFFF4081) else Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Reference Circular Glass Ring + Album Disc
                Box(
                    modifier = Modifier
                        .size(270.dp)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer Circular Track / Radial Ring with Progress Arc
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val touch = change.position
                                    val angleRad = atan2(touch.y - center.y, touch.x - center.x)
                                    var deg = Math.toDegrees(angleRad.toDouble()).toFloat()
                                    // Map starting from bottom-left (135°) to bottom-right (45°) or full 360°
                                    deg = (deg + 90f + 360f) % 360f
                                    val fraction = (deg / 360f).coerceIn(0f, 1f)
                                    onSeekFraction(fraction)
                                }
                            }
                    ) {
                        val strokeWidth = 5.dp.toPx()
                        val ringDiameter = size.minDimension - strokeWidth - 16.dp.toPx()
                        val topLeft = Offset((size.width - ringDiameter) / 2f, (size.height - ringDiameter) / 2f)
                        val arcSize = Size(ringDiameter, ringDiameter)

                        // Outer translucent glass border
                        drawArc(
                            color = Color.White.copy(alpha = 0.12f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Active Progress Arc (Cyan/Electric Blue glow matching the uploaded design)
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF),
                                    Color(0xFF2979FF),
                                    Color(0xFFE040FB),
                                    Color(0xFF00E5FF)
                                )
                            ),
                            startAngle = -90f,
                            sweepAngle = (progressFraction * 360f).coerceIn(0f, 360f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth + 1.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Rotating ambient disc / glass background
                    Box(
                        modifier = Modifier
                            .size(196.dp)
                            .rotate(if (isPlaying) ambientAngle else 0f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFBA68C8).copy(alpha = 0.25f),
                                        Color(0xFF311B92).copy(alpha = 0.4f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // Central Circle Artwork with rounded glass border
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF1F122B),
                        shadowElevation = 16.dp,
                        border = androidx.compose.foundation.BorderStroke(3.dp, Color.White.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .size(180.dp)
                            .testTag("full_player_artwork")
                    ) {
                        if (thumbnailUrl.isNotEmpty()) {
                            AsyncImage(
                                model = thumbnailUrl,
                                contentDescription = "Album Artwork",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0xFF6A1B9A),
                                                Color(0xFF311B92)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isOffline) Icons.Default.FileDownloadDone else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(68.dp)
                                )
                            }
                        }
                    }

                    // Little current position pill overlay inside bottom edge of circular ring
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-14).dp)
                    ) {
                        Text(
                            text = currentTimeText.ifEmpty { "0:00" },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }

                // Time labels row (underneath disc)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = currentTimeText.ifEmpty { "0:00" },
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = durationText.ifEmpty { "0:00" },
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Playback Controls Row: Shuffle | Prev | Play/Pause | Next | Repeat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { isShuffleActive = !isShuffleActive },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffleActive) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Previous
                    IconButton(
                        onClick = onSkipPrevious,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("full_player_prev")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Central Play / Pause with glowing glass aura
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .size(64.dp)
                            .scale(playPauseScale)
                            .clickable(
                                interactionSource = playPauseInteractionSource,
                                indication = null,
                                onClick = onPlayPauseToggle
                            )
                            .testTag("full_player_play_pause")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AnimatedContent(
                                targetState = isPlaying,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(140)) + scaleIn(initialScale = 0.8f))
                                        .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.8f))
                                },
                                label = "play_pause_icon_morph"
                            ) { playingState ->
                                Icon(
                                    imageVector = if (playingState) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playingState) "Pause" else "Play",
                                    tint = Color(0xFF140722),
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }

                    // Next
                    IconButton(
                        onClick = onSkipNext,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("full_player_next")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Repeat toggle
                    IconButton(
                        onClick = { repeatMode = (repeatMode + 1) % 3 },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode > 0) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Bottom Action Buttons: Add to Playlist & Download with Instant State Feedback
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Add to Playlist Button
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clickable { onAddToPlaylist() }
                            .testTag("full_player_add_playlist_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlaylistAdd,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Playlist",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Download Button with active spinner and feedback
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (isOffline) Color(0xFF1B5E20).copy(alpha = 0.4f)
                               else if (effectiveDownloading) Color(0xFF4A148C).copy(alpha = 0.4f)
                               else Color(0xFF7B1FA2).copy(alpha = 0.25f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isOffline) Color(0xFF69F0AE).copy(alpha = 0.6f)
                            else if (effectiveDownloading) Color(0xFFE040FB)
                            else Color(0xFFBA68C8).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clickable(enabled = !effectiveDownloading) {
                                onDownloadTrack()
                            }
                            .testTag("full_player_download_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (effectiveDownloading) {
                                CircularProgressIndicator(
                                    progress = {
                                        if (downloadProgress != null && downloadProgress > 0) {
                                            downloadProgress / 100f
                                        } else 0.5f
                                    },
                                    color = Color(0xFFE040FB),
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (downloadProgress != null) "Saving ${downloadProgress}%" else "Saving...",
                                    color = Color(0xFFE040FB),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (isOffline) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF69F0AE),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Downloaded +",
                                    color = Color(0xFF69F0AE),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Download",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
