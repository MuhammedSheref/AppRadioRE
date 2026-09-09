package com.ameer.appradiore.feature.livelog

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogEntry
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.core.presentation.ObserveAsEvents
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.StereoSpecs
import com.ameer.appradiore.core.usb.UsbConnectionState
import com.ameer.appradiore.feature.livelog.components.ConnectionStatusCard
import com.ameer.appradiore.feature.livelog.components.LogControlBar
import com.ameer.appradiore.feature.livelog.components.LogDetailDialog
import com.ameer.appradiore.feature.livelog.components.LogItemCard
import com.ameer.appradiore.ui.theme.AppRadioRETheme
import kotlinx.coroutines.launch
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
    onAction: (LiveLogAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.filteredLogs.size, state.autoScroll) {
        if (state.autoScroll && state.filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(state.filteredLogs.size - 1)
        }
    }

    Scaffold(
        modifier = modifier,
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
                    if (state.connectionState !is UsbConnectionState.Connected) {
                        IconButton(onClick = { onAction(LiveLogAction.OnSimulateHandshakeClick) }) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = "Test Offline Mock Demo (No Car Connected)",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
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
            ConnectionStatusCard(
                connectionState = state.connectionState,
                handshakeStep = state.handshakeStep,
                stereoSpecs = state.stereoSpecs,
                isStreaming = state.isStreaming,
                streamFps = state.streamFps,
                streamFramesSent = state.streamFramesSent,
                streamBytesSent = state.streamBytesSent,
                onToggleVideoStream = { onAction(LiveLogAction.OnToggleVideoStream(it)) }
            )

            LogControlBar(
                selectedFilter = state.selectedProtocolFilter,
                searchQuery = state.searchQuery,
                autoScroll = state.autoScroll,
                totalCount = state.logs.size,
                onFilterSelected = { onAction(LiveLogAction.OnProtocolFilterSelected(it)) },
                onSearchChanged = { onAction(LiveLogAction.OnSearchQueryChange(it)) },
                onToggleAutoScroll = { onAction(LiveLogAction.OnToggleAutoScroll(it)) }
            )

            if (state.filteredLogs.isEmpty()) {
                EmptyLogsPlaceholder(
                    hasLogs = state.logs.isNotEmpty(),
                    onSimulateClick = { onAction(LiveLogAction.OnSimulateHandshakeClick) },
                    modifier = Modifier.weight(1f)
                )
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

    state.selectedLogEntry?.let { entry ->
        LogDetailDialog(
            entry = entry,
            onDismiss = { onAction(LiveLogAction.OnSelectLogEntry(null)) }
        )
    }
}

@Composable
private fun EmptyLogsPlaceholder(
    hasLogs: Boolean,
    onSimulateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
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
                text = if (hasLogs) "No matching logs found" else "Waiting for Pioneer stereo connection...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasLogs) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onSimulateClick) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run Simulated Pioneer Handshake")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LiveLogScreenPreview() {
    AppRadioRETheme {
        LiveLogScreen(
            state = LiveLogState(
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
                logs = listOf(
                    LogEntry(
                        direction = LogDirection.INCOMING,
                        protocol = ProtocolType.SAC,
                        summary = "AuthResponse (result=OK, version=3.1)",
                        rawHex = "9F 02 00 00 00 03 00 01 02 9F 03"
                    ),
                    LogEntry(
                        direction = LogDirection.OUTGOING,
                        protocol = ProtocolType.SAC,
                        summary = "VideoOutputReply [0x06, 0x01]",
                        rawHex = "9F 02 06 06 01 01 9F 03"
                    )
                ),
                filteredLogs = listOf(
                    LogEntry(
                        direction = LogDirection.INCOMING,
                        protocol = ProtocolType.SAC,
                        summary = "AuthResponse (result=OK, version=3.1)",
                        rawHex = "9F 02 00 00 00 03 00 01 02 9F 03"
                    ),
                    LogEntry(
                        direction = LogDirection.OUTGOING,
                        protocol = ProtocolType.SAC,
                        summary = "VideoOutputReply [0x06, 0x01]",
                        rawHex = "9F 02 06 06 01 01 9F 03"
                    )
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onAction = {}
        )
    }
}
