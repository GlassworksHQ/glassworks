package dev.glassworks.setup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Setup's Panes step without its screen: the cards, which are on, and Continue. One per process
 * (Glassworks.panesStep), shared by the panel's own screen (SetupOverlay) and the setup channel's `panes.set` (Kiln, the
 * panel's setup page). Either one answers the step for both, and both apply the same [dev.glassworks.pane.PaneChoice]:
 * for a pane with a switch that is the flag its settings page, the Manage page and its Home Assistant switch use
 * ([dev.glassworks.pane.PaneSwitch.on]), so they all stay in step.
 *
 * A pane the frame lacks the hardware for has no card ([dev.glassworks.pane.CantRun]): it is hidden here too, never
 * offered as unavailable. Pure JVM (SetupChannelPanesTest); runs on Android 6 too (the legacy build).
 */
class PanesStep(
    val panes: List<SetupPane>,
    /** Starts a card's worker. Tests make it fail. */
    private val start: (Runnable, String) -> Unit = { r, name -> Thread(r, name).apply { isDaemon = true }.start() },
    /** Runs once every card has ended or timed out, just before the outcome is published. Tests hold it there. */
    private val beforePublish: () -> Unit = {},
) {

    /** What Continue applied, by card: the panes that are on from then on. Null: not answered in this process. */
    @Volatile private var applied: BooleanArray? = null

    private val lock = Any()
    private var applying = false

    /**
     * How many times an attempt has published its outcome (an answer, or none after a failure). A refusal is current
     * while this hasn't moved since it ([Outcome.answer]).
     */
    @Volatile var answers = 0
        private set

    /** What a Continue came to: [problem], null when every card applied, and [answers] as it left it (or found it, BUSY). */
    class Outcome(val problem: String?, val answer: Int)

    /** Continue on Panes, here or over the setup channel, in this process (SetupInputs.panesChosen). */
    val chosen: Boolean get() = applied != null

    /** Card [i] is on: as Continue applied it, else as the pane runs. */
    fun wanted(i: Int): Boolean = applied?.get(i) ?: panes[i].choice.on()

    /** The cards as the setup channel's state has them (channel.ts `PaneCard`): id, name, line under it, on. */
    fun toJson(): JSONArray = JSONArray(panes.mapIndexed { i, p ->
        JSONObject().put("id", p.id).put("title", p.choice.title).put("detail", p.choice.detail).put("on", wanted(i))
    })

    /**
     * `panes.set`: the cards whose ids are in [on] on, every other off, as Continue does. Null when every card applied,
     * else why not, in words.
     */
    fun set(on: Set<String>, budgetMs: Long = BUDGET_MS): String? {
        val unknown = on - panes.map { it.id }.toSet()
        if (unknown.isNotEmpty()) return "This panel has no pane ${unknown.joinToString { "'$it'" }}."
        return apply(BooleanArray(panes.size) { panes[it].id in on }, budgetMs)
    }

    /**
     * Continue: applies every card, each on its own thread, within [budgetMs] in all. Blocks; never on the main thread.
     * When each applied, the step is answered ([chosen]). Else every problem, one a line, and the step is unanswered
     * again: some switches may have changed, so an earlier answer no longer says what is on.
     * One Continue at a time: a second, from the screen or the channel, is refused while one applies. A card that didn't
     * answer in time still holds it until its worker ends, so a retry can't race its late write.
     */
    fun apply(wanted: BooleanArray, budgetMs: Long = BUDGET_MS): String? = attempt(wanted, budgetMs).problem

    /** [apply], with which answer it saw: the panel's screen shows its refusal only until another answer comes. */
    fun attempt(wanted: BooleanArray, budgetMs: Long = BUDGET_MS): Outcome {
        require(wanted.size == panes.size) { "one answer per card" }
        synchronized(lock) {
            if (applying) return Outcome(BUSY, answers)
            applying = true
        }
        // One count per card's worker, plus this caller until it has published `applied` (CodeRabbit on #699).
        val left = AtomicInteger(panes.size + 1)
        val finish = { synchronized(lock) { if (left.decrementAndGet() == 0) applying = false } }
        var published = false
        try {
            val results = arrayOfNulls<String>(panes.size)
            val done = Array(panes.size) { CountDownLatch(1) }
            panes.forEachIndexed { i, p ->
                val work = Runnable {
                    try {
                        results[i] = runCatching { p.choice.apply(wanted[i]) }.getOrElse { it.message ?: "failed" }
                    } finally {
                        // Its count back before it says it ended: the caller then publishes with the guard free of it,
                        // and a Continue right after isn't refused BUSY.
                        finish()
                        done[i].countDown()
                    }
                }
                // A worker that never starts is a card that failed, and gives back its count (CodeRabbit on #699).
                try { start(work, "glassworks-setup-pane-$i") } catch (e: Throwable) {
                    results[i] = "couldn't start (${e.message ?: e::class.java.simpleName})"
                    finish()
                    done[i].countDown()
                }
            }
            // Monotonic: a wall-clock correction mid-way must not stretch the budget.
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
            val ended = done.map { it.await(maxOf(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS) }
            val problems = panes.indices.mapNotNull { i ->
                val title = panes[i].choice.title
                when {
                    !ended[i] -> "The $title didn't answer."
                    results[i] != null -> "$title: ${results[i]}"
                    else -> null
                }
            }
            beforePublish()
            // The outcome and this caller's release in one step: a Continue refused BUSY sees the guard held with the
            // earlier answer, or the new answer with the guard free (CodeRabbit on #699). A card still running holds it on.
            return synchronized(lock) {
                applied = if (problems.isEmpty()) wanted.copyOf() else null
                answers++
                published = true
                if (left.decrementAndGet() == 0) applying = false
                Outcome(problems.joinToString("\n").ifEmpty { null }, answers)
            }
        } finally {
            if (!published) finish()
        }
    }

    companion object {
        /** How long Continue waits for every card in all. Kiln's `panes.set` waits 35 s for the reply. */
        const val BUDGET_MS = 30_000L
        const val BUSY = "The panel is setting up its panes already. Try again in a moment."
    }
}
