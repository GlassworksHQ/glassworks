package dev.glassworks.manage

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.manage.PanelCode
import dev.glassworks.ui.Inter

/**
 * The Manage page's code on the panel's screen (#33): what it's for, the six digits, and Close. Drawn
 * by the service over everything (an overlay window, which the app may draw: SYSTEM_ALERT_WINDOW), so
 * it shows whatever the activity is doing. Main thread. Without touch ([touch] false, `Input.NONE`) there
 * is no Close: the code goes when it is used or runs out (#34).
 */
class CodeOverlay(private val context: Context, private val touch: Boolean, private val onClose: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(shown: PanelCode.Shown?) {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        if (shown == null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "can't show the Manage code: the app may not draw over other apps")
            return
        }
        val v = build(context, shown, onClose, touch)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            dev.glassworks.platform.OverlayWindow.type(),
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(v, lp); view = v }.onFailure { Log.w(TAG, "can't show the Manage code", it) }
    }

    companion object {
        /** The Manage page's log tag, which this overlay belongs to. */
        private const val TAG = AppManage.TAG

        /** The overlay's view, also for the mock-ups. */
        fun build(context: Context, shown: PanelCode.Shown, onClose: () -> Unit, touch: Boolean = true): View {
            val dp = context.resources.displayMetrics.density
            fun text(s: String, sp: Float, weight: Int, color: Int) = TextView(context).apply {
                text = s
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                typeface = Inter.typeface(context, weight, sp)
                setTextColor(color)
                gravity = Gravity.CENTER
            }
            val wrap = { LinearLayout.LayoutParams(-2, -2) }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // A setup code (#34) pairs a laptop for setup over the LAN, not for the Manage page.
                val eyebrow = if (shown.purpose == dev.glassworks.setup.LanSetup.PURPOSE) "SET UP THIS PANEL" else "MANAGE THIS PANEL"
                addView(text(eyebrow, 11f, Inter.MEDIUM, FAINT).apply { letterSpacing = 0.14f }, wrap())
                addView(text(shown.label.removeSuffix("."), 26f, Inter.LIGHT, FG).apply { setPadding(0, (10 * dp).toInt(), 0, (24 * dp).toInt()) }, wrap())
                // On one line at 64 sp where it fits (a panel); smaller on a phone, whose 360 dp can't hold it.
                addView(object : TextView(context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                            // Sized on a copy of the paint: a size set only when it changes asks for no second layout.
                            val p = android.text.TextPaint(paint).apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CODE_SP, resources.displayMetrics) }
                            while (p.measureText(text.toString()) > room && p.textSize > 24 * dp) p.textSize *= 0.94f
                            if (p.textSize != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, p.textSize)
                        }
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }.apply {
                    text = shown.code.chunked(3).joinToString("  ")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, CODE_SP)
                    typeface = Inter.typeface(context, Inter.LIGHT, CODE_SP)
                    setTextColor(FG)
                    gravity = Gravity.CENTER
                    letterSpacing = 0.12f
                    maxLines = 1
                }, wrap())
                addView(text("Enter this code in your browser. It works once and expires in 5 minutes.", 15f, Inter.REGULAR, MUTED).apply {
                    setPadding(0, (22 * dp).toInt(), 0, (26 * dp).toInt())
                }, wrap())
                if (touch) addView(text("Close", 15f, Inter.MEDIUM, FG).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 999 * dp; setColor(0x14FFFFFF); setStroke((1 * dp).toInt(), 0x1FFFFFFF)
                    }
                    setPadding((26 * dp).toInt(), (11 * dp).toInt(), (26 * dp).toInt(), (11 * dp).toInt())
                    isClickable = true
                    setOnClickListener { onClose() }
                }, LinearLayout.LayoutParams(-2, -2))
            }
            return FrameLayout(context).apply {
                // Flat night, fully opaque: nothing behind (the night clock) may show through the digits.
                setBackgroundColor(0xFF0A0B0F.toInt())
                // A margin at the sides, so a phone's narrow screen keeps the words off its edges.
                addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = (24 * dp).toInt(); rightMargin = (24 * dp).toInt() })
            }
        }

        /** The code's size where it fits on one line. */
        private const val CODE_SP = 64f

        private val FG = Color.parseColor("#F4F4F5")
        private val MUTED = Color.parseColor("#A1A1AA")
        private val FAINT = Color.parseColor("#6B6B74")
    }
}
