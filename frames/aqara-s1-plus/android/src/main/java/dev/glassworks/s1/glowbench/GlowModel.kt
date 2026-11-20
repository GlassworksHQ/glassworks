package dev.glassworks.s1.glowbench

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * THROWAWAY (test-build/glowbench-s1, never merged): the #691 edge glow ("edge-lit glass") as a model, ported from
 * docs/design/overlays/edge.js on #694 with Spark's calm speaking. Lengths in dp along the screen's border: s runs
 * clockwise from the top left corner, round a [w] by [h] dp screen. Pure; times in ms.
 */
class GlowModel(val w: Float, val h: Float, val variant: Variant) {
    enum class Variant(val enterMs: Float, val exitMs: Float) { SPARK(650f, 400f), SHEEN(600f, 350f), POUR(700f, 450f) }
    enum class Phase { ENTER, LISTENING, THINKING, SPEAKING, EXIT, GAP }

    val p = 2 * (w + h)
    private val sMic = 0.5f * w // a placeholder: the middle of the top edge
    private val sBottom = 1.5f * w + h
    private val sTop = 0.5f * w

    /** One run: entrance, listening, thinking, speaking, exit, then a gap (nothing on screen). */
    val phases: List<Pair<Phase, Float>> = listOf(
        Phase.ENTER to variant.enterMs, Phase.LISTENING to 3600f, Phase.THINKING to 2600f,
        Phase.SPEAKING to 3800f, Phase.EXIT to variant.exitMs, Phase.GAP to 700f,
    )
    val totalMs = phases.sumOf { it.second.toDouble() }.toFloat()

    class At(val phase: Phase, val u: Float)

    fun at(t: Float): At {
        var u = t
        for ((ph, d) in phases) { if (u < d) return At(ph, u); u -= d }
        return At(Phase.GAP, u)
    }

    class Flare(val s: Float, val age: Float, val life: Float, val size: Float, val col: Int, val amp: Float, val grow: Boolean = false)

    /** What one frame draws: the light at any point of the border ([eval]), caustics, the sheen band, presence. */
    inner class Frame(
        val eval: (Float, FloatArray) -> Unit,
        val flares: List<Flare>,
        val presence: Float,
        val thick: Float,
        val sheenQ: Float = Float.NaN,
        val sheenA: Float = 0f,
    )

    // ------------------------------------------------------------------ geometry
    /** x, y in dp and the edge (0 top, 1 right, 2 bottom, 3 left) of border point [s], into [out]. */
    fun pos(s0: Float, out: FloatArray) {
        val s = ((s0 % p) + p) % p
        when {
            s < w -> { out[0] = s; out[1] = 0f; out[2] = 0f }
            s < w + h -> { out[0] = w; out[1] = s - w; out[2] = 1f }
            s < 2 * w + h -> { out[0] = w - (s - w - h); out[1] = h; out[2] = 2f }
            else -> { out[0] = 0f; out[1] = h - (s - 2 * w - h); out[2] = 3f }
        }
    }
    fun dist(s: Float, c: Float): Float = (((s - c) % p) + p * 1.5f) % p - p / 2
    fun diag(x: Float, y: Float): Float = clamp((x / w + y / h) / 2)

    private val xy = FloatArray(3)
    private fun posture(x: Float, y: Float): Float = when (variant) {
        Variant.SHEEN -> { val k = diag(x, y); 0.12f + 0.35f * exp(-(min(k, 1 - k) / 0.16f).pow(2)) }
        Variant.POUR -> 0.1f + 0.3f * (y / h).pow(2)
        Variant.SPARK -> 0.2f
    }

    // ------------------------------------------------------------------ a frame
    fun frame(t: Float): Frame? {
        val a = at(t)
        if (a.phase == Phase.GAP) return null
        val f = phaseFrame(a.phase, a.u)
        // cross-fade into thinking and speaking (300 ms)
        if (a.u < 300f && (a.phase == Phase.THINKING || a.phase == Phase.SPEAKING)) {
            val prev = if (a.phase == Phase.THINKING) Phase.LISTENING else Phase.THINKING
            val prevDur = phases.first { it.first == prev }.second
            val g = phaseFrame(prev, prevDur + a.u)
            val k = ease(a.u / 300f)
            val tmp = FloatArray(5)
            return Frame({ s, out ->
                f.eval(s, out); g.eval(s, tmp)
                for (i in 0 until 5) out[i] = out[i] * k + tmp[i] * (1 - k)
            }, f.flares + g.flares.map { Flare(it.s, it.age, it.life, it.size, it.col, it.amp * (1 - k), it.grow) }, 1f, f.thick * k + g.thick * (1 - k))
        }
        return f
    }

