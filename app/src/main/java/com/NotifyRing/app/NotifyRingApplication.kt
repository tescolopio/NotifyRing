package com.notifyring.app

import android.app.Application
import com.notifyring.app.di.AppContainer

class NotifyRingApplication : Application() {
    lateinit var container: AppContainer
        private set

    val isContainerInitialized: Boolean
        get() = ::container.isInitialized

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
