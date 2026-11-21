package dev.glassworks.assist

/**
 * One "a voice run is on screen" signal from both Assist looks (#691): the orb and the edge glow each report their
 * own, and [out] hears true while either shows and false once neither does. The timers hold a voice-set timer while
 * it is true, so a switch of look mid-run (one look coming, the other going) doesn't let them go early. Main thread.
 */
class VoiceShowing(private val out: (Boolean) -> Unit) {
    private var orb = false
    private var glow = false

    fun orb(showing: Boolean) { orb = showing; send() }

    fun glow(showing: Boolean) { glow = showing; send() }

    private fun send() = out(orb || glow)
}
