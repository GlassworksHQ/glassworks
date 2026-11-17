/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.voice

import dev.glassworks.voice.ModelFailure
import dev.glassworks.voice.VoiceReadiness
import dev.glassworks.voice.WakeWords

/**
 * Whether voice is ready, in words (#458): the `voice_status` entity's state, and the line on the Assist
 * page (the panel's and the Manage page's), which also says what to do and when it tries again. Pure.
 */
object VoiceStatus {
    /** Home Assistant's state: short and stable, so an automation can match it. Null while voice is off. */
    fun entity(r: VoiceReadiness?): String? = when (r) {
        null -> null
        VoiceReadiness.Starting -> "Getting ready"
        VoiceReadiness.Ready -> "Ready"
        is VoiceReadiness.ModelFailed -> when (r.failure) {
            ModelFailure.CheckFailed -> "Model check failed"
            ModelFailure.Storage -> "Model not saved"
            ModelFailure.Missing -> "Model missing"
            else -> "Download failed"
        }
        is VoiceReadiness.EngineFailed -> "Engine failed"
        VoiceReadiness.MicrophoneNotAllowed -> "Microphone not allowed"
        VoiceReadiness.MicrophoneUnavailable -> "Microphone unavailable"
    }

    /** Something keeps voice from working that the page should show in rose. */
    fun problem(r: VoiceReadiness?): Boolean = r != null && r != VoiceReadiness.Starting && r != VoiceReadiness.Ready

    /** A failure the engine retries on its own, so "Try again" can hurry it. */
    fun retries(r: VoiceReadiness?): Boolean = r is VoiceReadiness.ModelFailed || r is VoiceReadiness.EngineFailed

    /**
     * The line under the Assist page's heading: why voice can't get ready and what to do, else what it
     * listens for ([AssistDefinition.status]). [nowMs] is on the same clock as the retry times.
     */
    fun line(r: VoiceReadiness?, muted: Boolean, wakeWord: String, nowMs: Long): String = when (r) {
        is VoiceReadiness.ModelFailed -> "${model(r.failure)} The wake word won't work until the panel has it. ${retry(r.retryAtMs, nowMs)}"
        is VoiceReadiness.EngineFailed -> "The wake word engine didn't start on this panel. Assist from Home Assistant still works. ${retry(r.retryAtMs, nowMs)}"
        VoiceReadiness.MicrophoneNotAllowed -> "Voice isn't allowed to use the microphone. Allow Microphone for this app in Android's app settings."
        VoiceReadiness.MicrophoneUnavailable -> "The microphone doesn't work: the panel has none, another app is using it, or its reads fail. Trying again every few seconds."
        VoiceReadiness.Starting -> if (muted || wakeWord.isEmpty()) AssistDefinition.status(muted, wakeWord)
            else "Getting “${WakeWords.parse(wakeWord)?.phrase ?: wakeWord}” ready"
        VoiceReadiness.Ready, null -> AssistDefinition.status(muted, wakeWord)
    }

    /** The Try again row's line: what failed and when it tries on its own. */
    fun retryLine(r: VoiceReadiness?, nowMs: Long): String = when (r) {
        is VoiceReadiness.ModelFailed -> "${entity(r)}. ${retry(r.retryAtMs, nowMs)}"
        is VoiceReadiness.EngineFailed -> "${entity(r)}. ${retry(r.retryAtMs, nowMs)}"
        else -> "Ready"
    }

    private fun model(f: ModelFailure): String = when (f) {
        ModelFailure.Offline -> "The wake word model couldn't be downloaded: the panel couldn't reach GitHub. Check that it can reach the internet."
        ModelFailure.Certificate -> "The wake word model couldn't be downloaded: the panel couldn't verify GitHub's certificate. Check the panel's date and time."
        ModelFailure.SecureConnection -> "The wake word model couldn't be downloaded: the secure connection to GitHub failed."
        is ModelFailure.Http -> "The wake word model couldn't be downloaded: GitHub answered HTTP ${f.status}."
        ModelFailure.CheckFailed -> "The wake word model failed its check after the download, so the panel didn't use it."
        ModelFailure.Storage -> "The wake word model couldn't be saved. Free some storage on the panel."
        ModelFailure.Missing -> "The wake word model isn't on the panel. Pick another wake word."
    }

    /** `Trying again in 4 min.`, `in 20 s.`, or `now.` when its time has come. */
    fun retry(atMs: Long, nowMs: Long): String {
        val s = (atMs - nowMs + 999) / 1000
        return when {
            s <= 0 -> "Trying again now."
            s < 60 -> "Trying again in $s s."
            else -> "Trying again in ${(s + 59) / 60} min."
        }
    }
}
