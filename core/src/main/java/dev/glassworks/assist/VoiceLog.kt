package dev.glassworks.assist

/**
 * Tiny log facade: android.util.Log on the device, stdout in JVM unit tests. The voice library keeps its own
 * copy with the same tag (libs/voice/.../dev/glassworks/voice/VoiceLog.kt), because it can't depend on the core.
 */
object VoiceLog {
    private const val TAG = "Glassworks.Voice"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true

    fun d(msg: String) = if (onAndroid) { android.util.Log.d(TAG, msg); Unit } else println("D/$TAG: $msg")
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

fun nowMs(): Long = System.nanoTime() / 1_000_000L
