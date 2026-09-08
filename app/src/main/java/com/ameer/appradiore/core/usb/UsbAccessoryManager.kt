package com.ameer.appradiore.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.ProtocolType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

interface UsbAccessoryManager {
    val connectionState: StateFlow<UsbConnectionState>
    val incomingBytes: SharedFlow<ByteArray>

    fun startListening()
    fun stopListening()
    fun scanForAccessory()
    fun requestPermission(accessory: UsbAccessory)
    fun connect(accessory: UsbAccessory)
    fun disconnect()
    suspend fun send(data: ByteArray): Boolean
    fun simulateConnect()
}

class UsbAccessoryManagerImpl(
    private val context: Context,
    private val logRepository: LogRepository,
    private val coroutineScope: CoroutineScope
) : UsbAccessoryManager {

    companion object {
        const val ACTION_USB_PERMISSION = "com.ameer.appradiore.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    override val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var readJob: Job? = null
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "USB Broadcast received: $action"
            )

            when (action) {
                ACTION_USB_PERMISSION -> {
                    val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && accessory != null) {
                        logRepository.log(
                            direction = LogDirection.INTERNAL,
                            protocol = ProtocolType.USB,
                            summary = "USB Permission granted for: ${accessory.description ?: accessory.model}"
                        )
                        connect(accessory)
                    } else {
                        logRepository.log(
                            direction = LogDirection.INTERNAL,
                            protocol = ProtocolType.USB,
                            summary = "USB Permission DENIED",
                            isError = true
                        )
                        _connectionState.value = UsbConnectionState.Error("USB permission denied by user")
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    if (accessory != null) {
                        handleAccessoryDiscovered(accessory)
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    logRepository.log(
                        direction = LogDirection.INTERNAL,
                        protocol = ProtocolType.USB,
                        summary = "USB Accessory detached"
                    )
                    disconnect()
                }
            }
        }
    }

    override fun startListening() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "USB Listener registered. Scanning for existing accessories..."
            )
            scanForAccessory()
        }
    }

    override fun stopListening() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        disconnect()
    }

    override fun scanForAccessory() {
        val accessoryList = usbManager.accessoryList
        if (!accessoryList.isNullOrEmpty()) {
            val accessory = accessoryList[0]
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "Found attached accessory: ${accessory.manufacturer} - ${accessory.model} (v${accessory.version})"
            )
            handleAccessoryDiscovered(accessory)
        } else {
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "No USB accessory currently attached."
            )
        }
    }

    private fun handleAccessoryDiscovered(accessory: UsbAccessory) {
        if (usbManager.hasPermission(accessory)) {
            connect(accessory)
        } else {
            _connectionState.value = UsbConnectionState.PermissionRequired(accessory)
            requestPermission(accessory)
        }
    }

    override fun requestPermission(accessory: UsbAccessory) {
        logRepository.log(
            direction = LogDirection.INTERNAL,
            protocol = ProtocolType.USB,
            summary = "Requesting USB permission for ${accessory.model}"
        )
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        usbManager.requestPermission(accessory, permissionIntent)
    }

    override fun connect(accessory: UsbAccessory) {
        disconnect()
        _connectionState.value = UsbConnectionState.Connecting(accessory)

        try {
            val pfd = usbManager.openAccessory(accessory)
            if (pfd == null) {
                _connectionState.value = UsbConnectionState.Error("Failed to open USB accessory descriptor")
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.USB,
                    summary = "Failed to open USB accessory descriptor",
                    isError = true
                )
                return
            }

            fileDescriptor = pfd
            val fd = pfd.fileDescriptor
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)

            _connectionState.value = UsbConnectionState.Connected(accessory)
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "Connected to ${accessory.manufacturer} ${accessory.model} (v${accessory.version})"
            )

            startReadLoop()
        } catch (e: Exception) {
            _connectionState.value = UsbConnectionState.Error("Connection error: ${e.message}")
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "Connection failed: ${e.message}",
                isError = true
            )
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16384)
            while (isActive) {
                val input = inputStream ?: break
                try {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        _incomingBytes.emit(data)
                    } else if (bytesRead < 0) {
                        logRepository.log(
                            direction = LogDirection.INTERNAL,
                            protocol = ProtocolType.USB,
                            summary = "USB stream reached EOF"
                        )
                        break
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        logRepository.log(
                            direction = LogDirection.INTERNAL,
                            protocol = ProtocolType.USB,
                            summary = "USB Read error: ${e.message}",
                            isError = true
                        )
                    }
                    break
                }
            }
            withContext(Dispatchers.Main) {
                disconnect()
            }
        }
    }

    override suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val stream = outputStream ?: return@withContext false
        try {
            stream.write(data)
            stream.flush()
            true
        } catch (e: Exception) {
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "Failed to write to USB: ${e.message}",
                isError = true
            )
            false
        }
    }

    override fun disconnect() {
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

        if (_connectionState.value !is UsbConnectionState.Disconnected) {
            _connectionState.value = UsbConnectionState.Disconnected
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.USB,
                summary = "USB connection closed"
            )
        }
    }

    override fun simulateConnect() {
        disconnect()
        logRepository.log(
            direction = LogDirection.INTERNAL,
            protocol = ProtocolType.SYSTEM,
            summary = "Simulating Pioneer AAM2 connection..."
        )
    }
}
