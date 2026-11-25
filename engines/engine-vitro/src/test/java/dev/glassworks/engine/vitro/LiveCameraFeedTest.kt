/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.camera.CameraFitFrame
import dev.glassworks.engine.vitro.camera.CameraPlayers
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactory
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamKind.HLS
import dev.glassworks.engine.vitro.camera.CameraStreamKind.MJPEG
import dev.glassworks.engine.vitro.camera.CameraStreamKind.WEB_RTC
import dev.glassworks.engine.vitro.camera.CameraStreamVideoSize
import dev.glassworks.engine.vitro.camera.CameraVideoBudget
import dev.glassworks.engine.vitro.cards.LiveCameraFeed
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A camera's stream choice, fallback chain and lifecycle ([LiveCameraFeed]), on fake players. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveCameraFeedTest {
    /** A player that does what the test tells it, and records what it was asked. */
    class FakeStream(val kind: CameraStreamKind, context: android.content.Context) : CameraStream, CameraStreamVideoSize {
        override val view: View = View(context)
        override var listener: CameraStream.Listener? = null
        override var videoWidth = 0
        override var videoHeight = 0
        val calls = ArrayList<String>()
        var closed = false
        var throwOnStart = false

        /** setActive with this value throws. */
        var throwOnActive: Boolean? = null

        override fun start() {
            calls += "start"
            if (throwOnStart) throw IllegalStateException("player broke")
        }

        override fun setActive(active: Boolean) {
            calls += "active $active"
            if (throwOnActive == active) throw IllegalStateException("player broke")
        }

        override fun close() {
            calls += "close"
            closed = true
        }

        fun frame(w: Int = 1280, h: Int = 720) {
            videoWidth = w
            videoHeight = h
            listener?.onFirstFrame(42)
        }

        fun fail() = listener?.onError("test")
    }

    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val made = ArrayList<FakeStream>()
    private val wsCalls = ArrayList<JSONObject>()
    private var capabilities: Any? = JSONObject().put("frontend_stream_types", JSONArray().put("hls").put("web_rtc"))
    private var capabilitiesOk = true
    private val nullFor = HashSet<CameraStreamKind>()
    private val throwFor = HashSet<CameraStreamKind>()

    @Before
    fun setUp() = CameraVideoBudget.reset()

    @After
    fun tearDown() = CameraVideoBudget.reset()

    private fun factory(kinds: Set<CameraStreamKind>) = CameraPlayers(
        kinds.associateWith {
            object : CameraStreamFactory {
                override fun create(entityId: String, kind: CameraStreamKind): CameraStream? {
                    if (kind in nullFor) return null
                    return FakeStream(kind, activity).also {
                        it.throwOnStart = kind in throwFor
                        made += it
                    }
                }
            }
        },
    )

    private fun context(store: EntityStore, kinds: Set<CameraStreamKind> = setOf(WEB_RTC, HLS, MJPEG)) = VitroCardContext(
        context = activity,
        haBase = "http://192.0.2.10:8123",
        store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = emptyList(),
        accessToken = { null },
        callService = { _, _, _, _ -> },
        callWs = { msg, done ->
            wsCalls += msg
            if (msg.optString("type") == "camera/capabilities") {
                if (capabilitiesOk) done(true, capabilities, null) else done(false, null, JSONObject().put("code", "not_found"))
            } else {
                done(false, null, null)
            }
        },
        navigate = {},
        openMoreInfo = {},
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
        cameraPlayers = factory(kinds),
    )

    private fun store(features: Int = 2) = EntityStore().apply {
        putLocal(EntityState("camera.porch", "streaming", mapOf("supported_features" to features)))
    }

    private fun feed(ctx: VitroCardContext, host: FrameLayout = FrameLayout(activity), onSize: ((Int, Int) -> Unit)? = null) =
        LiveCameraFeed(ctx, "camera.porch", { null }, host = host, onVideoSize = onSize) {}

    private fun idle() = ShadowLooper.idleMainLooper()
    private fun live(kind: CameraStreamKind) = made.last { it.kind == kind && !it.closed }
    private fun running() = made.filter { !it.closed }.map { it.kind }

    @Test
    fun aCameraWithoutTheStreamFeatureGoesStraightToMjpeg() {
        val f = feed(context(store(features = 0)))
        f.start()
        idle()
        assertEquals(listOf(MJPEG), running())
        // HA's frontend doesn't ask such a camera for capabilities.
        assertTrue(wsCalls.none { it.optString("type") == "camera/capabilities" })
    }

    @Test
    fun hlsAndWebRtcLoadTogetherAndWebRtcTakesOver() {
        val host = FrameLayout(activity)
        val f = feed(context(store()), host)
        f.start()
        idle()
        assertEquals("camera/capabilities", wsCalls.single().optString("type"))
        assertEquals(listOf(HLS, WEB_RTC), f.runningKinds)
        // HLS shows, WebRTC loads hidden.
        val frames = (0 until host.childCount).map { host.getChildAt(it) as CameraFitFrame }
        assertEquals(listOf(1f, 0f), frames.map { it.alpha })
        live(HLS).frame()
        idle()
        assertEquals(HLS, f.shownKind)
        assertEquals(listOf(HLS, WEB_RTC), f.runningKinds)
        live(WEB_RTC).frame()
        idle()
        // Both have video: WebRTC alone, HLS closed and its view gone.
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertEquals(WEB_RTC, f.shownKind)
        assertTrue(made.first { it.kind == HLS }.closed)
        assertEquals(1, host.childCount)
        assertEquals(1f, host.getChildAt(0).alpha)
    }

    @Test
    fun withoutAWebRtcPlayerWebRtcFallsThroughToHls() {
        val f = feed(context(store(), kinds = setOf(HLS, MJPEG)))
        f.start()
        idle()
        assertEquals(listOf(HLS), f.runningKinds)
        live(HLS).fail()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
    }

    @Test
    fun theChainFallsFromWebRtcToHlsToMjpegToStills() {
        val f = feed(context(store()))
        f.start()
        idle()
        live(WEB_RTC).fail()
        idle()
        assertEquals(listOf(HLS), f.runningKinds)
        live(HLS).fail()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        assertFalse(f.isPolling)
        live(MJPEG).fail()
        idle()
        // MJPEG down too: stills poll, and MJPEG is tried again after RETRY_MS.
        assertEquals(emptyList<CameraStreamKind>(), f.runningKinds)
        assertTrue(f.isPolling)
        ShadowLooper.idleMainLooper(LiveCameraFeed.RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(listOf(MJPEG), f.runningKinds)
        live(MJPEG).frame()
        idle()
        assertFalse(f.isPolling)
        assertEquals(MJPEG, f.shownKind)
    }

    @Test
    fun aPlayerThatCantBeMadeOrThrowsIsAFailedKindNotACrash() {
        nullFor += WEB_RTC
        throwFor += HLS
        val failures = ArrayList<String>()
        VitroFailures.listener = { where, _, _ -> failures += where }
        try {
            val f = feed(context(store()))
            f.start()
            idle()
            idle()
            assertEquals(listOf(MJPEG), f.runningKinds)
            assertEquals(listOf("camera-hls"), failures)
            assertTrue(made.first { it.kind == HLS }.closed)
        } finally {
            VitroFailures.listener = null
        }
    }

    @Test
    fun capabilitiesThatFailPlayMjpeg() {
        capabilitiesOk = false
        val f = feed(context(store()))
        f.start()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
    }

    @Test
    fun hiddenPausesTheStreamsShownResumesThemAndStopClosesThem() {
        val host = FrameLayout(activity)
        val f = feed(context(store(), kinds = setOf(HLS, MJPEG)), host)
        f.start()
        idle()
        val hls = live(HLS)
        hls.frame()
        idle()
        f.setActive(false)
        assertEquals(listOf("start", "active false"), hls.calls)
        assertEquals(0, CameraVideoBudget.inUse)
        f.setActive(true)
        idle()
        assertEquals(listOf("start", "active false", "active true"), hls.calls)
        assertEquals(1, CameraVideoBudget.inUse)
        f.stop()
        assertTrue(hls.closed)
        assertEquals(0, host.childCount)
        assertEquals(0, CameraVideoBudget.inUse)
        // Started again (the card came back), it begins afresh, capabilities remembered.
        f.start()
        idle()
        assertEquals(listOf(HLS), f.runningKinds)
        assertEquals(1, wsCalls.count { it.optString("type") == "camera/capabilities" })
    }

    @Test
    fun theVideoCapPlaysMjpegUntilASlotFrees() {
        CameraVideoBudget.max = 1
        val ctx = context(store(), kinds = setOf(HLS, MJPEG))
        val first = feed(ctx)
        val second = feed(ctx)
        first.start()
        second.start()
        idle()
        assertEquals(listOf(HLS), first.runningKinds)
        assertEquals(listOf(MJPEG), second.runningKinds)
        // The first goes away: the second takes up HLS.
        first.stop()
        idle()
        assertEquals(listOf(HLS), second.runningKinds)
        // A hidden camera gives its slot back too.
        first.start()
        idle()
        assertEquals(listOf(MJPEG), first.runningKinds)
        second.setActive(false)
        idle()
        assertEquals(listOf(HLS), first.runningKinds)
        second.setActive(true)
        idle()
        assertEquals(listOf(MJPEG), second.runningKinds)
    }

    @Test
    fun theCapCountsVideoPlayersNotCameras() {
        // HLS and WebRTC side by side are two decoders: two slots.
        CameraVideoBudget.max = 2
        val ctx = context(store())
        val first = feed(ctx)
        val second = feed(ctx)
        first.start()
        second.start()
        idle()
        assertEquals(listOf(HLS, WEB_RTC), first.runningKinds)
        assertEquals(2, CameraVideoBudget.inUse)
        assertEquals(listOf(MJPEG), second.runningKinds)
        // Once both of the first's players have answered it runs one, and its other slot goes to the second.
        live(WEB_RTC).frame()
        live(HLS).frame()
        idle()
        assertEquals(listOf(WEB_RTC), first.runningKinds)
        assertEquals(listOf(HLS), second.runningKinds)
        assertEquals(2, CameraVideoBudget.inUse)
    }

    @Test
    fun withOneSlotLeftACameraRunsOneVideoPlayerAndTakesTheOtherWhenASlotFrees() {
        CameraVideoBudget.max = 2
        val ctx = context(store(), kinds = setOf(HLS, MJPEG))
        val holder = feed(ctx)
        holder.start()
        idle()
        assertEquals(1, CameraVideoBudget.inUse)
        val both = feed(context(store()))
        both.start()
        idle()
        // One slot left: HLS alone (shown), not HLS and WebRTC.
        assertEquals(listOf(HLS), both.runningKinds)
        assertEquals(HLS, both.runningKinds.single())
        assertEquals(2, CameraVideoBudget.inUse)
        // The other camera goes: WebRTC starts beside HLS.
        holder.stop()
        idle()
        assertEquals(listOf(HLS, WEB_RTC), both.runningKinds)
        assertEquals(2, CameraVideoBudget.inUse)
    }

    @Test
    fun aPlayerThatCantResumeHasFailedAndOneThatCantPauseIsClosed() {
        val f = feed(context(store(), kinds = setOf(HLS, MJPEG)))
        f.start()
        idle()
        val hls = live(HLS)
        f.setActive(false)
        hls.throwOnActive = true
        f.setActive(true)
        idle()
        // HLS failed to resume: closed, and the choice fell back to MJPEG.
        assertTrue(hls.closed)
        assertEquals(listOf(MJPEG), f.runningKinds)
        val mjpeg = live(MJPEG)
        mjpeg.throwOnActive = false
        f.setActive(false)
        idle()
        // MJPEG couldn't pause: closed, its slot gone, and nothing polls while the camera is hidden.
        assertTrue(mjpeg.closed)
        assertEquals(emptyList<CameraStreamKind>(), f.runningKinds)
        assertFalse(f.isPolling)
        assertEquals(0, CameraVideoBudget.inUse)
    }

    @Test
    fun aCappedCameraHiddenAndShownAgainStillGetsTheNextSlot() {
        CameraVideoBudget.max = 1
        val ctx = context(store(), kinds = setOf(HLS, MJPEG))
        val first = feed(ctx)
        val second = feed(ctx)
        first.start()
        second.start()
        idle()
        assertEquals(listOf(MJPEG), second.runningKinds)
        second.setActive(false)
        second.setActive(true)
        idle()
        assertEquals(listOf(MJPEG), second.runningKinds)
        first.stop()
        idle()
        assertEquals(listOf(HLS), second.runningKinds)
    }

    @Test
    fun aFreedSlotReachesTheNextWaiterWhenTheOneToldFirstStops() {
        CameraVideoBudget.max = 1
        val ctx = context(store(), kinds = setOf(HLS, MJPEG))
        val holder = feed(ctx)
        val first = feed(ctx)
        val second = feed(ctx)
        holder.start()
        first.start()
        second.start()
        idle()
        // The slot frees, and the longest waiter goes away (its card closes) before it takes the slot up.
        holder.stop()
        first.stop()
        idle()
        assertEquals(listOf(HLS), second.runningKinds)
        assertEquals(1, CameraVideoBudget.inUse)
    }

    @Test
    fun aFreedSlotReachesEveryWaitingCameraNotOnlyTheFirst() {
        CameraVideoBudget.max = 1
        val ctx = context(store(), kinds = setOf(HLS, MJPEG))
        val holder = feed(ctx)
        val first = feed(ctx)
        val second = feed(ctx)
        holder.start()
        first.start()
        second.start()
        idle()
        assertEquals(listOf(MJPEG), first.runningKinds)
        assertEquals(listOf(MJPEG), second.runningKinds)
        // The slot frees, and the longest waiter hides before it takes it up: the next one gets it.
        holder.stop()
        first.setActive(false)
        idle()
        assertEquals(listOf(HLS), second.runningKinds)
        assertEquals(1, CameraVideoBudget.inUse)
        // The cap holds: the first, shown again, waits.
        first.setActive(true)
        idle()
        assertEquals(listOf(MJPEG), first.runningKinds)
    }

    @Test
    fun theMoreInfoCameraPausesWhileItsWindowIsHidden() {
        val st = store()
        val sheet = dev.glassworks.engine.vitro.cards.MoreInfoSheet(context(st, kinds = setOf(HLS, MJPEG))) {}
        activity.setContentView(sheet)
        showWindow(activity.window.decorView)
        sheet.show("camera.porch")
        idle()
        idle()
        val hls = live(HLS)
        assertEquals(listOf("start"), hls.calls)
        // The screen goes off (or the app to the background), then comes back.
        sheet.dispatchWindowVisibilityChanged(View.GONE)
        idle()
        assertEquals("active false", hls.calls.last())
        sheet.dispatchWindowVisibilityChanged(View.VISIBLE)
        idle()
        assertEquals("active true", hls.calls.last())
    }

    @Test
    fun aCardInAWindowThatIsGoneStartsNothingUntilTheWindowShows() {
        // Android doesn't call onWindowVisibilityChanged on attach to a GONE window (Robolectric's window is GONE).
        val parent = FrameLayout(activity)
        parent.addView(card(context(store(), kinds = setOf(HLS, MJPEG))), FrameLayout.LayoutParams(720, 405))
        activity.setContentView(parent)
        idle()
        assertTrue(made.isEmpty())
        assertTrue(wsCalls.isEmpty())
        showWindow(activity.window.decorView)
        idle()
        val hls = live(HLS)
        // The window goes away (the screen off) and comes back: paused, then resumed.
        hideWindow(activity.window.decorView)
        assertEquals("active false", hls.calls.last())
        showWindow(activity.window.decorView)
        idle()
        assertEquals("active true", hls.calls.last())
    }

    @Test
    fun aCardAttachedToAVisibleWindowStartsAtOnce() {
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        showWindow(activity.window.decorView)
        idle()
        // Added to a window that shows: the card reads it on attach and starts, no callback needed.
        parent.addView(card(context(store(), kinds = setOf(HLS, MJPEG))), FrameLayout.LayoutParams(720, 405))
        assertEquals("camera/capabilities", wsCalls.single().optString("type"))
        idle()
        assertEquals(listOf(HLS), running())
    }

    @Test
    fun theMoreInfoCameraStartsOnlyOnceItsWindowShows() {
        val sheet = dev.glassworks.engine.vitro.cards.MoreInfoSheet(context(store(), kinds = setOf(HLS, MJPEG))) {}
        activity.setContentView(sheet)
        sheet.show("camera.porch")
        idle()
        idle()
        // The sheet's window is GONE: nothing starts.
        assertTrue(made.isEmpty())
        assertTrue(wsCalls.isEmpty())
        showWindow(activity.window.decorView)
        idle()
        val hls = live(HLS)
        assertEquals(listOf("start"), hls.calls)
        hideWindow(activity.window.decorView)
        assertEquals("active false", hls.calls.last())
        showWindow(activity.window.decorView)
        assertEquals("active true", hls.calls.last())
    }

    @Test
    fun theVisibleVideosSizeReachesTheCard() {
        var size: Pair<Int, Int>? = null
        val f = feed(context(store(), kinds = setOf(HLS, MJPEG)), onSize = { w, h -> size = w to h })
        f.start()
        idle()
        live(HLS).frame(640, 480)
        idle()
        assertEquals(640 to 480, size)
    }

    // ---- the picture cards

    private fun card(ctx: VitroCardContext, extra: String = "") = PictureCardView(
        ctx,
        LovelaceParser.parseCard(JSONObject("""{"type": "picture-entity", "entity": "camera.porch", "camera_view": "live"$extra}""")),
    )

    @Test
    fun aCardPlaysWhileShownPausesWhileHiddenAndClosesWhenItGoes() {
        val parent = FrameLayout(activity)
        val c = card(context(store(), kinds = setOf(HLS, MJPEG)))
        parent.addView(c, FrameLayout.LayoutParams(720, 405))
        activity.setContentView(parent)
        showWindow(activity.window.decorView)
        idle()
        val hls = live(HLS)
        // A cached view another one hides (or HA's own page in front of the dashboard).
        parent.visibility = View.GONE
        idle()
        assertEquals("active false", hls.calls.last())
        parent.visibility = View.VISIBLE
        idle()
        assertEquals("active true", hls.calls.last())
        activity.setContentView(View(activity))
        idle()
        assertTrue(hls.closed)
    }

    @Test
    fun aCardThatIsNeverShownOpensNoStream() {
        val parent = FrameLayout(activity).apply { visibility = View.GONE }
        parent.addView(card(context(store())), FrameLayout.LayoutParams(720, 405))
        activity.setContentView(parent)
        showWindow(activity.window.decorView)
        idle()
        assertTrue(made.isEmpty())
        assertTrue(wsCalls.isEmpty())
        parent.visibility = View.VISIBLE
        idle()
        assertEquals(listOf(HLS, WEB_RTC), running())
    }

    @Test
    fun aCardTakesTheVideosShapeWithoutAnAspectRatioAndFitsItAsHaDoes() {
        val parent = FrameLayout(activity)
        val c = card(context(store(), kinds = setOf(HLS, MJPEG)))
        parent.addView(c, FrameLayout.LayoutParams(720, FrameLayout.LayoutParams.WRAP_CONTENT))
        activity.setContentView(parent)
        showWindow(activity.window.decorView)
        idle()
        c.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // hui-image's width is the card's inside its border: the picture's height comes from that, then the border.
        val border = c.paddingTop + c.paddingBottom
        val inner = 720 - c.paddingLeft - c.paddingRight
        assertEquals(Math.round(inner / (16f / 9f)) + border, c.measuredHeight) // 16:9 until the picture is known
        live(HLS).frame(640, 480)
        idle()
        c.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(Math.round(inner / (4f / 3f)) + border, c.measuredHeight) // 4:3
        val frame = live(HLS).view.parent as CameraFitFrame
        assertEquals(CameraFitFrame.CONTAIN, frame.fit)
    }

    @Test
    fun fitModeFollowsTheCardElseCssDefaults() {
        assertEquals(CameraFitFrame.COVER, CameraFitFrame.fitFor("cover", HLS))
        assertEquals(CameraFitFrame.CONTAIN, CameraFitFrame.fitFor(null, WEB_RTC))
        assertEquals(CameraFitFrame.FILL, CameraFitFrame.fitFor(null, MJPEG))
        val frame = CameraFitFrame(activity, CameraFitFrame.CONTAIN)
        frame.setVideoSize(1280, 720)
        assertEquals(400 to 225, frame.fitted(400, 400))
        frame.fit = CameraFitFrame.COVER
        assertEquals(711 to 400, frame.fitted(400, 400))
        frame.fit = CameraFitFrame.FILL
        assertEquals(400 to 400, frame.fitted(400, 400))
    }

    @Test
    fun aCardThatFailedClosesItsStreams() {
        val parent = FrameLayout(activity)
        val c = card(context(store(), kinds = setOf(HLS, MJPEG)))
        parent.addView(c, FrameLayout.LayoutParams(720, 405))
        activity.setContentView(parent)
        showWindow(activity.window.decorView)
        idle()
        val hls = live(HLS)
        c.fail("test", IllegalStateException("boom"))
        idle()
        assertTrue(hls.closed)
    }
}
