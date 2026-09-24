package com.jarvis.app

import android.app.Application
import android.util.Log
import com.jarvis.ui.reliability.GlobalErrorHandler

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalErrorHandler.install(this)
        Log.i("JarvisApp", "JarvisApplication initialized")
    }
}