    private fun phaseFrame(ph: Phase, u: Float): Frame = when (ph) {
        Phase.ENTER -> when (variant) { Variant.SPARK -> sparkIn(u); Variant.SHEEN -> sheenIn(u); Variant.POUR -> pourIn(u) }
        Phase.LISTENING -> listening(u)
        Phase.THINKING -> thinking(u)
        Phase.SPEAKING -> speaking(u)
        Phase.EXIT -> exit(u)
        Phase.GAP -> Frame({ _, out -> out.fill(0f) }, emptyList(), 0f, 1f)
    }

    // out = [intensity, weight a, weight b, weight white, weight rose]
    private fun listening(u: Float): Frame {
        val l = micLevel(u)
        val ripples = ArrayList<FloatArray>()
        var te = 0f
        while (te <= u) {
            val lv = micLevel(te); val age = u - te
            if (lv > 0.3f && age < 1600f) ripples += floatArrayOf(0.62f * age, 0.95f * lv * (1 - age / 1600f).pow(1.4f))
            te += 140f
        }
        val tail = enterFrame(variant.enterMs + u).flares
        return Frame({ s, out ->
            pos(s, xy); val k = diag(xy[0], xy[1])
            var i = posture(xy[0], xy[1]) * (1 + 0.5f * l); var wa = i * (1 - k); var wb = i * k; var wh = 0f
            val d = dist(s, sMic); val sig = 34 + 120 * l
            val m = (0.3f + 1.0f * l) * exp(-d * d / (2 * sig * sig))
            i += m; wa += m * (1 - k) * (1 - .4f * l); wb += m * k * (1 - .4f * l); wh += m * .4f * l
            for (r in ripples) {
                val ga = r[1] * exp(-(d + r[0]).pow(2) / (2 * 22f * 22f)); val gb = r[1] * exp(-(d - r[0]).pow(2) / (2 * 22f * 22f))
                i += ga + gb; wa += ga; wb += gb
            }
            out[0] = i; out[1] = wa; out[2] = wb; out[3] = wh; out[4] = 0f
        }, tail, 1f, 1 + 0.5f * l)
    }

    private fun phi(u: Float) = 1.05f * u + 120 * sin(u * 0.0024f)
    private val crossings: FloatArray by lazy {
        FloatArray(5) { idx ->
            val n = idx + 1; var lo = 0f; var hi = 6000f
            repeat(40) { val mid = (lo + hi) / 2; if (2 * phi(mid) < n * p) lo = mid else hi = mid }
            lo
        }
    }

    private fun thinking(u: Float): Frame {
        val ph = phi(u); val heads = floatArrayOf(sMic - ph, sMic + ph)
        val flares = ArrayList<Flare>()
        for (idx in crossings.indices) {
            val age = u - crossings[idx]
            if (age in 0f..520f) flares += Flare(sMic + (idx + 1) * p / 2, age, 520f, 1.15f, HOT, 1f)
        }
        var j = floor((u - 300) / 90).toInt()
        while (j <= floor(u / 90).toInt()) {
            if (j >= 0 && hash(j.toFloat()) >= 0.45f) {
                val age = u - j * 90
                if (age in 0f..300f) flares += Flare(hash(j + 7.3f) * p, age, 300f, .28f, if (hash(j + 2.1f) < .5f) A else B, .9f)
            }
            j++
        }
        return Frame({ s, out ->
            pos(s, xy); val k = diag(xy[0], xy[1])
            var i = .07f; var wa = i * (1 - k); var wb = i * k; var wh = 0f
            for (n in 0..1) {
                val dir = if (n == 0) -1f else 1f
                val d = dist(s, heads[n]) * dir
                val v = if (d > 0) 1.7f * exp(-d * d / (2 * 28f * 28f)) else 1.7f * exp(d / 340f) * (0.5f + 0.5f * exp(d / 40f))
                i += v; if (n == 0) wa += v else wb += v
                wh += v * .45f * exp(-d * d / (2 * 16f * 16f))
            }
            out[0] = i; out[1] = wa; out[2] = wb; out[3] = wh; out[4] = 0f
        }, flares, 1f, 1f)
    }

    /** Calm (round 2 review): a soft light resting in the corners, breathing slowly with the voice; no pulses or flares. */
    private fun speaking(u: Float): Frame {
        var l = 0f; var n = 0; var te = u - 700
        while (te <= u) { l += ttsLevel(max(0f, te)); n++; te += 50 }
        l /= n
        return Frame({ s, out ->
            pos(s, xy); val k = diag(xy[0], xy[1])
            val corner = exp(-(min(k, 1 - k) / 0.2f).pow(2))
            val under = if (xy[1] >= h - 0.5f) 0.6f else 1f
            val i = (0.34f + 0.36f * corner) * (0.88f + 0.3f * l) * under
            out[0] = i; out[1] = i * (1 - k); out[2] = i * k; out[3] = 0f; out[4] = 0f
        }, emptyList(), 1f, 1 + .12f * l)
    }

