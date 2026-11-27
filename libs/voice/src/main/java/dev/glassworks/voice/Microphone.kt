package dev.glassworks.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous 16 kHz / 16-bit / mono capture with [AudioRecord] on its own thread.
 * Delivers exactly [frameSamples] samples per callback (80 ms by default, the openWakeWord frame).
 * Tries `VOICE_RECOGNITION` first (tuned for ASR, no AGC on most devices), then `MIC`.
 * Re-opens the recorder after errors.
 */
internal class Microphone(
    gain: Float,
    /**
     * Whether the app holds `RECORD_AUDIO`. Without it no recorder is made: on Android 6 one made without it
     * crashed the app in native code (#583). It's asked before every try, so a grant takes effect.
     */
    private val allowed: () -> Boolean,
    private val frameSamples: Int = OwwFeatures.FRAME,
    private val onError: (String) -> Unit,
    private val onFrame: (ShortArray) -> Unit,
    /** No source opened. It tries again 5 s later. */
    private val onOpenFailed: () -> Unit = { onError("Cannot open the microphone (RECORD_AUDIO granted? mic busy?)") },
    /** A read failed with this code. It opens the microphone again 1 s later. */
    private val onReadFailed: (Int) -> Unit = { onError("Microphone read error $it") },
    /** Makes a recorder for an audio source with this buffer size in bytes. Tests count the calls. */
    private val newRecorder: (source: Int, bufBytes: Int) -> AudioRecord = ::recorder,
    /**
     * [onFrame] is done with a frame when it returns (it copies what it keeps): the next read fills the same array
     * instead of a new one, 12.5 times a second for as long as the microphone is open. False: each frame is new, for
     * a consumer that keeps it.
     */
    private val reuseFrame: Boolean = false,
) {
    companion object {
        const val RATE = 16_000

        @SuppressLint("MissingPermission") // loop() checks [allowed] first.
        private fun recorder(source: Int, bufBytes: Int) =
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
    }

    /** Linear gain applied to every sample; may be changed while running. */
    @Volatile var gain: Float = gain

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "voice-mic").apply { start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.let {
            it.interrupt()
            it.join(2_000)
        }
        thread = null
    }

    private fun open(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf * 2, frameSamples * 2 * 8)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val rec = try {
                newRecorder(source, bufBytes)
            } catch (e: Exception) {
                VoiceLog.w("AudioRecord(source=$source) failed", e)
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                continue
            }
            try {
                rec.startRecording()
            } catch (e: Exception) {
                VoiceLog.w("startRecording(source=$source) failed", e)
                rec.release()
                continue
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                continue
            }
            VoiceLog.i("Microphone open: source=$source buffer=$bufBytes")
            return rec
        }
        return null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running.get()) {
            val rec = if (allowed()) open() else null
            if (rec == null) {
                if (running.get()) onOpenFailed()
                sleepQuietly(5_000)
                continue
            }
            try {
                readLoop(rec)
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
            if (running.get()) sleepQuietly(1_000)
        }
    }

    private fun readLoop(rec: AudioRecord) {
        var frame = ShortArray(frameSamples)
        var filled = 0
        while (running.get()) {
            val n = rec.read(frame, filled, frameSamples - filled)
            if (n < 0) {
                // A read that outlived stop()'s join: a stopped microphone reports nothing.
                if (running.get()) onReadFailed(n)
                return
            }
            filled += n
            if (filled < frameSamples) continue
            val g = gain
            if (g != 1f) applyGain(frame, g)
            try {
                onFrame(frame)
            } catch (e: Exception) {
                VoiceLog.w("mic frame handler failed", e)
            }
            // A consumer that keeps frames gets a new one each time; one that copies (reuseFrame) gets the same array.
            if (!reuseFrame) frame = ShortArray(frameSamples)
            filled = 0
        }
    }

    private fun applyGain(frame: ShortArray, gain: Float) {
        for (i in frame.indices) {
            frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
