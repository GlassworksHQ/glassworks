package dev.glassworks.voice

import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Whether the wake word can listen now, and if not, why ([VoiceInput.readiness]). Assist started from
 * Home Assistant still works in every state but the microphone ones: only the wake word needs the model
 * and the engine. Times are `SystemClock.elapsedRealtime`.
 */
public sealed interface VoiceReadiness {
    /** The wake word is loading. Its model may be downloading. */
    public data object Starting : VoiceReadiness

    /** It listens for the wake word, or there is none to listen for. */
    public data object Ready : VoiceReadiness

    /** The wake word's model couldn't be had. It tries again at [retryAtMs]. */
    public data class ModelFailed(
        /** Why. */
        val failure: ModelFailure,
        /** When it tries again on its own. */
        val retryAtMs: Long,
    ) : VoiceReadiness

    /** The wake word engine (TensorFlow Lite) didn't start on this device. It tries again at [retryAtMs]. */
    public data class EngineFailed(
        /** When it tries again on its own. */
        val retryAtMs: Long,
    ) : VoiceReadiness

    /** The app may not use the microphone (RECORD_AUDIO refused). */
    public data object MicrophoneNotAllowed : VoiceReadiness

    /**
     * The microphone doesn't work: it doesn't open (none, or another app holds it), or it opens but its reads
     * keep failing. It tries again every few seconds.
     */
    public data object MicrophoneUnavailable : VoiceReadiness
}

/** Why a wake word model couldn't be had ([VoiceReadiness.ModelFailed]). */
public sealed interface ModelFailure {
    /** The model's server didn't answer: no network, no DNS, a timeout. */
    public data object Offline : ModelFailure

    /** The server's certificate couldn't be verified: an old CA store, or the device's clock is wrong. */
    public data object Certificate : ModelFailure

    /** The secure connection failed for another reason. */
    public data object SecureConnection : ModelFailure

    /** The server answered with HTTP [status]. */
    public data class Http(
        /** The HTTP status code, such as 404. */
        val status: Int,
    ) : ModelFailure

    /** The download wasn't the model: it failed its SHA-256 check, so it wasn't used. */
    public data object CheckFailed : ModelFailure

    /** The model couldn't be saved on the device (storage full?). */
    public data object Storage : ModelFailure

    /** The model isn't on the device and there's nowhere to download it from (a custom openWakeWord model). */
    public data object Missing : ModelFailure
}

/** A download that failed, and why. Its message is the cause's, so the log line reads as it always has. */
internal class ModelDownloadException(message: String, val failure: ModelFailure, cause: Throwable? = null) : IOException(message, cause)

/** A server's answer other than 200. */
internal class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** What a failed download means, from its exception and its causes. */
internal fun modelFailureOf(e: Throwable): ModelFailure {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    chain.firstNotNullOfOrNull { (it as? ModelDownloadException)?.failure }?.let { return it }
    chain.firstNotNullOfOrNull { it as? HttpStatusException }?.let { return ModelFailure.Http(it.status) }
    return when {
        chain.any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException } -> ModelFailure.Certificate
        chain.any { it is SSLException } -> ModelFailure.SecureConnection
        else -> ModelFailure.Offline
    }
}

/** How long after the [failures]th failure in a row the engine tries again: 30 s, 1, 2, 5, then every 10 minutes. */
internal fun retryDelayMs(failures: Int): Long = RETRY_DELAYS_MS[(failures - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)]

private val RETRY_DELAYS_MS = longArrayOf(30_000, 60_000, 120_000, 300_000, 600_000)