    private fun enterFrame(u: Float) = when (variant) { Variant.SPARK -> sparkIn(u); Variant.SHEEN -> sheenIn(u); Variant.POUR -> pourIn(u) }

    private fun sparkIn(u: Float): Frame {
        val half = p / 2; val ph = half * ease((u - 90) / 430)
        val flares = ArrayList<Flare>()
        if (u < 260) flares += Flare(sMic, u, 260f, .55f * easeOut(u / 90), HOT, 1f, true)
        if (u >= 520 && u < 1250) flares += Flare(sMic + half, u - 520, 730f, 1.5f, HOT, 1f)
        if (u >= 540 && u < 1150) flares += Flare(sMic + half, u - 540, 610f, 1f, B, .8f)
        return Frame({ s, out ->
            out.fill(0f)
            if (u < 90) return@Frame
            pos(s, xy); val d = abs(dist(s, sMic))
            if (d > ph + 40) return@Frame
            val lit = if (d <= ph) 1f else exp(-(d - ph).pow(2) / (2 * 18f * 18f))
            val tLit = 90 + 430 * invEase(min(1f, d / half))
            val after = exp(-(u - tLit) / 260f)
            var i = lit * (posture(xy[0], xy[1]) + 0.55f * after)
            val left = dist(s, sMic) < 0
            val head = if (ph < half) 1.2f * exp(-(d - ph).pow(2) / (2 * 24f * 24f)) else 0f
            i += head
            out[0] = i; out[1] = i * (if (left) .8f else .2f) * (1 - .3f * after); out[2] = i * (if (left) .2f else .8f) * (1 - .3f * after)
            out[3] = i * .3f * after + head * .6f
        }, flares, ease(u / 650), 1 + .4f * clamp(1 - u / 650))
    }

    private fun sheenQ(u: Float) = -0.2f + 1.4f * ease(u / 460)
    private val sheenTimes: FloatArray by lazy {
        floatArrayOf(0f, 1f).map { target -> var lo = 0f; var hi = 460f; repeat(30) { val m = (lo + hi) / 2; if (sheenQ(m) < target) lo = m else hi = m }; lo }.toFloatArray()
    }

    private fun sheenIn(u: Float): Frame {
        val q = sheenQ(u); val (tTl, tBr) = sheenTimes.let { it[0] to it[1] }
        val flares = ArrayList<Flare>()
        if (u >= tTl && u < tTl + 520) flares += Flare(0f, u - tTl, 520f, 1.2f, A, 1f)
        if (u >= tBr && u < tBr + 650) flares += Flare(w + h, u - tBr, 650f, 1.35f, B, 1f)
        if (u >= tBr && u < tBr + 400) flares += Flare(w + h, u - tBr, 400f, .8f, HOT, .8f)
        return Frame({ s, out ->
            out.fill(0f)
            pos(s, xy); val g = diag(xy[0], xy[1])
            if (g > q + .02f) return@Frame
            val since = max(0f, q - g); val spike = exp(-since / .07f)
            val i = posture(xy[0], xy[1]) + .95f * spike + .15f * exp(-since / .3f)
            out[0] = i; out[1] = i * (1 - g); out[2] = i * g; out[3] = .5f * spike * i
        }, flares, ease(u / 600), 1f, if (u < 560) q else Float.NaN, .2f * clamp(1 - (u - 380) / 180))
    }

    private fun pourIn(u: Float): Frame {
        val half = p / 2; val ph = half * ease((u - 120) / 480)
        val flares = ArrayList<Flare>()
        if (u < 380) flares += Flare(sBottom, u, 380f, .9f * easeOut(u / 120), HOT, 1f, true)
        if (u >= 600 && u < 1300) flares += Flare(sTop, u - 600, 700f, 1.4f, HOT, 1f)
        if (u >= 640 && u < 1200) flares += Flare(sTop, u - 640, 560f, 1f, A, .8f)
        return Frame({ s, out ->
            out.fill(0f)
            if (u < 40) return@Frame
            pos(s, xy); val k = diag(xy[0], xy[1]); val d = abs(dist(s, sBottom))
            val front = max(ph, 30 * easeOut(u / 120))
            if (d > front + 50) return@Frame
            val lit = if (d <= front) 1f else exp(-(d - front).pow(2) / (2 * 22f * 22f))
            val tLit = if (d < 30) 40f else 120 + 480 * invEase(min(1f, d / half))
            val hot = exp(-max(0f, u - tLit) / 240f)
            val head = if (ph < half) 1.3f * exp(-(d - ph).pow(2) / (2 * 34f * 34f)) else 0f
            val i = lit * (posture(xy[0], xy[1]) + .9f * hot) + head
            val cool = i * (1 - hot)
            out[0] = i; out[1] = cool * (1 - k); out[2] = cool * k; out[3] = i * hot + head * .5f
        }, flares, ease(u / 700), 1 + .5f * exp(-max(0f, u - 300) / 300f))
    }

