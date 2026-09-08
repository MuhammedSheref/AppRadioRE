package com.ameer.appradiore.di

import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.LogRepositoryImpl
import org.koin.dsl.module

val coreLoggingModule = module {
    single<LogRepository> { LogRepositoryImpl() }
}
