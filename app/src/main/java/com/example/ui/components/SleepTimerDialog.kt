package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SleepTimerDialog(
    remainingSeconds: Int?,
    onSetTimerMinutes: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(5, 10, 15, 30, 45, 60)
    var showCustomInput by remember { mutableStateOf(false) }
    var customMinutesText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF2C223E),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = null,
                            tint = Color(0xFFBB86FC),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    text = "Sleep Timer",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // If timer is already running: show countdown
                if (remainingSeconds != null && remainingSeconds > 0) {
                    val mins = remainingSeconds / 60
                    val secs = remainingSeconds % 60
                    val formatted = String.format("%d:%02d", mins, secs)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E1729),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBB86FC).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Audio will stop in",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                            Text(
                                text = formatted,
                                color = Color(0xFFBB86FC),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("sleep_timer_countdown_display")
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val newMinutes = (remainingSeconds / 60) + 5
                                        onSetTimerMinutes(newMinutes)
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFBB86FC)),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(
                                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFBB86FC).copy(alpha = 0.5f))
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("+5 min", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        onCancelTimer()
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("cancel_sleep_timer_button")
                                ) {
                                    Text("Turn Off", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Choose a duration. Playback will smoothly pause when the timer ends.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }

                Text(
                    text = "Select duration:",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                // Preset options grid / list
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rows = presets.chunked(3)
                    for (row in rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (minutes in row) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF262626),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clickable {
                                            onSetTimerMinutes(minutes)
                                            onDismiss()
                                        }
                                        .testTag("sleep_timer_preset_$minutes")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "$minutes min",
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom input
                if (!showCustomInput) {
                    TextButton(
                        onClick = { showCustomInput = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Custom minutes...", color = Color(0xFFBB86FC), fontSize = 13.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customMinutesText,
                            onValueChange = { customMinutesText = it.filter { ch -> ch.isDigit() }.take(3) },
                            placeholder = { Text("Minutes", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("sleep_timer_custom_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFBB86FC),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )

                        Button(
                            onClick = {
                                val mins = customMinutesText.toIntOrNull()
                                if (mins != null && mins > 0) {
                                    onSetTimerMinutes(mins)
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(50.dp).testTag("set_custom_sleep_timer_button")
                        ) {
                            Text("Set", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF181818),
        shape = RoundedCornerShape(20.dp)
    )
}
