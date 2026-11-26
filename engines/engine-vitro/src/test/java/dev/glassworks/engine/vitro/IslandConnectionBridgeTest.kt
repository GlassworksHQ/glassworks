/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Looper
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The native side of an island's `hass.connection` (#799 D3): a page's subscriptions run on the panel's one connection
 * to HA, follow it across a reconnect (HA drops a connection's subscriptions with it), and end with the bridge, the
 * card or the page's own unsubscribe. And the hand-off of a card that threw on something the island lacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IslandConnectionBridgeTest {
    private lateinit var activity: Activity
    private val islands = VitroIslandServer(haBase = { "http://192.0.2.10:8123" }, accessToken = { null })

    /** One subscription the card asked HA for. */
    private class Sub(
        val msg: JSONObject,
        val onStarted: () -> Unit,
        val onEvent: (JSONObject) -> Unit,
        val onError: (JSONObject?) -> Unit,
    ) {
        var cancelled = false
    }

    private val subs = ArrayList<Sub>()
    private var connected = true

    /** HA accepts each subscription as soon as it's asked (false: the test answers with [Sub.onStarted]). */
    private var acceptAtOnce = true
    private var connectionWatcher: ((Boolean) -> Unit)? = null
    private val failed = ArrayList<Pair<String, String>>()
    private var token = "first-token"
    private var haConfig = JSONObject()
    private var configWatcher: ((JSONObject) -> Unit)? = null

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After fun tearDown() {
        runCatching { islands.stop() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun context() = VitroCardContext(
        context = activity, haBase = "http://192.0.2.10:8123", store = EntityStore(),
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(), accessToken = { token },
        callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
        islandServer = islands, openIslandPage = { RecordingPage(activity) },
        subscribeWsStarted = { msg, onStarted, onEvent, onError ->
            val sub = Sub(msg, onStarted, onEvent, onError)
            subs += sub
            // The client: a command it can't send fails at once, with no error from HA.
            val cancel: () -> Unit = { sub.cancelled = true }
            if (!connected) onError(null) else if (acceptAtOnce) onStarted()
            if (connected) cancel else ({})
        },
        haConnected = { connected },
        watchConnection = { onChange -> connectionWatcher = onChange; { connectionWatcher = null } },
        islandFailed = { card, reason -> failed += card.type to reason },
        haConfig = { haConfig },
        watchConfig = { onChange -> configWatcher = onChange; { configWatcher = null } },
    )

    private fun card(): CardConfig = LovelaceParser.parseCard(JSONObject("""{"type": "custom:scheduler-card"}"""))

    @Suppress("UNCHECKED_CAST")
    private fun slot(): VitroIslandServer.SlotHandler =
        (islands.javaClass.getDeclaredField("slots").apply { isAccessible = true }.get(islands) as Map<String, VitroIslandServer.SlotHandler>).values.single()

    /** An island on screen with one bridge open, and what it sent there. */
    private fun island(): Triple<WebIslandCardView, (JSONObject) -> Unit, MutableList<JSONObject>> {
        val island = WebIslandCardView(context(), card())
        activity.setContentView(island)
        idle()
        val sent = ArrayList<JSONObject>()
        val send: (JSONObject) -> Unit = { synchronized(sent) { sent += it } }
        slot().onConnected(send)
        return Triple(island, send, sent)
    }

    private fun subscribe(sid: Int, type: String, resubscribe: Boolean = true) =
        JSONObject().put("type", "subscribe").put("sid", sid).put("msg", JSONObject().put("type", type)).put("resubscribe", resubscribe)

    private fun List<JSONObject>.ofType(type: String) = filter { it.getString("type") == type }

    @Test fun aPageSubscriptionRunsOnThePanelsConnectionAndEndsWithIt() {
        val (island, send, sent) = island()
        assertEquals("every bridge hears where the connection is", listOf(true), sent.ofType("conn").map { it.getBoolean("connected") })

        island.handleIslandMessage(subscribe(1, "scheduler/subscribe"), send)
        assertEquals("scheduler/subscribe", subs.single().msg.getString("type"))
        assertEquals(listOf(1), sent.ofType("subAck").map { it.getInt("sid") })
        subs.single().onEvent(JSONObject().put("event", "item_updated"))
        assertEquals("item_updated", sent.ofType("subEvent").single().getJSONObject("event").getString("event"))

        // The same id again on the same bridge starts nothing more.
        island.handleIslandMessage(subscribe(1, "scheduler/subscribe"), send)
        assertEquals(1, subs.size)

        island.handleIslandMessage(JSONObject().put("type", "unsubscribe").put("sid", 1), send)
        assertTrue(subs.single().cancelled)
        subs.single().onEvent(JSONObject().put("event", "late"))
        assertEquals("nothing after the page ended it", 1, sent.ofType("subEvent").size)
    }

    @Test fun subscriptionsStartAgainAfterAReconnectAsHasClientDoes() {
        val (island, send, sent) = island()
        island.handleIslandMessage(subscribe(1, "scheduler/subscribe"), send)
        island.handleIslandMessage(subscribe(2, "render_template", resubscribe = false), send)
        assertEquals(2, subs.size)

        // The connection drops: HA dropped its subscriptions with it.
        connected = false
        connectionWatcher!!(false)
        assertEquals(listOf(true, false), sent.ofType("conn").map { it.getBoolean("connected") })
        // One the page asks for meanwhile waits for the connection.
        island.handleIslandMessage(subscribe(3, "calendar/event/subscribe"), send)
        assertTrue("no ack while it waits", sent.ofType("subAck").none { it.getInt("sid") == 3 })

        connected = true
        connectionWatcher!!(true)
        val restarted = subs.drop(3).map { it.msg.getString("type") }
        assertEquals("the resubscribing one and the waiting one start on the new connection", listOf("scheduler/subscribe", "calendar/event/subscribe"), restarted)
        assertEquals("one that doesn't resubscribe ends", listOf(2), sent.ofType("subEnd").map { it.getInt("sid") })
        assertEquals("the waiting one is acked once it started", 1, sent.ofType("subAck").count { it.getInt("sid") == 3 })
        assertEquals("the others aren't acked twice", 1, sent.ofType("subAck").count { it.getInt("sid") == 1 })
        assertEquals(listOf(true, false, true), sent.ofType("conn").map { it.getBoolean("connected") })

        // Events of the old subscription never reach the page.
        subs[0].onEvent(JSONObject().put("old", true))
        assertTrue(sent.ofType("subEvent").isEmpty())
        subs[3].onEvent(JSONObject().put("new", true))
        assertEquals(1, sent.ofType("subEvent").size)
    }

    @Test fun aPageThatConnectsWhileTheConnectionIsDownHearsOfIt() {
        connected = false
        val (_, _, sent) = island()
        assertEquals(listOf(false), sent.ofType("conn").map { it.getBoolean("connected") })
        connected = true
        connectionWatcher!!(true)
        assertEquals(listOf(false, true), sent.ofType("conn").map { it.getBoolean("connected") })
    }

    @Test fun aSubscriptionIsAckedOnlyOnceHaAcceptedIt() {
        acceptAtOnce = false
        val (island, send, sent) = island()
        island.handleIslandMessage(subscribe(1, "admin/only"), send)
        assertTrue("queued is not accepted", sent.ofType("subAck").isEmpty())
        subs.single().onError(JSONObject().put("code", "unauthorized").put("message", "Unauthorized"))
        assertEquals("the refusal can still reject the card's promise", 1, sent.ofType("subError").size)
        assertTrue(sent.ofType("subAck").isEmpty())

        // Not accepted when the connection drops: it starts again, even one that doesn't resubscribe.
        island.handleIslandMessage(subscribe(2, "render_template", resubscribe = false), send)
        connectionWatcher!!(false)
        connectionWatcher!!(true)
        assertEquals(3, subs.size)
        assertTrue(sent.ofType("subEnd").isEmpty())
        subs.last().onStarted()
        subs.last().onStarted()
        assertEquals(listOf(2), sent.ofType("subAck").map { it.getInt("sid") })
    }

    @Test fun aWriteThatFailedWaitsForTheConnectionLikeALostOne() {
        val (island, send, sent) = island()
        island.handleIslandMessage(subscribe(1, "scheduler/subscribe"), send)
        subs.single().onError(JSONObject().put("code", HaDashboardClient.SEND_FAILED))
        assertTrue(sent.ofType("subError").isEmpty())
        connectionWatcher!!(false)
        connectionWatcher!!(true)
        assertEquals(2, subs.size)
    }

    @Test fun oneBudgetForTheIslandWhateverItsBridges() {
        val (island, send, sent) = island()
        val sent2 = ArrayList<JSONObject>()
        val send2: (JSONObject) -> Unit = { sent2 += it }
        slot().onConnected(send2)
        for (sid in 1..WebIslandCardView.MAX_PAGE_SUBSCRIPTIONS) island.handleIslandMessage(subscribe(sid, "x/subscribe"), send)
        island.handleIslandMessage(subscribe(1, "x/subscribe"), send2)
        assertEquals("too_many_subscriptions", sent2.ofType("subError").single().getJSONObject("error").getString("code"))
        assertEquals(WebIslandCardView.MAX_PAGE_SUBSCRIPTIONS, subs.size)
        assertTrue(sent.ofType("subError").isEmpty())
    }

    @Test fun aMoveInTheLayoutKeepsLiveSubscriptionsAndADropMeanwhileIsHeard() {
        val (island, send, sent) = island()
        island.handleIslandMessage(subscribe(1, "scheduler/subscribe"), send)
        val parent = island.parent as android.view.ViewGroup
        parent.removeView(island)
        connectionWatcher!!(false)
        parent.addView(island)
        idle()
        assertEquals("heard while detached", listOf(true, false), sent.ofType("conn").map { it.getBoolean("connected") })
        connectionWatcher!!(true)
        assertEquals("started again once, on the new connection", 2, subs.size)
        // A move with no drop: nothing restarts.
        parent.removeView(island)
        parent.addView(island)
        idle()
        assertEquals(2, subs.size)
        assertFalse(subs.last().cancelled)
    }

    @Test fun aSecondBridgeOpeningMeanwhileDoesntHideAChangeFromTheFirst() {
        val (island, send, sent) = island()
        connected = false
        val sent2 = ArrayList<JSONObject>()
        slot().onConnected { sent2 += it }
        // The watcher hears of the drop after the second bridge read it.
        connectionWatcher!!(false)
        assertEquals(listOf(true, false), sent.ofType("conn").map { it.getBoolean("connected") })
        assertEquals(listOf(false), sent2.ofType("conn").map { it.getBoolean("connected") })
        island.release()
    }

    @Test fun hassConfigReachesThePageAsItArrives() {
        // Made before the panel had HA's config: the bridge brings it when it arrives.
        val (_, _, sent) = island()
        assertTrue("nothing to say yet", sent.ofType("config").isEmpty())
        haConfig = JSONObject().put("version", "2026.11.0")
        configWatcher!!(haConfig)
        assertEquals("2026.11.0", sent.ofType("config").single().getJSONObject("config").getString("version"))
        // A bridge that opens later gets it with its states.
        val sent2 = ArrayList<JSONObject>()
        slot().onConnected { sent2 += it }
        assertEquals(listOf("config", "snapshot"), sent2.map { it.getString("type") }.filter { it == "config" || it == "snapshot" })
    }

    @Test fun aRefusedSubscriptionIsTheCardsErrorAndALostOneWaits() {
        val (island, send, sent) = island()
        island.handleIslandMessage(subscribe(1, "admin/only"), send)
        subs.single().onError(JSONObject().put("code", "unauthorized").put("message", "Unauthorized"))
        assertEquals("unauthorized", sent.ofType("subError").single().getJSONObject("error").getString("code"))

        // Lost before HA answered: not the card's error, it starts again with the connection.
        island.handleIslandMessage(subscribe(2, "scheduler/subscribe"), send)
        subs.last().onError(JSONObject().put("code", HaDashboardClient.DISCONNECTED))
        assertEquals(1, sent.ofType("subError").size)
        connectionWatcher!!(false)
        connectionWatcher!!(true)
        assertEquals("scheduler/subscribe", subs.last().msg.getString("type"))
        assertEquals(3, subs.size)
    }

    @Test fun theBridgeClosingTheCardLeavingOrTooManyEndThem() {
        val (island, send, sent) = island()
        for (sid in 1..WebIslandCardView.MAX_PAGE_SUBSCRIPTIONS) island.handleIslandMessage(subscribe(sid, "x/subscribe"), send)
        island.handleIslandMessage(subscribe(999, "x/subscribe"), send)
        assertEquals("too_many_subscriptions", sent.ofType("subError").single().getJSONObject("error").getString("code"))
        assertEquals(WebIslandCardView.MAX_PAGE_SUBSCRIPTIONS, subs.size)

        // The page's socket closes (a hand-over): it asks for them again on its next one.
        slot().onDisconnected(send)
        assertTrue(subs.all { it.cancelled })

        val sent2 = ArrayList<JSONObject>()
        val send2: (JSONObject) -> Unit = { sent2 += it }
        slot().onConnected(send2)
        island.handleIslandMessage(subscribe(1, "x/subscribe"), send2)
        assertFalse(subs.last().cancelled)
        island.release()
        assertTrue("the card leaving ends what its page held", subs.last().cancelled)
    }

    @Test fun aCardThatBrokeOnAMissingMemberGoesToHasFrontendOnce() {
        val (island, send, _) = island()
        island.handleIslandMessage(JSONObject().put("type", "cardBroken").put("member", "loadBackendTranslation"), send)
        island.handleIslandMessage(JSONObject().put("type", "cardBroken").put("member", "other"), send)
        idle()
        assertEquals(1, failed.size)
        assertEquals("custom:scheduler-card", failed.single().first)
        assertTrue(failed.single().second, "`loadBackendTranslation`" in failed.single().second)

        val (other, send2, _) = island()
        other.handleIslandMessage(JSONObject().put("type", "cardBroken").put("member", "</script><b>"), send2)
        idle()
        assertEquals("the second card's report arrived", 2, failed.size)
        assertFalse("a name that isn't an identifier never reaches the log", "<" in failed.last().second)
    }

    @Test fun aTokenRefreshGetsTheTokenThePanelHoldsNow() {
        val (island, send, sent) = island()
        token = "second-token"
        island.handleIslandMessage(JSONObject().put("type", "refreshToken").put("rid", 7), send)
        assertEquals("second-token", sent.ofType("auth").last().getString("token"))
        assertEquals("the reply names its request", 7, sent.ofType("auth").last().getInt("rid"))
    }
}
