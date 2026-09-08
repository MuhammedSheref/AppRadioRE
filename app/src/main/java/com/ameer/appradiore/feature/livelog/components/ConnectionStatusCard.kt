package com.ameer.appradiore.feature.livelog.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.StereoSpecs
import com.ameer.appradiore.core.usb.UsbConnectionState
import com.ameer.appradiore.ui.theme.AppRadioRETheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionStatusCard(
    connectionState: UsbConnectionState,
    handshakeStep: HandshakeStep,
    stereoSpecs: StereoSpecs,
    isStreaming: Boolean = false,
    streamFps: Int = 0,
    streamFramesSent: Long = 0L,
    streamBytesSent: Long = 0L,
    onToggleVideoStream: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (statusColor, statusText) = when (connectionState) {
                        is UsbConnectionState.Connected -> Color(0xFF4CAF50) to "CONNECTED: ${connectionState.accessory.model}"
                        is UsbConnectionState.Connecting -> Color(0xFFFF9800) to "CONNECTING..."
                        is UsbConnectionState.PermissionRequired -> Color(0xFFFF9800) to "PERMISSION REQUIRED"
                        is UsbConnectionState.Error -> Color(0xFFF44336) to "ERROR: ${connectionState.message}"
                        is UsbConnectionState.Disconnected -> Color(0xFF9E9E9E) to "DISCONNECTED"
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = handshakeStep.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (handshakeStep == HandshakeStep.CONNECTED_READY) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (handshakeStep != HandshakeStep.DISCONNECTED &&
                handshakeStep != HandshakeStep.CONNECTED_READY &&
                handshakeStep != HandshakeStep.FAILED
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (stereoSpecs.isIdentified) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SpecBadge(label = "Screen", value = "${stereoSpecs.width}x${stereoSpecs.height}")
                    if (stereoSpecs.dpi > 0) {
                        SpecBadge(label = "DPI", value = "${stereoSpecs.dpi}")
                    }
                    if (stereoSpecs.modelId != 0.toShort()) {
                        SpecBadge(label = "Model", value = "0x${String.format("%04X", stereoSpecs.modelId)}")
                    }
                    SpecBadge(label = "Touch", value = "${stereoSpecs.pointerCount} pts")
                    if (stereoSpecs.hasGps) {
                        SpecBadge(label = "GPS", value = "Yes")
                    }
                    if (stereoSpecs.isParkingBrakeOn) {
                        SpecBadge(label = "Brake", value = "ON")
                    }
                    if (stereoSpecs.isHdmiConnected) {
                        SpecBadge(label = "HDMI", value = "Connected")
                    }
                }
            }

            // Video Streaming Section (Visible when handshake is ready)
            if (handshakeStep == HandshakeStep.CONNECTED_READY || stereoSpecs.isReadyForVideo) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isStreaming) {
                        val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseAlpha"
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .alpha(pulseAlpha)
                                    .background(Color(0xFF00E676), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "STREAMING (H.264 @ Port 12346)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676)
                            )
                        }

                        Button(
                            onClick = { onToggleVideoStream?.invoke(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STOP", fontSize = 12.sp)
                        }
                    } else {
                        Column {
                            Text(
                                text = "Pioneer Mirroring Video Ready",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Stream 800x480 test pattern to head unit",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { onToggleVideoStream?.invoke(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STREAM VIDEO", fontSize = 12.sp)
                        }
                    }
                }

                if (isStreaming) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpecBadge(label = "FPS", value = "$streamFps")
                        SpecBadge(label = "Frames", value = "$streamFramesSent")
                        val mbSent = streamBytesSent / (1024f * 1024f)
                        SpecBadge(label = "Sent", value = String.format(Locale.US, "%.1f MB", mbSent))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionStatusCardPreview() {
    AppRadioRETheme {
        ConnectionStatusCard(
            connectionState = UsbConnectionState.Disconnected,
            handshakeStep = HandshakeStep.CONNECTED_READY,
            stereoSpecs = StereoSpecs(
                width = 800,
                height = 480,
                modelId = 0x0112,
                pointerCount = 2,
                hasGps = true,
                isParkingBrakeOn = true,
                isReadyForVideo = true
            ),
            isStreaming = true,
            streamFps = 30,
            streamFramesSent = 120,
            streamBytesSent = 5242880
        )
    }
}
