package com.ameer.appradiore

import android.app.Application
import com.ameer.appradiore.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class AppRadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@AppRadioApp)
            modules(appModule)
        }
    }
}
