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
import org.junit.Assert.assertNull
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

        override fun connectionRestored() {
            calls += "restored"
        }

        override fun setRendering(rendering: Boolean) {
            calls += "rendering $rendering"
        }

        /** Like WebRTC's: closed rather than paused when its camera hides. */
        var closesWhenHidden = false
        override val closeWhenHidden: Boolean get() = closesWhenHidden

        fun frame(w: Int = 1280, h: Int = 720) {
            videoWidth = w
            videoHeight = h
            listener?.onFirstFrame(42)
        }

        fun fail() = listener?.onError("test")

        /** Its view has a surface (a player built before its view is laid out has none yet). */
        var hasSurface = true
        override val surfaceReady: Boolean get() = hasSurface

        fun surface() {
            hasSurface = true
            listener?.onSurfaceReady()
        }

        fun loseSurface() {
            hasSurface = false
            listener?.onSurfaceLost()
        }
    }

    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val made = ArrayList<FakeStream>()

    /** The most video players (decoders) open at once, read each time one is made. */
    private var peakVideo = 0
    private val wsCalls = ArrayList<JSONObject>()
    private var capabilities: Any? = JSONObject().put("frontend_stream_types", JSONArray().put("hls").put("web_rtc"))
    private var capabilitiesOk = true

    /** Set: capabilities requests wait here for the test to answer them. */
    private var pendingCaps: ArrayList<(Boolean, Any?, JSONObject?) -> Unit>? = null
    private val nullFor = HashSet<CameraStreamKind>()
    private val throwFor = HashSet<CameraStreamKind>()

    /** Kinds whose players start without a surface (their view not laid out yet). */
    private val noSurfaceFor = HashSet<CameraStreamKind>()
    private val closesWhenHiddenFor = HashSet<CameraStreamKind>()

    /** The kinds whose factory was asked to get ready, in order. */
    private val prewarmed = ArrayList<CameraStreamKind>()
    private val prewarmThreads = ArrayList<Thread>()

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
                        it.hasSurface = kind !in noSurfaceFor
                        it.throwOnStart = kind in throwFor
                        it.closesWhenHidden = kind in closesWhenHiddenFor
                        made += it
                        peakVideo = maxOf(peakVideo, made.count { m -> !m.closed && m.kind != MJPEG })
                    }
                }

                override fun prewarm() {
                    prewarmThreads += Thread.currentThread()
                    prewarmed += it
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
                val held = pendingCaps
                if (held != null) held += done
                else if (capabilitiesOk) done(true, capabilities, null) else done(false, null, JSONObject().put("code", "not_found"))
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

    /** WebRTC has played alone for its first [LiveCameraFeed.EXTRA_DELAY_MS]: HLS may load beside it now. */
    private fun extraDue() = ShadowLooper.idleMainLooper(LiveCameraFeed.EXTRA_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
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
        // WebRTC alone first, shown (clear until its first frame, over the still).
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertEquals(1f, host.getChildAt(0).alpha)
        // No video yet after its first moments: HLS loads beside it, as HA's do side by side. HLS shows, WebRTC
        // loads hidden.
        extraDue()
        assertEquals(listOf(WEB_RTC, HLS), f.runningKinds)
        val frames = (0 until host.childCount).map { host.getChildAt(it) as CameraFitFrame }
        assertEquals(listOf(0f, 1f), frames.map { it.alpha })
        live(HLS).frame()
        idle()
        assertEquals(HLS, f.shownKind)
        assertEquals(listOf(WEB_RTC, HLS), f.runningKinds)
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
    fun aFailedCapabilitiesRequestIsAskedAgainNotKept() {
        capabilitiesOk = false
        val f = feed(context(store(), kinds = setOf(HLS, MJPEG)))
        f.start()
        idle()
        // MJPEG at once, as before.
        assertEquals(listOf(MJPEG), f.runningKinds)
        live(MJPEG).frame()
        idle()
        val asks = { wsCalls.count { it.optString("type") == "camera/capabilities" } }
        assertEquals(1, asks())
        // HA answers the next request (the connection came back): the camera takes up HLS.
        capabilitiesOk = true
        capabilities = JSONObject().put("frontend_stream_types", JSONArray().put("hls"))
        ShadowLooper.idleMainLooper(LiveCameraFeed.CAPABILITIES_RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(2, asks())
        // MJPEG stays on screen until HLS draws (no blank meanwhile), then goes.
        assertEquals(listOf(MJPEG, HLS), f.runningKinds)
        assertEquals(MJPEG, f.shownKind)
        live(HLS).frame()
        idle()
        assertEquals(listOf(HLS), f.runningKinds)
        assertEquals(HLS, f.shownKind)
        // HA's answer is kept now: started again, the camera doesn't ask.
        f.stop()
        f.start()
        idle()
        assertEquals(2, asks())
    }

    @Test
    fun failedCapabilitiesAreAskedAgainLessOftenAndNotWhileHidden() {
        capabilitiesOk = false
        val f = feed(context(store(), kinds = setOf(HLS, MJPEG)))
        f.start()
        idle()
        val asks = { wsCalls.count { it.optString("type") == "camera/capabilities" } }
        ShadowLooper.idleMainLooper(LiveCameraFeed.CAPABILITIES_RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(2, asks())
        // The wait doubles: nothing more after another 10 s, the next ask after 20 s.
        ShadowLooper.idleMainLooper(LiveCameraFeed.CAPABILITIES_RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(2, asks())
        ShadowLooper.idleMainLooper(LiveCameraFeed.CAPABILITIES_RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(3, asks())
        // Hidden: no asking. Shown again: it asks at once.
        f.setActive(false)
        ShadowLooper.idleMainLooper(LiveCameraFeed.CAPABILITIES_RETRY_MAX_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(3, asks())
        f.setActive(true)
        idle()
        assertEquals(4, asks())
    }

    @Test
    fun anEmptyAnswerIsKeptAndPlaysMjpegWithoutAskingAgain() {
        capabilities = JSONObject().put("frontend_stream_types", JSONArray())
        val f = feed(context(store(), kinds = setOf(HLS, MJPEG)))
        f.start()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        ShadowLooper.idleMainLooper(LiveCameraFeed.CAPABILITIES_RETRY_MAX_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        f.stop()
        f.start()
        idle()
        assertEquals(1, wsCalls.count { it.optString("type") == "camera/capabilities" })
    }

    @Test
    fun aCappedCameraKeepsItsMjpegOnScreenUntilTheVideoDraws() {
        CameraVideoBudget.max = 1
        val ctx = context(store(), kinds = setOf(HLS, MJPEG))
        val first = feed(ctx)
        val second = feed(ctx)
        first.start()
        second.start()
        idle()
        val mjpeg = live(MJPEG)
        mjpeg.frame()
        idle()
        assertEquals(MJPEG, second.shownKind)
        // A slot frees: HLS starts, and MJPEG stays on screen meanwhile.
        first.stop()
        idle()
        assertEquals(listOf(MJPEG, HLS), second.runningKinds)
        assertEquals(MJPEG, second.shownKind)
        assertFalse(mjpeg.closed)
        live(HLS).frame()
        idle()
        assertTrue(mjpeg.closed)
        assertEquals(listOf(HLS), second.runningKinds)
        assertEquals(HLS, second.shownKind)
    }

    @Test
    fun fourCamerasAllReachWebRtcWithNoMjpegDetourAndNeverMoreDecodersThanTheCap() {
        // Clarity's heavy view: four cameras that offer HLS and WebRTC, on a panel with four video slots.
        val ctx = context(store())
        val cams = (0 until 4).map { feed(ctx) }
        cams.forEach { it.start() }
        idle()
        idle()
        // Each camera's main player (WebRTC) has a slot at once; the side-by-side HLS loads gave theirs up.
        for (c in cams) assertEquals(listOf(WEB_RTC), c.runningKinds)
        assertTrue(made.none { it.kind == MJPEG })
        assertEquals(4, CameraVideoBudget.inUse)
        assertEquals(0, CameraVideoBudget.extrasInUse)
        assertTrue("peak $peakVideo", peakVideo <= CameraVideoBudget.MAX_VIDEO_CAMERAS)
        // Every WebRTC draws: all four show WebRTC, and still four players.
        made.filter { it.kind == WEB_RTC && !it.closed }.forEach { it.frame() }
        idle()
        for (c in cams) assertEquals(WEB_RTC, c.shownKind)
        assertEquals(4, made.count { !it.closed })
        assertTrue(peakVideo <= CameraVideoBudget.MAX_VIDEO_CAMERAS)
    }

    @Test
    fun fourCamerasStartedOneByOneEndOnWebRtcToo() {
        // Cards that attach a frame apart: the first ones load side by side, the later ones take those extras.
        val ctx = context(store())
        val cams = (0 until 4).map { feed(ctx) }
        for (c in cams) {
            c.start()
            idle()
        }
        for (c in cams) assertEquals(listOf(WEB_RTC), c.runningKinds)
        assertTrue(made.none { it.kind == MJPEG })
        assertTrue("peak $peakVideo", peakVideo <= CameraVideoBudget.MAX_VIDEO_CAMERAS)
    }

    @Test
    fun anExtraThatAlreadyDrewGivesWayAndItsCameraWaitsForWebRtc() {
        CameraVideoBudget.max = 2
        val ctx = context(store())
        val first = feed(ctx)
        first.start()
        idle()
        extraDue()
        // HLS drew first: it shows while WebRTC loads, as HA's side-by-side does.
        live(HLS).frame()
        idle()
        assertEquals(HLS, first.shownKind)
        // A second camera needs its main player: the first's HLS goes, its WebRTC is now the one shown (clear until
        // its first frame, over the still).
        val second = feed(ctx)
        second.start()
        idle()
        assertEquals(listOf(WEB_RTC), first.runningKinds)
        assertNull(first.shownKind)
        made.filter { it.kind == WEB_RTC && !it.closed }.forEach { it.frame() }
        idle()
        assertEquals(WEB_RTC, first.shownKind)
        assertEquals(WEB_RTC, second.shownKind)
    }

    @Test
    fun withTheBudgetFullAFailedWebRtcFallsToHlsInItsOwnSlotNotMjpeg() {
        val ctx = context(store())
        val cams = (0 until 4).map { feed(ctx) }
        cams.forEach { it.start() }
        idle()
        idle()
        made.first { it.kind == WEB_RTC && !it.closed }.fail()
        idle()
        assertEquals(1, cams.count { it.runningKinds == listOf(HLS) })
        assertTrue(made.none { it.kind == MJPEG })
        assertEquals(4, CameraVideoBudget.inUse)
        assertTrue(peakVideo <= CameraVideoBudget.MAX_VIDEO_CAMERAS)
    }

    @Test
    fun anHlsExtraThatBecomesTheMainPlayerIsNeverTakenAgain() {
        CameraVideoBudget.max = 2
        val ctx = context(store())
        val first = feed(ctx)
        first.start()
        idle()
        extraDue()
        assertEquals(listOf(WEB_RTC, HLS), first.runningKinds)
        assertEquals(1, CameraVideoBudget.extrasInUse)
        // WebRTC fails: HLS, held as the extra, is now the camera's only (main) player.
        live(WEB_RTC).fail()
        idle()
        assertEquals(listOf(HLS), first.runningKinds)
        assertEquals(0, CameraVideoBudget.extrasInUse)
        // Two more cameras need main players: one takes the free slot, the other waits on MJPEG. Neither takes the
        // first camera's HLS. (Other cameras, whose WebRTC hasn't failed: here another connection's.)
        val others = context(store())
        val second = feed(others)
        val third = feed(others)
        second.start()
        third.start()
        idle()
        assertEquals(listOf(HLS), first.runningKinds)
        assertEquals(listOf(WEB_RTC), second.runningKinds)
        assertEquals(listOf(MJPEG), third.runningKinds)
        assertTrue(peakVideo <= 2)
    }

    @Test
    fun aFifthCameraWaitsOnMjpegAndNoMainPlayerIsTakenFromAnother() {
        val ctx = context(store())
        val cams = (0 until 5).map { feed(ctx) }
        cams.forEach { it.start() }
        idle()
        idle()
        assertEquals(4, cams.count { it.runningKinds == listOf(WEB_RTC) })
        assertEquals(listOf(MJPEG), cams.last().runningKinds)
        assertTrue(peakVideo <= CameraVideoBudget.MAX_VIDEO_CAMERAS)
        // One goes: the fifth takes up WebRTC.
        cams.first().stop()
        idle()
        assertEquals(listOf(WEB_RTC), cams.last().runningKinds)
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
        // HLS and WebRTC side by side are two decoders: two slots. The second camera's main player (WebRTC) takes the
        // first's extra (its HLS), so both play WebRTC and neither detours through MJPEG.
        CameraVideoBudget.max = 2
        val ctx = context(store())
        val first = feed(ctx)
        first.start()
        idle()
        extraDue()
        assertEquals(listOf(WEB_RTC, HLS), first.runningKinds)
        assertEquals(2, CameraVideoBudget.inUse)
        val second = feed(ctx)
        second.start()
        idle()
        assertEquals(listOf(WEB_RTC), first.runningKinds)
        assertEquals(listOf(WEB_RTC), second.runningKinds)
        assertEquals(2, CameraVideoBudget.inUse)
        assertEquals(0, CameraVideoBudget.extrasInUse)
        assertTrue(made.none { it.kind == MJPEG })
        assertTrue(peakVideo <= 2)
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
        // One slot left: the main player (WebRTC, the kind HA settles on) alone and shown, not HLS first.
        assertEquals(listOf(WEB_RTC), both.runningKinds)
        assertEquals(2, CameraVideoBudget.inUse)
        // The other camera goes: HLS starts beside WebRTC (still without video), as HA loads them.
        holder.stop()
        idle()
        extraDue()
        assertEquals(listOf(WEB_RTC, HLS), both.runningKinds)
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
        // Only HA's capabilities are asked, as the card is built.
        assertEquals(listOf("camera/capabilities"), wsCalls.map { it.optString("type") })
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
        // The sheet's window is GONE: nothing starts (HA's capabilities are asked, as the sheet is built).
        assertTrue(made.isEmpty())
        assertEquals(listOf("camera/capabilities"), wsCalls.map { it.optString("type") })
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
        assertEquals(listOf("camera/capabilities"), wsCalls.map { it.optString("type") })
        parent.visibility = View.VISIBLE
        idle()
        assertEquals(listOf(WEB_RTC), running())
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
    fun aVideoInACardOfItsOwnShapeFillsItToTheEdge() {
        // A 641 px wide card sized to a 16:9 video rounds its height to 360. Contain alone gives 640 x 360, centred at
        // left 0, so the card's background showed as a dark line down the right edge.
        val frame = CameraFitFrame(activity, CameraFitFrame.CONTAIN)
        frame.setVideoSize(1280, 720)
        assertEquals(641 to 360, frame.fitted(641, 360))
        assertEquals(1007 to 566, frame.fitted(1007, 566))
        // A real letterbox stays one.
        assertEquals(400 to 225, frame.fitted(400, 400))
        assertEquals(636 to 358, frame.fitted(641, 358))
    }


    // ---- Recovery after a drop (#799 C1): HA restarts, every kind fails, HA comes back.

    private fun ms(n: Long) = ShadowLooper.idleMainLooper(n, java.util.concurrent.TimeUnit.MILLISECONDS)

    /** A camera that played WebRTC, then lost every kind while HA was down; MJPEG [mjpegPlays] once HA's HTTP is back. */
    private fun droppedToMjpeg(f: LiveCameraFeed, mjpegPlays: Boolean) {
        f.start()
        idle()
        extraDue()
        live(HLS).frame()
        live(WEB_RTC).frame()
        idle()
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        live(WEB_RTC).fail()
        idle()
        assertEquals(listOf(HLS), f.runningKinds)
        live(HLS).fail()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        if (mjpegPlays) live(MJPEG).frame() else live(MJPEG).fail()
        idle()
    }

    @Test
    fun afterADropHaIsBackAndTheCameraGoesBackToWebRtc() {
        val s = store()
        val f = feed(context(s))
        droppedToMjpeg(f, mjpegPlays = true)
        assertEquals(MJPEG, f.shownKind)
        val mjpeg = live(MJPEG)

        LiveCameraFeed.connectionRestored(s)
        idle()
        // The choice starts over from WebRTC; MJPEG stays on screen until video draws.
        assertEquals(listOf(MJPEG, WEB_RTC), f.runningKinds)
        assertEquals(MJPEG, f.shownKind)
        assertTrue("MJPEG heard it too", "restored" in mjpeg.calls)
        live(WEB_RTC).frame()
        idle()
        // WebRTC shows, and HLS needn't load.
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertEquals(WEB_RTC, f.shownKind)
        assertTrue(mjpeg.closed)
    }

    @Test
    fun afterADropThatTookStillsTooTheCameraGoesBackToVideo() {
        val s = store()
        val f = feed(context(s))
        droppedToMjpeg(f, mjpegPlays = false)
        assertEquals(emptyList<CameraStreamKind>(), f.runningKinds)
        assertTrue(f.isPolling)
        LiveCameraFeed.connectionRestored(s)
        idle()
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        // The stills go on until the video draws.
        assertTrue(f.isPolling)
        live(WEB_RTC).frame()
        idle()
        assertEquals(WEB_RTC, f.shownKind)
        assertFalse(f.isPolling)
        // MJPEG's retry was dropped with its failure: it doesn't open over the video later.
        ms(LiveCameraFeed.RETRY_MS)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
    }

    @Test
    fun aPlayerStillPlayingAtTheReconnectIsLeftAlone() {
        val s = store()
        val f = feed(context(s))
        f.start()
        idle()
        live(WEB_RTC).frame()
        idle()
        val rtc = live(WEB_RTC)
        LiveCameraFeed.connectionRestored(s)
        idle()
        assertEquals(listOf("start", "restored"), rtc.calls)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertEquals(1, made.count { it.kind == WEB_RTC })
    }

    @Test
    fun aReconnectReachesOnlyTheFeedsOfThatConnectionAndNoStoppedOne() {
        val s = store()
        val other = store()
        val f = feed(context(s))
        val g = feed(context(other))
        droppedToMjpeg(f, mjpegPlays = true)
        LiveCameraFeed.connectionRestored(other)
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        g.start()
        g.stop()
        f.stop()
        LiveCameraFeed.connectionRestored(s)
        idle()
        assertEquals(emptyList<CameraStreamKind>(), f.runningKinds)
        assertTrue(made.all { it.closed })
    }

    @Test
    fun aHiddenCameraTriesAgainWhenItShows() {
        val s = store()
        val f = feed(context(s))
        droppedToMjpeg(f, mjpegPlays = true)
        f.setActive(false)
        LiveCameraFeed.connectionRestored(s)
        idle()
        assertTrue("nothing opens while hidden", made.none { !it.closed && it.kind != MJPEG })
        f.setActive(true)
        idle()
        assertEquals(listOf(MJPEG, WEB_RTC), f.runningKinds)
    }

    @Test
    fun aLongHideTriesWhatFailedAgainAndAShortOneDoesnt() {
        val f = feed(context(store()))
        droppedToMjpeg(f, mjpegPlays = true)
        f.setActive(false)
        ms(LiveCameraFeed.LONG_HIDE_MS - 1_000)
        f.setActive(true)
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        f.setActive(false)
        ms(LiveCameraFeed.LONG_HIDE_MS)
        f.setActive(true)
        idle()
        assertEquals(listOf(MJPEG, WEB_RTC), f.runningKinds)
    }

    @Test
    fun aCameraWhoseVideoFailedTriesItAgainNowAndThen() {
        val f = feed(context(store()))
        droppedToMjpeg(f, mjpegPlays = true)
        ms(LiveCameraFeed.VIDEO_RETRY_MS - 1_000)
        assertEquals(listOf(MJPEG), f.runningKinds)
        ms(1_000)
        assertEquals(listOf(MJPEG, WEB_RTC), f.runningKinds)
        // It fails again: back on MJPEG, and the next try is a whole wait away.
        live(WEB_RTC).fail()
        idle()
        assertEquals(listOf(MJPEG, HLS), f.runningKinds)
        live(HLS).fail()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        ms(LiveCameraFeed.VIDEO_RETRY_MS - 1_000)
        assertEquals(listOf(MJPEG), f.runningKinds)
        ms(1_000)
        assertEquals(listOf(MJPEG, WEB_RTC), f.runningKinds)
    }

    @Test
    fun aCameraOnMjpegForWantOfASlotDoesntRetryOnATimer() {
        CameraVideoBudget.max = 1
        val first = feed(context(store()))
        first.start()
        idle()
        live(WEB_RTC).frame()
        idle()
        val s = store()
        val second = LiveCameraFeed(context(s), "camera.porch", { null }, host = FrameLayout(activity)) {}
        second.start()
        idle()
        assertEquals(listOf(MJPEG), second.runningKinds)
        val opened = made.size
        ms(LiveCameraFeed.VIDEO_RETRY_MS)
        assertEquals(listOf(MJPEG), second.runningKinds)
        assertEquals(opened, made.size)
        // A reconnect doesn't take it off the waiting list either: still MJPEG until a slot frees.
        LiveCameraFeed.connectionRestored(s)
        idle()
        assertEquals(listOf(MJPEG), second.runningKinds)
        first.stop()
        idle()
        assertTrue(WEB_RTC in second.runningKinds)
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

    // ---- More Info comes first for a slot (#799 C2)

    /** Four cameras on WebRTC, one slot each, with video: every slot held by a main player. */
    private fun fourOnWebRtc(ctx: VitroCardContext): List<LiveCameraFeed> {
        val cams = (0 until 4).map { feed(ctx) }
        cams.forEach { it.start() }
        idle()
        made.filter { it.kind == WEB_RTC && !it.closed }.forEach { it.frame() }
        idle()
        for (c in cams) assertEquals(listOf(WEB_RTC), c.runningKinds)
        assertEquals(4, CameraVideoBudget.inUse)
        return cams
    }

    @Test
    fun theMoreInfoCameraTakesASlotFromACardBehindAndGivesItBackWhenItCloses() {
        val st = store()
        val cams = fourOnWebRtc(context(st))
        val sheet = dev.glassworks.engine.vitro.cards.MoreInfoSheet(context(st)) {}
        activity.setContentView(sheet)
        showWindow(activity.window.decorView)
        sheet.show("camera.porch")
        idle()
        idle()
        // The sheet plays WebRTC, not MJPEG; the card granted last plays MJPEG meanwhile, and the cap holds.
        val sheetRtc = made.last { it.kind == WEB_RTC }
        assertFalse(sheetRtc.closed)
        assertEquals(listOf(MJPEG), cams.last().runningKinds)
        for (c in cams.dropLast(1)) assertEquals(listOf(WEB_RTC), c.runningKinds)
        assertEquals(4, CameraVideoBudget.inUse)
        assertTrue("peak $peakVideo", peakVideo <= CameraVideoBudget.MAX_VIDEO_CAMERAS)
        // The sheet closes: that card takes WebRTC up again.
        sheet.dismiss()
        activity.setContentView(View(activity))
        idle()
        idle()
        assertTrue(sheetRtc.closed)
        assertEquals(listOf(WEB_RTC), cams.last().runningKinds)
    }

    @Test
    fun aCardNeverTakesASlotFromAnotherCardOnlyMoreInfoDoes() {
        val ctx = context(store())
        val cams = fourOnWebRtc(ctx)
        val fifth = feed(ctx)
        fifth.start()
        idle()
        assertEquals(listOf(MJPEG), fifth.runningKinds)
        for (c in cams) assertEquals(listOf(WEB_RTC), c.runningKinds)
        // A foreground camera (More Info's) does, and the card it took the slot from is first in line for the next.
        val sheet = LiveCameraFeed(ctx, "camera.porch", { null }, host = FrameLayout(activity), foreground = true) {}
        sheet.start()
        idle()
        assertEquals(listOf(WEB_RTC), sheet.runningKinds)
        assertEquals(listOf(MJPEG), cams.last().runningKinds)
        sheet.stop()
        idle()
        assertTrue(WEB_RTC in cams.last().runningKinds)
        assertEquals(listOf(MJPEG), fifth.runningKinds)
    }

    // ---- WebRTC that can't connect (#799 C3)

    @Test
    fun aWebRtcThatCouldntConnectSendsTheCamerasOtherPicturesToHlsForAWhile() {
        val ctx = context(store())
        val first = feed(ctx)
        first.start()
        idle()
        live(WEB_RTC).fail()
        idle()
        assertEquals(listOf(HLS), first.runningKinds)
        // The camera's picture made again (a rebuilt view, More Info): HLS at once, no WebRTC to wait for.
        val again = feed(ctx)
        again.start()
        idle()
        assertEquals(listOf(HLS), again.runningKinds)
        again.stop()
        // Later, WebRTC is tried first again.
        ms(dev.glassworks.engine.vitro.camera.CameraKnowledge.WEBRTC_FAILED_FOR_MS)
        val later = feed(ctx)
        later.start()
        idle()
        assertEquals(listOf(WEB_RTC), later.runningKinds)
    }

    @Test
    fun aWebRtcThatFailedForWantOfHasConnectionIsNotHeldAgainstTheCamera() {
        val ctx = context(store())
        val first = feed(ctx)
        first.start()
        idle()
        live(WEB_RTC).listener!!.onError("start_failed: not_connected: no connection")
        idle()
        val again = feed(ctx)
        again.start()
        idle()
        assertEquals(listOf(WEB_RTC), again.runningKinds)
    }

    @Test
    fun webRtcAloneWithoutVideoGivesWayToHlsSoonerThanItsOwnTimeout() {
        // One slot: WebRTC can't have HLS beside it.
        CameraVideoBudget.max = 1
        val f = feed(context(store()))
        f.start()
        idle()
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS - 100)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        ms(100)
        assertTrue(made.first { it.kind == WEB_RTC }.closed)
        assertEquals(listOf(HLS), f.runningKinds)
        assertEquals(1, CameraVideoBudget.inUse)
    }

    @Test
    fun webRtcAlonesWaitForVideoCountsFromItsSurfaceNotItsStart() {
        // One slot, and a player started while its view is still being built: no surface yet.
        CameraVideoBudget.max = 1
        noSurfaceFor += WEB_RTC
        val f = feed(context(store()))
        f.start()
        idle()
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        // A long build: the wait hasn't started.
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS * 2)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        // Laid out: the wait counts from here.
        live(WEB_RTC).surface()
        idle()
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS - 100)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        ms(100)
        assertEquals(listOf(HLS), f.runningKinds)
    }

    @Test
    fun webRtcAlonesWaitStartsOverOnTheNewestSurface() {
        CameraVideoBudget.max = 1
        val f = feed(context(store()))
        f.start()
        idle()
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS - 3_000)
        // The view is hidden and shown again before a first frame: its surface goes and a new one comes.
        live(WEB_RTC).loseSurface()
        idle()
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS)
        assertEquals("no surface, no wait", listOf(WEB_RTC), f.runningKinds)
        live(WEB_RTC).surface()
        idle()
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS - 100)
        assertEquals("the wait counts from the new surface", listOf(WEB_RTC), f.runningKinds)
        ms(100)
        assertEquals(listOf(HLS), f.runningKinds)
    }

    @Test
    fun webRtcWithHlsBesideItKeepsItsOwnTimeout() {
        val f = feed(context(store()))
        f.start()
        idle()
        extraDue()
        assertEquals(listOf(WEB_RTC, HLS), f.runningKinds)
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS * 2)
        assertEquals(listOf(WEB_RTC, HLS), f.runningKinds)
    }

    @Test
    fun aWebRtcThatShowsVideoInTimeIsntCutOff() {
        CameraVideoBudget.max = 1
        val f = feed(context(store()))
        f.start()
        idle()
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS / 2)
        live(WEB_RTC).frame()
        idle()
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertEquals(WEB_RTC, f.shownKind)
    }

    // ---- The first frame's levers (#799 C4)

    @Test
    fun capabilitiesAreAskedAsTheCardIsBuiltAndOnceForEveryPictureOfTheCamera() {
        val ctx = context(store())
        val first = feed(ctx)
        // Built, not started: asked already.
        assertEquals(listOf("camera/capabilities"), wsCalls.map { it.optString("type") })
        val second = feed(ctx)
        idle()
        first.start()
        second.start()
        idle()
        assertEquals(1, wsCalls.count { it.optString("type") == "camera/capabilities" })
        assertEquals(listOf(WEB_RTC), first.runningKinds)
        assertEquals(listOf(WEB_RTC), second.runningKinds)
        // HA offers WebRTC: its player got ready while the view built.
        assertEquals(listOf(WEB_RTC), prewarmed)
    }

    @Test
    fun webRtcIsPrewarmedOnTheMainThreadWhenTheAnswerComesThrough() {
        // The answer comes on the socket's thread; the factory's prewarm is a main-thread entry point (it starts its
        // own background work), so it runs when the answer reaches the main thread, once.
        pendingCaps = ArrayList()
        feed(context(store()))
        val answer = pendingCaps!!.single()
        val worker = Thread { answer(true, capabilities, null) }
        worker.start()
        worker.join()
        assertTrue("not on the socket's thread", prewarmed.isEmpty())
        idle()
        assertEquals(listOf(WEB_RTC), prewarmed)
        assertEquals(android.os.Looper.getMainLooper().thread, prewarmThreads.single())
    }

    @Test
    fun aCameraWhoseCapabilitiesAreKnownStartsAsItsViewBuilds() {
        val ctx = context(store())
        // Unknown yet: building the card starts nothing (it asks, as before).
        val unknown = card(ctx)
        unknown.startEarly()
        assertTrue(made.isEmpty())
        idle()
        // Known now (the first card's answer): the next card's players open as it's built, before it's attached.
        val known = card(ctx)
        known.startEarly()
        assertEquals(listOf(WEB_RTC), running())
        // Attached and shown in the same turn, as a view's build does: it plays on.
        val parent = FrameLayout(activity)
        parent.addView(known, FrameLayout.LayoutParams(720, 405))
        activity.setContentView(parent)
        showWindow(activity.window.decorView)
        idle()
        assertEquals(listOf(WEB_RTC), running())
        assertEquals(1, made.size)
        // One the build never attached closes what it opened on the next turn.
        val dropped = card(ctx)
        dropped.startEarly()
        assertEquals(2, made.count { !it.closed })
        idle()
        assertEquals(listOf(WEB_RTC), running())
    }

    @Test
    fun aViewBuildStartsOnlyTheCamerasItShows() {
        val s = store().apply { putLocal(EntityState("binary_sensor.door", "off", emptyMap())) }
        // The connection knows the camera (another picture of it asked).
        feed(context(s))
        idle()
        val vv = dev.glassworks.engine.vitro.ui.VitroView(activity, s, 0xFF000000.toInt())
        vv.contextFactory = { _ -> context(s) }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"title": "Cams", "cards": [
            {"type": "picture-entity", "entity": "camera.porch", "camera_view": "live"},
            {"type": "picture-entity", "entity": "camera.porch", "camera_view": "live",
             "visibility": [{"condition": "state", "entity": "binary_sensor.door", "state": "on"}]}]}]}""")), null)
        // Built (not attached yet): the shown card's player is open, the hidden card's isn't.
        assertEquals(listOf(WEB_RTC), running())
        // Never attached here: it closes.
        idle()
        assertTrue(running().isEmpty())
    }

    @Test
    fun theConnectionBackAfterADropAsksForCapabilitiesAgain() {
        val s = store()
        feed(context(s))
        idle()
        LiveCameraFeed.connectionRestored(s)
        val f = feed(context(s))
        idle()
        f.start()
        idle()
        assertEquals(2, wsCalls.count { it.optString("type") == "camera/capabilities" })
    }

    @Test
    fun aCameraThatOffersNoWebRtcPrewarmsNothing() {
        capabilities = JSONObject().put("frontend_stream_types", JSONArray().put("hls"))
        val f = feed(context(store()))
        f.start()
        idle()
        assertEquals(listOf(HLS), f.runningKinds)
        assertTrue(prewarmed.isEmpty())
    }

    @Test
    fun webRtcWithVideoInItsFirstMomentsNeverLoadsHls() {
        val f = feed(context(store()))
        f.start()
        idle()
        live(WEB_RTC).frame()
        idle()
        extraDue()
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertTrue(made.none { it.kind == HLS })
        assertEquals(1, CameraVideoBudget.inUse)
    }

    @Test
    fun hlsLoadingBesideWebRtcStopsOnceWebRtcHasVideo() {
        val f = feed(context(store()))
        f.start()
        idle()
        extraDue()
        assertEquals(listOf(WEB_RTC, HLS), f.runningKinds)
        live(WEB_RTC).frame()
        idle()
        // HA would run HLS hidden until it loads, then show WebRTC anyway (muted): it goes now.
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertEquals(1, CameraVideoBudget.inUse)
    }

    @Test
    fun heavysFourCamerasNeverOpenAnHlsTheNextCameraTakesBack() {
        val ctx = context(store())
        val cams = (0 until 4).map { feed(ctx) }
        for (c in cams) {
            c.start()
            idle()
        }
        made.filter { it.kind == WEB_RTC && !it.closed }.forEach { it.frame() }
        idle()
        extraDue()
        for (c in cams) assertEquals(listOf(WEB_RTC), c.runningKinds)
        assertTrue(made.none { it.kind == HLS })
        assertEquals(4, made.size)
    }

    // ---- Hidden and off-screen cameras (#799 C6, C7; G L4)

    @Test
    fun aPlayerThatKeepsNothingWorthKeepingIsClosedWhenHiddenAndOpenedAgainOnShow() {
        closesWhenHiddenFor += WEB_RTC
        val f = feed(context(store()))
        f.start()
        idle()
        val rtc = live(WEB_RTC)
        rtc.frame()
        idle()
        f.setActive(false)
        assertTrue(rtc.closed)
        assertFalse("closed, not paused", "active false" in rtc.calls)
        assertEquals(0, CameraVideoBudget.inUse)
        f.setActive(true)
        idle()
        // A new one, straight to WebRTC (it had video): no HLS beside it.
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        assertTrue(live(WEB_RTC) !== rtc)
        assertEquals(1, CameraVideoBudget.inUse)
    }

    @Test
    fun aReplacementOpenedOnShowThatShowsNoVideoStillGivesWayToHls() {
        closesWhenHiddenFor += WEB_RTC
        CameraVideoBudget.max = 1
        val f = feed(context(store()))
        f.start()
        idle()
        live(WEB_RTC).frame()
        idle()
        f.setActive(false)
        f.setActive(true)
        idle()
        // The new player has shown nothing: it gets WebRTC's short wait alone, then HLS takes over.
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS)
        assertEquals(listOf(HLS), f.runningKinds)
    }

    @Test
    fun aParkedCamerasReplacementThatShowsNoVideoStillGivesWayToHls() {
        CameraVideoBudget.max = 1
        val top = feed(context(store()))
        top.start()
        idle()
        live(WEB_RTC).frame()
        idle()
        val below = feed(context(store()))
        below.start()
        idle()
        top.setOnScreen(false)
        ms(LiveCameraFeed.PARK_MS)
        idle()
        assertEquals(emptyList<CameraStreamKind>(), top.runningKinds)
        // The camera below goes; the top one, back on screen, takes the slot with a new WebRTC that shows nothing.
        below.stop()
        top.setOnScreen(true)
        idle()
        assertEquals(listOf(WEB_RTC), top.runningKinds)
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS)
        assertEquals(listOf(HLS), top.runningKinds)
    }

    @Test
    fun aCardMoreInfoTookTheSlotFromComesBackAsANewPlayer() {
        val st = store()
        val cams = fourOnWebRtc(context(st))
        val sheet = LiveCameraFeed(context(st), "camera.porch", { null }, host = FrameLayout(activity), foreground = true) {}
        sheet.start()
        idle()
        assertEquals(listOf(MJPEG), cams.last().runningKinds)
        sheet.stop()
        idle()
        // Its new WebRTC has shown nothing: loading, so with no slot for HLS beside it, it gets WebRTC's short wait
        // alone and then HLS, as any new player.
        assertEquals(listOf(WEB_RTC), cams.last().runningKinds)
        ms(LiveCameraFeed.ALONE_TIMEOUT_MS)
        assertEquals(listOf(HLS), cams.last().runningKinds)
    }

    @Test
    fun aCameraThatLostTheStreamFeatureIsReadAgainOnStartAndReconnect() {
        val st = EntityStore().apply {
            putLocal(EntityState("camera.porch", "streaming", mapOf("supported_features" to 2)))
        }
        val f = feed(context(st))
        f.start()
        idle()
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        // HA restarts and the camera lost its STREAM feature: MJPEG, without asking.
        st.putLocal(EntityState("camera.porch", "streaming", mapOf("supported_features" to 0)))
        LiveCameraFeed.connectionRestored(st)
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        assertEquals(1, wsCalls.count { it.optString("type") == "camera/capabilities" })
        f.stop()
        f.start()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
    }

    @Test
    fun aCapabilitiesRequestLostToAReconnectIsAskedAgain() {
        // HA never answers the first request: the connection dropped with it.
        val pending = ArrayList<(Boolean, Any?, JSONObject?) -> Unit>()
        pendingCaps = pending
        val s = store()
        val f = feed(context(s))
        f.start()
        idle()
        assertEquals(1, pending.size)
        LiveCameraFeed.connectionRestored(s)
        idle()
        assertEquals(2, pending.size)
        // The old reply lands late: dropped. The new one is the answer.
        pending[0](true, JSONObject().put("frontend_stream_types", JSONArray()), null)
        idle()
        assertTrue(f.runningKinds.isEmpty())
        pending[1](true, capabilities, null)
        idle()
        assertEquals(listOf(WEB_RTC), f.runningKinds)
    }

    @Test
    fun aCameraHeardBackAfterADropAsksHaAgainAndTakesUpWhatItOffersNow() {
        // HA offered no stream type; it restarts with HLS on.
        capabilities = JSONObject().put("frontend_stream_types", JSONArray())
        val s = store()
        val f = feed(context(s))
        f.start()
        idle()
        assertEquals(listOf(MJPEG), f.runningKinds)
        capabilities = JSONObject().put("frontend_stream_types", JSONArray().put("hls"))
        LiveCameraFeed.connectionRestored(s)
        idle()
        assertEquals(2, wsCalls.count { it.optString("type") == "camera/capabilities" })
        assertTrue(HLS in f.runningKinds)
        // Stopped and started again, it takes the connection's answer, not a copy of its own from before.
        f.stop()
        f.start()
        idle()
        assertEquals(listOf(HLS), f.runningKinds)
    }

    @Test
    fun anOffScreenCameraStopsDrawingAtOnceAndDrawsAgainBackOnScreen() {
        val f = feed(context(store()))
        f.start()
        idle()
        val rtc = live(WEB_RTC)
        rtc.frame()
        idle()
        f.setOnScreen(false)
        assertEquals("rendering false", rtc.calls.last())
        assertFalse(rtc.closed)
        f.setOnScreen(true)
        assertEquals("rendering true", rtc.calls.last())
        assertEquals(listOf(WEB_RTC), f.runningKinds)
    }

    @Test
    fun anOffScreenCamerasSlotGoesToACameraOnScreenThatWaits() {
        CameraVideoBudget.max = 1
        val ctx = context(store())
        val top = feed(ctx)
        top.start()
        idle()
        live(WEB_RTC).frame()
        idle()
        val below = feed(context(store()))
        below.start()
        idle()
        assertEquals(listOf(MJPEG), below.runningKinds)
        // Scrolled: the top camera off screen, the one below on it. A moment off screen changes nothing.
        top.setOnScreen(false)
        ms(LiveCameraFeed.PARK_MS - 100)
        assertEquals(listOf(WEB_RTC), top.runningKinds)
        assertEquals(listOf(MJPEG), below.runningKinds)
        ms(100)
        idle()
        // Off screen a while: its slot goes to the camera on screen, and it rests (no MJPEG off screen either).
        assertEquals(emptyList<CameraStreamKind>(), top.runningKinds)
        assertTrue(WEB_RTC in below.runningKinds)
        assertEquals(1, CameraVideoBudget.inUse)
        // Back on screen: it waits for a slot on MJPEG, as any camera past the cap.
        top.setOnScreen(true)
        idle()
        assertEquals(listOf(MJPEG), top.runningKinds)
        assertTrue(peakVideo <= 1)
    }

    @Test
    fun anOffScreenCameraNobodyWaitsForKeepsPlaying() {
        val f = feed(context(store()))
        f.start()
        idle()
        live(WEB_RTC).frame()
        idle()
        f.setOnScreen(false)
        ms(LiveCameraFeed.PARK_MS * 10)
        assertEquals(listOf(WEB_RTC), f.runningKinds)
        // Then one does: the slot goes at once.
        CameraVideoBudget.max = 1
        val other = feed(context(store()))
        other.start()
        idle()
        idle()
        assertEquals(emptyList<CameraStreamKind>(), f.runningKinds)
        assertTrue(WEB_RTC in other.runningKinds)
    }

    /** What a frame does after a scroll: the window's scroll listeners hear it. */
    private fun scrolled() = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
        activity.window.decorView.viewTreeObserver, "dispatchOnScrollChanged",
    )

    @Test
    fun aCardScrolledOutOfSightTellsItsCameraItsOffScreen() {
        val scroll = android.widget.ScrollView(activity)
        val column = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        scroll.addView(column)
        val c = card(context(store(), kinds = setOf(HLS, MJPEG)), extra = ", \"aspect_ratio\": \"16:9\"")
        column.addView(c, android.widget.LinearLayout.LayoutParams(720, 405))
        column.addView(View(activity), android.widget.LinearLayout.LayoutParams(720, 4000))
        activity.setContentView(scroll, android.view.ViewGroup.LayoutParams(720, 720))
        showWindow(activity.window.decorView)
        idle()
        val hls = live(HLS)
        assertFalse("rendering false" in hls.calls)
        scroll.scrollTo(0, 2000)
        idle()
        scrolled()
        assertEquals("rendering false", hls.calls.last())
        scroll.scrollTo(0, 0)
        scrolled()
        assertEquals("rendering true", hls.calls.last())
    }

    // ---- Logged fallbacks (#799 C8)

    @Test
    fun aFallbackIsLoggedOnceWithItsCodeOnly() {
        org.robolectric.shadows.ShadowLog.clear()
        val f = feed(context(store()))
        f.start()
        idle()
        live(WEB_RTC).listener!!.onError("start_failed: webrtc_offer_failed: rtsp://user:secret@192.0.2.7/stream")
        idle()
        val lines = org.robolectric.shadows.ShadowLog.getLogsForTag("VitroCamera").map { it.msg }
        assertEquals(listOf("camera.porch: web_rtc fell back (start_failed: webrtc_offer_failed)"), lines)
        assertEquals("error", LiveCameraFeed.codeOf("Something: else"))
    }
}
