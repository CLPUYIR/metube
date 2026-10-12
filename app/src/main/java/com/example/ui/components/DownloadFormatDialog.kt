package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class DownloadFormatOption(
    val title: String,
    val subtitle: String,
    val isVideo: Boolean,
    val format: String,
    val qualityBadge: String,
    val icon: ImageVector,
    val accentColor: Color
) {
    AUDIO_M4A(
        title = "M4A Audio (High Quality)",
        subtitle = "AAC Stereo • 160-256 kbps • Best for Music",
        isVideo = false,
        format = "m4a",
        qualityBadge = "HQ",
        icon = Icons.Default.Audiotrack,
        accentColor = Color(0xFF00E5FF)
    ),
    AUDIO_MP3(
        title = "MP3 Audio (Standard)",
        subtitle = "MP3 Stereo • Universal Player Compatibility",
        isVideo = false,
        format = "mp3",
        qualityBadge = "MP3",
        icon = Icons.Default.MusicNote,
        accentColor = Color(0xFFE040FB)
    ),
    VIDEO_720P(
        title = "MP4 Video (720p HD)",
        subtitle = "High Definition Video + Clear Audio • Gallery Ready",
        isVideo = true,
        format = "mp4",
        qualityBadge = "HD 720p",
        icon = Icons.Default.HighQuality,
        accentColor = Color(0xFFFF5252)
    ),
    VIDEO_360P(
        title = "MP4 Video (360p Standard)",
        subtitle = "Standard Definition Video • Smaller File Size",
        isVideo = true,
        format = "mp4",
        qualityBadge = "360p",
        icon = Icons.Default.Videocam,
        accentColor = Color(0xFFFFAB00)
    )
}

/**
 * YMusic-style format and quality selector dialog that lets users choose
 * between Audio (M4A/MP3) and Video (720p HD/360p SD) before downloading.
 */
@Composable
fun DownloadFormatDialog(
    trackTitle: String,
    trackArtist: String,
    defaultIsVideo: Boolean = false,
    downloadedOptions: Set<DownloadFormatOption> = emptySet(),
    onDismiss: () -> Unit,
    onConfirmDownload: (option: DownloadFormatOption) -> Unit
) {
    var selectedOption by remember {
        mutableStateOf(
            if (defaultIsVideo) {
                if (downloadedOptions.contains(DownloadFormatOption.VIDEO_720P) && !downloadedOptions.contains(DownloadFormatOption.VIDEO_360P)) {
                    DownloadFormatOption.VIDEO_360P
                } else {
                    DownloadFormatOption.VIDEO_720P
                }
            } else {
                if (downloadedOptions.contains(DownloadFormatOption.AUDIO_M4A)) {
                    if (!downloadedOptions.contains(DownloadFormatOption.VIDEO_720P)) DownloadFormatOption.VIDEO_720P
                    else if (!downloadedOptions.contains(DownloadFormatOption.AUDIO_MP3)) DownloadFormatOption.AUDIO_MP3
                    else DownloadFormatOption.AUDIO_M4A
                } else {
                    DownloadFormatOption.AUDIO_M4A
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF8E24AA), Color(0xFFE040FB))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Download Options",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Choose format and quality",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = trackTitle.ifEmpty { "Selected Track" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (trackArtist.isNotEmpty()) {
                    Text(
                        text = trackArtist,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "SELECT FORMAT & QUALITY",
                    color = Color(0xFFB388FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )

                DownloadFormatOption.values().forEach { option ->
                    val isSelected = (selectedOption == option)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) Color(0xFF2E1A47) else Color(0xFF1E1E26),
                        border = BorderStroke(
                            1.5.dp,
                            if (isSelected) option.accentColor else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedOption = option }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        option.accentColor.copy(alpha = if (isSelected) 0.25f else 0.12f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    tint = option.accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = option.title,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = option.accentColor.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = option.qualityBadge,
                                            color = option.accentColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }

                                    if (downloadedOptions.contains(option)) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF1B5E20)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color(0xFF69F0AE),
                                                    modifier = Modifier.size(10.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = "SAVED",
                                                    color = Color(0xFF69F0AE),
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                Text(
                                    text = option.subtitle,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 11.sp
                                )
                            }

                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(option.accentColor, shape = CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val isCurrentSaved = downloadedOptions.contains(selectedOption)
            Button(
                onClick = {
                    onConfirmDownload(selectedOption)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = selectedOption.accentColor
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("confirm_format_download_btn")
            ) {
                Text(
                    text = if (isCurrentSaved) {
                        if (selectedOption.isVideo) "Re-download Video" else "Re-download Audio"
                    } else {
                        if (selectedOption.isVideo) "Download Video" else "Download Audio"
                    },
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.6f))
            }
        },
        containerColor = Color(0xFF140D1E),
        shape = RoundedCornerShape(20.dp)
    )
}
