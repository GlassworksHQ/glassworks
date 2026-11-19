/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.bluetoothproxy.ProxyPhase.BLUETOOTH_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.LOCATION_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.NO_BLE
import dev.glassworks.bluetoothproxy.ProxyPhase.OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.RETRYING
import dev.glassworks.pane.IN_HOME_ASSISTANT
import dev.glassworks.pane.SettingsItem
import dev.glassworks.ui.Glass
import dev.glassworks.ui.Glass.Companion.BD
import dev.glassworks.ui.Glass.Companion.FA
import dev.glassworks.ui.Glass.Companion.MU
import dev.glassworks.ui.Glass.Companion.ROSE
import dev.glassworks.ui.Glass.Companion.SKY
import dev.glassworks.ui.Glass.Companion.TX
import dev.glassworks.ui.Glass.Companion.VIOLET

/**
 * The Bluetooth proxy's settings page, as approved on #121 (2026-11-13): a status line with no dot,
 * a card when something stops it (Bluetooth or location off), the advertisements, scanner and
 * connections tiles, the mode (listen only, or listen and connect, #246), then the pane's entities as
 * Home Assistant shows them.
 */
class BluetoothProxyPage(private val host: SettingsItem.NativeHost, private val proxy: BluetoothProxyModel) : SettingsItem.NativeView {
    private val c: Context = host.context
    private val g = Glass(c)
    private fun px(v: Float) = g.px(v)

    private val line = g.text(15f, MU).apply { setPadding(0, px(2f), 0, px(8f)) }

    private val cardTitle = g.heading(22f)
    private val cardText = g.text(15f, MU).apply { setLineSpacing(0f, 1.25f) }
    private val cardButton = g.primary("").apply { setPadding(px(24f), px(10f), px(24f), px(10f)) }
    /** The phase whose button the panel refused: its card says so until the phase changes. */
    private var refused: ProxyPhase? = null
    private val card = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = g.surface(24f)
        setPadding(px(26f), px(22f), px(26f), px(22f))
        addView(cardTitle)
        addView(cardText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(LinearLayout(c).apply { addView(cardButton) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
    }

    private val ads = Tile(ProxyPage.ADS, ProxyPage.ADS_UNIT)
    private val scanner = Tile(ProxyPage.SCANNER, "")
    private val conns = Tile(ProxyPage.CONNECTIONS, "")
    private val spark = Spark(c)
    private val meter = Meter(c)
    private val tiles = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(ads.view.apply { addView(spark, LinearLayout.LayoutParams(-1, px(22f)).apply { topMargin = px(8f) }) }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(scanner.view.apply { addView(View(c), LinearLayout.LayoutParams(-1, px(22f)).apply { topMargin = px(8f) }) },
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(12f) })
        addView(conns.view.apply { addView(meter, LinearLayout.LayoutParams(-1, px(22f)).apply { topMargin = px(8f) }) },
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(12f) })
    }

