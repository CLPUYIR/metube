package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.MediaPlaybackService
import com.example.data.downloader.OfflineDownloadManager
import com.example.data.downloader.OfflineDownloadService
import com.example.data.local.entities.DownloadEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.ui.YMusicViewModel
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun DownloadsView(
    viewModel: YMusicViewModel,
    service: MediaPlaybackService?,
    modifier: Modifier = Modifier
) {
    val downloads by viewModel.downloads.collectAsState()
    val totalStorageBytes by viewModel.totalStorageUsed.collectAsState()
    val downloadProgress by viewModel.currentDownloadProgress.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val activeTask by OfflineDownloadService.currentDownload.collectAsState()

    val completedDownloads = remember(downloads) {
        downloads.filter { it.status.equals("COMPLETED", ignoreCase = true) }
    }
    val isDownloadingActive = downloadProgress != null || activeTask != null
    val effectivePercent = downloadProgress ?: activeTask?.progress ?: 10

    var showClearDialog by remember { mutableStateOf(false) }
    var showUrlDownloadDialog by remember { mutableStateOf(false) }
    var manualDownloadUrl by remember { mutableStateOf("") }
    var isVideoDownloadMode by remember { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<DownloadEntity?>(null) }
    var activeVideoToPlay by remember { mutableStateOf<DownloadEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Offline Downloads",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${completedDownloads.size} tracks saved for offline playback",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showUrlDownloadDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50)),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4CAF50).copy(alpha = 0.5f))),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("download_url_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLink,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Download URL", fontSize = 12.sp)
                }

                if (downloads.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFF5252).copy(alpha = 0.5f))),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("clear_downloads_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Storage Management Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1C1C1C),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SdCard,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Storage Used",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    Text(
                        text = OfflineDownloadManager.formatFileSize(totalStorageBytes),
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Storage bar
                LinearProgressIndicator(
                    progress = {
                        val used = totalStorageBytes.toFloat()
                        val total = used + viewModel.getFreeStorage().toFloat()
                        if (total > 0f) (used / total).coerceIn(0.01f, 1f) else 0.05f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF4CAF50),
                    trackColor = Color(0xFF2C2C2C)
                )

                Spacer(modifier = Modifier.height(6.dp))

                val freeSpaceMb = viewModel.getFreeStorage() / (1024L * 1024L * 1024L)
                Text(
                    text = "Free device storage: ~${freeSpaceMb} GB available",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }

        // Active Download Progress Banner
        AnimatedVisibility(visible = isDownloadingActive) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E2F23),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (activeTask != null) {
                                val kind = if (activeTask?.formatOption?.isVideo == true) "video" else "audio"
                                "Downloading $kind: \"${activeTask?.title}\" (${activeTask?.formatOption?.qualityBadge})..."
                            } else {
                                "Downloading media in background..."
                            },
                            color = Color(0xFF81C784),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$effectivePercent%",
                            color = Color(0xFF81C784),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { effectivePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFF2E4032)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Downloads List
        if (completedDownloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF1E1E1E),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.DownloadDone,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Text(
                        text = "No Offline Tracks",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Tap the download icon on the player bar while\nstreaming any song to save it for offline listening.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(completedDownloads) { download ->
                    val isVideoItem = download.localFilePath.endsWith(".mp4", ignoreCase = true) ||
                            download.localFilePath.endsWith(".webm", ignoreCase = true) ||
                            download.title.contains("Video", ignoreCase = true)

                    DownloadItemRow(
                        download = download,
                        onPlay = {
                            if (isVideoItem) {
                                activeVideoToPlay = download
                            } else {
                                viewModel.playOfflineDownload(download, service)
                            }
                        },
                        onAddToPlaylist = { trackToAddToPlaylist = download },
                        onDelete = { viewModel.deleteDownload(download) }
                    )
                }
            }
        }
    }

    if (showUrlDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDownloadDialog = false },
            title = { Text("Download Song / Video URL", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter any YouTube, YouTube Music, or media link to download directly into Phone Storage & Gallery:",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = manualDownloadUrl,
                        onValueChange = { manualDownloadUrl = it },
                        placeholder = { Text("https://www.youtube.com/watch?v=...", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF444444)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("manual_download_url_input")
                    )

                    // Format selection: Audio (Song) vs Video (MP4)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !isVideoDownloadMode,
                            onClick = { isVideoDownloadMode = false },
                            label = { Text("Song (Audio • Music)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2E7D32),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        FilterChip(
                            selected = isVideoDownloadMode,
                            onClick = { isVideoDownloadMode = true },
                            label = { Text("Video (MP4 • Gallery)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1565C0),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val inputUrl = manualDownloadUrl.trim()
                        if (inputUrl.isNotEmpty()) {
                            viewModel.downloadTrack(
                                title = if (isVideoDownloadMode) "Downloaded Video" else "Downloaded Track",
                                artist = "MeTube Offline",
                                duration = "3:30",
                                sourceUrl = inputUrl,
                                thumbnailUrl = "",
                                directStreamUrl = "",
                                isVideo = isVideoDownloadMode
                            )
                            manualDownloadUrl = ""
                            showUrlDownloadDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isVideoDownloadMode) Color(0xFF1976D2) else Color(0xFF4CAF50)),
                    modifier = Modifier.testTag("manual_download_confirm_button")
                ) {
                    Text(if (isVideoDownloadMode) "Download Video" else "Download Song")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDownloadDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Delete All Downloads?", color = Color.White) },
            text = { Text("This will remove all downloaded offline tracks from your device storage to free up space.", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllDownloads()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    trackToAddToPlaylist?.let { download ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { trackToAddToPlaylist = null },
            onSelectPlaylist = { playlistId ->
                viewModel.addDownloadToPlaylist(playlistId, download)
                trackToAddToPlaylist = null
            },
            onCreateNewPlaylist = { name ->
                viewModel.createPlaylist(name) { newId ->
                    viewModel.addDownloadToPlaylist(newId, download)
                }
                trackToAddToPlaylist = null
            }
        )
    }

    activeVideoToPlay?.let { videoEntity ->
        OfflineVideoPlayerDialog(
            download = videoEntity,
            onDismiss = { activeVideoToPlay = null },
            onPauseBackgroundAudio = { service?.internalPlayer?.pause() }
        )
    }
}

@Composable
fun DownloadItemRow(
    download: DownloadEntity,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val isVideo = download.localFilePath.endsWith(".mp4", ignoreCase = true) ||
            download.localFilePath.endsWith(".webm", ignoreCase = true) ||
            download.title.contains("Video", ignoreCase = true)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1A1A1A),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .testTag("download_item_${download.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                if (download.thumbnailUrl.isNotEmpty()) {
                    AsyncImage(
                        model = download.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF262626),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (isVideo) Color(0xFF42A5F5) else Color(0xFF4CAF50),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                if (isVideo) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                    ) {
                        Text(
                            text = "MP4",
                            color = Color(0xFF64B5F6),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (download.artist.isNotEmpty()) download.artist else "Offline",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF262626)
                    ) {
                        Text(
                            text = OfflineDownloadManager.formatFileSize(download.fileSizeBytes),
                            color = Color(0xFF81C784),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isVideo) Color(0xFF0D47A1).copy(alpha = 0.35f) else Color(0xFF1B5E20).copy(alpha = 0.35f)
                    ) {
                        Text(
                            text = if (isVideo) "Gallery" else "Music",
                            color = if (isVideo) Color(0xFF90CAF9) else Color(0xFFA5D6A7),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Play button
                IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "Play Offline",
                        tint = if (isVideo) Color(0xFF42A5F5) else Color(0xFF4CAF50),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Open in Gallery / Phone Storage
                IconButton(
                    onClick = {
                        try {
                            val directFile = File(download.localFilePath)
                            val file = if (directFile.exists()) directFile else {
                                val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                                val fallback = File(musicDir, directFile.name)
                                if (fallback.exists()) fallback else directFile
                            }
                            val uri = if (file.exists()) {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                            } else {
                                android.net.Uri.parse(download.localFilePath)
                            }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, if (isVideo) "video/*" else "audio/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                if (isVideo) "Video saved in Phone Storage & Gallery!" else "Song saved in Phone Storage & Music!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open in Gallery/Phone Storage",
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Add to playlist
                IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = "Add to playlist",
                        tint = Color.LightGray,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Delete button
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreateNewPlaylist: (String) -> Unit
) {
    var showCreateSubDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Track to Playlist", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showCreateSubDialog) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("New Playlist Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFE50914)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                onCreateNewPlaylist(newPlaylistName)
                                showCreateSubDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create")
                    }
                } else {
                    OutlinedButton(
                        onClick = { showCreateSubDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE50914))
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+ Create New Playlist")
                    }
                }

                Divider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 4.dp))

                if (playlists.isEmpty()) {
                    Text("No playlists created yet.", color = Color.Gray, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists) { playlist ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF262626),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPlaylist(playlist.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QueueMusic,
                                        contentDescription = null,
                                        tint = Color(0xFFE50914),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = playlist.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1E1E1E)
    )
}
