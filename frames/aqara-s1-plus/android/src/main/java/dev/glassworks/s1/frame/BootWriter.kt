package dev.glassworks.s1.frame

import android.content.Context
import android.util.Log
import dev.glassworks.frame.ServiceWork
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The boot writer (#139, route 2; frames/aqara-s1-plus/tools/root/glassworks_bootwriter.sh, docs/research/s1-boot-writer.md):
 * the radio bundle's root script that puts the frame loader into a 0.4.3 S1's own boot image, over the air, once.
 *
 * The app's part is small. It reads the writer's record ([RECORD], root 0644: "<state> <epoch> <detail>") for the Device
 * page's *Frame loader* row, and, when the writer asks for it (`need-magisk`), downloads the official Magisk APK it patches
 * with. This project never ships Magisk (GPL, AGENTS.md rule 4): the panel fetches the pinned release from Magisk's own
 * GitHub, and the writer takes it only with the same SHA-256 ([APK_SHA256], also kiln/assets.lock.json's).
 */
object BootWriter {
    const val RECORD = "/data/glassworks-pub/boot-writer"
    const val APK_URL = "https://github.com/topjohnwu/Magisk/releases/download/v31.1/Magisk-v31.1.apk"
    const val APK_SHA256 = "89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7"
    const val APK_SIZE = 10_679_283L
    /** Where the writer takes it from, under the app's files/ (s1kiosk_verify --copy: app-owned, no symlinks, this size). */
    const val APK_PATH = "boot-writer/magisk.apk"
    const val TITLE = "Frame loader"
    const val LOADER_PROP = "ro.glassworks.frame.id"
    /** The 0.4.3 boot image's root helper contract: the writer runs from its radio bundle, so it needs v4 or later. */
    const val HELPER_PROP = "sys.s1kiosk.radio.version"

    /** Whether this boot image's root helper can run the boot writer: a patched 0.4.3 image (helper v4) can, stock can't. */
    fun helperCanWrite(version: String): Boolean = (version.trim().toIntOrNull() ?: 0) >= 4

    data class Record(val state: String, val epoch: Long, val detail: String)

    fun parse(line: String?): Record? {
        val p = line?.trim()?.split(' ', limit = 3) ?: return null
        if (p.size < 2 || p[0].isEmpty()) return null
        return Record(p[0], p[1].toLongOrNull() ?: return null, p.getOrElse(2) { "" })
    }

    fun read(file: File = File(RECORD)): Record? = runCatching { parse(file.readLines().firstOrNull()) }.getOrNull()

    /**
     * The Device page's row. [loaderRuns]: this boot ran the loader (its frame id is set). [helper]: the boot image's root
     * helper can run the writer ([helperCanWrite]); without it only Kiln can. [date]: an epoch in the panel's own words.
     */
    fun rowText(record: Record?, loaderRuns: Boolean, helper: Boolean = true, date: (Long) -> String = ::dateOf): String {
        val d = record?.detail.orEmpty()
        return when (record?.state) {
            "ok" -> "Yes: installed over the air on ${date(record.epoch)}"
            "installed" -> "Yes"
            "error" -> if (loaderRuns) "Yes, but the check after the restart failed: $d" else "Error: $d"
            "restarting" -> if (loaderRuns) "Yes" else "Installing: restarting once to finish"
            "need-magisk" -> "Installing: downloading Magisk's tools (the official release)"
            "checking", "waiting" -> "Installing: $d"
            "refused", "restored" -> "Not installed: $d"
            else -> when {
                loaderRuns -> "Yes"
                helper -> "Not yet: the app's radio bundle installs it"
                else -> NO_HELPER
            }
        }
    }

    /** A boot image without our root helper (stock): no over-the-air route, Kiln's install is the way. */
    val NO_HELPER = "No: this boot image has no root helper. To install it: update the panel once with Kiln, at ${dev.glassworks.frame.FrameRelease.KILN}"

    private fun dateOf(epoch: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epoch * 1000))

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Fetches the Magisk APK while the boot writer waits for it, and removes it once the loader is in. Checks the record every
 * [everyS] seconds on its own thread: a file read, so cheap. One per service run ([dev.glassworks.frame.Frame.serviceWork]).
 */
class MagiskFetch(
    private val filesDir: File,
    private val record: () -> BootWriter.Record? = { BootWriter.read() },
    private val download: (String, File, Long) -> Unit = ::httpDownload,
    private val everyS: Long = 300,
    private val inline: Boolean = false,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : ServiceWork {
    constructor(context: Context) : this(context.filesDir)

    @Volatile private var exec: ScheduledExecutorService? = null

    override fun start() {
        if (inline) { check(); return }
        val e = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "s1-magisk-fetch").apply { isDaemon = true } }
        exec = e
        e.scheduleWithFixedDelay({ runCatching { check() }.onFailure { log("check failed: ${it.message}") } }, 5, everyS, TimeUnit.SECONDS)
    }

    override fun stop() { exec?.shutdownNow(); exec = null }

    /** One look at the record: download when asked, clean up when done. */
    fun check() {
        val apk = File(filesDir, BootWriter.APK_PATH)
        when (record()?.state) {
            "need-magisk" -> if (!good(apk)) fetch(apk)
            "ok", "installed" -> if (apk.exists() && apk.delete()) log("the loader is in: removed the Magisk APK")
        }
    }

    private fun good(f: File) = f.isFile && f.length() == BootWriter.APK_SIZE && BootWriter.sha256(f) == BootWriter.APK_SHA256

    private fun fetch(apk: File) {
        val dir = apk.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make $dir")
        val tmp = File(dir, "magisk.apk.part")
        tmp.delete()
        log("the boot writer asks for Magisk's tools: downloading the official v31.1 APK")
        try {
            download(BootWriter.APK_URL, tmp, BootWriter.APK_SIZE)
            if (!good(tmp)) throw IOException("the download has the wrong size or SHA-256")
            apk.delete()
            if (!tmp.renameTo(apk)) throw IOException("rename failed")
            log("Magisk APK downloaded (SHA-256 matches); the boot writer takes it at its next start")
        } finally { tmp.delete() }
    }

    companion object {
        const val TAG = "Glassworks.BootWriter"

        /** A plain HTTPS download, at most [max] bytes (redirects https -> https only, as GitHub's release downloads do). */
        fun httpDownload(url: String, to: File, max: Long) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Glassworks")
            try {
                if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode} from Magisk's release")
                var n = 0L
                c.inputStream.use { s ->
                    to.outputStream().use { o ->
                        val b = ByteArray(1 shl 16)
                        while (true) {
                            val r = s.read(b); if (r < 0) break
                            n += r; if (n > max) throw IOException("download larger than expected")
                            o.write(b, 0, r)
                        }
                    }
                }
            } finally { c.disconnect() }
        }
    }
}
