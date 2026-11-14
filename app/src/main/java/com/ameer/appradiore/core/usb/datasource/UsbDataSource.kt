package com.ameer.appradiore.core.usb.datasource

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.ameer.appradiore.core.error.DataError
import com.ameer.appradiore.core.error.EmptyResult
import com.ameer.appradiore.core.error.Result
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.ProtocolType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

interface UsbDataSource {
    val incomingBytes: SharedFlow<ByteArray>
    val isConnected: Boolean

    fun open(accessory: UsbAccessory): EmptyResult<DataError.Usb>
    fun close()
    suspend fun write(data: ByteArray, isHighPriority: Boolean = false): EmptyResult<DataError.Usb>
}

class UsbDataSourceImpl(
    private val context: Context,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) : UsbDataSource {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _incomingBytes = MutableSharedFlow<ByteArray>(replay = 16, extraBufferCapacity = 64)
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var readJob: Job? = null
    private var writerJob: Job? = null
    private var watchdogJob: Job? = null

    private class WriteRequest(
        val data: ByteArray,
        val isHighPriority: Boolean,
        val deferred: CompletableDeferred<EmptyResult<DataError.Usb>>
    )

    private val highPriorityChannel = Channel<WriteRequest>(Channel.UNLIMITED)
    private val videoChannel = Channel<WriteRequest>(capacity = 4)

    @Volatile
    private var writeInProgressStartTime = 0L

    override val isConnected: Boolean
        get() = fileDescriptor != null

    override fun open(accessory: UsbAccessory): EmptyResult<DataError.Usb> {
        close()
        return try {
            val pfd = usbManager.openAccessory(accessory)
                ?: return Result.Error(DataError.Usb.DESCRIPTOR_OPEN_FAILED)

            fileDescriptor = pfd
            val fd = pfd.fileDescriptor
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)

            startReadLoop()
            startWriterLoop()
            startWatchdog()
            Result.Success(Unit)
        } catch (e: Exception) {
            close()
            Result.Error(DataError.Usb.IO_ERROR)
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16384)
            while (isActive) {
                val input = inputStream ?: break
                try {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        val hexPreview = data.take(64).joinToString(" ") { String.format("%02X", it) }
                        val suffix = if (data.size > 64) " ... (${data.size} bytes total)" else ""
                        logRepository.log(
                            direction = LogDirection.INCOMING,
                            protocol = ProtocolType.RAW,
                            summary = "RX Raw USB Chunk (${data.size} bytes)",
                            rawHex = hexPreview + suffix
                        )
                        _incomingBytes.emit(data)
                    } else if (bytesRead < 0) {
                        break
                    }
                } catch (e: IOException) {
                    break
                }
            }
            withContext(Dispatchers.Main) {
                close()
            }
        }
    }

    private fun startWriterLoop() {
        writerJob?.cancel()
        writerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val stream = outputStream ?: break
                // Prioritize high-priority (control) traffic over video traffic
                val req: WriteRequest = highPriorityChannel.tryReceive().getOrNull()
                    ?: select {
                        highPriorityChannel.onReceive { it }
                        videoChannel.onReceive { it }
                    }

                try {
                    writeInProgressStartTime = SystemClock.uptimeMillis()
                    val data = req.data
                    val hexPreview = data.take(64).joinToString(" ") { String.format("%02X", it) }
                    val suffix = if (data.size > 64) " ... (${data.size} bytes total)" else ""
                    logRepository.log(
                        direction = LogDirection.OUTGOING,
                        protocol = ProtocolType.RAW,
                        summary = "TX Raw USB Chunk (${data.size} bytes)",
                        rawHex = hexPreview + suffix
                    )

                    // USB write chunking matching UsbAccessoryLayer.writeDataInternal() from Pioneer OEM source:
                    // max 5000 bytes per chunk. If chunkSize % 512 == 0, reduce by 257 bytes to prevent USB ZLP stalls.
                    var offset = 0
                    var remaining = data.size
                    while (remaining > 0) {
                        var chunkSize = minOf(5000, remaining)
                        if (chunkSize % 512 == 0) {
                            chunkSize -= 257
                        }
                        stream.write(data, offset, chunkSize)
                        offset += chunkSize
                        remaining -= chunkSize
                    }
                    writeInProgressStartTime = 0L
                    req.deferred.complete(Result.Success(Unit))
                } catch (e: Exception) {
                    writeInProgressStartTime = 0L
                    req.deferred.complete(Result.Error(DataError.Usb.IO_ERROR))
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            close()
                        }
                    }
                    break
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val start = writeInProgressStartTime
                if (start > 0L && SystemClock.uptimeMillis() - start > 4000L) {
                    logRepository.log(
                        direction = LogDirection.INTERNAL,
                        protocol = ProtocolType.USB,
                        summary = "USB hardware write stalled for >4s. Force closing descriptor to unblock kernel.",
                        isError = true
                    )
                    withContext(Dispatchers.Main) {
                        close()
                    }
                    break
                }
            }
        }
    }

    override suspend fun write(data: ByteArray, isHighPriority: Boolean): EmptyResult<DataError.Usb> {
        if (outputStream == null) {
            return Result.Error(DataError.Usb.STREAM_CLOSED)
        }

        val deferred = CompletableDeferred<EmptyResult<DataError.Usb>>()
        val req = WriteRequest(data, isHighPriority, deferred)

        return if (isHighPriority) {
            highPriorityChannel.send(req)
            withTimeoutOrNull(2500L) {
                deferred.await()
            } ?: run {
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.RAW,
                    summary = "USB High-Priority Write timed out (${data.size} bytes)",
                    isError = true
                )
                withContext(Dispatchers.Main) {
                    close()
                }
                Result.Error(DataError.Usb.IO_ERROR)
            }
        } else {
            // For video frames: trySend to avoid blocking when write queue is backed up
            val sendResult = videoChannel.trySend(req)
            if (!sendResult.isSuccess) {
                // Buffer congested; drop frame chunk to preserve real-time video pacing
                Result.Error(DataError.Usb.IO_ERROR)
            } else {
                withTimeoutOrNull(2000L) {
                    deferred.await()
                } ?: Result.Error(DataError.Usb.IO_ERROR)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun close() {
        readJob?.cancel()
        readJob = null
        writerJob?.cancel()
        writerJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        writeInProgressStartTime = 0L

        // Drain any pending requests
        while (true) {
            val req = highPriorityChannel.tryReceive().getOrNull() ?: break
            req.deferred.complete(Result.Error(DataError.Usb.STREAM_CLOSED))
        }
        while (true) {
            val req = videoChannel.tryReceive().getOrNull() ?: break
            req.deferred.complete(Result.Error(DataError.Usb.STREAM_CLOSED))
        }

        _incomingBytes.resetReplayCache()

        try {
            inputStream?.close()
            outputStream?.close()
            fileDescriptor?.close()
        } catch (_: Exception) {}

        inputStream = null
        outputStream = null
        fileDescriptor = null
    }
}