    private fun exit(u: Float): Frame {
        val x = variant.exitMs
        val base = speaking(3800 + u)
        return when (variant) {
            Variant.SPARK -> {
                val rho = (p / 2 + 40) * (1 - ease(u / (x - 80)))
                val flares = if (u > x - 120) listOf(Flare(sMic, u - (x - 120), 160f, .5f, HOT, 1f)) else emptyList()
                Frame({ s, out ->
                    base.eval(s, out)
                    val d = abs(dist(s, sMic))
                    val m = if (d < rho) 1f else exp(-(d - rho).pow(2) / (2 * 16f * 16f))
                    val b = if (u < x - 80) .9f * exp(-(d - rho).pow(2) / (2 * 20f * 20f)) else 0f
                    out[0] = out[0] * m + b; out[1] = out[1] * m + b * .5f; out[2] = out[2] * m + b * .5f; out[3] = out[3] * m + b * .4f
                }, flares, 1 - ease(u / x), 1f)
            }
            Variant.SHEEN -> {
                val q = -0.2f + 1.4f * ease(u / (x - 30))
                Frame({ s, out ->
                    base.eval(s, out)
                    pos(s, xy); val g = diag(xy[0], xy[1])
                    if (g < q - .01f) { out.fill(0f); return@Frame }
                    val spike = exp(-max(0f, g - q) / .05f) * .8f
                    out[0] += spike; out[1] += spike * (1 - g); out[2] += spike * g; out[3] += spike * .4f
                }, emptyList(), 1 - ease(u / x), 1f, q, .12f)
            }
            Variant.POUR -> {
                val psi = (p / 2 + 30) * ease(u / (x - 50))
                Frame({ s, out ->
                    base.eval(s, out)
                    val d = abs(dist(s, sTop))
                    val m = (if (d > psi) 1f else exp(-(psi - d).pow(2) / (2 * 26f * 26f))) * (1 - .5f * (u / x))
                    out[0] *= m; out[1] *= m; out[2] *= m; out[3] = 0f
                }, emptyList(), 1 - ease(u / x), 1f)
            }
        }
    }

    companion object {
        const val A = 0; const val B = 1; const val HOT = 2; const val ROSE = 3

        fun clamp(x: Float, a: Float = 0f, b: Float = 1f) = max(a, min(b, x))
        fun ease(x0: Float): Float { val x = clamp(x0); return if (x < .5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2 }
        fun easeOut(x: Float) = 1 - (1 - clamp(x)).pow(3)
        private val invLut = FloatArray(257).also { lut ->
            for (i in 0..256) { val y = i / 256f; var lo = 0f; var hi = 1f; repeat(24) { val m = (lo + hi) / 2; if (ease(m) < y) lo = m else hi = m }; lut[i] = lo }
        }
        fun invEase(y: Float): Float { val f = clamp(y) * 256; val i = f.toInt().coerceAtMost(255); return invLut[i] + (invLut[i + 1] - invLut[i]) * (f - i) }
        fun hash(n: Float): Float { val s = sin(n * 127.1f + 311.7f) * 43758.5453f; return s - floor(s) }

        /** A fake voice: "Turn off the kitchen lights" from 0.3 s to 2.5 s of listening, then quiet. */
        fun micLevel(u: Float): Float {
            val env = clamp((u - 300) / 120) * clamp((2500 - u) / 160)
            val syl = (0.5f + 0.5f * sin(u * 0.021f + 1.9f * sin(u * 0.0053f))).pow(1.6f)
            return 0.06f + 0.94f * env * syl
        }

        /** The reply's level: the app's TTS pulse (AudioLevel.ttsPulse), shaped into words. */
        fun ttsLevel(u: Float): Float {
            val env = clamp(u / 150) * clamp((3500 - u) / 250)
            return env * (0.35f + 0.25f * sin(u * 0.027f + 2 * sin(u * 0.0041f))) * (0.7f + 0.6f * (0.5f + 0.5f * sin(u * 0.019f)).pow(2))
        }
    }
}
