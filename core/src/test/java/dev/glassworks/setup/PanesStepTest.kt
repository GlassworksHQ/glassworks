package dev.glassworks.setup

import dev.glassworks.pane.PaneChoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Setup's Panes step without its screen (#621): the cards the setup channel reports and `panes.set`, which answers the
 * step as the panel's Continue does, for the panel's screen too (one [PanesStep] per process).
 */
class PanesStepTest {

    private val applied = Collections.synchronizedList(ArrayList<String>())
    private var voice = true
    private var music = false

    private fun card(id: String, title: String, detail: String, on: () -> Boolean, set: (Boolean) -> Unit, refuse: () -> String? = { null }) =
        SetupPane(PaneChoice(title, detail, on = on, apply = { v -> applied += "$id $v"; refuse() ?: run { set(v); null } }), emptyList(), id)

    private fun step(refuseMusic: () -> String? = { null }) = PanesStep(listOf(
        card("assist", "Voice", "Assist and timers", { voice }, { voice = it }),
        card("sendspin", "Music", "A Sendspin speaker", { music }, { music = it }, refuseMusic),
    ))

    private class Core : SetupChannel.Backend {
        override fun state() = ChannelState(ip = "192.0.2.10", ha = ChannelState.Ha(null, "", false, null))
        override fun setHa(url: String) = SetupChannel.Change()
    }

    private fun reply(c: SetupChannel, line: String) = JSONObject(c.handle(line).first)

    @Test
    fun theCardsAreThePanesWithTheirIdsAndHowTheyRun() {
        val cards = step().toJson()
        assertEquals(2, cards.length())
        val first = cards.getJSONObject(0)
        assertEquals("assist", first.getString("id"))
        assertEquals("Voice", first.getString("title"))
        assertEquals("Assist and timers", first.getString("detail"))
        assertTrue(first.getBoolean("on"))
        assertFalse(cards.getJSONObject(1).getBoolean("on"))
    }

    @Test
    fun setTurnsOnTheNamedCardsAndEveryOtherOff() {
        val s = step()
        assertFalse(s.chosen)
        assertNull(s.set(setOf("sendspin")))
        assertEquals(setOf("assist false", "sendspin true"), applied.toSet()) // every card applied, any order
        assertFalse(voice)
        assertTrue(music)
        assertTrue(s.chosen) // the panel's screen moves past Panes too
        assertFalse(s.wanted(0))
        assertTrue(s.wanted(1))
    }

    @Test
    fun anUnknownPaneIsRefusedAndNothingApplies() {
        val s = step()
        assertEquals("This panel has no pane 'radio'.", s.set(setOf("assist", "radio")))
        assertEquals(emptyList<String>(), applied)
        assertFalse(s.chosen)
    }

    @Test
    fun aRefusalSaysWhyAndLeavesTheStepUnanswered() {
        val s = step { "the player didn't start" }
        assertEquals("Music: the player didn't start", s.set(setOf("sendspin")))
        assertFalse(s.chosen)
        assertFalse(voice) // the others applied anyway, as on the panel
    }

