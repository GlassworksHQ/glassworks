/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.HaButton
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.HaToast
import dev.glassworks.engine.vitro.ui.HaToastHost
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * HA's toast (`notification-manager`, `ha-toast`, frontend 20261028.5) in Vitro: how long it stays, an id that
 * replaces one, its action and close buttons, TalkBack, and the flows Vitro draws natively that show one in HA (an
 * update that fails after its dialog closed, a tap with nothing to act on, the connection lost).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HaToastTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun host(): HaToastHost {
        val host = HaToastHost(activity) { ThemeResolver.resolve(ThemeCatalog(), true) }
        activity.setContentView(FrameLayout(activity).apply { addView(host, FrameLayout.LayoutParams(-1, -1)) })
        return host
    }

    private fun idle() = ShadowLooper.idleMainLooper()
    private fun after(ms: Long) = ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Test
    fun aToastStaysFourSecondsByDefaultAndItsOwnDurationIfLonger() {
        val host = host()
        host.show(HaToast("Saved"))
        host.show(HaToast("Update failed", durationMs = 10_000))
        idle()
        assertEquals(listOf("Saved", "Update failed"), host.messages)
        after(4_200)
        assertEquals(listOf("Update failed"), host.messages)
        after(6_000)
        assertTrue(host.messages.isEmpty())
    }

    @Test
    fun aShortDurationIsFourSecondsAndANegativeOneStays() {
        val host = host()
        host.show(HaToast("Quick", durationMs = 1_000))
        host.show(HaToast("Lost", durationMs = -1, id = "connection-lost"))
        idle()
        after(2_000)
        assertEquals(listOf("Quick", "Lost"), host.messages)
        after(3_000)
        assertEquals(listOf("Lost"), host.messages)
        after(60_000)
        assertEquals(listOf("Lost"), host.messages)
        // Duration 0 closes the toast with that id.
        host.show(HaToast("", durationMs = 0, id = "connection-lost"))
        after(500)
        assertTrue(host.messages.isEmpty())
    }

    @Test
    fun anIdReplacesItsToastInsteadOfAddingOne() {
        val host = host()
        host.show(HaToast("Checking for updates…", id = "check-updates"))
        host.show(HaToast("2 updates refreshed", id = "check-updates"))
        idle()
        assertEquals(listOf("2 updates refreshed"), host.messages)
    }

    @Test
    fun theActionRunsAndClosesTheToastAndCloseCallsDismiss() {
        val host = host()
        var ran = 0
        var dismissed = 0
        host.show(HaToast("Area created", action = HaToast.Action("Open") { ran++ }))
        host.show(HaToast("Undo failed", dismissable = true, onDismiss = { dismissed++ }))
        idle()
        all(host).filterIsInstance<TextView>().single { it.text.toString() == "Open" }.performClick()
        after(500)
        assertEquals(1, ran)
        assertEquals(listOf("Undo failed"), host.messages)
        all(host).single { it.contentDescription == "Close" }.performClick()
        after(500)
        assertEquals(1, dismissed)
        assertTrue(host.messages.isEmpty())
    }

    @Test
    fun talkBackHearsTheToastPolitely() {
        val am = activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        Shadows.shadowOf(am).setEnabled(true)
        val host = host()
        host.show(HaToast("The update could not be started: offline"))
        idle()
        val message = all(host).filterIsInstance<TextView>().single { it.text.toString() == "The update could not be started: offline" }
        // role="status", aria-live="polite".
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, message.accessibilityLiveRegion)
        // And it's announced as it appears.
        val announced = Shadows.shadowOf(am).sentAccessibilityEvents.filter { it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT }
        assertEquals(listOf("The update could not be started: offline"), announced.map { it.text.joinToString("") })
    }

    @Test
    fun aToastIsHasDarkBarWithWhiteText() {
        val host = host()
        host.show(HaToast("Saved"))
        idle()
        val message = all(host).filterIsInstance<TextView>().single { it.text.toString() == "Saved" }
        assertEquals(Color.WHITE, message.currentTextColor)
        val bar = message.parent as View
        assertEquals(0xFF202020.toInt(), (bar.background as android.graphics.drawable.GradientDrawable).color?.defaultColor)
        assertTrue(bar.minimumHeight >= Math.round(48 * activity.resources.displayMetrics.density))
    }

    // ---- Where Vitro shows one ----

    private fun context(store: EntityStore, toasts: MutableList<HaToast>, ws: MutableList<Pair<JSONObject, (Boolean, Any?, JSONObject?) -> Unit>>) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(), accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {},
        openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        callWs = { msg, done -> ws += msg to done }, showToast = { toasts += it },
    )

    @Test
    fun anUpdateThatFailsAfterItsDialogClosedSaysSoInAToast() {
        val store = EntityStore().apply {
            putLocal(EntityState(UPDATE, "on", mapOf("supported_features" to 1, "friendly_name" to "Firmware",
                "installed_version" to "1.0", "latest_version" to "1.1", "in_progress" to false)))
        }
        val toasts = ArrayList<HaToast>()
        val ws = ArrayList<Pair<JSONObject, (Boolean, Any?, JSONObject?) -> Unit>>()
        val sheet = MoreInfoSheet(context(store, toasts, ws)) {}
        activity.setContentView(sheet)
        sheet.show(UPDATE)
        idle()
        all(sheet).filterIsInstance<HaButton>().single { it.contentDescription == "Update" && it.visibility == View.VISIBLE }.performClick()
        sheet.dismiss()
        ws.single { it.first.optString("type") == "call_service" }.second(false, null, JSONObject().put("code", "home_assistant_error").put("message", "Device offline"))
        idle()
        val toast = toasts.single()
        assertEquals("The update could not be started: Device offline", toast.message)
        assertEquals(10_000L, toast.durationMs)
    }

    @Test
    fun aTapWithNothingToActOnSaysSoInAToast() {
        val toasts = ArrayList<HaToast>()
        val ctx = context(EntityStore(), toasts, ArrayList())
        ctx.performAction(ActionConfig(action = "more-info"), null)
        ctx.performAction(ActionConfig(action = "toggle"), null)
        ctx.performAction(ActionConfig(action = "navigate"), null)
        ctx.performAction(ActionConfig(action = "url"), null)
        ctx.performAction(ActionConfig(action = "perform-action"), null)
        assertEquals(listOf(
            "No entity provided for more info dialog", "No entity provided to toggle", "No navigation path specified",
            "No URL to open specified", "No action to run specified",
        ), toasts.map { it.message })
    }

    @Test
    fun theConnectionLostToastWaitsASecondAndGoesWhenItsBack() {
        val view = VitroView(activity, EntityStore(), Color.BLACK)
        activity.setContentView(view)
        view.setConnectionBanner("Connection lost. Reconnecting…")
        after(500)
        assertTrue("a quick reconnect shows nothing", view.toastMessages.isEmpty())
        view.setConnectionBanner(null)
        after(2_000)
        assertTrue(view.toastMessages.isEmpty())
        view.setConnectionBanner("Connection lost. Reconnecting…")
        after(1_100)
        assertEquals(listOf("Connection lost. Reconnecting…"), view.toastMessages)
        after(60_000)
        assertEquals("it stays while the connection is down", 1, view.toastMessages.size)
        view.setConnectionBanner(null)
        after(500)
        assertTrue(view.toastMessages.isEmpty())
    }

    @Test
    fun aToastOnScreenStaysAboveAMoreInfoSheetOpenedAfterIt() {
        val store = EntityStore().apply { putLocal(EntityState("light.desk", "on", mapOf("friendly_name" to "Desk"))) }
        val view = VitroView(activity, store, Color.BLACK)
        view.contextFactory = { context(store, ArrayList(), ArrayList()) }
        activity.setContentView(view)
        view.showToast(HaToast("Update failed", durationMs = -1L))
        idle()
        val host = (0 until view.childCount).map(view::getChildAt).single { it is HaToastHost }
        view.openMoreInfo("light.desk")
        idle()
        val sheet = (0 until view.childCount).map(view::getChildAt).single { it is MoreInfoSheet }
        assertTrue("the sheet is under the toasts", view.indexOfChild(sheet) < view.indexOfChild(host))
        assertEquals(view.childCount - 1, view.indexOfChild(host))
        assertEquals(listOf("Update failed"), view.toastMessages)
    }

    private companion object {
        const val UPDATE = "update.hallway_display_firmware"
    }
}
