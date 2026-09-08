package com.ameer.appradiore.feature.livelog.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogEntry
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.ui.theme.AppRadioRETheme

@Composable
fun LogItemCard(
    entry: LogEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dirColor, dirText) = when (entry.direction) {
        LogDirection.INCOMING -> Color(0xFF00ACC1) to "◄ RX"
        LogDirection.OUTGOING -> Color(0xFFFF7043) to "► TX"
        LogDirection.INTERNAL -> Color(0xFF7E57C2) to "● SYS"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = dirColor.copy(alpha = 0.15f),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = dirText,
                    color = dirColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            Text(
                text = entry.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 6.dp)
            )

            Surface(
                shape = RoundedCornerShape(3.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = entry.protocol.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.rawHex.isNotBlank()) {
                    Text(
                        text = entry.rawHex,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LogItemCardPreview() {
    AppRadioRETheme {
        LogItemCard(
            entry = LogEntry(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "AuthResponse (result=OK, version=3.1)",
                rawHex = "9F 02 00 00 00 03 00 01 02 9F 03",
                details = "AuthResponse(result=0, majorVersion=3, minorVersion=1)"
            ),
            onClick = {}
        )
    }
}
