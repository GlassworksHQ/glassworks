package dev.glassworks.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.glassworks.esphome.WakeWordInfo
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Microphone + on-device wake word, feeding the ESPHome voice assistant ([controller]). Needs the
 * `RECORD_AUDIO` permission, which the app asks for.
 *
 * The microphone has one consumer: this engine. Every 80 ms frame goes through the wake word model
 * (so its buffers never hold stale audio), and while a pipeline run is starting/streaming the same
 * frame is handed to the controller, which sends it to Home Assistant. Detections only count while
 * the controller is idle and after the refractory period.
 *
 * Also the [WakeWordCatalog] behind Home Assistant's wake word select: the microWakeWord models
 * ([WakeWords.MICRO], fetched on first use into `micro/`), then, as a legacy fallback for one
 * release, every openWakeWord `*.tflite` already in the models dir except the shared
 * melspectrogram/embedding models. The active word id is persisted by the caller through
 * [onActiveWordChanged].
 *
 * A detection reports the word of the detector that fired. While a new word loads, the old one keeps
 * listening as itself. If the new word fails to load, the old detector is closed (a word HA didn't
 * pick must not answer), the error is reported, and loading is retried after 30 s, then less often
 * ([retryDelayMs]). [readiness] says which of these it is in, and why it can't listen.
 *
 * [retryDelay] gives the wait after the nth failure in a row. [loadDetector] loads a word's model; null
 * loads the real ones (tests pass a fake for both).
 */
