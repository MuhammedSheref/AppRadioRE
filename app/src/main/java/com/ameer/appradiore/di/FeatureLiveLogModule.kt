package com.ameer.appradiore.di

import com.ameer.appradiore.feature.livelog.LiveLogViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val featureLiveLogModule = module {
    viewModel {
        LiveLogViewModel(
            context = androidContext(),
            usbAccessoryManager = get(),
            handshakeStateMachine = get(),
            logRepository = get(),
            videoStreamingManager = get()
        )
    }
}
