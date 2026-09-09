package com.ameer.appradiore.core.usb.datasource

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.ameer.appradiore.core.error.DataError
import com.ameer.appradiore.core.error.EmptyResult
import com.ameer.appradiore.core.error.Result
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.ProtocolType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

interface UsbDataSource {
    val incomingBytes: SharedFlow<ByteArray>
    val isConnected: Boolean

    fun open(accessory: UsbAccessory): EmptyResult<DataError.Usb>
    fun close()
    suspend fun write(data: ByteArray): EmptyResult<DataError.Usb>
}

class UsbDataSourceImpl(
    private val context: Context,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) : UsbDataSource {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var readJob: Job? = null

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

    override suspend fun write(data: ByteArray): EmptyResult<DataError.Usb> = withContext(Dispatchers.IO) {
        val stream = outputStream ?: return@withContext Result.Error(DataError.Usb.STREAM_CLOSED)
        try {
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
            stream.flush()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(DataError.Usb.IO_ERROR)
        }
    }

    override fun close() {
        readJob?.cancel()
        readJob = null

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
