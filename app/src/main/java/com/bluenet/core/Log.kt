package com.bluenet.core

/**
 * Logging facade for the platform-independent core. The Android layer installs a sink that
 * forwards to android.util.Log; unit tests leave the default no-op sink in place.
 */
object Log {
    const val DEBUG = 3
    const val WARN = 5
    const val ERROR = 6

    fun interface Sink {
        fun log(level: Int, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { _, _, _, _ -> }

    fun d(tag: String, message: String) = sink.log(DEBUG, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.log(WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.log(ERROR, tag, message, error)
}