public class VoiceEngine internal constructor(
    context: Context,
    private val config: VoiceConfig,
    initialWord: String,
    private val onActiveWordChanged: (String) -> Unit,
    private val retryDelay: (Int) -> Long = ::retryDelayMs,
    private val download: (URL) -> InputStream = ModelFetcher::httpGet,
    loadDetector: ((WakeWord) -> WakeDetector)?,
) : VoiceInput {
    /**
     * An engine with the models in [config]'s directory, listening for [initialWord] (a wake word id from
     * [available], or empty for none). [onActiveWordChanged] stores the word Home Assistant picks, so the
     * app can pass it back as [initialWord] next time.
     */
    public constructor(context: Context, config: VoiceConfig, initialWord: String, onActiveWordChanged: (String) -> Unit) :
        this(context, config, initialWord, onActiveWordChanged, ::retryDelayMs, ModelFetcher::httpGet, null)

    /** A loaded model and the word it detects. */
    private class Loaded(val id: String, val phrase: String, val detector: WakeDetector)

    @Volatile override var controller: VoiceAssistantController? = null
    @Volatile override var threshold: Float = config.threshold

    private val appContext: Context = context.applicationContext
    private val modelsDir: File = config.resolveModelsDir(appContext)
    private val microDir = File(modelsDir, "micro")
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-main").apply { isDaemon = true } }

    private val load: (WakeWord) -> WakeDetector = loadDetector ?: { word ->
        when (word) {
            is WakeWord.Micro -> loadMicro(word.model)
            is WakeWord.Legacy -> loadLegacy("${word.id}.tflite")
        }
    }
    private val wwLock = Any()
    @Volatile private var loaded: Loaded? = null // written under wwLock
    @Volatile private var activeWord: String = initialWord
    private var mic: Microphone? = null
    @Volatile private var gain = config.micGain
    // Mic thread only: level stats for the current pipeline run.
    private var runPeak = 0
    private var runSumSq = 0.0
    private var runSamples = 0L
    @Volatile private var muted = false
    @Volatile private var running = false
    private var refractoryUntil = Long.MIN_VALUE
    private var lastError: String? = null
    private var slowFrames = 0

    /** The wake word's side of [readiness]: Starting, Ready, or why its model didn't load. */
    @Volatile private var model: VoiceReadiness = VoiceReadiness.Starting

    /** The microphone's side of [readiness]: null while it opens (or is closed on purpose). */
    @Volatile private var micProblem: VoiceReadiness? = null

    /** Mic thread: failed reads since the last frame, or since the microphone opened ([closeMic]). */
    private var micReadFailures = 0

    /** Executor thread: failed loads in a row, and the next try. */
    private var failures = 0
    private var retry: ScheduledFuture<*>? = null

    private val readyLock = Any()
    private var published: VoiceReadiness? = null // under readyLock

    override val readiness: VoiceReadiness get() = micProblem ?: model
    @Volatile override var onReadiness: ((VoiceReadiness) -> Unit)? = null

    /** Errors for the UI (models missing, mic failure). Background thread. */
    @Volatile override var onError: ((String) -> Unit)? = null
    @Volatile override var onMicLevel: ((Float) -> Unit)? = null

    override fun start(muted: Boolean) {
        running = true
        this.muted = muted
        exec.execute { loadAfresh() }
        if (!muted) exec.execute { startMic() }
    }

    override fun stop() {
        running = false
        exec.shutdownNow()
        closeMic()
        clear()
        // Stopped, it reads Starting again, as before start; a new start reports afresh.
        model = VoiceReadiness.Starting
        synchronized(readyLock) { published = null }
    }

    /** A person's tap means "I fixed it": the backoff starts over. */
    override fun retryNow() {
        if (!running) return
        later { loadAfresh() }
    }

    override fun setMuted(m: Boolean) {
        if (m == muted || !running) return
        muted = m
        VoiceLog.i("Microphone ${if (m) "muted (released)" else "unmuted"}")
        exec.execute { if (m) stopMic() else startMic() }
    }

    override fun setGain(g: Float) {
        gain = g
        mic?.gain = g
    }

    // ------------------------------------------------------------------ WakeWordCatalog

    override fun available(): List<WakeWordInfo> =
        WakeWords.MICRO.map { WakeWordInfo(it.id, it.phrase) } +
            legacyFiles().map { WakeWord.Legacy(TfliteWakeWord.modelName(it.name)) }
                .map { WakeWordInfo(it.id, "${it.phrase} (openWakeWord)") }

    override fun activeIds(): List<String> = if (activeWord.isEmpty()) emptyList() else listOf(activeWord)

    override fun setActive(ids: List<String>) {
        val id = ids.firstOrNull() ?: ""
        if (id.isNotEmpty() && available().none { it.id == id }) {
            VoiceLog.w("Unknown wake word '$id'; ignoring")
            return
        }
        if (id == activeWord) return
        activeWord = id
        onActiveWordChanged(id)
        if (running) later { loadAfresh() }
    }

    /** openWakeWord models already on the panel (0.4 and earlier fetched them). */
    private fun legacyFiles(): List<File> =
        modelsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".tflite") &&
                f.name != TfliteWakeWord.MELSPEC_FILE && f.name != TfliteWakeWord.EMBEDDING_FILE
        }.orEmpty().sortedBy { it.name }

    // ------------------------------------------------------------------ internals

    private fun error(msg: String) {
        VoiceLog.w(msg)
        try { onError?.invoke(msg) } catch (_: Exception) {}
    }

    /** Runs [block] on the executor, unless the engine stopped (its executor is shut down). */
    private fun later(block: () -> Unit) {
        try { exec.execute(block) } catch (_: RejectedExecutionException) {}
    }

    /** Tells [onReadiness] when [readiness] changed. Any thread. */
    private fun publish() {
        // Read and delivered under the lock: a thread that read an older state can't deliver it after a newer one.
        synchronized(readyLock) {
            val now = readiness
            if (published == now) return
            published = now
            try { onReadiness?.invoke(now) } catch (e: Exception) { VoiceLog.w("readiness listener failed", e) }
        }
    }

    private fun setModel(r: VoiceReadiness) {
        model = r
        publish()
    }

    /** Executor thread. A new word, or the engine starting: no failures yet, so it shows Starting. */
    private fun loadAfresh() {
        failures = 0
        retry?.cancel(false)
        retry = null
        loadModel(showStarting = true)
    }

    private fun clear() {
        synchronized(wwLock) { loaded.also { loaded = null } }?.let(::closeQuietly)
    }

    /**
     * Makes [next] the detector, unless the engine stopped or HA picked another word while it
     * loaded. Checked under [wwLock], which [stop] also takes, so a model that finishes loading
     * after stop() is never left running.
     */
    private fun install(next: Loaded): Boolean {
        val old = synchronized(wwLock) {
            if (!running || next.id != activeWord) return false
            loaded.also { loaded = next }
        }
        if (old != null) closeQuietly(old)
        return true
    }

    private fun closeQuietly(l: Loaded) {
        // A throw here would end the load and its retry, so it's logged and dropped.
        try { l.detector.close() } catch (e: Exception) { VoiceLog.w("Closing wake word '${l.id}' failed: $e") }
    }

    /**
     * Executor thread. Loads [activeWord], replacing the current one. After a failure it tries again later
     * ([retryDelay]); while it does, [readiness] keeps the failure, unless [showStarting] (a new word, Try again).
     */
    private fun loadModel(showStarting: Boolean = false) {
        val id = activeWord
        val word = WakeWords.parse(id)
        if (word == null) {
            clear()
            setModel(VoiceReadiness.Ready)
            VoiceLog.i("No wake word selected; wake word detection off")
            return
        }
        if (loaded?.id == id) {
            // Back to the word that still listens, while another was loading (it showed Starting).
            failures = 0
            setModel(VoiceReadiness.Ready)
            return
        }
        if (showStarting) setModel(VoiceReadiness.Starting)
        // Every load, even one that finds its model and downloads nothing.
        ModelFetcher.sweep(if (word is WakeWord.Micro) microDir else modelsDir)
        val failed: Pair<String, (Long) -> VoiceReadiness> = try {
            val next = Loaded(id, word.phrase, load(word))
            if (!install(next)) { closeQuietly(next); return }
            lastError = null
            failures = 0
            setModel(VoiceReadiness.Ready)
            val engine = if (word is WakeWord.Micro) "microWakeWord" else "openWakeWord"
            VoiceLog.i("Wake word '$id' ('${word.phrase}', $engine) loaded; threshold $threshold")
            return
        } catch (e: ModelsMissingException) {
            (e.message ?: "Wake word models missing") to { at: Long -> VoiceReadiness.ModelFailed(e.failure, at) }
        } catch (e: Throwable) {
            "Wake word init failed: ${e.message}" to { at: Long -> VoiceReadiness.EngineFailed(at) }
        }
        val (failure, state) = failed
        if (id != activeWord || !running) return
        loaded?.let { VoiceLog.w("Wake word '${it.id}' stopped: '$id' could not load") }
        clear()
        if (failure != lastError) error(failure) // don't spam on every retry
        lastError = failure
        val wait = retryDelay(++failures)
        retry = try { exec.schedule(Runnable { loadModel() }, wait, TimeUnit.MILLISECONDS) } catch (_: RejectedExecutionException) { return }
        setModel(state(SystemClock.elapsedRealtime() + wait))
    }

    /**
     * The panel fetches the models itself (a web install, or a wiped app, has none yet): all four
     * at once when this one is missing or fails its hash, so switching words later works offline.
     */
    private fun loadMicro(model: MicroModel): WakeDetector {
        val file = "${model.id}.tflite"
        val fetcher = ModelFetcher.micro(download)
        if (file in fetcher.missing(microDir)) {
            try {
                fetcher.ensure(microDir)
            } catch (io: java.io.IOException) {
                throw ModelsMissingException("Downloading the wake word model $file failed: ${io.message}", modelFailureOf(io))
            }
        }
        return MicroWakeWord.load(File(microDir, file), model)
    }

    private fun loadLegacy(file: String): WakeDetector = try {
        TfliteWakeWord.load(modelsDir, file)
    } catch (e: Exception) {
        // Missing: fetch the set. Refused by TFLite: maybe a corrupt file, so fetch this word's files that fail
        // their hash; if that download fails too, the engine's error stands.
        if (e !is ModelsMissingException && e !is WakeEngineException) throw e
        val fetcher = if (e is WakeEngineException) {
            ModelFetcher(files = ModelFetcher.FILES.filterKeys { it == file || it == TfliteWakeWord.MELSPEC_FILE || it == TfliteWakeWord.EMBEDDING_FILE }, open = download)
        } else ModelFetcher(open = download)
        // TFLite refused files that pass their hash: the engine's error stands, nothing to repair.
        if (e is WakeEngineException && fetcher.missing(modelsDir).isEmpty()) throw e
        val fetched = try { fetcher.ensure(modelsDir) } catch (io: java.io.IOException) {
            throw ModelsMissingException("${e.message}; downloading them failed: ${io.message}", modelFailureOf(io))
        }
        if (fetched.isEmpty()) throw e
        TfliteWakeWord.load(modelsDir, file)
    }

    private fun startMic() {
        if (!running || muted || mic != null) return
        // onMicFrame copies what it keeps (toBytes for HA, the detectors' own buffers) before it returns: one frame array.
        val m = Microphone(gain = gain, allowed = ::micAllowed, onError = ::error, onFrame = ::onMicFrame, onOpenFailed = ::micOpenFailed, onReadFailed = ::micReadFailed, reuseFrame = true)
        mic = m
        m.start()
    }

    private fun stopMic() {
        closeMic()
        publish()
    }

    /**
     * Closes the microphone (its thread is joined) and forgets its failures: the next one starts its count over.
     * Then reports the level down, as no frame will.
     */
    private fun closeMic() {
        mic?.stop()
        mic = null
        micProblem = null
        micReadFailures = 0
        onMicLevel?.invoke(0f)
    }

    /**
     * Mic thread: a read failed, and the microphone opens again 1 s later. One failure can pass; [MIC_READ_FAILURES]
     * in a row with no frame between (about 3 s) is a microphone that opens but doesn't work.
     */
    internal fun micReadFailed(code: Int) {
        if (++micReadFailures < MIC_READ_FAILURES) { VoiceLog.w("Microphone read error $code"); return }
        if (micProblem == VoiceReadiness.MicrophoneUnavailable) return
        micProblem = VoiceReadiness.MicrophoneUnavailable
        error("The microphone opens but its reads fail (error $code, $micReadFailures in a row)")
        publish()
    }

    private fun micAllowed(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Mic thread, every 5 s while no source opens, or it may not open one. Logged once per reason, not on every try. */
    internal fun micOpenFailed() {
        val allowed = micAllowed()
        val now = if (allowed) VoiceReadiness.MicrophoneUnavailable else VoiceReadiness.MicrophoneNotAllowed
        if (now == micProblem) return
        micProblem = now
        error(if (allowed) "Cannot open the microphone (none, or another app holds it)" else "Cannot open the microphone: RECORD_AUDIO isn't granted")
        publish()
    }

    /** Mic thread. */
    internal fun onMicFrame(frame: ShortArray) {
        micReadFailures = 0
        if (micProblem != null) { micProblem = null; publish() }
        val c = controller
        val state = c?.currentState
        val streaming = state == VoiceAssistantController.State.STARTING || state == VoiceAssistantController.State.STREAMING
        if (streaming) {
            c?.onMicAudio(toBytes(frame))
            // For an orb or a meter (after micGain, i.e. what Home Assistant receives).
            onMicLevel?.invoke(MicLevel.fromRms(MicLevel.rms(frame)))
            for (v in frame) {
                val a = kotlin.math.abs(v.toInt())
                if (a > runPeak) runPeak = a
                runSumSq += a.toDouble() * a
            }
            runSamples += frame.size
        } else if (runSamples > 0) {
            onMicLevel?.invoke(0f)
            // Level of the audio HA received for the last run (helps tune mic gain).
            val rms = kotlin.math.sqrt(runSumSq / runSamples) / 32768.0
            VoiceLog.i("Run audio: %.1f s, peak %.3f, rms %.4f (gain %.1f)".format(
                runSamples / 16000.0, runPeak / 32768.0, rms, gain))
            runPeak = 0; runSumSq = 0.0; runSamples = 0
        }
        val t0 = nowMs()
        var phrase = ""
        val score = synchronized(wwLock) {
            val l = loaded ?: return
            phrase = l.phrase
            try {
                l.detector.process(frame)
            } catch (e: Exception) {
                VoiceLog.w("wake word inference failed", e)
                return
            }
        }
        val now = nowMs()
        if (now - t0 > 60 && ++slowFrames % 50 == 1) VoiceLog.w("Wake word inference slow: ${now - t0} ms per ${frame.size / 16} ms frame")
        if (score >= threshold && now >= refractoryUntil && state == VoiceAssistantController.State.IDLE) {
            refractoryUntil = now + config.refractoryMs
            VoiceLog.i("Wake word '$phrase' detected (score %.3f)".format(score))
            c?.onWakeWord(phrase)
        }
    }

    private fun toBytes(frame: ShortArray): ByteArray {
        val out = ByteArray(frame.size * 2)
        for (i in frame.indices) {
            val s = frame[i].toInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }
}

/** Failed microphone reads in a row, with no frame between, that make it [VoiceReadiness.MicrophoneUnavailable]. */
private const val MIC_READ_FAILURES = 3
