/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import dev.glassworks.update.FrameBundleFixture.FILES
import dev.glassworks.update.FrameBundleFixture.FORMAT
import dev.glassworks.update.FrameBundleFixture.FRAME
import dev.glassworks.update.FrameBundleFixture.bundle
import dev.glassworks.update.FrameBundleFixture.manifest
import dev.glassworks.update.FrameBundleFixture.sha
import dev.glassworks.update.FrameBundleFixture.zip
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #139: a frame release's bundle zip lands in the loader's staging dir only as the bundle's own files: the manifest, its
 * signature and what the manifest lists, at the listed sizes and hashes. Path traversal, links, directory entries,
 * names twice and anything unlisted are refused, and a refused zip leaves the staging dir empty.
 */
class FrameBundleZipTest {
    private val root: File = Files.createTempDirectory("frame-bundle").toFile()
    private val dest = File(root, "frame/bundle-in")

    private fun unpack(bytes: ByteArray, version: String = "1.0.1"): FrameBundleZip.Manifest {
        val z = File(root, "bundle.zip").apply { writeBytes(bytes) }
        return FrameBundleZip.unpack(z, FORMAT, FRAME, version, dest)
    }

    private fun refused(bytes: ByteArray, why: String, version: String = "1.0.1") {
        // Something staged before must not survive a refusal either.
        File(dest, "old").apply { parentFile.mkdirs(); writeText("old") }
        try {
            unpack(bytes, version)
            fail("staged: $why")
        } catch (e: FrameBundleZip.Refused) {
            assertTrue("'${e.message}' should say $why", e.message!!.contains(why))
        }
        assertFalse("nothing staged after a refusal ($why)", dest.exists())
        assertFalse(File(root, "frame/bundle-in.part").exists())
    }

    @Test fun aGoodBundleLandsWhole() {
        val m = unpack(bundle())
        assertEquals(FRAME, m.frameId)
        assertEquals("1.0.1", m.frameVersion)
        assertEquals(FILES.keys.toList(), m.files.map { it.path })
        val staged = dest.walkTopDown().filter { it.isFile }.map { it.relativeTo(dest).path }.toSet()
        assertEquals(FILES.keys + setOf("manifest", "manifest.sig"), staged)
        for ((p, b) in FILES) assertArrayEquals(p, b, File(dest, p).readBytes())
        assertEquals(manifest(), File(dest, "manifest").readText())
    }

    @Test fun aSecondBundleReplacesTheFirst() {
        unpack(bundle())
        val fewer = linkedMapOf("services/agent.sh" to FILES.getValue("services/agent.sh"))
        unpack(bundle(files = fewer))
        assertFalse("nothing left of the first", File(dest, "sisdk").exists())
        assertTrue(File(dest, "services/agent.sh").isFile)
    }

    @Test fun pathTraversalIsRefused() {
        // An entry the manifest doesn't list, whatever its name.
        refused(zip(mapOf("manifest" to manifest().toByteArray(), "manifest.sig" to "x".toByteArray(), "../evil" to "x".toByteArray()) + FILES), "../evil isn't in the manifest")
        // A manifest that lists one: the loader's path rule, before anything is written.
        for (bad in listOf("../evil", "/system/bin/evil", "services/../../evil", ".hidden", "a/.b", "a//b", "a/b/c/d/e/f")) {
            val files = linkedMapOf(bad to "x".toByteArray())
            val text = manifest(files = emptyMap(), extra = listOf("file 0644 1 ${sha("x".toByteArray())} $bad"))
            refused(zip(mapOf("manifest" to text.toByteArray(), "manifest.sig" to "x".toByteArray()) + files), "manifest:")
        }
    }

    @Test fun linksAndDirectoriesAreRefused() {
        refused(FrameBundleFixture.withUnixMode(bundle(), "services/agent.sh", 0xA1FF), "a link (services/agent.sh)")
        refused(FrameBundleFixture.withUnixMode(bundle(), "services/agent.sh", 0x41ED), "a directory entry (services/agent.sh)")
        refused(FrameBundleFixture.withUnixMode(bundle(), "services/agent.sh", 0x11B6), "isn't a regular file") // a FIFO
        refused(zip(mapOf("manifest" to manifest().toByteArray(), "manifest.sig" to "x".toByteArray()) + FILES, dirs = listOf("services/")), "a directory entry (services/)")
        // A regular file made on Unix is fine.
        unpack(FrameBundleFixture.withUnixMode(bundle(), "services/agent.sh", 0x81ED))
        assertTrue(File(dest, "services/agent.sh").isFile)
    }

