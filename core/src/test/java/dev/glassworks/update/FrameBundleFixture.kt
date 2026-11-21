/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import dev.glassworks.frame.BundleAgent
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Frame bundle zips as frame-bundle.sh writes them (a manifest, its signature and the listed files, flat, no directory
 * entries), with ways to spoil one, and a stand-in for the S1's loader install. Made-up contents: the signature is not
 * a real one, which the app never checks (the loader does).
 */
object FrameBundleFixture {
    const val FORMAT = "GLASSWORKS-BUNDLE 1"
    const val FRAME = "aqara-s1-plus"

    val FILES: Map<String, ByteArray> = linkedMapOf(
        "services/agent.sh" to "#!/system/bin/sh\necho agent\n".toByteArray(),
        "sisdk/lib/libc.so.6" to ByteArray(70_000).also { java.util.Random(7).nextBytes(it) }, // doesn't compress: several chunks
        "bootanimation.zip" to "PK\u0003\u0004 desc.txt".toByteArray(),
    )

    fun sha(b: ByteArray): String = Releases.hex(MessageDigest.getInstance("SHA-256").digest(b))

    fun manifest(version: String = "1.0.1", frame: String = FRAME, files: Map<String, ByteArray> = FILES, extra: List<String> = emptyList()): String =
        (listOf(FORMAT, "frame $frame $version", "version ${AppVersion.parse(version)!!.let { it.major * 1_000_000 + it.minor * 1000 + it.patch }}", "created unknown") +
            files.map { (p, b) -> "file ${if (p.endsWith(".sh")) "0700" else "0644"} ${b.size} ${sha(b)} $p" } + extra + "end").joinToString("\n") + "\n"

    /** A zip of [entries], in order; [dirs] adds directory entries. */
    fun zip(entries: Map<String, ByteArray>, dirs: List<String> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (d in dirs) { z.putNextEntry(ZipEntry(d)); z.closeEntry() }
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The release's zip: manifest, signature and [files]. */
    fun bundle(version: String = "1.0.1", files: Map<String, ByteArray> = FILES, manifest: String = manifest(version, files = files)): ByteArray =
        zip(linkedMapOf("manifest" to manifest.toByteArray(), "manifest.sig" to "ab".repeat(64).toByteArray()) + files)

    /** [zip] with [name]'s central directory entry marked as made on Unix with [mode] (e.g. 0xA1FF, a link). */
    fun withUnixMode(zip: ByteArray, name: String, mode: Int): ByteArray {
        val b = zip.copyOf()
        val n = name.toByteArray()
        var i = 0
        while (i + 46 < b.size) {
            if (le32(b, i) == 0x02014b50L && le16(b, i + 28) == n.size && b.copyOfRange(i + 46, i + 46 + n.size).contentEquals(n)) {
                b[i + 5] = 3 // made by: Unix
                val ext = mode.toLong() shl 16
                for (k in 0 until 4) b[i + 38 + k] = (ext ushr (8 * k)).toByte()
                return b
            }
            i++
        }
        error("no central entry for $name")
    }

    /** [zip] with every [from] in its names replaced by [to] (same length): two entries under one name. */
    fun renamed(zip: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val b = zip.copyOf()
        val f = from.toByteArray()
        var i = 0
        while (i + f.size <= b.size) {
            if (b.copyOfRange(i, i + f.size).contentEquals(f)) to.toByteArray().copyInto(b, i)
            i++
        }
        return b
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int): Long = le16(b, i).toLong() or (le16(b, i + 2).toLong() shl 16)

    /**
     * The S1 loader's install as the app sees it: [request] reads what the app staged under [filesDir] and, unless told
     * to refuse, records it as staged in its state file and status, as glassworks_loader.sh `install` does.
     */
    class Loader(private val filesDir: File) : BundleAgent {
        var state: String? = "none - 1000000 -"
        var status = ""
        var running = false
        /** What the loader says instead of staging, e.g. `error: refused: ...`; null stages it. */
        var refuse: String? = null
        /** The service never starts (status stays `requested`). */
        var dead = false
        /** The service starts and keeps copying (status `running: …`), until a test says otherwise. */
        var busy = false
        val calls = ArrayList<String>()
        /** The staging dir's files when [request] ran. */
        var seen: Map<String, ByteArray> = emptyMap()

        override val stageDir = "frame/bundle-in"
        override val format = FORMAT
        override fun state() = state
        override fun running() = running
        override fun status() = status

        override fun request() {
            calls += "request"
            status = "requested"
            if (dead) return
            if (busy) { running = true; status = "running: copying the frame bundle"; return }
            val dir = File(filesDir, stageDir)
            seen = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes() }
            val v = seen["manifest"]?.toString(Charsets.UTF_8)?.lineSequence()?.firstOrNull { it.startsWith("frame ") }?.split(' ')?.get(2)
            val r = refuse
            if (r != null) { status = r; return }
            state = "none - 1000000 $v"
            status = "ok: frame bundle $v staged (${seen.size - 2} files); it loads at the next restart"
        }

        override fun restart() { calls += "restart" }
    }
}
