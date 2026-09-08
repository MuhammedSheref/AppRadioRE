package com.ameer.appradiore.di

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbManager
import com.ameer.appradiore.core.logging.LogRepository
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class KoinModuleTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun testLogRepositoryResolved() {
        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.ameer.appradiore"
        }

        val koinApp = startKoin {
            androidContext(fakeContext)
            modules(appModule)
        }

        val logRepo = koinApp.koin.get<LogRepository>()
        assertNotNull(logRepo)
    }
}
