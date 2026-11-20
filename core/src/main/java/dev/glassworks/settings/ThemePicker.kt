package dev.glassworks.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.glassworks.pane.SettingsItem
import dev.glassworks.ui.Glass
import dev.glassworks.ui.Glass.Companion.BD
import dev.glassworks.ui.Glass.Companion.FA
import dev.glassworks.ui.Glass.Companion.TX
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The theme picker on the panel (#691's approved mockup): one card per theme ([SettingsItem.Swatch.group]) with its
 * light and its dark orb under the theme's name, the cards side by side and wrapping on a narrow page. The picked orb
 * is ringed and carries a sun (light) or moon (dark) badge; a tap on another picks it at once ([onPick]). The Manage
 * page draws the same cards ([dev.glassworks.manage.ManagePages]).
 */
class ThemePicker(context: Context, private val item: SettingsItem.Choice, private val onPick: (String) -> Unit) : ViewGroup(context) {
    private val g = Glass(context)
    private val dp = context.resources.displayMetrics.density
    private val gap = (10 * dp).toInt()
    private val minCard = (104 * dp).toInt()
    private val cards = ArrayList<Card>()

    init {
        val swatch = item.swatch ?: error("ThemePicker needs a choice with swatches")
        val picked = runCatching { item.value() }.getOrNull()
        // One card per group, in the options' order; each option is an orb in its group's card.
        val groups = LinkedHashMap<String, MutableList<Pair<String, SettingsItem.Swatch>>>()
        for ((id, _) in runCatching { item.options() }.getOrDefault(emptyList())) {
            val s = swatch(id)
            groups.getOrPut(s.group) { ArrayList() } += id to s
        }
        for ((name, orbs) in groups) Card(name, orbs, picked).also { cards += it; addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cols = columns(w)
        val cw = (w - gap * (cols - 1)) / cols
        var h = 0; var row = 0
        for ((i, c) in cards.withIndex()) {
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            row = max(row, c.measuredHeight)
            if (i % cols == cols - 1 || i == cards.size - 1) { h += row + (if (h > 0) gap else 0); row = 0 }
        }
        // Every card in a row is as tall as the tallest (a name with a note under it).
        setMeasuredDimension(w, h + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val cols = columns(w)
        val cw = (w - gap * (cols - 1)) / cols
        var y = paddingTop
        for (start in cards.indices step cols) {
            val rowCards = cards.subList(start, min(cards.size, start + cols))
            val rh = rowCards.maxOf { it.measuredHeight }
            for ((j, c) in rowCards.withIndex()) {
                c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rh, MeasureSpec.EXACTLY))
                val x = j * (cw + gap)
                c.layout(x, y, x + cw, y + rh)
            }
            y += rh + gap
        }
    }

    private fun columns(w: Int): Int = max(1, min(cards.size, (w + gap) / (minCard + gap)))

    /** One theme: its orbs side by side, its name, and a note under it ("Default"). */
    private inner class Card(name: String, orbs: List<Pair<String, SettingsItem.Swatch>>, picked: String?) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val on = orbs.any { it.first == picked }
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(if (on) Color.argb(15, 255, 255, 255) else Color.argb(6, 255, 255, 255))
                setStroke(dp.toInt().coerceAtLeast(1), if (on) Color.argb(72, 255, 255, 255) else BD)
            }
            setPadding((8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), (11 * dp).toInt())
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                for ((i, o) in orbs.withIndex()) {
                    val (id, s) = o
                    addView(Orb(id, s, id == picked), LayoutParams(((OrbSize + 12) * dp).toInt(), ((OrbSize + 12) * dp).toInt()).apply {
                        if (i > 0) marginStart = (2 * dp).toInt()
                    })
                }
            }, LayoutParams(-2, -2))
            addView(g.text(13.5f, TX).apply { text = name; gravity = Gravity.CENTER; setSingleLine() }, LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
            val note = orbs.first().second.note
            if (note.isNotEmpty()) addView(g.text(11f, FA).apply { text = note; gravity = Gravity.CENTER; setSingleLine() }, LayoutParams(-2, -2))
        }
    }

    /**
     * One orb: the light variant a light gradient with a highlight, the dark one the two lights inside dark glass (as
     * the mockup's swatches). Picked: a ring, and a badge with a sun or a moon. Drawn once; a tap picks it.
     */
    private inner class Orb(private val id: String, private val s: SettingsItem.Swatch, private val picked: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = TX }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; color = if (s.dark) Color.argb(36, 255, 255, 255) else Color.argb(26, 16, 20, 32) }

        init {
            contentDescription = "${s.group}, ${if (s.dark) "dark" else "light"}"
            isClickable = true
            setOnClickListener { if (!picked) onPick(id) }
        }

        override fun hasOverlappingRendering() = false

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = OrbSize * dp / 2
            if (s.dark) {
                c.drawCircle(cx, cy, r, p.apply { shader = null; color = mix(s.ground, Color.WHITE, .05f) })
                p.shader = RadialGradient(cx - .36f * r, cy - .32f * r, 1.24f * r, intArrayOf(s.a, withAlpha(s.a, 0)), null, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
                p.shader = RadialGradient(cx + .4f * r, cy + .44f * r, 1.28f * r, intArrayOf(s.b, withAlpha(s.b, 0)), null, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
                p.shader = RadialGradient(cx - .4f * r, cy - .44f * r, .44f * r, intArrayOf(Color.argb(90, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
            } else {
                p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, mix(s.a, Color.WHITE, .35f), mix(s.b, Color.WHITE, .25f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
                p.shader = RadialGradient(cx - .4f * r, cy - .48f * r, .6f * r, intArrayOf(Color.argb(242, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r, p)
            }
            c.drawCircle(cx, cy, r - dp / 2, edge)
            if (!picked) return
            c.drawCircle(cx, cy, r + 5 * dp, ring)
            // the badge, at the ring's top right: a sun for the light variant, a moon for the dark one
            val bx = cx + .78f * (r + 5 * dp); val by = cy - .78f * (r + 5 * dp); val br = 9.5f * dp
            c.drawCircle(bx, by, br, p.apply { shader = null; color = 0xff1b1c22.toInt() })
            c.drawCircle(bx, by, br, edge)
            p.color = TX
            if (s.dark) {
                val moon = Path().apply { addCircle(bx, by, 5 * dp, Path.Direction.CW) }
                moon.op(Path().apply { addCircle(bx + 2.6f * dp, by - 2.2f * dp, 4.2f * dp, Path.Direction.CW) }, Path.Op.DIFFERENCE)
                c.drawPath(moon, p)
            } else {
                c.drawCircle(bx, by, 2.4f * dp, p)
                val ray = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * dp; strokeCap = Paint.Cap.ROUND }
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4
                    c.drawLine(bx + (3.8f * dp * cos(a)).toFloat(), by + (3.8f * dp * sin(a)).toFloat(), bx + (5.6f * dp * cos(a)).toFloat(), by + (5.6f * dp * sin(a)).toFloat(), ray)
                }
            }
        }
    }

    private companion object {
        /** An orb's diameter, dp. */
        const val OrbSize = 34

        fun withAlpha(c: Int, a: Int) = (c and 0x00ffffff) or (a shl 24)
        fun mix(c: Int, d: Int, k: Float): Int = Color.rgb(
            (Color.red(c) + (Color.red(d) - Color.red(c)) * k).toInt(),
            (Color.green(c) + (Color.green(d) - Color.green(c)) * k).toInt(),
            (Color.blue(c) + (Color.blue(d) - Color.blue(c)) * k).toInt(),
        )
    }
}
