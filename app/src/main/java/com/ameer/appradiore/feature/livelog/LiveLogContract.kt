package com.ameer.appradiore.feature.livelog

import android.net.Uri
import com.ameer.appradiore.core.logging.LogEntry
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.core.presentation.UiText
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.StereoSpecs
import com.ameer.appradiore.core.usb.UsbConnectionState

data class LiveLogState(
    val connectionState: UsbConnectionState = UsbConnectionState.Disconnected,
    val handshakeStep: HandshakeStep = HandshakeStep.DISCONNECTED,
    val stereoSpecs: StereoSpecs = StereoSpecs(),
    val logs: List<LogEntry> = emptyList(),
    val filteredLogs: List<LogEntry> = emptyList(),
    val selectedProtocolFilter: ProtocolType? = null,
    val searchQuery: String = "",
    val autoScroll: Boolean = true,
    val selectedLogEntry: LogEntry? = null,
    val isExporting: Boolean = false
)

sealed interface LiveLogAction {
    data object OnScanUsbClick : LiveLogAction
    data object OnSimulateHandshakeClick : LiveLogAction
    data object OnClearLogsClick : LiveLogAction
    data object OnExportLogsClick : LiveLogAction
    data class OnProtocolFilterSelected(val protocol: ProtocolType?) : LiveLogAction
    data class OnSearchQueryChange(val query: String) : LiveLogAction
    data class OnToggleAutoScroll(val enabled: Boolean) : LiveLogAction
    data class OnSelectLogEntry(val entry: LogEntry?) : LiveLogAction
}

sealed interface LiveLogEvent {
    data class ShowSnackbar(val message: UiText) : LiveLogEvent
    data class ShareLogs(val logFileUri: Uri) : LiveLogEvent
}
