package com.ameer.appradiore.feature.livelog

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbAccessory
import app.cash.turbine.test
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogEntry
import com.ameer.appradiore.core.logging.LogRepositoryImpl
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.core.usb.HandshakeStateMachine
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.StereoSpecs
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.core.usb.UsbConnectionState
import com.ameer.appradiore.core.video.VideoStreamingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveLogViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeUsbAccessoryManager : UsbAccessoryManager {
        override val connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        override val incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

        var scanned = false
        var listening = false

        override fun startListening() { listening = true }
        override fun stopListening() { listening = false }
        override fun scanForAccessory() { scanned = true }
        override fun requestPermission(accessory: UsbAccessory) {}
        override fun connect(accessory: UsbAccessory) {}
        override fun disconnect() {}
        override suspend fun send(data: ByteArray): Boolean = true
        override fun simulateConnect() {}
    }

    private class FakeHandshakeStateMachine : HandshakeStateMachine {
        override val currentStep = MutableStateFlow(HandshakeStep.DISCONNECTED)
        override val stereoSpecs = MutableStateFlow(StereoSpecs())

        var started = false
        var simulated = false

        override fun startHandshake() { started = true }
        override fun reset() {}
        override fun simulateHandshake() { simulated = true }
    }

    private class FakeVideoStreamingManager : VideoStreamingManager {
        override val isStreaming = MutableStateFlow(false)
        override val fps = MutableStateFlow(0)
        override val framesSent = MutableStateFlow(0L)
        override val bytesSent = MutableStateFlow(0L)

        var streamingStarted = false
        var streamingStopped = false

        override fun startStreaming(width: Int, height: Int, fps: Int) {
            streamingStarted = true
            isStreaming.value = true
        }

        override fun stopStreaming() {
            streamingStopped = true
            isStreaming.value = false
        }
    }

    private lateinit var fakeContext: Context
    private lateinit var fakeUsb: FakeUsbAccessoryManager
    private lateinit var fakeStateMachine: FakeHandshakeStateMachine
    private lateinit var logRepository: LogRepositoryImpl
    private lateinit var fakeVideoStreamingManager: FakeVideoStreamingManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.ameer.appradiore"
        }
        fakeUsb = FakeUsbAccessoryManager()
        fakeStateMachine = FakeHandshakeStateMachine()
        logRepository = LogRepositoryImpl()
        fakeVideoStreamingManager = FakeVideoStreamingManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() = runTest {
        val viewModel = LiveLogViewModel(fakeContext, fakeUsb, fakeStateMachine, logRepository, fakeVideoStreamingManager)

        assertTrue(fakeUsb.listening)
        assertEquals(UsbConnectionState.Disconnected, viewModel.state.value.connectionState)
        assertEquals(HandshakeStep.DISCONNECTED, viewModel.state.value.handshakeStep)
        assertTrue(viewModel.state.value.logs.isEmpty())
        assertTrue(viewModel.state.value.autoScroll)
        assertFalse(viewModel.state.value.isStreaming)
    }

    @Test
    fun testFilteringByProtocol() = runTest {
        val viewModel = LiveLogViewModel(fakeContext, fakeUsb, fakeStateMachine, logRepository, fakeVideoStreamingManager)

        logRepository.log(LogDirection.INCOMING, ProtocolType.SAC, "AuthResponse")
        logRepository.log(LogDirection.INCOMING, ProtocolType.WEBLINK, "TouchCommand")
        logRepository.log(LogDirection.INTERNAL, ProtocolType.USB, "Attached")

        viewModel.state.test {
            val item = awaitItem()
            assertEquals(3, item.logs.size)
            assertEquals(3, item.filteredLogs.size)

            // Filter for SAC
            viewModel.onAction(LiveLogAction.OnProtocolFilterSelected(ProtocolType.SAC))
            val filteredSac = awaitItem()
            assertEquals(ProtocolType.SAC, filteredSac.selectedProtocolFilter)
            assertEquals(1, filteredSac.filteredLogs.size)
            assertEquals("AuthResponse", filteredSac.filteredLogs[0].summary)

            // Clear filter (ALL)
            viewModel.onAction(LiveLogAction.OnProtocolFilterSelected(null))
            val all = awaitItem()
            assertNull(all.selectedProtocolFilter)
            assertEquals(3, all.filteredLogs.size)
        }
    }

    @Test
    fun testFilteringBySearchQuery() = runTest {
        val viewModel = LiveLogViewModel(fakeContext, fakeUsb, fakeStateMachine, logRepository, fakeVideoStreamingManager)

        logRepository.log(LogDirection.INCOMING, ProtocolType.SAC, "AuthResponse", rawHex = "9F 02 00 9F 03")
        logRepository.log(LogDirection.INCOMING, ProtocolType.SAC, "DisplaySpecInfo", rawHex = "9F 02 01 9F 03")

        viewModel.state.test {
            awaitItem() // initial

            viewModel.onAction(LiveLogAction.OnSearchQueryChange("Display"))
            val result = awaitItem()
            assertEquals(1, result.filteredLogs.size)
            assertEquals("DisplaySpecInfo", result.filteredLogs[0].summary)

            // Search by hex
            viewModel.onAction(LiveLogAction.OnSearchQueryChange("01"))
            val hexResult = awaitItem()
            assertEquals(1, hexResult.filteredLogs.size)
            assertEquals("DisplaySpecInfo", hexResult.filteredLogs[0].summary)
        }
    }

    @Test
    fun testClearLogsAction() = runTest {
        val viewModel = LiveLogViewModel(fakeContext, fakeUsb, fakeStateMachine, logRepository, fakeVideoStreamingManager)

        logRepository.log(LogDirection.INTERNAL, ProtocolType.SYSTEM, "Started")
        assertEquals(1, viewModel.state.value.logs.size)

        viewModel.onAction(LiveLogAction.OnClearLogsClick)
        assertEquals(0, viewModel.state.value.logs.size)
        assertEquals(0, viewModel.state.value.filteredLogs.size)
    }

    @Test
    fun testToggleAutoScrollAndSelectLog() = runTest {
        val viewModel = LiveLogViewModel(fakeContext, fakeUsb, fakeStateMachine, logRepository, fakeVideoStreamingManager)

        viewModel.onAction(LiveLogAction.OnToggleAutoScroll(false))
        assertFalse(viewModel.state.value.autoScroll)

        val entry = LogEntry(
            direction = LogDirection.INCOMING,
            protocol = ProtocolType.SAC,
            summary = "Packet"
        )
        viewModel.onAction(LiveLogAction.OnSelectLogEntry(entry))
        assertNotNull(viewModel.state.value.selectedLogEntry)
        assertEquals("Packet", viewModel.state.value.selectedLogEntry?.summary)

        viewModel.onAction(LiveLogAction.OnSelectLogEntry(null))
        assertNull(viewModel.state.value.selectedLogEntry)
    }

    @Test
    fun testSimulateHandshakeTriggersStateMachine() = runTest {
        val viewModel = LiveLogViewModel(fakeContext, fakeUsb, fakeStateMachine, logRepository, fakeVideoStreamingManager)

        viewModel.onAction(LiveLogAction.OnSimulateHandshakeClick)
        assertTrue(fakeStateMachine.simulated)
    }

    @Test
    fun testToggleVideoStreamAction() = runTest {
        val viewModel = LiveLogViewModel(fakeContext, fakeUsb, fakeStateMachine, logRepository, fakeVideoStreamingManager)

        assertFalse(viewModel.state.value.isStreaming)

        // Toggle on
        viewModel.onAction(LiveLogAction.OnToggleVideoStream(true))
        assertTrue(fakeVideoStreamingManager.streamingStarted)
        assertTrue(viewModel.state.value.isStreaming)

        // Toggle off
        viewModel.onAction(LiveLogAction.OnToggleVideoStream(false))
        assertTrue(fakeVideoStreamingManager.streamingStopped)
        assertFalse(viewModel.state.value.isStreaming)
    }
}
