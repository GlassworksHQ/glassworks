/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.conformance

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.glassworks.engine.Page
import dev.glassworks.engine.conformance.RecordingHost.Companion.CONNECT
import dev.glassworks.engine.conformance.RecordingHost.Companion.LOCATION
import dev.glassworks.engine.conformance.RecordingHost.Companion.PAINT
import dev.glassworks.engine.conformance.RecordingHost.Companion.START
import dev.glassworks.engine.conformance.RecordingHost.Companion.STOP
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What every engine test stands on: a [FakeHomeAssistant] on the device's loopback, the engine under test built
 * fresh (each test runs in a process of its own, the orchestrator), an activity to open pages in, and a host that
 * writes down what the page says. After each test the main thread must still answer (no ANR), and every host call
 * must have come on it.
 */
abstract class EngineHarness {
    /** The engine under test, built fresh for each test as an app builds it at start. */
    protected abstract fun subject(ha: FakeHomeAssistant): Subject

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context: Context = instrumentation.targetContext
    protected lateinit var ha: FakeHomeAssistant
    protected lateinit var subject: Subject
    protected val engine get() = subject.engine
    protected val host = RecordingHost()
    private lateinit var scenario: ActivityScenario<HostActivity>

    @Before fun setUp() {
        ha = FakeHomeAssistant()
        subject = subject(ha)
        scenario = ActivityScenario.launch(HostActivity::class.java)
    }

    @After fun tearDown() {
        try {
            // No ANR: the main thread still answers once the test is done with the page.
            assertTrue("the main thread is blocked", mainAnswersWithin(ANR_MS))
            assertTrue("host calls off the main thread: ${host.offMain}", host.offMain.isEmpty())
        } finally {
            runCatching { scenario.close() }
            subject.close()
            ha.stop()
        }
    }

    // ---- helpers

    /** Opens a page in the activity and puts its view in it, as MainActivity does. */
    protected fun open(): Page {
        val page = AtomicReference<Page>()
        scenario.onActivity { a -> page.set(engine.open(a, host, Color.BLACK).also { a.show(it.view) }) }
        return page.get()
    }

    /** Loads [url] and waits for its `ready`. */
    protected fun load(page: Page, url: String) {
        val readies = host.readies().size
        onMain { page.load(url) }
        val path = RecordingHost.pathOf(url)
        await("$path is ready: $host", FIRST_LOAD_MS) { host.readies().size > readies }
        await("$path is where the page is: ${host.locationPaths()}") { host.locationPaths().lastOrNull() == path }
    }

