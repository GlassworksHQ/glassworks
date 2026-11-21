package dev.glassworks.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import dev.glassworks.theme.Themes
import dev.glassworks.ui.Glass.Companion.BD
import dev.glassworks.ui.Glass.Companion.TX
import dev.glassworks.ui.Wrap

/**
 * A color on the panel's sheet (#691's Custom themes): the suggestions as round swatches to tap, and under them a dot
 * showing the color now next to the typed `#rrggbb` ([field], the sheet's own field, which Save reads). Touch first: a
 * tap on a swatch fills the field, and typing is there for an exact color. The swatch that matches is ringed.
 */
class ColourField(context: Context, suggestions: List<String>, private val field: EditText) : LinearLayout(context) {
    private val dp = context.resources.displayMetrics.density
    private val swatches = ArrayList<Pair<Int, View>>()
    private val now = Dot(context)
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = bind()
    }

    init {
        orientation = VERTICAL
        val grid = Wrap(context, px(12f), px(12f))
        for (hex in suggestions) {
            val c = Themes.colour(hex) ?: continue
            val dot = Dot(context).apply {
                contentDescription = hex
                isClickable = true
                setOnClickListener { field.setText(Themes.hex(c)); field.setSelection(field.text.length) }
            }
            swatches += c to dot
            grid.addView(dot, LayoutParams(px(SWATCH_DP), px(SWATCH_DP)))
        }
        addView(grid, LayoutParams(-1, -2))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(now, LayoutParams(px(SWATCH_DP), px(SWATCH_DP)).apply { marginEnd = px(14f) })
        addView(row, LayoutParams(-1, -2).apply { topMargin = px(18f) })
        // The sheet's field moves in here; it leaves again when the sheet opens for something else.
        (field.parent as? android.view.ViewGroup)?.removeView(field)
        row.addView(field, LayoutParams(0, -2, 1f))
        field.addTextChangedListener(watcher)
        bind()
    }

    /** Lets go of the sheet's field: the next sheet uses it without this one listening. */
    fun release() = field.removeTextChangedListener(watcher)

    private fun bind() {
        val typed = Themes.colour(field.text?.toString().orEmpty().let { if (it.startsWith("#")) it else "#$it" })
        now.background = dot(typed ?: Color.TRANSPARENT, ring = false, dashed = typed == null)
        for ((c, v) in swatches) v.background = dot(c, ring = c == typed, dashed = false)
    }

    private fun dot(c: Int, ring: Boolean, dashed: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(c)
        val edge = if (ring) TX else BD
        if (dashed) setStroke(px(1.5f), edge, px(4f).toFloat(), px(3f).toFloat()) else setStroke(px(if (ring) 3f else 1f), edge)
    }

    private fun px(v: Float) = (v * dp).toInt()

    /** A swatch: always [SWATCH_DP] square, whatever its parent offers ([Wrap] measures loosely). */
    private class Dot(context: Context) : View(context) {
        private val side = (SWATCH_DP * context.resources.displayMetrics.density).toInt()
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(side, side)
    }

    companion object {
        const val SWATCH_DP = 44f
    }
}
