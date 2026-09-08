package com.ameer.appradiore.feature.livelog.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.StereoSpecs
import com.ameer.appradiore.core.usb.UsbConnectionState
import com.ameer.appradiore.ui.theme.AppRadioRETheme

@Composable
fun ConnectionStatusCard(
    connectionState: UsbConnectionState,
    handshakeStep: HandshakeStep,
    stereoSpecs: StereoSpecs,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SpecBadge(label = "Display", value = "${stereoSpecs.width}x${stereoSpecs.height}")
                    SpecBadge(label = "Model", value = "0x${String.format("%04X", stereoSpecs.modelId)}")
                    SpecBadge(label = "Touch", value = "${stereoSpecs.pointerCount} pts")
                    SpecBadge(label = "GPS", value = if (stereoSpecs.hasGps) "Yes" else "No")
                    SpecBadge(label = "Brake", value = if (stereoSpecs.isParkingBrakeOn) "ON" else "OFF")
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
            )
        )
    }
}
