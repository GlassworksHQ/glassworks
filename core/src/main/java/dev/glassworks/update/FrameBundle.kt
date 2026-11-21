/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * A frame release's bundle zip (`frame-bundle-<id>-<version>.zip`, frames/aqara-s1-plus/tools/radio/frame-bundle.sh),
 * unpacked into the app's one staging dir for the frame's loader (#139). The app is only the courier: the loader checks
 * the signature and every file again before it stages anything. What this adds is that nothing but the bundle's own
 * files ever lands in the staging dir: the zip may hold `manifest`, `manifest.sig` and the files the manifest lists, at
 * the listed size and SHA-256, and nothing else. No directory entries, no links, no paths outside the loader's rule, no
 * name twice. Pure JVM.
 */
object FrameBundleZip {
    /** The loader's limits (glassworks_loader.sh: MAX_FILES, MAX_TOTAL, MAX_MANIFEST) and the signature's size. */
    const val MAX_FILES = 200
    const val MAX_TOTAL = 96L * 1024 * 1024
    const val MAX_MANIFEST = 65536
    const val MAX_SIG = 256

    /** The largest zip a release may offer: [MAX_TOTAL] stored, plus the zip's own headers. */
    const val MAX_ZIP = MAX_TOTAL + 8L * 1024 * 1024

    /** The loader's path rule (good_path; make-bundle.py's PATH_RE): up to 5 components, none starting with a dot. */
    private val PATH = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*(/[A-Za-z0-9][A-Za-z0-9._+-]*){0,4}$")
    private val MODES = setOf("0600", "0644", "0700", "0755")
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val NUM = Regex("^[0-9]{1,9}$")
    private const val MANIFEST = "manifest"
    private const val SIG = "manifest.sig"

    /** Why a zip isn't staged. Its message is a short reason for the update card. */
    class Refused(message: String) : IOException(message)

    /** One `file` line of the manifest. */
    data class Listed(val path: String, val size: Long, val sha256: String)

    /** What the app reads of a manifest; the loader reads it all again, signed. */
    class Manifest(val frameId: String, val frameVersion: String, val files: List<Listed>)

    /** One entry as the zip's central directory has it. [unixMode]: the mode a Unix zip records, else null. */
    class Entry(val name: String, val flags: Int, val method: Int, val size: Long, val unixMode: Int?, val dosAttributes: Int)

    fun goodPath(p: String) = p.length <= 160 && PATH.matches(p) && p != MANIFEST && p != SIG

    /** The manifest's frame and files, for a bundle in [format] (its first line); throws [Refused]. */
    fun parseManifest(text: String, format: String): Manifest {
        val lines = text.split('\n')
        if (lines.first() != format) throw Refused("not a bundle this panel installs")
        var frame: Pair<String, String>? = null
        val files = ArrayList<Listed>()
        val seen = HashSet<String>()
        var total = 0L
        for (l in lines.drop(1)) {
            val t = l.split(' ')
            when (t[0]) {
                "frame" -> {
                    if (t.size != 3 || frame != null) throw Refused("manifest: a bad frame line")
                    frame = t[1] to t[2]
                }
                "file" -> {
                    if (t.size != 5 || t[1] !in MODES || !NUM.matches(t[2]) || !SHA.matches(t[3])) throw Refused("manifest: a bad file line")
                    val path = t[4]
                    if (!goodPath(path)) throw Refused("manifest: a path the loader refuses")
                    if (!seen.add(path)) throw Refused("manifest: $path is listed twice")
                    val size = t[2].toLong()
                    total += size
                    files += Listed(path, size, t[3])
                }
            }
        }
        val (id, version) = frame ?: throw Refused("manifest: no frame line")
        if (files.size > MAX_FILES) throw Refused("manifest: more than $MAX_FILES files")
        if (total > MAX_TOTAL) throw Refused("manifest: the files are larger than the loader takes")
        return Manifest(id, version, files)
    }

    /**
     * Every entry in [zip]'s central directory, read by hand: [ZipFile] doesn't say whether an entry is a link. Refuses
     * what frame-bundle.sh never writes: Zip64, a split archive, a directory that doesn't add up.
     */
    fun entries(zip: File): List<Entry> = RandomAccessFile(zip, "r").use { f ->
        val len = f.length()
        if (len < EOCD_SIZE) throw Refused("not a zip")
        if (len > MAX_ZIP) throw Refused("the zip is too large")
        val tailLen = minOf(len, EOCD_SIZE + 0xFFFFL).toInt()
        val tail = ByteArray(tailLen)
        f.seek(len - tailLen)
        f.readFully(tail)
        val at = (tailLen - EOCD_SIZE downTo 0).firstOrNull { i ->
            le32(tail, i) == EOCD_SIG && i + EOCD_SIZE + le16(tail, i + 20) == tailLen
        } ?: throw Refused("not a zip")
        val count = le16(tail, at + 10)
        val cdSize = le32(tail, at + 12)
        val cdOffset = le32(tail, at + 16)
        if (le16(tail, at + 4) != 0 || le16(tail, at + 6) != 0 || le16(tail, at + 8) != count) throw Refused("a split zip")
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) throw Refused("a Zip64 zip")
        if (count > MAX_FILES + 2) throw Refused("more entries than a bundle has")
        val eocdAt = len - tailLen + at
        if (cdOffset + cdSize != eocdAt || cdSize > Int.MAX_VALUE) throw Refused("the zip's directory doesn't add up")
        val cd = ByteArray(cdSize.toInt())
        f.seek(cdOffset)
        f.readFully(cd)
        var p = 0
        List(count) {
            if (p + CDH_SIZE > cd.size || le32(cd, p) != CDH_SIG) throw Refused("the zip's directory doesn't add up")
            val madeBy = le16(cd, p + 4)
            val flags = le16(cd, p + 8)
            val method = le16(cd, p + 10)
            val size = le32(cd, p + 24)
            val nameLen = le16(cd, p + 28)
            val extraLen = le16(cd, p + 30)
            val commentLen = le16(cd, p + 32)
            val external = le32(cd, p + 38)
            val end = p + CDH_SIZE + nameLen + extraLen + commentLen
            if (end > cd.size) throw Refused("the zip's directory doesn't add up")
            val raw = cd.copyOfRange(p + CDH_SIZE, p + CDH_SIZE + nameLen)
            if (raw.isEmpty() || raw.any { it < 0x21 || it > 0x7e }) throw Refused("an entry name that isn't plain ASCII")
            p = end
            Entry(
                String(raw, Charsets.US_ASCII), flags, method, size,
                unixMode = if (madeBy ushr 8 == HOST_UNIX) (external ushr 16).toInt() else null,
                dosAttributes = (external and 0xFF).toInt(),
            )
        }
    }

    /**
     * Checks [zip] and unpacks it into [dest] for the loader: a bundle in [format] for frame [frameId] at [version]
     * (what the release says it is). [dest] is replaced only when every file is in; on a refusal it is left empty.
     * Returns the manifest; throws [Refused] (or another [IOException] for the disk).
     */
    fun unpack(zip: File, format: String, frameId: String, version: String, dest: File): Manifest {
        // Whatever was staged before goes first: a refused zip must never leave an older bundle for the loader.
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.deleteRecursively()
        dest.deleteRecursively()
        val entries = entries(zip)
        val names = HashSet<String>()
        for (e in entries) {
            val what = e.name
            if (!names.add(what)) throw Refused("$what is in the zip twice")
            if (what.endsWith("/") || e.dosAttributes and DOS_DIRECTORY != 0) throw Refused("a directory entry ($what)")
            when (e.unixMode?.and(S_IFMT)) {
                null, 0, S_IFREG -> {}
                S_IFLNK -> throw Refused("a link ($what)")
                S_IFDIR -> throw Refused("a directory entry ($what)")
                else -> throw Refused("$what isn't a regular file")
            }
            if (e.flags and FLAG_ENCRYPTED != 0) throw Refused("an encrypted entry ($what)")
            if (e.method != STORED && e.method != DEFLATED) throw Refused("$what is compressed in a way bundles never are")
        }
        if (MANIFEST !in names) throw Refused("no manifest")
        if (SIG !in names) throw Refused("no manifest.sig")
        try {
            ZipFile(zip).use { z ->
                if (z.size() != entries.size) throw Refused("the zip's directory doesn't add up")
                fun open(name: String): InputStream = z.getEntry(name)?.let(z::getInputStream) ?: throw Refused("the zip's directory doesn't add up")
                val manifestBytes = open(MANIFEST).use { readCapped(it, MAX_MANIFEST) } ?: throw Refused("the manifest is too large")
                val sig = open(SIG).use { readCapped(it, MAX_SIG) } ?: throw Refused("manifest.sig is too large")
                if (sig.isEmpty()) throw Refused("manifest.sig is empty")
                val m = parseManifest(String(manifestBytes, Charsets.UTF_8), format)
                if (m.frameId != frameId) throw Refused("a bundle for ${m.frameId.take(40)}, not $frameId")
                if (m.frameVersion != version) throw Refused("the bundle says ${m.frameVersion.take(20)}, the release $version")
                val listed = m.files.associateBy { it.path }
                for (n in names) if (n != MANIFEST && n != SIG && n !in listed) throw Refused("$n isn't in the manifest")
                for (l in m.files) {
                    if (l.path !in names) throw Refused("${l.path} is missing")
                    if (entries.first { it.name == l.path }.size != l.size) throw Refused("${l.path} isn't the size the manifest says")
                }
                mkdirPrivate(tmp)
                write(tmp, MANIFEST, manifestBytes)
                write(tmp, SIG, sig)
                for (l in m.files) open(l.path).use { copyChecked(it, l, file(tmp, l.path)) }
                if (!tmp.renameTo(dest)) throw IOException("cannot move the bundle into ${dest.name}")
                return m
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            dest.deleteRecursively()
            throw if (e is IOException) e else Refused("the zip can't be read (${e.javaClass.simpleName})")
        }
    }

    /** [path] under [root], its folders made owner-only; refuses anything that would land outside [root]. */
    private fun file(root: File, path: String): File {
        val f = File(root, path)
        if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw Refused("$path is outside the bundle")
        var d = f.parentFile
        val chain = ArrayList<File>()
        while (d != null && d != root) { chain += d; d = d.parentFile }
        chain.asReversed().forEach(::mkdirPrivate)
        return f
    }

    private fun write(root: File, name: String, bytes: ByteArray) {
        val f = file(root, name)
        f.outputStream().use { it.write(bytes); it.fd.sync() }
        ownerOnly(f)
    }

    /** Streams one listed file to [to]: no more than its size, and its SHA-256 must match. */
    private fun copyChecked(input: InputStream, l: Listed, to: File) {
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        to.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                done += n
                if (done > l.size) throw Refused("${l.path} is larger than the manifest says")
                md.update(buf, 0, n)
                out.write(buf, 0, n)
            }
            out.fd.sync()
        }
        if (done != l.size) throw Refused("${l.path} isn't the size the manifest says")
        if (Releases.hex(md.digest()) != l.sha256) throw Refused("${l.path} doesn't match the manifest's SHA-256")
        ownerOnly(to)
    }

    /** At most [max] bytes of [input], or null when it holds more. */
    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
    }

    private fun mkdirPrivate(d: File) {
        if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create ${d.name}")
        ownerOnly(d)
        d.setExecutable(false, false); d.setExecutable(true, true)
    }

    private fun ownerOnly(f: File) {
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int): Long = (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)

    private const val EOCD_SIG = 0x06054b50L
    private const val EOCD_SIZE = 22
    private const val CDH_SIG = 0x02014b50L
    private const val CDH_SIZE = 46
    private const val HOST_UNIX = 3
    private const val S_IFMT = 0xF000
    private const val S_IFREG = 0x8000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000
    private const val DOS_DIRECTORY = 0x10
    private const val FLAG_ENCRYPTED = 1
    private const val STORED = 0
    private const val DEFLATED = 8
}

/**
 * The loader's record of the frame bundle, `/data/glassworks-pub/frame-bundle` (glassworks_loader.sh): what this boot
 * loaded and what waits for the next restart, `<ok|bad|none> <loaded version|-> <floor> <staged version|->`. The app
 * decides from this file (root writes it), never from a `sys.*` property: any app may set those.
 */
data class FrameBundleState(val state: String, val loaded: String?, val floor: Long, val staged: String?) {
    companion object {
        /** The record in [text], or null when it isn't one (the 0.4.3 image's three-field radio record among them). */
        fun parse(text: String?): FrameBundleState? {
            val p = text?.trim()?.split(' ') ?: return null
            if (p.size != 4 || p[0] !in setOf("ok", "bad", "none")) return null
            val floor = p[2].toLongOrNull() ?: return null
            return FrameBundleState(p[0], p[1].takeUnless { it == "-" }, floor, p[3].takeUnless { it == "-" })
        }
    }
}