    protected fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    protected fun await(what: String, timeoutMs: Long = WAIT_MS, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out after $timeoutMs ms: $what")
            Thread.sleep(50)
        }
    }

    protected fun capture(page: Page): Bitmap? {
        val got = AtomicReference<Bitmap?>()
        val done = CountDownLatch(1)
        val onMain = AtomicReference<Boolean>()
        onMain { page.capture { b -> onMain.set(Looper.myLooper() == Looper.getMainLooper()); got.set(b); done.countDown() } }
        assertTrue("capture answers", done.await(WAIT_MS, TimeUnit.MILLISECONDS))
        assertTrue("capture answers on the main thread", onMain.get())
        assertNotNull("a bitmap: this engine can capture", got.get())
        return got.get()
    }

    protected fun colors(b: Bitmap): Int {
        val seen = HashSet<Int>()
        val step = maxOf(1, minOf(b.width, b.height) / 40)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            seen += b.getPixel(x, y)
            if (seen.size > 1) return seen.size
        }
        return seen.size
    }

    protected fun isInside(v: View, ancestor: View): Boolean {
        var p = v.parent
        while (p != null) { if (p === ancestor) return true; p = (p as? ViewGroup)?.parent ?: p.parent }
        return false
    }

    protected fun processName(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses.orEmpty().first { it.pid == Process.myPid() }.processName
    }

    private fun mainAnswersWithin(ms: Long): Boolean {
        val l = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { l.countDown() }
        return l.await(ms, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** A first load starts the engine (GeckoView's runtime takes seconds on an emulator). */
        const val FIRST_LOAD_MS = 90_000L
        const val WAIT_MS = 30_000L
        /** How long a refused load has to show it never went out: a load that is allowed takes about 2 s on an emulator. */
        const val REFUSAL_MS = 6_000L
        /** Android's own ANR limit for input. */
        const val ANR_MS = 5_000L
    }
}

/**
 * The engine contract (docs/engine-contract.md): what the core relies on from every [dev.glassworks.engine.Engine],
 * checked on a real Android runtime. Each engine has a subclass that names its [subject]; every test here runs for
 * each, in a process of its own (the orchestrator), against a [FakeHomeAssistant] on the device's loopback.
 *
 * Where engines differ, they say so in [Capabilities] and the check tests what they declared.
 */
abstract class EngineContract : EngineHarness() {
    // ---- open

    @Test fun openGivesAViewTheHostAttaches() {
        assertNull("no settings read before the first open", engine.started)
        val page = open()
        onMain {
            assertTrue("the page's view is in the window", page.view.isAttachedToWindow)
            assertTrue("and fills it", page.view.width > 0 && page.view.height > 0)
        }
        assertNotNull("the settings it started with, once open", engine.started)
        assertEquals("the dashboard it was set to", Subjects.dashboard(ha), engine.started?.dashboard)
        assertTrue("open loads nothing but an empty first page: $host",
            host.events.none { it.kind == START && it.value != "about:blank" })
    }

    // ---- load

    /**
     * A dashboard load: onPageStart for the dashboard's URL, then a first paint, onPageStop(true) and a `ready` from a
     * top-level port the host was given in onConnect. A new browser session may report its empty first page
     * (`about:blank`) before it; nothing of the dashboard comes before its start. The core shows the page on `ready`
     * (MainActivity.onPageReady) and keeps onFirstPaint and onPageStop(true) as fallbacks, so it needs each of them after
     * the start, not their order among themselves (GeckoView paints after it stops, Vitro before).
     */
    @Test fun aDashboardLoadStartsPaintsStopsAndIsReady() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        fun dashboardStart(e: List<RecordingHost.Event>) =
            e.indexOfFirst { it.kind == START && RecordingHost.pathOf(it.value.orEmpty()) == "/lovelace/home" }
        // `ready` may come first: wait for the paint and the stop too before reading the order.
        await("a paint and a stop after the dashboard's start: $host") {
            val e = host.events.toList()
            val start = dashboardStart(e)
            start >= 0 && e.drop(start + 1).let { after -> after.any { it.kind == PAINT } && after.any { it.kind == STOP } }
        }
        val e = host.events.toList()
        val start = dashboardStart(e)
        assertTrue("onPageStart for the dashboard: $host", start >= 0)
        assertTrue("before it, only an empty first page: $host",
            e.take(start).filter { it.kind == START }.all { it.value == "about:blank" })
        val after = e.drop(start + 1)
        fun at(i: Int) = if (i < 0) -1 else start + 1 + i
        val paint = at(after.indexOfFirst { it.kind == PAINT })
        val stop = at(after.indexOfFirst { it.kind == STOP })
        assertTrue("a first paint after the start: $host", paint > start)
        assertTrue("onPageStop after the start: $host", stop > start)
        assertEquals("a successful stop: $host", "true", e[stop].value)
        val ready = at(after.indexOfFirst { it.kind == RecordingHost.MESSAGE && it.message?.optString("type") == "ready" })
        assertTrue("ready after the start: $host", ready > start)
        val port = e[ready].port!!
        assertTrue("from the top-level page", port.topLevel)
        assertTrue("from a port the host was given first: $host", e.take(ready).any { it.kind == CONNECT && it.port === port })
        assertTrue("the location is the dashboard: ${host.locationPaths()}", "/lovelace/home" in host.locationPaths())
    }

    /**
     * A page that isn't a dashboard (Home Assistant's sign-in) loads and reports where it is. An engine with a fallback
     * opens the fallback's page for it, inside its own view.
     */
    @Test fun aSignInPageLoadsAndSaysWhereItIs() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        val readies = host.readies().size
        onMain { page.load(Subjects.signIn(ha)) }
        await("the sign-in page's location: $host") { "/auth/authorize" in host.locationPaths() }
        await("the sign-in page is ready: $host") { host.readies().size > readies }
        assertTrue("Home Assistant served it: ${ha.pages}", "/auth/authorize" in ha.pages)
        val fallback = subject.fallback
        if (subject.capabilities.fallback) {
            assertNotNull(fallback)
            assertEquals("one fallback page", 1, fallback!!.pages.size)
            onMain {
                val shown = fallback.pages.single().view
                assertTrue("inside the page's view", isInside(shown, page.view))
                assertEquals("and shown", View.VISIBLE, shown.visibility)
            }
        } else {
            assertNull("no fallback declared, none made", fallback)
        }
    }

    // ---- post

    /** A port message reaches the page: `navigate` (a notification's view, #118) moves it to another view. */
    @Test fun postReachesThePage() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        onMain { page.post(JSONObject().put("type", "navigate").put("url", Subjects.secondView(ha))) }
        await("the page moved to the second view: ${host.locationPaths()}") { "/lovelace/second" in host.locationPaths() }
    }

    // ---- goBack, reload, restart

    @Test fun goBackReturnsToThePreviousPage() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        load(page, Subjects.secondView(ha))
        val before = host.count(LOCATION)
        onMain { page.goBack() }
        await("back on the first view: ${host.locationPaths()}") {
            host.events.filter { it.kind == LOCATION }.drop(before).any { RecordingHost.pathOf(it.value.orEmpty()) == "/lovelace/home" }
        }
    }

    @Test fun reloadLoadsThePageAgain() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        val stops = host.count(STOP, "true")
        val readies = host.readies().size
        onMain { page.reload() }
        await("ready again: $host") { host.readies().size > readies }
        await("stopped again: $host") { host.count(STOP, "true") > stops }
    }

    /** What MainActivity does when the content is gone: restart the page, then load the dashboard in it. */
    @Test fun restartGivesAPageThatLoadsAgain() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        val readies = host.readies().size
        onMain {
            page.restart()
            page.load(Subjects.dashboard(ha))
        }
        await("ready after the restart: $host") { host.readies().size > readies }
        onMain { assertTrue("the same view stays in the window", page.view.isAttachedToWindow) }
    }

    // ---- capture

    /** A still of the page, on the main thread, at the view's size, showing more than one color. */
    @Test fun captureShowsThePage() {
        val page = open()
        load(page, Subjects.dashboard(ha))
        val size = AtomicReference<Pair<Int, Int>>()
        onMain { size.set(page.view.width to page.view.height) }
        // A browser's compositor may show its first frame a moment after the page said it is ready.
        var last: Bitmap? = null
        await("a capture that isn't one flat color") {
            last = capture(page)
            last?.let { colors(it) > 1 } == true
        }
        val b = last!!
        assertEquals("the view's size", size.get(), b.width to b.height)
    }

    // ---- what the engine says about itself

    /** The app's own process is never a content process; an engine that has them names them. */
    @Test fun contentProcessesAreTheEngines() {
        val own = processName()
        assertFalse("the app's own process ($own)", engine.isContentProcess(own))
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services.orEmpty().mapNotNull { it.processName }.distinct()
        val content = declared.filter(engine::isContentProcess)
        if (subject.capabilities.contentProcesses) {
            assertTrue("a content process among $declared", content.isNotEmpty())
        } else {
            assertTrue("no content process: $content", content.isEmpty())
        }
        subject.fallback?.let { fb ->
            for (name in declared + own) assertEquals("as its fallback says, for $name", fb.isContentProcess(name), engine.isContentProcess(name))
        }
    }

    @Test fun lockedDownIsWhatItDeclares() {
        assertEquals(subject.capabilities.lockedDown, engine.lockedDown)
        subject.fallback?.let { assertEquals("as its fallback", it.lockedDown, engine.lockedDown) }
        open()
        assertEquals("the same once open", subject.capabilities.lockedDown, engine.lockedDown)
        assertTrue("a name for the Manage page", engine.name.isNotBlank())
    }

    /**
     * What `lockedDown` means, done: a locked-down engine never asks another origin for a page, and an open one loads
     * it. The page is one no dashboard engine draws (a settings page), so a fallback engine hands it to its fallback.
     */
    @Test fun anotherOriginLoadsOnlyWhenNotLockedDown() {
        val other = FakeHomeAssistant()
        try {
            val page = open()
            load(page, Subjects.dashboard(ha))
            onMain { page.load("${other.origin}/config/info") }
            if (subject.capabilities.lockedDown) {
                Thread.sleep(REFUSAL_MS)
                assertTrue("no page asked of another origin: ${other.pages}", other.pages.isEmpty())
                assertTrue("and none shown: ${host.locationPaths()}", "/config/info" !in host.locationPaths())
            } else {
                await("the other origin's page is where the page is: ${host.locationPaths()}") { "/config/info" in host.locationPaths() }
                assertTrue("the other origin served it: ${other.pages}", "/config/info" in other.pages)
            }
        } finally {
            other.stop()
        }
    }
}
