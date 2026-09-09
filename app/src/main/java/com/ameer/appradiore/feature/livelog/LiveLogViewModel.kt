package com.ameer.appradiore.feature.livelog

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogEntry
import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.core.presentation.UiText
import com.ameer.appradiore.core.usb.HandshakeStateMachine
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.core.usb.UsbConnectionState
import com.ameer.appradiore.core.video.VideoStreamingManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LiveLogViewModel(
    private val context: Context,
    private val usbAccessoryManager: UsbAccessoryManager,
    private val handshakeStateMachine: HandshakeStateMachine,
    private val logRepository: LogRepository,
    private val videoStreamingManager: VideoStreamingManager
) : ViewModel() {

    private val _state = MutableStateFlow(LiveLogState())
    val state = _state.asStateFlow()

    private val _events = Channel<LiveLogEvent>()
    val events = _events.receiveAsFlow()

    init {
        usbAccessoryManager.startListening()

        // Observe USB connection state
        viewModelScope.launch {
            usbAccessoryManager.connectionState.collect { connectionState ->
                _state.update { it.copy(connectionState = connectionState) }
                if (connectionState is com.ameer.appradiore.core.usb.UsbConnectionState.Disconnected ||
                    connectionState is com.ameer.appradiore.core.usb.UsbConnectionState.Error
                ) {
                    videoStreamingManager.stopStreaming()
                }
            }
        }

        // Observe Handshake step
        viewModelScope.launch {
            handshakeStateMachine.currentStep.collect { step ->
                _state.update { it.copy(handshakeStep = step) }
                if (step == HandshakeStep.DISCONNECTED || step == HandshakeStep.FAILED) {
                    videoStreamingManager.stopStreaming()
                } else if (step == HandshakeStep.CONNECTED_READY) {
                    if (!_state.value.isStreaming) {
                        val width = if (_state.value.stereoSpecs.width > 0) _state.value.stereoSpecs.width else 800
                        val height = if (_state.value.stereoSpecs.height > 0) _state.value.stereoSpecs.height else 480
                        videoStreamingManager.startStreaming(width = width, height = height, fps = 30)
                    }
                }
            }
        }

        // Observe Stereo Specs
        viewModelScope.launch {
            handshakeStateMachine.stereoSpecs.collect { specs ->
                _state.update { it.copy(stereoSpecs = specs) }
                if (specs.isReadyForVideo && !_state.value.isStreaming) {
                    val width = if (specs.width > 0) specs.width else 800
                    val height = if (specs.height > 0) specs.height else 480
                    videoStreamingManager.startStreaming(width = width, height = height, fps = 30)
                }
            }
        }

        // Observe Video Streaming metrics
        viewModelScope.launch {
            videoStreamingManager.isStreaming.collect { isStreaming ->
                _state.update { it.copy(isStreaming = isStreaming) }
            }
        }
        viewModelScope.launch {
            videoStreamingManager.fps.collect { fps ->
                _state.update { it.copy(streamFps = fps) }
            }
        }
        viewModelScope.launch {
            videoStreamingManager.framesSent.collect { frames ->
                _state.update { it.copy(streamFramesSent = frames) }
            }
        }
        viewModelScope.launch {
            videoStreamingManager.bytesSent.collect { bytes ->
                _state.update { it.copy(streamBytesSent = bytes) }
            }
        }

        // Observe Logs
        viewModelScope.launch {
            logRepository.logs.collect { logs ->
                _state.update { current ->
                    val filtered = applyFilter(logs, current.selectedProtocolFilter, current.searchQuery)
                    current.copy(logs = logs, filteredLogs = filtered)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        videoStreamingManager.stopStreaming()
        usbAccessoryManager.stopListening()
    }

    fun onAction(action: LiveLogAction) {
        when (action) {
            is LiveLogAction.OnScanUsbClick -> {
                usbAccessoryManager.scanForAccessory()
            }
            is LiveLogAction.OnSimulateHandshakeClick -> {
                if (_state.value.connectionState is UsbConnectionState.Connected) {
                    logRepository.log(
                        direction = LogDirection.INTERNAL,
                        protocol = ProtocolType.SYSTEM,
                        summary = "USB device connected. Triggering real Pioneer handshake restart..."
                    )
                    handshakeStateMachine.restartHandshake()
                } else {
                    handshakeStateMachine.simulateHandshake()
                }
            }
            is LiveLogAction.OnClearLogsClick -> {
                logRepository.clearLogs()
            }
            is LiveLogAction.OnExportLogsClick -> {
                exportLogs()
            }
            is LiveLogAction.OnProtocolFilterSelected -> {
                _state.update { current ->
                    val filtered = applyFilter(current.logs, action.protocol, current.searchQuery)
                    current.copy(selectedProtocolFilter = action.protocol, filteredLogs = filtered)
                }
            }
            is LiveLogAction.OnSearchQueryChange -> {
                _state.update { current ->
                    val filtered = applyFilter(current.logs, current.selectedProtocolFilter, action.query)
                    current.copy(searchQuery = action.query, filteredLogs = filtered)
                }
            }
            is LiveLogAction.OnToggleAutoScroll -> {
                _state.update { it.copy(autoScroll = action.enabled) }
            }
            is LiveLogAction.OnSelectLogEntry -> {
                _state.update { it.copy(selectedLogEntry = action.entry) }
            }
            is LiveLogAction.OnToggleVideoStream -> {
                if (action.enable) {
                    val width = if (_state.value.stereoSpecs.width > 0) _state.value.stereoSpecs.width else 800
                    val height = if (_state.value.stereoSpecs.height > 0) _state.value.stereoSpecs.height else 480
                    videoStreamingManager.startStreaming(width = width, height = height, fps = 30)
                } else {
                    videoStreamingManager.stopStreaming()
                }
            }
        }
    }

    private fun exportLogs() {
        viewModelScope.launch {
            _state.update { it.copy(isExporting = true) }
            try {
                val uri = logRepository.exportLogs(context)
                _events.send(LiveLogEvent.ShareLogs(uri))
            } catch (e: Exception) {
                _events.send(LiveLogEvent.ShowSnackbar(UiText.DynamicString("Failed to export logs: ${e.message}")))
            } finally {
                _state.update { it.copy(isExporting = false) }
            }
        }
    }

    private fun applyFilter(
        logs: List<LogEntry>,
        protocol: ProtocolType?,
        query: String
    ): List<LogEntry> {
        val trimmedQuery = query.trim().lowercase()
        return logs.filter { entry ->
            val matchesProtocol = (protocol == null || entry.protocol == protocol)
            val matchesQuery = if (trimmedQuery.isEmpty()) {
                true
            } else {
                entry.summary.lowercase().contains(trimmedQuery) ||
                        entry.rawHex.lowercase().contains(trimmedQuery) ||
                        entry.details.lowercase().contains(trimmedQuery)
            }
            matchesProtocol && matchesQuery
        }
    }
}
