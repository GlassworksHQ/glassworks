package dev.glassworks.assist

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice chimes, made here from tones: no recordings, so nothing to license beyond this file. 24 kHz, 16-bit,
 * mono, little-endian PCM, each made once ([prepare]) and kept.
 *
 * Both are an open E add9 chord (E4 B4 E5 F#5 G#5 B5) on soft mallets that swell in, hold for a moment and let go
 * into a small room, so they read as a glow rather than a beep or a notification ding:
 *
 * | Chime | What it is | Length |
 * |---|---|---|
 * | [wake] | the chord once | 1.1 s |
 * | [timer] | the chord, then a fourth higher (A add9) 0.42 s later; the timers pane repeats it | 1.8 s |
 *
 * What makes them sound made rather than generated, each learned from a version that didn't:
 * - Several notes at once over about an octave and a half; one or two plain sines sound like a beep.
 * - Overtones slightly sharp of whole multiples ([STRETCH]), as a real bar or tine has.
 * - A 60 ms swell, not a strike: struck notes read as a notification.
 * - No detune and no pitch slides: two copies a few hertz apart beat, and it wobbles.
 * - The tail is a room (decaying noise, highs dying first), not a fade or comb filters. Its lows don't outlast its
 *   mids, so the sound doesn't sink in pitch as it fades; every step goes up.
 * - Low-passed twice at [BRIGHTNESS] Hz: nothing shrill on a small speaker.
 */
object Chimes {
    const val RATE = 24_000

    /** The wake word was heard: the chord once. */
    val wake: ByteArray by lazy { makeWake() }

    /** A timer finished: the chord, then a fourth higher. A little louder than [wake]: it's an alert. */
    val timer: ByteArray by lazy { makeTimer() }

    /**
     * Makes both chimes now, on a background thread of their own. Making one takes tens of milliseconds here and
     * a few hundred on a slow panel, so the device does this at start, and the first wake word chimes on time.
     */
    fun prepare() {
        Thread({ wake; timer }, "chimes").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    internal fun makeWake() = render(1.1, listOf(Strike(0.0, 1.0, 1.0)), peak = 0.20, fadeOut = 0.25, seed = 27)

    internal fun makeTimer() =
        render(1.8, listOf(Strike(0.0, 1.0, 0.8), Strike(0.42, 4.0 / 3, 1.0)), peak = 0.28, fadeOut = 0.30, seed = 29)

    /** E add9, open voicing: frequency (Hz) and level. */
    private val CHORD = listOf(329.6 to 0.55, 493.9 to 0.8, 659.3 to 1.0, 740.0 to 0.6, 830.6 to 0.7, 987.8 to 0.45)

    /** Overtones: multiple and level. */
    private val PARTIALS = listOf(1 to 1.0, 2 to 0.28, 3 to 0.08, 4 to 0.03)
    private const val STRETCH = 0.0004
    private const val ATTACK = 0.06
    private const val HOLD = 0.08
    private const val RELEASE = 0.06
    /** How far apart the chord's notes start, at most: a hand, not a strum. */
    private const val SPREAD = 0.008
    private const val BRIGHTNESS = 3200.0
    /** The room's level against the dry sound, both at full scale. */
    private const val ROOM = 0.30

    /** The chord at [at] s, every note times [scale], at [gain]. */
    private class Strike(val at: Double, val scale: Double, val gain: Double)

    private fun render(seconds: Double, strikes: List<Strike>, peak: Double, fadeOut: Double, seed: Long): ByteArray {
        val rng = Random(seed)
        val n = (seconds * RATE).toInt()
        val dry = DoubleArray(n)
        for (s in strikes) for ((f, level) in CHORD) {
            val start = ((s.at + rng.nextDouble() * SPREAD) * RATE).toInt()
            note(dry, start, f * s.scale, s.gain * level, rng)
        }
        lowpass(dry, BRIGHTNESS); lowpass(dry, BRIGHTNESS)
        val wet = convolve(dry, room(rng))
        val dryMax = maxAbs(dry); val wetMax = maxAbs(wet)
        val mix = DoubleArray(n) { dry[it] / dryMax + ROOM * wet[it] / wetMax }
        // A raised-cosine fade over the last [fadeOut] s, so the tail ends at silence.
        val fade = (fadeOut * RATE).toInt()
        for (i in 0 until fade) mix[n - fade + i] *= 0.5 * (1 + cos(PI * i / fade))
        val scale = peak / maxAbs(mix)
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (mix[i] * scale * 32767).toInt()
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** One mallet note into [into] from sample [start]: swell in, hold, let go; higher overtones fade sooner. */
    private fun note(into: DoubleArray, start: Int, f: Double, gain: Double, rng: Random) {
        val phases = DoubleArray(PARTIALS.size) { rng.nextDouble() * 2 * PI }
        for (k in 0 until into.size - start) {
            val t = k.toDouble() / RATE
            val swell = sin(PI / 2 * min(1.0, t / ATTACK)).let { it * it }
            val env = swell * if (t < ATTACK + HOLD) 1.0 else exp(-(t - ATTACK - HOLD) / RELEASE)
            if (env < 1e-6 && t > ATTACK) break
            var v = 0.0
            for ((p, partial) in PARTIALS.withIndex()) {
                val (m, level) = partial
                val fm = f * m * sqrt(1 + STRETCH * m * m)
                v += level * exp(-t / (0.25 / m.toDouble().pow(0.7))) * sin(2 * PI * fm * t + phases[p])
            }
            into[start + k] += gain * env * v
        }
    }

    /** The room: 1.2 s of noise after a 12 ms pre-delay, lows, mids and highs each dying at their own rate. */
    private fun room(rng: Random): DoubleArray {
        val n = (1.2 * RATE).toInt()
        val noise = DoubleArray(n) { rng.nextGaussian() }
        val low = noise.copyOf().also { lowpass(it, 700.0) }
        val belowHigh = noise.copyOf().also { lowpass(it, 2500.0) }
        val pre = (0.012 * RATE).toInt()
        val ir = DoubleArray(pre + n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val high = noise[i] - belowHigh[i]
            val mid = noise[i] - low[i] - high
            val v = low[i] * exp(-t / 0.20) + mid * exp(-t / 0.28) + 0.5 * high * exp(-t / 0.12)
            ir[pre + i] = v * min(1.0, t / 0.02)
        }
        return ir
    }

    /** One-pole low-pass at [fc] Hz, in place. */
    private fun lowpass(x: DoubleArray, fc: Double) {
        val a = 1 - exp(-2 * PI * fc / RATE)
        var s = 0.0
        for (i in x.indices) { s += a * (x[i] - s); x[i] = s }
    }

    private fun maxAbs(x: DoubleArray): Double = x.maxOf { abs(it) }.coerceAtLeast(1e-12)

    /** The first `x.size` samples of x convolved with [h], by FFT (direct would be ~10⁹ steps). */
    private fun convolve(x: DoubleArray, h: DoubleArray): DoubleArray {
        var size = 1
        while (size < x.size + h.size) size = size shl 1
        val ar = x.copyOf(size); val ai = DoubleArray(size)
        val br = h.copyOf(size); val bi = DoubleArray(size)
        fft(ar, ai, false); fft(br, bi, false)
        for (i in 0 until size) {
            val r = ar[i] * br[i] - ai[i] * bi[i]
            ai[i] = ar[i] * bi[i] + ai[i] * br[i]; ar[i] = r
        }
        fft(ar, ai, true)
        return DoubleArray(x.size) { ar[it] / size }
    }

    /** In-place radix-2 FFT; [inverse] leaves the 1/n scaling to the caller. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
