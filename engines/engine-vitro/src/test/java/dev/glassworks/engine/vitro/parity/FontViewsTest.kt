/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.TextView
import dev.glassworks.engine.vitro.fonts.WebFonts
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.testing.RepoFiles
import dev.glassworks.ui.Inter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Two VitroViews on screen at once, as when another test (ParityHarnessSelfTest) leaves its activity up: the font
 * loader is one per process, and a view whose dashboard it isn't set up for must not redraw for its fonts, or each
 * redraw sets it up for that view and the two take it from each other until the font case times out (#813's CI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontViewsTest {
    @Test
    fun aViewWithoutStylesheetsLeavesTheFontsToTheViewOnScreen() {
        Inter.load = { File(RepoFiles.root, "core/src/main/res/font/inter.ttf").readBytes() }
        WebFonts.reset()
        val root = ParityFiles.committed
        val frame = ParityFrame.load().first { it.id == "s1" }
        val shared = ParityShared.load(root)
        val cases = ParityCase.loadAll(root)
        // A dashboard with no stylesheets, left on screen in its own activity.
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        ParityRenderer(first, frame, root, shared).render(cases.first { it.id == "tile-light" }, null)
        // Then a card in a web font, in a second activity: it must get its font, not time out.
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        val case = cases.first { it.id == "font-cardmod" }
        val render = ParityRenderer(second, frame, root, shared).render(case, null)
        val texts = ParityViews.all(render.root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }.toList()
        assertTrue("the card has text", texts.isNotEmpty())
        val pacifico = (WebFonts.lookup("Pacifico", 400, false) as? WebFonts.Lookup.Ready)?.typeface
        assertTrue("Pacifico arrived", pacifico != null)
        // The tile's name (500) and state (400) both draw in the one 400 face, not in a fallback.
        assertTrue("its text is in the web font", texts.all { it.typeface === pacifico })
    }

    @Test
    fun aCardKeepsItsViewsFontWhenAnotherViewRendersLater() {
        Inter.load = { File(RepoFiles.root, "core/src/main/res/font/inter.ttf").readBytes() }
        WebFonts.reset()
        val root = ParityFiles.committed
        val frame = ParityFrame.load().first { it.id == "s1" }
        val shared = ParityShared.load(root)
        val case = ParityCase.loadAll(root).first { it.id == "font-theme-web" }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val render = ParityRenderer(activity, frame, root, shared).render(case, null)
        val text = ParityViews.all(render.root).filterIsInstance<TextView>().first { it.isShown && it.text.isNotBlank() }
        // Another view renders with another theme: the global body font moves, a card's own context doesn't.
        VitroFonts.family = "serif"
        assertTrue("Product Sans" in VitroFonts.familyOf(text.context))
    }

    @Test
    fun aViewBackOnScreenAfterAnotherDashboardTookTheFontsRebuilds() {
        Inter.load = { File(RepoFiles.root, "core/src/main/res/font/inter.ttf").readBytes() }
        WebFonts.reset()
        val root = ParityFiles.committed
        val frame = ParityFrame.load().first { it.id == "s1" }
        val shared = ParityShared.load(root)
        val cases = ParityCase.loadAll(root)
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val a = ParityRenderer(first, frame, root, shared).render(cases.first { it.id == "font-cardmod" }, null)
        val before = a.slotViews.first()
        // A leaves the screen; another dashboard (another server, its own stylesheet) takes the font loader meanwhile.
        // It draws in built-in fonts only, so no font notice reaches A: the loader just stops being A's.
        first.setContentView(View(first))
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        val plain = ParityCase.parse(
            "plain-with-sheet",
            org.json.JSONObject().put("card", org.json.JSONObject().put("type", "tile").put("entity", "light.entryway_ceiling_halo"))
                .put("resources", org.json.JSONArray().put(org.json.JSONObject().put("type", "css").put("url", "${ParityCase.FIXTURE_PREFIX}fonts/markdown.css"))),
        )
        ParityRenderer(second, frame, root, shared).render(plain, null)
        // A comes back: it builds again (and takes the loader back), rather than keep what it drew before.
        first.setContentView(a.root)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("A was built again", ParityViews.all(a.root).none { it === before })
    }
}
