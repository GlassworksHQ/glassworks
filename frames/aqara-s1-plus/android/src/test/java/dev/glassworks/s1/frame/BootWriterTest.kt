package dev.glassworks.s1.frame

import dev.glassworks.testing.RepoFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The app's part of the boot writer (#139, route 2): the record for the Device page, and the Magisk download. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theRecordIsTheWritersLine() {
        assertEquals(BootWriter.Record("refused", 1800000000, "two exports of boot differ: nothing written"),
            BootWriter.parse("refused 1800000000 two exports of boot differ: nothing written\n"))
        assertEquals(BootWriter.Record("installed", 1, ""), BootWriter.parse("installed 1"))
        assertNull("no epoch", BootWriter.parse("ok"))
        assertNull("not a number", BootWriter.parse("ok soon detail"))
        assertNull(BootWriter.parse(""))
        assertNull(BootWriter.parse(null))
        assertNull("no file: nothing read", BootWriter.read(File(tmp.root, "none")))
    }

    @Test fun theRowSaysHowFarItGot() {
        fun row(line: String?, runs: Boolean = false) = BootWriter.rowText(BootWriter.parse(line), runs) { "19 Nov 2026" }
        assertEquals("Yes: installed over the air on 19 Nov 2026", row("ok 1 the frame loader is in boot", runs = true))
        assertEquals("Yes", row("installed 1 boot already has the frame loader"))
        assertEquals("Yes", row(null, runs = true))
        assertEquals("Not yet: the app's radio bundle installs it", row(null))
        assertEquals("a stock boot image (no root helper): Kiln, not an app update",
            BootWriter.NO_HELPER, BootWriter.rowText(null, loaderRuns = false, helper = false) { "" })
        assertTrue(BootWriter.helperCanWrite("4")); assertTrue(BootWriter.helperCanWrite("5\n"))
        assertFalse(BootWriter.helperCanWrite("")); assertFalse(BootWriter.helperCanWrite("3")); assertFalse(BootWriter.helperCanWrite("x"))
        assertEquals("Installing: downloading Magisk's tools (the official release)", row("need-magisk 1 waiting for the app"))
        assertEquals("Installing: patching a copy of boot", row("checking 1 patching a copy of boot"))
        assertEquals("Installing: restarting once to finish", row("restarting 1 the loader is written"))
        assertEquals("Not installed: the patched image's kernel differs from the panel's", row("refused 1 the patched image's kernel differs from the panel's"))
        assertEquals("Not installed: boot read back wrong; the backup is back", row("restored 1 boot read back wrong; the backup is back"))
        assertEquals("Error: recover with FEL", row("error 1 recover with FEL"))
        assertEquals("Yes, but the check after the restart failed: x", row("error 1 x", runs = true))
    }

    @Test fun theFrameUpdateCardSaysKilnOnlyWithoutTheRootHelper() {
        assertEquals(AqaraS1Plus.NEEDS_LOADER, AqaraS1Plus.needsLoader("4"))
        assertEquals(AqaraS1Plus.NEEDS_LOADER_KILN, AqaraS1Plus.needsLoader(""))
        assertTrue(AqaraS1Plus.NEEDS_LOADER_KILN.contains("Kiln"))
    }

    /** One pin, three places: this app, the writer that checks it, and Kiln's asset lock. */
    @Test fun theMagiskPinIsTheWritersAndKilns() {
        val sh = RepoFiles.file("frames/aqara-s1-plus/tools/root/glassworks_bootwriter.sh").readText()
        assertTrue(sh.contains("MAGISK_SHA256=\${GWB_MAGISK_SHA256:-${BootWriter.APK_SHA256}}"))
        assertTrue(sh.contains("MAGISK_SIZE=\${GWB_MAGISK_SIZE:-${BootWriter.APK_SIZE}}"))
        assertTrue(sh.contains("APP_MAGISK=${BootWriter.APK_PATH} "))
        assertTrue(sh.contains("PUB=\${GWB_PUB:-${BootWriter.RECORD.substringBeforeLast('/')}}"))
        val lock = JSONObject(RepoFiles.file("kiln/assets.lock.json").readText()).getJSONArray("assets")
        val magisk = (0 until lock.length()).map { lock.getJSONObject(it) }.single { it.getString("name") == "magisk" }
        assertEquals(BootWriter.APK_URL, magisk.getString("url"))
        assertEquals(BootWriter.APK_SHA256, magisk.getString("sha256"))
    }

    private fun fetch(state: String?, download: (String, File, Long) -> Unit, logs: MutableList<String> = mutableListOf()) =
        MagiskFetch(tmp.root, record = { state?.let { BootWriter.Record(it, 1, "") } }, download = download, inline = true, log = { logs += it })

    @Test fun downloadsOnlyWhenTheWriterAsks() {
        val calls = mutableListOf<String>()
        for (s in listOf(null, "checking", "refused", "restarting")) fetch(s, { u, _, _ -> calls += u }).check()
        assertTrue("not asked: no download", calls.isEmpty())
        fetch("need-magisk", { u, _, _ -> calls += u }).runCatching { check() }
        assertEquals(listOf(BootWriter.APK_URL), calls)
    }

    @Test fun aDownloadWithTheWrongBytesIsDropped() {
        val f = fetch("need-magisk", { _, to, _ -> to.writeText("not magisk") })
        assertTrue(runCatching { f.check() }.exceptionOrNull() is IOException)
        val dir = File(tmp.root, "boot-writer")
        assertFalse("nothing left for the writer to take", File(dir, "magisk.apk").exists())
        assertFalse("no partial file left", File(dir, "magisk.apk.part").exists())
    }

    @Test fun theLoaderInPlaceRemovesTheApk() {
        val apk = File(tmp.root, BootWriter.APK_PATH).apply { parentFile!!.mkdirs(); writeText("x") }
        fetch("checking", { _, _, _ -> }).check()
        assertTrue("still installing: kept", apk.exists())
        fetch("ok", { _, _, _ -> }).check()
        assertFalse(apk.exists())
    }
}