    @Test
    fun aCardThatDoesntAnswerIsNamed() {
        val stuck = CountDownLatch(1)
        val s = PanesStep(listOf(SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { stuck.await(10, TimeUnit.SECONDS); null }), emptyList(), "sendspin")))
        assertEquals("The Music didn't answer.", s.set(setOf("sendspin"), budgetMs = 200))
        assertFalse(s.chosen)
        stuck.countDown()
    }

    @Test
    fun aLateCardHoldsTheStepUntilItEnds() {
        // CodeRabbit on #699: a retry must not race a timed-out card's late write.
        val stuck = CountDownLatch(1)
        val s = PanesStep(listOf(SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { music }, apply = { v -> stuck.await(10, TimeUnit.SECONDS); music = v; null }), emptyList(), "sendspin")))
        assertEquals("The Music didn't answer.", s.set(setOf("sendspin"), budgetMs = 100))
        assertEquals(PanesStep.BUSY, s.set(emptySet()))
        stuck.countDown()
        val until = System.currentTimeMillis() + 5_000
        var r: String? = PanesStep.BUSY
        while (r == PanesStep.BUSY && System.currentTimeMillis() < until) { Thread.sleep(20); r = s.set(emptySet()) }
        assertNull(r)
        assertFalse(music)
    }

    @Test
    fun aFailedAttemptUnanswersAnEarlierAnswer() {
        // CodeRabbit on #699: some switches may have changed, so the earlier answer no longer says what is on.
        var refuse: String? = null
        val s = step { refuse }
        assertNull(s.set(setOf("assist")))
        assertTrue(s.chosen)
        refuse = "the player didn't start"
        assertEquals("Music: the player didn't start", s.set(setOf("sendspin")))
        assertFalse(s.chosen)
        assertFalse(s.wanted(0)) // the cards say what runs now
        // a refusal before anything is applied keeps the answer
        refuse = null
        assertNull(s.set(setOf("assist")))
        assertTrue(s.set(setOf("radio"))!!.startsWith("This panel has no pane"))
        assertTrue(s.chosen)
    }

    @Test
    fun aCardWhoseWorkerCantStartFailsAndFreesTheStep() {
        // CodeRabbit on #699: a worker that never starts must not keep the step BUSY, nor leave an earlier answer.
        var failStart = false
        val s = PanesStep(listOf(
            card("assist", "Voice", "Assist and timers", { voice }, { voice = it }),
            card("sendspin", "Music", "A Sendspin speaker", { music }, { music = it }),
        ), start = { r, name -> if (failStart && name.endsWith("-1")) throw OutOfMemoryError("no threads") else Thread(r, name).start() })
        assertNull(s.set(setOf("assist")))
        failStart = true
        assertEquals("Music: couldn't start (no threads)", s.set(setOf("sendspin")))
        assertFalse(s.chosen)
        failStart = false
        assertNull(s.set(setOf("sendspin"))) // not BUSY: its count was given back
        assertTrue(s.chosen)
    }

    @Test
    fun theAnswerIsTheLastOneApplied() {
        // CodeRabbit on #699: a second Continue right as the first publishes must not have its answer overwritten by
        // the first's. Two callers race many times; after each round the answer says what the switch is.
        val s = PanesStep(listOf(card("sendspin", "Music", "A Sendspin speaker", { music }, { music = it })))
        repeat(300) {
            val a = Thread { s.set(setOf("sendspin")) }
            val b = Thread { s.set(emptySet()) }
            a.start(); b.start(); a.join(); b.join()
            if (s.chosen) assertEquals("round $it", music, s.wanted(0))
        }
    }

    @Test
    fun aContinueAtThePublicationBoundaryIsRefusedAndTheFirstAnswerStands() {
        // CodeRabbit on #699: every card of the first Continue has ended, and it is about to publish. A second one then
        // is refused BUSY with the answer from before, and can't publish an answer the first would overwrite.
        val atBoundary = CountDownLatch(1)
        val publish = CountDownLatch(1)
        var hold = true
        val s = PanesStep(
            listOf(card("sendspin", "Music", "A Sendspin speaker", { music }, { music = it })),
            beforePublish = { if (hold) { hold = false; atBoundary.countDown(); publish.await(5, TimeUnit.SECONDS) } },
        )
        var first: String? = "not yet"
        val t = Thread { first = s.set(setOf("sendspin")) }.apply { start() }
        assertTrue(atBoundary.await(5, TimeUnit.SECONDS))
        assertTrue(music) // the first's card has applied
        val second = s.attempt(booleanArrayOf(false))
        assertEquals(PanesStep.BUSY, second.problem)
        assertEquals(0, second.answer) // the answer it saw: none yet
        assertTrue(music)
        publish.countDown()
        t.join(5_000)
        assertNull(first)
        assertEquals(1, s.answers)
        assertTrue(s.chosen)
        assertTrue(s.wanted(0))
        assertNull(s.set(emptySet())) // and the guard is free again
        assertFalse(s.wanted(0))
    }

    @Test
    fun aBusyRefusalSeesTheAnswerItFound() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val s = PanesStep(listOf(SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { started.countDown(); release.await(5, TimeUnit.SECONDS); null }), emptyList(), "sendspin")))
        val t = Thread { s.set(setOf("sendspin")) }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val busy = s.attempt(booleanArrayOf(false))
        assertEquals(PanesStep.BUSY, busy.problem)
        assertEquals(0, busy.answer)
        release.countDown()
        t.join(5_000)
        assertEquals(1, s.answers) // the refusal is no longer current
    }

    @Test
    fun oneContinueAtATime() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val s = PanesStep(listOf(SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { started.countDown(); release.await(5, TimeUnit.SECONDS); null }), emptyList(), "sendspin")))
        var first: String? = "not yet"
        val t = Thread { first = s.set(setOf("sendspin")) }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(PanesStep.BUSY, s.set(emptySet()))
        release.countDown()
        t.join(5_000)
        assertNull(first)
        assertTrue(s.chosen)
    }

    @Test
    fun theChannelReportsTheCardsAndSetAnswersThem() {
        val s = step()
        val c = SetupChannel(Core(), panes = s)
        val cards = reply(c, """{"id":1,"op":"state"}""").getJSONObject("state").getJSONArray("panes")
        assertEquals(listOf("assist", "sendspin"), (0 until cards.length()).map { cards.getJSONObject(it).getString("id") })

        val r = reply(c, """{"id":2,"op":"panes.set","on":"sendspin"}""")
        assertTrue(r.toString(), r.getBoolean("ok"))
        val after = r.getJSONObject("state").getJSONArray("panes")
        assertFalse(after.getJSONObject(0).getBoolean("on"))
        assertTrue(after.getJSONObject(1).getBoolean("on"))
        assertTrue(s.chosen)
    }

    @Test
    fun theChannelSaysWhyACardRefused() {
        val c = SetupChannel(Core(), panes = step { "the player didn't start" })
        val r = reply(c, """{"id":1,"op":"panes.set","on":"assist,sendspin"}""")
        assertFalse(r.getBoolean("ok"))
        assertEquals("Music: the player didn't start", r.getString("error"))
    }

    @Test
    fun anEmptyListTurnsEveryPaneOff() {
        val c = SetupChannel(Core(), panes = step())
        assertTrue(reply(c, """{"id":1,"op":"panes.set","on":""}""").getBoolean("ok"))
        assertFalse(voice)
        assertFalse(music)
    }

    @Test
    fun overTheLanItWaitsForPairing() {
        val c = SetupChannel(Core(), mayChange = { false }, panes = step())
        val r = reply(c, """{"id":1,"op":"panes.set","on":"sendspin"}""")
        assertEquals(SetupChannel.NOT_PAIRED, r.getString("error"))
        assertEquals(emptyList<String>(), applied)
    }

    @Test
    fun anAppWithoutCardsHasNoPanes() {
        val c = SetupChannel(Core(), panes = PanesStep(emptyList()))
        assertFalse(reply(c, """{"id":1,"op":"state"}""").getJSONObject("state").has("panes"))
        assertEquals("Unknown request 'panes.set'.", reply(c, """{"id":2,"op":"panes.set","on":""}""").getString("error"))
    }
}