    @Test fun unexpectedMissingOrSpoiltEntriesAreRefused() {
        val extra = linkedMapOf<String, ByteArray>().apply { putAll(FILES); put("services/svc1.sh", "x".toByteArray()) }
        refused(zip(mapOf("manifest" to manifest().toByteArray(), "manifest.sig" to "x".toByteArray()) + extra), "services/svc1.sh isn't in the manifest")
        refused(bundle(manifest = manifest(files = extra), files = FILES), "services/svc1.sh is missing")
        val longer = FILES + ("services/agent.sh" to "#!/system/bin/sh\necho something longer\n".toByteArray())
        refused(bundle(manifest = manifest(), files = longer), "services/agent.sh isn't the size the manifest says")
        val same = FILES + ("services/agent.sh" to "#!/system/bin/sh\necho AGENT\n".toByteArray())
        refused(bundle(manifest = manifest(), files = same), "services/agent.sh doesn't match the manifest's SHA-256")
        refused(zip(mapOf("manifest.sig" to "x".toByteArray()) + FILES), "no manifest")
        refused(zip(mapOf("manifest" to manifest().toByteArray()) + FILES), "no manifest.sig")
        refused(zip(mapOf("manifest" to manifest().toByteArray(), "manifest.sig" to ByteArray(0)) + FILES), "manifest.sig is empty")
        refused(zip(mapOf("manifest" to manifest().toByteArray(), "manifest.sig" to ByteArray(300)) + FILES), "manifest.sig is too large")
    }

    @Test fun aNameTwiceIsRefused() {
        val files = linkedMapOf("services/zz1.sh" to "a".toByteArray(), "services/zz2.sh" to "b".toByteArray())
        refused(FrameBundleFixture.renamed(bundle(files = files), "services/zz2.sh", "services/zz1.sh"), "services/zz1.sh is in the zip twice")
        val twice = manifest(files = files, extra = listOf("file 0644 1 ${sha("a".toByteArray())} services/zz1.sh"))
        refused(bundle(files = files, manifest = twice), "services/zz1.sh is listed twice")
    }

    @Test fun theBundleMustBeThisReleasesAndThisFramesInThisFormat() {
        refused(bundle(), "the bundle says 1.0.1, the release 1.0.2", version = "1.0.2")
        refused(bundle(manifest = manifest(frame = "lululemon-mirror")), "a bundle for lululemon-mirror, not aqara-s1-plus")
        refused(bundle(manifest = manifest().replaceFirst(FORMAT, "S1RADIO-BUNDLE 1")), "not a bundle this panel installs")
        refused(bundle(manifest = manifest().replace("frame aqara-s1-plus 1.0.1\n", "")), "manifest: no frame line")
        refused(zip(mapOf("manifest" to ByteArray(FrameBundleZip.MAX_MANIFEST + 1) { 'a'.code.toByte() }, "manifest.sig" to "x".toByteArray())), "the manifest is too large")
    }

    @Test fun notAZipIsRefused() {
        refused("not a zip at all, just text".toByteArray(), "not a zip")
        refused(ByteArray(10), "not a zip")
        refused(bundle().copyOf(bundle().size - 30), "not a zip")
    }

    @Test fun theLoadersRecordParses() {
        assertEquals(FrameBundleState("none", null, 1000000, "1.0.1"), FrameBundleState.parse("none - 1000000 1.0.1\n"))
        assertEquals(FrameBundleState("ok", "1.0.1", 1000001, null), FrameBundleState.parse("ok 1.0.1 1000001 -"))
        assertNull("the 0.4.3 image's radio record", FrameBundleState.parse("ok 3 3"))
        assertNull(FrameBundleState.parse("staged 1.0.1 1 -"))
        assertNull(FrameBundleState.parse(null))
        assertNull(FrameBundleState.parse("ok - x -"))
    }
}
