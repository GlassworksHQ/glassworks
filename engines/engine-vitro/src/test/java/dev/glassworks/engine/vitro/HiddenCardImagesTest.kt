/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import com.sun.net.httpserver.HttpServer
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** A card its visibility conditions hide loads no image, as HA's page never requests one; it loads once shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HiddenCardImagesTest {
    private val requests = CopyOnWriteArrayList<String>()
    private val png = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", 0,
    )
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests += ex.requestURI.path
            ex.sendResponseHeaders(200, png.size.toLong())
            ex.responseBody.use { it.write(png) }
        }
        start()
    }

    @After
    fun stop() = server.stop(0)

    private fun settle(times: Int = 10) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        Thread.sleep(20)
    }

    @Test
    fun aHiddenCardsPictureLoadsOnlyOnceItShows() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val store = EntityStore()
        store.putLocal(EntityState("binary_sensor.presence", "off", emptyMap()))
        val base = "http://127.0.0.1:${server.address.port}"
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = base, store = store, theme = theme, resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"title": "Home", "type": "sections",
            "sections": [{"type": "grid", "cards": [
              {"type": "markdown", "content": "<img src=\"/local/shown.png\" width=\"10\" height=\"10\">"},
              {"type": "markdown", "content": "<img src=\"/local/hidden.png\" width=\"10\" height=\"10\">",
               "visibility": [{"condition": "state", "entity": "binary_sensor.presence", "state": "on"}]}
            ]}]}]}""")), null)
        activity.setContentView(vv)
        settle()
        assertEquals("only the card on screen asks for its picture", listOf("/local/shown.png"), requests.toList())

        store.putLocal(EntityState("binary_sensor.presence", "on", emptyMap()))
        settle()
        assertEquals("the card that turned visible gets its picture", listOf("/local/shown.png", "/local/hidden.png"), requests.toList())
        // The picture reached the card: nothing is left waiting.
        assertEquals(0, VitroCardContext.pendingImageLoads.get())
        assertEquals(View.VISIBLE, vv.visibility)
    }
}
