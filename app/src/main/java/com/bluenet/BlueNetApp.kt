package com.bluenet

import android.app.Application
import com.bluenet.core.Log

class BlueNetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.sink = Log.Sink { level, tag, message, error ->
            when (level) {
                Log.ERROR -> android.util.Log.e(tag, message, error)
                Log.WARN -> android.util.Log.w(tag, message, error)
                else -> if (error != null) android.util.Log.d(tag, message, error) else android.util.Log.d(tag, message)
            }
        }
    }
}