    /** Listen only, or listen and connect: two rows, the chosen one lifted, as in the time zone picker (#121's mockup). */
    private val modeRows = ProxyPage.MODES.map { (id, name, detail) -> ModeRow(id, name, detail) }
    private val mode = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(g.label(ProxyPage.MODE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f); bottomMargin = px(8f) })
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(20f)
            setPadding(px(8f), px(8f), px(8f), px(8f))
            modeRows.forEachIndexed { i, r -> addView(r.view, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(4f) }) }
        })
    }

    private val note = g.text(15f, MU).apply { setLineSpacing(0f, 1.25f) }
    private val adsRow = HaRow(ProxyPage.ADS_ENTITY)
    private val statusRow = HaRow(ProxyPage.STATUS_ENTITY)
    private val inHa = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(g.label(IN_HOME_ASSISTANT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f); bottomMargin = px(6f) })
        addView(adsRow.view)
        addView(statusRow.view)
        addView(g.text(13.5f, FA).apply { text = ProxyPage.FOOTNOTE; setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
    }

    override val view: View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(line)
        addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12f) })
        addView(tiles)
        addView(mode)
        addView(note)
        addView(inHa)
    }

    init { refresh() }

    override fun refresh() {
        val phase = proxy.phase()
        val s = proxy.status()
        line.text = ProxyPage.line(phase, s)
        line.setTextColor(if (phase == RETRYING) ROSE else MU)
        line.visibility = if (phase == OFF || phase == NO_BLE) View.GONE else View.VISIBLE
        showCard(phase)
        tiles.visibility = if (ProxyPage.tiles(phase)) View.VISIBLE else View.GONE
        val n = ProxyPage.note(phase)
        note.visibility = if (n != null) View.VISIBLE else View.GONE
        note.text = n.orEmpty()
        inHa.visibility = if (ProxyPage.inHomeAssistant(phase)) View.VISIBLE else View.GONE

        ProxyPage.ads(s).let { (v, l) -> ads.set(v, l) }
        spark.values = s?.lastHour.orEmpty()
        ProxyPage.scanner(phase, s).let { (v, l) -> scanner.set(v, l) }
        val cs = ProxyPage.connections(proxy.connect, proxy.connections())
        conns.set(cs.value, cs.line, cs.unit)
        meter.visibility = if (cs.meter != null) View.VISIBLE else View.INVISIBLE
        meter.fill = cs.meter ?: 0f
        mode.visibility = if (ProxyPage.mode(phase)) View.VISIBLE else View.GONE
        val chosen = if (proxy.connect) ProxyPage.CONNECT else ProxyPage.LISTEN
        modeRows.forEach { it.show(it.modeId == chosen) }
        val (adsState, statusState) = ProxyPage.entities(phase, s).map { it.second }
        adsRow.value(adsState.takeIf { it != ProxyPage.UNAVAILABLE })
        statusRow.value(statusState.takeIf { it != ProxyPage.UNAVAILABLE })
    }

    private fun showCard(phase: ProxyPhase) {
        val c = ProxyPage.card(phase) ?: run { card.visibility = View.GONE; refused = null; return }
        if (refused != phase) refused = null
        card.visibility = View.VISIBLE
        cardTitle.text = c.title
        cardText.text = if (refused == phase) ProxyPage.REFUSED else c.text
        cardButton.visibility = if (c.action != null) View.VISIBLE else View.GONE
        cardButton.text = c.action ?: ""
        cardButton.setOnClickListener {
            val done = when (phase) {
                BLUETOOTH_OFF -> proxy.turnBluetoothOn() || open(Settings.ACTION_BLUETOOTH_SETTINGS)
                LOCATION_OFF -> proxy.turnLocationOn() || open(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                else -> true
            }
            if (!done) refused = phase
            refresh()
        }
    }

    /** Android's own screen for it, where the device has one. The S1 has none. */
    private fun open(action: String): Boolean {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(c.packageManager) == null) return false
        return runCatching { c.startActivity(i) }.isSuccess
    }

    /** A reading: its name, the value large with its unit, a line under it. */
    private inner class Tile(name: String, private val unit: String) {
        private val value = g.heading(27f).apply { setSingleLine() }
        private val unitView = g.text(14f, MU).apply {
            text = unit
            setPadding(px(5f), 0, 0, px(4f))
            visibility = if (unit.isEmpty()) View.GONE else View.VISIBLE
        }
        private val sub = g.text(13.5f, MU).apply { setSingleLine() }
        val view = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(20f)
            setPadding(px(16f), px(13f), px(16f), px(12f))
            addView(g.label(name))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(value)
                addView(unitView)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(1f) })
        }

        fun set(v: String, line: String, unit: String = this.unit) {
            value.text = v
            sub.text = line
            unitView.text = unit
            unitView.visibility = if (unit.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** One mode: its name and line; picked, the row is lifted and carries the brand's mark. Tapping it picks it. */
    private inner class ModeRow(val modeId: String, name: String, detail: String) {
        private val title = g.text(16.5f, TX).apply { text = name }
        private val line = g.text(14f, MU).apply { text = detail; setLineSpacing(0f, 1.15f) }
        private val mark = View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(SKY, VIOLET)).apply { shape = GradientDrawable.OVAL }
        }
        val view = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16f), px(12f), px(16f), px(12f))
            isClickable = true
            contentDescription = name
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mark, LinearLayout.LayoutParams(px(9f), px(9f)).apply { marginStart = px(12f) })
            setOnClickListener {
                proxy.connect = modeId == ProxyPage.CONNECT
                refresh()
            }
        }

        fun show(chosen: Boolean) {
            view.background = if (chosen) g.surface(14f, Glass.S2) else null
            title.setTextColor(if (chosen) TX else MU)
            line.setTextColor(if (chosen) MU else FA)
            mark.visibility = if (chosen) View.VISIBLE else View.INVISIBLE
            view.isSelected = chosen
        }
    }

    /** An entity as Home Assistant shows it: its name on the left, its state on the right. */
    private inner class HaRow(name: String) {
        private val valueView: TextView = g.text(15f, MU).apply { setSingleLine() }
        val view = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(c).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(46f)
                addView(g.text(15.5f, TX).apply { text = name; setSingleLine() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueView)
            })
        }

        /** Null: the entity is unavailable (the pane is off). */
        fun value(v: String?) {
            valueView.text = v ?: "Unavailable"
            valueView.setTextColor(if (v == null) FA else MU)
        }
    }

    /** How full the connection slots are: a track, filled in the brand's sky-to-violet (not a status colour). */
    private class Meter(context: Context) : View(context) {
        var fill = 0f
            set(v) { if (v != field) { field = v; invalidate() } }
        private val dp = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val h = 6f * dp
            val top = height - h
            val r = h / 2
            paint.shader = null
            paint.color = Color.argb(30, 255, 255, 255)
            canvas.drawRoundRect(0f, top, width.toFloat(), height.toFloat(), r, r, paint)
            if (fill <= 0f) return
            val w = width * fill.coerceIn(0f, 1f)
            paint.color = Color.WHITE // opaque: the shader is drawn at the paint's alpha
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, SKY, VIOLET, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, top, w, height.toFloat(), r, r, paint)
        }
    }

    /** The last hour's advertisements a minute: the brand's sky-to-violet, quiet, never a status colour. */
    private class Spark(context: Context) : View(context) {
        var values: List<Int> = emptyList()
            set(v) { if (v != field) { field = v; invalidate() } }
        private val dp = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val v = if (values.size >= 2) values else listOf(0, 0)
            val lo = v.min().toFloat()
            val hi = v.max().toFloat().coerceAtLeast(lo + 1f)
            val pad = 2f * dp
            val path = Path()
            v.forEachIndexed { i, x ->
                val px = i * w / (v.size - 1)
                val py = pad + (h - 2 * pad) * (1f - (x - lo) / (hi - lo))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            fill.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(38, 0x81, 0x8c, 0xf8), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawPath(Path(path).apply { lineTo(w, h); lineTo(0f, h); close() }, fill)
            stroke.shader = LinearGradient(0f, 0f, w, 0f, alpha(SKY), alpha(VIOLET), Shader.TileMode.CLAMP)
            canvas.drawPath(path, stroke)
        }

        private fun alpha(c: Int) = Color.argb(200, Color.red(c), Color.green(c), Color.blue(c))
    }
}
