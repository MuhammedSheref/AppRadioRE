package com.ameer.appradiore.di

import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.LogRepositoryImpl
import com.ameer.appradiore.core.usb.HandshakeStateMachine
import com.ameer.appradiore.core.usb.HandshakeStateMachineImpl
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.core.usb.UsbAccessoryManagerImpl
import com.ameer.appradiore.feature.livelog.LiveLogViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Coroutine Scope for background USB and state machine operations
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // Logging repository
    single<LogRepository> { LogRepositoryImpl() }

    // USB Accessory Manager
    single<UsbAccessoryManager> {
        UsbAccessoryManagerImpl(
            context = androidContext(),
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

    // LiveLog ViewModel
    viewModel {
        LiveLogViewModel(
            context = androidContext(),
            usbAccessoryManager = get(),
            handshakeStateMachine = get(),
            logRepository = get()
        )
    }
}
