package com.ameer.appradiore.feature.livelog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogEntry
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.core.presentation.ObserveAsEvents
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.StereoSpecs
import com.ameer.appradiore.core.usb.UsbConnectionState
import org.koin.androidx.compose.koinViewModel

@Composable
fun LiveLogRoot(
    viewModel: LiveLogViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is LiveLogEvent.ShowSnackbar -> {
                val msg = event.message.asString(context)
                scope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
            is LiveLogEvent.ShareLogs -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, event.logFileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share AppRadio Protocol Logs"))
            }
        }
    }

    LiveLogScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLogScreen(
    state: LiveLogState,
    snackbarHostState: SnackbarHostState,
    onAction: (LiveLogAction) -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll when new logs arrive if enabled
    LaunchedEffect(state.filteredLogs.size, state.autoScroll) {
        if (state.autoScroll && state.filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(state.filteredLogs.size - 1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AppRadio RE - Protocol Inspector",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pioneer AAM2 USB Live Sniffer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onAction(LiveLogAction.OnSimulateHandshakeClick) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Simulate Handshake", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = { onAction(LiveLogAction.OnExportLogsClick) },
                        enabled = !state.isExporting && state.logs.isNotEmpty()
                    ) {
                        if (state.isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Share, contentDescription = "Export & Share Logs")
                        }
                    }
                    IconButton(onClick = { onAction(LiveLogAction.OnScanUsbClick) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan USB")
                    }
                    IconButton(onClick = { onAction(LiveLogAction.OnClearLogsClick) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Connection & Handshake Status Card
            ConnectionStatusCard(
                connectionState = state.connectionState,
                handshakeStep = state.handshakeStep,
                stereoSpecs = state.stereoSpecs,
                onScanClick = { onAction(LiveLogAction.OnScanUsbClick) }
            )

            // 2. Filter & Controls Bar
            LogControlBar(
                selectedFilter = state.selectedProtocolFilter,
                searchQuery = state.searchQuery,
                autoScroll = state.autoScroll,
                logCount = state.filteredLogs.size,
                totalCount = state.logs.size,
                onFilterSelected = { onAction(LiveLogAction.OnProtocolFilterSelected(it)) },
                onSearchChanged = { onAction(LiveLogAction.OnSearchQueryChange(it)) },
                onToggleAutoScroll = { onAction(LiveLogAction.OnToggleAutoScroll(it)) }
            )

            // 3. Log Stream
            if (state.filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Usb,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.logs.isEmpty()) "Waiting for Pioneer stereo connection..." else "No matching logs found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.logs.isEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = { onAction(LiveLogAction.OnSimulateHandshakeClick) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Run Simulated Pioneer Handshake")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.filteredLogs, key = { it.id }) { log ->
                        LogItemCard(
                            entry = log,
                            onClick = { onAction(LiveLogAction.OnSelectLogEntry(log)) }
                        )
                    }
                }
            }
        }
    }

    // Detail Dialog
    state.selectedLogEntry?.let { entry ->
        LogDetailDialog(
            entry = entry,
            onDismiss = { onAction(LiveLogAction.OnSelectLogEntry(null)) }
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    connectionState: UsbConnectionState,
    handshakeStep: HandshakeStep,
    stereoSpecs: StereoSpecs,
    onScanClick: () -> Unit
) {
    Card(
        modifier = Modifier
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
                        is UsbConnectionState.Error -> Color(0xFFF44336) to "ERROR"
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

            if (handshakeStep != HandshakeStep.DISCONNECTED && handshakeStep != HandshakeStep.CONNECTED_READY && handshakeStep != HandshakeStep.FAILED) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Stereo Specs details when identified
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

@Composable
private fun SpecBadge(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogControlBar(
    selectedFilter: ProtocolType?,
    searchQuery: String,
    autoScroll: Boolean,
    logCount: Int,
    totalCount: Int,
    onFilterSelected: (ProtocolType?) -> Unit,
    onSearchChanged: (String) -> Unit,
    onToggleAutoScroll: (Boolean) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        // Filter Chips Row
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { onFilterSelected(null) },
                label = { Text("ALL ($totalCount)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors()
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.SAC,
                onClick = { onFilterSelected(ProtocolType.SAC) },
                label = { Text("SAC", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.WEBLINK,
                onClick = { onFilterSelected(ProtocolType.WEBLINK) },
                label = { Text("WebLink", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.USB,
                onClick = { onFilterSelected(ProtocolType.USB) },
                label = { Text("USB", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.SYSTEM,
                onClick = { onFilterSelected(ProtocolType.SYSTEM) },
                label = { Text("SYS", fontSize = 11.sp) }
            )

            // Auto-scroll toggle
            FilterChip(
                selected = autoScroll,
                onClick = { onToggleAutoScroll(!autoScroll) },
                leadingIcon = {
                    Icon(
                        Icons.Default.VerticalAlignBottom,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                label = { Text("Auto-Scroll", fontSize = 11.sp) }
            )
        }

        // Search text input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            placeholder = { Text("Filter logs by text or hex...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true
        )
    }
}

@Composable
private fun LogItemCard(
    entry: LogEntry,
    onClick: () -> Unit
) {
    val (dirColor, dirText) = when (entry.direction) {
        LogDirection.INCOMING -> Color(0xFF00ACC1) to "◄ RX"
        LogDirection.OUTGOING -> Color(0xFFFF7043) to "► TX"
        LogDirection.INTERNAL -> Color(0xFF7E57C2) to "● SYS"
    }

    Card(
        modifier = Modifier
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
            // Direction Badge
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

            // Timestamp
            Text(
                text = entry.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 6.dp)
            )

            // Protocol tag
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

            // Summary
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

@Composable
private fun LogDetailDialog(
    entry: LogEntry,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "${entry.protocol.name} Packet Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${entry.direction.name} at ${entry.formattedTime}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Summary:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (entry.details.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Decoded Fields:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = entry.details,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                if (entry.rawHex.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Raw Wire Hex (${entry.rawHex.split(" ").filter { it.isNotBlank() }.size} bytes):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = entry.rawHex,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Pioneer Packet Hex", entry.rawHex.ifBlank { entry.summary })
                    cm.setPrimaryClip(clip)
                }
            ) {
                Text("Copy Hex")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
