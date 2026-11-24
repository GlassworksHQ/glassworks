/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The harness's own checks: the diff measures what it claims, and the committed fixtures hold nothing private. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParityHarnessSelfTest {
    private fun box(w: Int, h: Int, density: Int, dx: Int = 0, color: Int = Color.WHITE): Bitmap {
        val b = Bitmap.createBitmap(w * density, h * density, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 20, 24))
        c.drawRect(((10 + dx) * density).toFloat(), (10 * density).toFloat(), ((30 + dx) * density).toFloat(), (30 * density).toFloat(), Paint().apply { this.color = color })
        return b
    }

    @Test
    fun identicalRendersDontDiffer() {
        val d = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2), 40, 40)
        assertEquals(0.0, d.strict, 0.0)
        assertEquals(0.0, d.tolerant, 0.0)
    }

    @Test
    fun aOnePixelShiftIsToleratedButCountedAsStrict() {
        val d = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, dx = 1), 40, 40)
        assertTrue("strict sees the shift: ${d.strict}", d.strict > 0.0)
        assertEquals(0.0, d.tolerant, 0.0)
    }

    @Test
    fun aMissingOrRecolouredShapeDiffers() {
        val gone = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, color = Color.rgb(20, 20, 24)), 40, 40)
        // The 20x20 box is a quarter of the image; only its 1 px rim is forgiven as a shift.
        assertTrue("tolerant ${gone.tolerant}", gone.tolerant > 0.15)
        val red = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, color = Color.RED), 40, 40)
        assertTrue("tolerant ${red.tolerant}", red.tolerant > 0.15)
    }

    @Test
    fun masksLeaveTheirRegionOut() {
        val d = ParityDiff.compare(box(40, 40, 2), box(40, 40, 2, color = Color.RED), 40, 40, listOf(android.graphics.Rect(5, 5, 35, 35)))
        assertEquals(0.0, d.tolerant, 0.0)
    }

    @Test
    fun cropOutsideTheRenderIsFilledNotStretched() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val c = ParityDiff.crop(b, 5, 5, 10, 10)
        assertEquals(Color.BLUE, c.getPixel(0, 0))
        assertEquals(Color.MAGENTA, c.getPixel(9, 9))
    }

    /** CLAUDE.md hard rule 2: no tokens, addresses, MACs or serials in fixtures, references or reports. */
    @Test
    fun committedFixturesHoldNothingPrivate() {
        val root = ParityFiles.committed
        val files = root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue("the corpus has fixtures", files.size > 10)
        val ipv4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
        val mac = Regex("""\b[0-9a-fA-F]{2}([:-])[0-9a-fA-F]{2}(\1[0-9a-fA-F]{2}){4}\b""")
        val jwt = Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
        val tokenParam = Regex("""[?&](token|access_token|authSig)=(?!redacted)""")
        val privateKeys = Regex("""\"(ip_address|mac_address|serial_number|latitude|longitude|access_token|ssid|bssid|source_list|sound_mode_list|media_playlist|media_title|media_artist)\"\s*:""")
        val problems = ArrayList<String>()
        for (f in files) {
            val text = f.readText()
            val rel = f.relativeTo(root).path
            ipv4.findAll(text).filter { m -> m.groupValues.drop(1).all { it.toInt() <= 255 } }
                .filterNot { it.value.startsWith("192.0.2.") || it.value.startsWith("198.51.100.") || it.value.startsWith("203.0.113.") }
                .forEach { problems += "$rel: an IPv4 address outside RFC 5737 (${it.value.take(4)}…)" }
            if (mac.containsMatchIn(text.replace("00:00:5e:00:53:00", ""))) problems += "$rel: a MAC address"
            if (jwt.containsMatchIn(text)) problems += "$rel: a token"
            if (tokenParam.containsMatchIn(text)) problems += "$rel: a token parameter"
            privateKeys.find(text)?.let { problems += "$rel: a private attribute (${it.groupValues[1]})" }
            if (Regex("""https?://(?!127\.0\.0\.1|homeassistant\.local)[a-z0-9.-]+[:/]""").containsMatchIn(text)) problems += "$rel: an absolute URL to a host"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyCaseHasAReferenceOnEachOfItsFrames() {
        val root = ParityFiles.committed
        val missing = ParityCase.loadAll(root).flatMap { c ->
            ParityFrame.load().filter { c.runsOn(it) && ParityReference.find(root, it, c) == null }.map { "${it.id}/${c.id}" }
        }
        assertTrue("no reference for: $missing (run tools/vitro-parity/run.sh)", missing.isEmpty())
        assertTrue(File(root, "refs/shared.json").isFile)
        assertTrue(JSONObject(File(root, "refs/shared.json").readText()).has("themes"))
    }
}
