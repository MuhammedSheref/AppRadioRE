package com.ameer.appradiore.di

import com.ameer.appradiore.core.video.VideoStreamingManager
import com.ameer.appradiore.core.video.VideoStreamingManagerImpl
import org.koin.dsl.module

val coreVideoModule = module {
    single<VideoStreamingManager> {
        VideoStreamingManagerImpl(
            usbAccessoryManager = get(),
            logRepository = get(),
            scope = get()
        )
    }
}
