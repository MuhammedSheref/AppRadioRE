package com.ameer.appradiore.di

import org.koin.dsl.module

val appModule = module {
    includes(
        coreLoggingModule,
        coreUsbModule,
        coreVideoModule,
        featureLiveLogModule
    )
}
