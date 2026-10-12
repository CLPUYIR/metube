package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Interactive SeekBar / Slider to track and manually scrub through audio playback.
 */
@Composable
fun PlaybackSeekBar(
    currentPositionText: String,
    durationText: String,
    progressFraction: Float,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val displayFraction = if (isDragging) dragFraction else progressFraction.coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = displayFraction,
            onValueChange = { newValue ->
                isDragging = true
                dragFraction = newValue
            },
            onValueChangeFinished = {
                onSeekFraction(dragFraction)
                isDragging = false
            },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFFE50914),
                inactiveTrackColor = Color(0xFF333333),
                disabledThumbColor = Color.Gray,
                disabledActiveTrackColor = Color(0xFF662222),
                disabledInactiveTrackColor = Color(0xFF222222)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .testTag("playback_progress_slider")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentPositionText.ifEmpty { "0:00" },
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("playback_current_time")
            )

            Text(
                text = durationText.ifEmpty { "0:00" },
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("playback_duration_time")
            )
        }
    }
}

/**
 * Slim progress indicator displayed along the top edge of the mini-player.
 */
@Composable
fun MiniPlaybackProgressBar(
    progressFraction: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = progressFraction.coerceIn(0f, 1f))
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF00E5FF),
                            Color(0xFF2979FF),
                            Color(0xFFE040FB)
                        )
                    )
                )
                .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
        )
    }
}
