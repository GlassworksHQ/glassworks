package dev.glassworks

/**
 * Start-up work the first dashboard frame doesn't wait for. On the S1 (4 slow cores) the voice pane's wake word and
 * microphone, the ESPHome server and its mDNS, Sendspin, the wall base's probes and Home Assistant's discovery all
 * started with the app, and took the CPU and the main thread from the dashboard's first build. Each of them now asks
 * [run], and starts [settleMs] after the first dashboard frame is on screen.
 *
 * What counts as that frame ([reached]), whichever comes first:
 * - the engine's first paint of the page: Vitro's first screen of the view, GeckoView's first contentful paint;
 * - the page's load ending, loaded or failed (GeckoView's first page load; a Home Assistant that isn't there);
 * - the page saying it's drawn (`ready`), or the connecting screen hidden for any other reason;
 * - the setup screen up instead of a dashboard;
 * - [timeoutMs] after the first use, so nothing waits for a frame that never comes (no activity, a page that hangs).
 *
 * Once per process: a task asked for after that runs at once (on [schedule]'s thread, posted). Tasks run in the order
 * they were asked for; one that throws is logged and the others still run. Any thread.
 *
 * @param schedule Runs a task after a delay, on the main thread in the app (a test runs them by hand).
 * @param now The clock the log's times are read on.
 */
class AfterFirstFrame(
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    private val warn: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val settleMs: Long = SETTLE_MS,
    timeoutMs: Long = TIMEOUT_MS,
) {
    private class Task(val name: String, val block: () -> Unit) {
        @Volatile var cancelled = false
    }

    private val lock = Any()
    private val queue = ArrayList<Task>()
    private val since = now()
    private var reachedWhy: String? = null
    private var released = false

    init {
        schedule(timeoutMs) { reached("no dashboard frame after $timeoutMs ms") }
    }

    /** The first dashboard frame has been reached (the tasks may still wait their [settleMs]). */
    val passed: Boolean get() = synchronized(lock) { reachedWhy != null }

    /**
     * Runs [block] [settleMs] after the first dashboard frame, or now (posted) if that has passed. [name] is for the
     * log. Returns what cancels it, for an owner that stops before it ran.
     */
    fun run(name: String, block: () -> Unit): () -> Unit {
        val task = Task(name, block)
        val later = synchronized(lock) { if (released) false else queue.add(task) }
        if (!later) schedule(0) { runOne(task) }
        return { task.cancelled = true }
    }

    /** The first dashboard frame is on screen, for [why]. Only the first call counts. */
    fun reached(why: String) {
        synchronized(lock) {
            if (reachedWhy != null) return
            reachedWhy = why
        }
        log("First dashboard frame (${now() - since} ms in): $why; the start-up work follows in $settleMs ms")
        schedule(settleMs) { release() }
    }

    private fun release() {
        val tasks = synchronized(lock) {
            released = true
            queue.toList().also { queue.clear() }
        }
        tasks.forEach(::runOne)
    }

    private fun runOne(task: Task) {
        if (task.cancelled) return
        try {
            task.block()
        } catch (e: RuntimeException) {
            warn("Start-up work '${task.name}' failed", e)
        }
    }

    companion object {
        /** How long after the first frame the work starts: the frame after it (a sliced build's next chunk) goes first. */
        const val SETTLE_MS = 250L

        /** The longest any work waits: past the S1's slowest first frame (Vitro's heavy test dashboard, about 3.4 s). */
        const val TIMEOUT_MS = 10_000L
    }
}
