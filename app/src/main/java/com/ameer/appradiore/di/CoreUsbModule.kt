package com.ameer.appradiore.di

import com.ameer.appradiore.core.usb.HandshakeStateMachine
import com.ameer.appradiore.core.usb.HandshakeStateMachineImpl
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.core.usb.UsbAccessoryManagerImpl
import com.ameer.appradiore.core.usb.datasource.UsbDataSource
import com.ameer.appradiore.core.usb.datasource.UsbDataSourceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreUsbModule = module {
    // Coroutine Scope for background USB and state machine operations
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // USB Low-level stream data source
    single<UsbDataSource> {
        UsbDataSourceImpl(
            context = androidContext(),
            logRepository = get(),
            scope = get()
        )
    }

    // USB Accessory Manager
    single<UsbAccessoryManager> {
        UsbAccessoryManagerImpl(
            context = androidContext(),
            usbDataSource = get(),
            logRepository = get(),
            coroutineScope = get()
        )
    }

    // Handshake State Machine
    single<HandshakeStateMachine> {
        HandshakeStateMachineImpl(
            usbAccessoryManager = get(),
            logRepository = get(),
            scope = get()
        )
    }
}
