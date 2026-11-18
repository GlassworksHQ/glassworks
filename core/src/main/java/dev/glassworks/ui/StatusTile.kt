/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The setup screen's tile (#63, first drawn for Improv on the Wi-Fi step): a status dot in its halo, a name, a line
 * under it, and room for one button on the right. Main thread.
 */
class StatusTile(context: Context) {
    private val g = Glass(context)
    private val dot = View(context)
    private val halo = FrameLayout(context).apply { addView(dot, FrameLayout.LayoutParams(g.px(9f), g.px(9f), Gravity.CENTER)) }
    val title = g.text(16.5f, Glass.TX, Inter.MEDIUM).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    val detail = g.text(15f, Glass.MU).apply { setLineSpacing(0f, 1.2f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = g.surface(19f, Glass.S1)
        setPadding(g.px(13f), g.px(15f), g.px(18f), g.px(15f))
        addView(halo, LinearLayout.LayoutParams(g.px(18f), g.px(18f)).apply { marginEnd = g.px(14f) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = g.px(5f) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** The button on the right, or none. */
    fun end(button: View?) {
        while (view.childCount > 2) view.removeViewAt(2)
        if (button == null) return
        (button.parent as? LinearLayout)?.removeView(button)
        view.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginStart = g.px(14f) })
    }

    /** The dot's status colour. */
    fun tone(color: Int) {
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        halo.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))) }
    }
}
