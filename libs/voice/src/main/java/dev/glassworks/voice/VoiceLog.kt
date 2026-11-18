package dev.glassworks.voice

/**
 * Tiny log facade: android.util.Log on the device, stdout in JVM unit tests. The tag is the app's voice tag.
 * The core keeps its own copy (core/.../dev/glassworks/assist/VoiceLog.kt) for its players: the library
 * can't depend on the core.
 */
internal object VoiceLog {
    private const val TAG = "Glassworks.Voice"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true

    fun d(msg: String) = if (onAndroid) { android.util.Log.d(TAG, msg); Unit } else println("D/$TAG: $msg")
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

internal fun nowMs(): Long = System.nanoTime() / 1_000_000L
