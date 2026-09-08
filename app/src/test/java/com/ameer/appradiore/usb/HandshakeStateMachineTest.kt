package com.ameer.appradiore.usb

import android.hardware.usb.UsbAccessory
import com.ameer.appradiore.core.logging.LogRepositoryImpl
import com.ameer.appradiore.core.usb.HandshakeStateMachineImpl
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.core.usb.UsbConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeStateMachineTest {

    private class FakeUsbAccessoryManager : UsbAccessoryManager {
        override val connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        override val incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

        val sentBytes = mutableListOf<ByteArray>()

        override fun startListening() {}
        override fun stopListening() {}
        override fun scanForAccessory() {}
        override fun requestPermission(accessory: UsbAccessory) {}
        override fun connect(accessory: UsbAccessory) {}
        override fun disconnect() {}
        override suspend fun send(data: ByteArray): Boolean {
            sentBytes.add(data)
            return true
        }
        override fun simulateConnect() {}
    }

    @Test
    fun testInitialStateDisconnected() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)

        assertEquals(HandshakeStep.DISCONNECTED, stateMachine.currentStep.value)
        assertEquals(0, stateMachine.stereoSpecs.value.width)
    }

    @Test
    fun testSimulateHandshakeCompletes() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)

        stateMachine.simulateHandshake()
        testScope.advanceUntilIdle()

        assertEquals(HandshakeStep.CONNECTED_READY, stateMachine.currentStep.value)
        assertTrue(stateMachine.stereoSpecs.value.isReadyForVideo)
        assertEquals(800, stateMachine.stereoSpecs.value.width)
        assertEquals(480, stateMachine.stereoSpecs.value.height)
        assertEquals(0x0112.toShort(), stateMachine.stereoSpecs.value.modelId)
        assertTrue(stateMachine.stereoSpecs.value.isParkingBrakeOn)

        val logs = logRepo.logs.first()
        assertTrue(logs.size > 10)
    }

    @Test
    fun testMtpStreamNegotiationAndAuthResponse() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)
        testScope.advanceUntilIdle()

        stateMachine.startHandshake()
        testScope.testScheduler.advanceTimeBy(1100)

        // Verify that AuthBegin was wrapped in MTP framing (starts with 0x1E, ends with 0x03)
        assertTrue(fakeUsb.sentBytes.isNotEmpty())
        val sentMtp = fakeUsb.sentBytes.last()
        assertEquals(0x1E.toByte(), sentMtp[0])
        assertEquals(0x03.toByte(), sentMtp[sentMtp.size - 1])

        // Simulate stereo replying with AuthResponse inside an MTP packet
        val authRespPFormat = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x00, 0x01)
        )
        val authRespMtp = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(authRespPFormat)

        fakeUsb.incomingBytes.emit(authRespMtp)
        testScope.advanceUntilIdle()

        // Verify state advanced to STEP_1_START_APP_ACC (as AuthResponse triggers AuthEnd and StartAppAcc)
        assertEquals(HandshakeStep.STEP_1_START_APP_ACC, stateMachine.currentStep.value)
    }
}
