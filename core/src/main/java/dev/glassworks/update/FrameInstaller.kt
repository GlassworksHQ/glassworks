/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import dev.glassworks.frame.BundleAgent
import java.io.File
import java.io.IOException

/**
 * A frame update from Home Assistant's Frame update entity (#139), on a frame whose loader installs signed bundles (the
 * S1's): the release's bundle zip, checked against its `.sha256` as an app update is, unpacked into the agent's staging
 * dir ([FrameBundleZip]), handed to the agent's bundle install ([BundleAgent.request]: the loader's `install`, which
 * checks the signature itself), and once the loader's record says it is staged, a restart ([BundleAgent.restart]), at
 * once: the person or the automation already chose to update. The loader loads it at boot.
 *
 * Every decision comes from the loader's root-owned record ([BundleAgent.state], [FrameBundleState]); its status
 * property only words an error. Pure JVM: [net] fetches, [now] and [sleep] keep time. [run] blocks for the whole install
 * (minutes): call it on the update thread.
 */
class FrameInstaller(
    private val frameId: String,
    private val bundle: BundleAgent,
    private val filesDir: File,
    private val model: FrameUpdateModel,
    private val net: Net,
    private val onChange: () -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** The two downloads, over HTTPS (the update manager's, which GitHub redirects to its CDN). */
    interface Net {
        /** A small text file, at most [max] bytes. */
        fun text(url: String, max: Int): String

        /**
         * Streams [url] into [to], at most [max] bytes and no more than [size] when it is known, calling [progress] with
         * the bytes so far; [progress] false stops it. Returns the SHA-256 of what it wrote, or null when stopped.
         */
        fun download(url: String, to: File, size: Long, max: Long, progress: (Long) -> Boolean): ByteArray?
    }

    private val downloads get() = File(filesDir, DOWNLOAD_DIR)
    private val stage get() = File(filesDir, bundle.stageDir)

    /** At start: what an install cut short left, and what the loader loaded and staged ([FrameUpdateModel.onLoaderRecord]). */
    fun atStart() {
        downloads.deleteRecursively()
        // Never under the loader: deleting while it copies would make it fail half-way. A staging dir that is still there
        // is one an install of this app handed over (every other way out deletes it), so a loader running now copies it:
        // app updates wait for it as before the restart ([loaderHolds]). A loader run nobody here started has no dir.
        if (bundle.running() && stage.exists()) handedOff = true else stage.deleteRecursively()
        model.onLoaderRecord(FrameBundleState.parse(bundle.state()), bundle.rejected())
    }

    /**
     * The staging dir is the loader's: handed over with [BundleAgent.request], and not yet known to be done with. Kept
     * then, whatever ends the install (a timeout, the app stopping); the next install or start clears it.
     */
    @Volatile private var handedOff = false

    /** The loader may still be copying a bundle an install gave up waiting for: an app update waits too. Reads the device. */
    fun loaderHolds(): Boolean = handedOff && bundle.running()

    /** Why the device can't take a frame update now, or null; [appBusy]: an app update is under way. Reads the device. */
    fun busy(appBusy: Boolean): String? = when {
        appBusy -> APP_BUSY
        bundle.running() -> SERVICE_BUSY
        FrameBundleState.parse(bundle.state()) == null -> NO_LOADER
        else -> null
    }

    /** Installs [a] to the end: a restart, or [FrameUpdateModel.onFailed] with why. */
    fun run(a: FrameUpdateModel.Attempt) {
        val v = a.offer.version.toString()
        log("installing frame $v (${a.bundle.zip.size} bytes)")
        onChange()
        handedOff = false
        try {
            install(a, v)
        } catch (e: FrameBundleZip.Refused) {
            fail(a, "the bundle doesn't check out (${e.message})")
        } catch (e: IOException) {
            fail(a, if (GithubHttp.isNetworkError(e)) "no network (${e.javaClass.simpleName})" else "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        } catch (e: Exception) {
            fail(a, "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        } finally {
            downloads.deleteRecursively()
            // Only once the loader is done with it: deleting under it would make it fail half-way.
            if (!handedOff) stage.deleteRecursively()
        }
    }

    /** The install's steps; every way out but the restart ends in [fail]. */
    private fun install(a: FrameUpdateModel.Attempt, v: String) {
        val zip = a.bundle.zip
        val dir = downloads
        dir.deleteRecursively()
        if (!dir.mkdirs()) return fail(a, "cannot create ${dir.name}")
        // The zip, what it unpacks to, and the loader's copy of that, all on /data.
        val need = zip.size * 3 + FREE_SPACE_MARGIN
        if (dir.usableSpace < need) return fail(a, "not enough storage (${dir.usableSpace / 1_048_576} MiB free)")

        val expected = Releases.parseSha256(net.text(a.bundle.sha256.url, Releases.MAX_SHA_BYTES), zip.name)
            ?: return fail(a, "${a.bundle.sha256.name} isn't a SHA-256")
        zip.digest?.let { if (!it.contentEquals(expected)) return fail(a, "${a.bundle.sha256.name} disagrees with GitHub's digest of ${zip.name}") }
        val part = File(dir, "bundle.zip")
        var lastPublish = 0L
        val digest = net.download(zip.url, part, zip.size, FrameBundleZip.MAX_ZIP) { done ->
            if (expired(a)) return@download false
            val t = now()
            if (model.onProgress(a, done.toDouble() / zip.size) && t - lastPublish >= PROGRESS_PUBLISH_MS) {
                lastPublish = t
                onChange()
            }
            true
        } ?: return fail(a, "the download stopped")
        if (!digest.contentEquals(expected)) {
            return fail(a, "the download doesn't match the release's SHA-256 (got ${Releases.hex(digest).take(16)}…, release says ${Releases.hex(expected).take(16)}…)")
        }
        model.onProgress(a, 1.0)
        model.onPhase(a, FrameUpdateModel.Phase.CHECKING, now(), "Checking frame $v")
        onChange()
        log("frame $v: sha256 ${Releases.hex(digest)}")

        FrameBundleZip.unpack(part, bundle.format, frameId, v, stage)
        part.delete()
        if (!model.isActive(a)) return
        if (bundle.running()) return fail(a, SERVICE_BUSY)

        model.onPhase(a, FrameUpdateModel.Phase.STAGING, now(), "Staging frame $v")
        onChange()
        handedOff = true // before the request: from here the staging dir is the loader's until it is known to be done
        bundle.request()
        val (result, finished) = await(a)
        if (finished) handedOff = false
        val record = FrameBundleState.parse(bundle.state())
        if (record?.staged != v) {
            val why = result?.removePrefix("error: ")?.removePrefix("refused: ")?.take(160)
            return fail(a, if (result?.startsWith("error:") == true) "the panel refused it ($why)" else "the panel didn't stage it (${why ?: "no result from its bundle service"})")
        }

        log("frame $v staged: restarting to load it")
        model.onPhase(a, FrameUpdateModel.Phase.RESTARTING, now(), "Frame $v is staged. Restarting the panel to finish")
        onChange()
        sleep(RESTART_NOTICE_MS) // Home Assistant's card and the Device page show it first
        stage.deleteRecursively() // the loader is done: it staged its own copy
        bundle.restart()
        sleep(RESTART_WAIT_MS)
        fail(a, "frame $v is staged, but the panel didn't restart. Restart it to finish the update")
    }

    /**
     * Waits for the loader's install: it has to start within [START_WAIT_MS] and then finish. Finished is its service
     * stopped with a final status (`ok: …`, `error: …`), which also covers a run that ended between two polls; a status
     * that still says `running: …` is in progress even if one read missed the service. A service stopped for
     * [STOPPED_POLLS] polls in a row without a final status died half-way: finished too. Returns its last status, and
     * whether it is known to have finished (else the staging dir must stay).
     */
    private fun await(a: FrameUpdateModel.Attempt): Pair<String?, Boolean> {
        var waited = 0L
        var started = false
        var stoppedPolls = 0
        while (waited < MAX_WAIT_MS) {
            sleep(POLL_MS); waited += POLL_MS
            val st = bundle.status()
            val running = bundle.running() // read after the status: a run that just started shows in one or the other
            val final = st.startsWith("ok:") || st.startsWith("error:")
            if (running || (st.isNotEmpty() && st != "requested")) started = true
            if (!running && final) return st to true
            stoppedPolls = if (!running && started) stoppedPolls + 1 else 0
            if (stoppedPolls >= STOPPED_POLLS) return "its bundle service stopped without a result" to true
            if (!started && waited >= START_WAIT_MS) return "its bundle service did not start" to true
            if (expired(a)) return "timed out" to false
        }
        return "its bundle service is still running" to false
    }

    private fun expired(a: FrameUpdateModel.Attempt): Boolean {
        model.expire(now())?.let { why ->
            log("frame update $why")
            onChange()
        }
        return !model.isActive(a)
    }

    private fun fail(a: FrameUpdateModel.Attempt, reason: String) {
        if (model.onFailed(a, reason)) {
            log("install of frame ${a.offer.version} failed: $reason")
            onChange()
        }
    }

    companion object {
        /** Where the zip downloads, in the app's files dir: apart from the staging dir the loader reads. */
        const val DOWNLOAD_DIR = "frame-update"
        const val APP_BUSY = "an app update is installing. Try again once it's done."
        const val SERVICE_BUSY = "the panel's bundle service is busy. Try again in a minute."
        const val NO_LOADER = "the panel's loader hasn't written its record. Try again after a restart."
        private const val FREE_SPACE_MARGIN = 32L * 1024 * 1024
        private const val PROGRESS_PUBLISH_MS = 1_000L
        internal const val POLL_MS = 500L
        internal const val START_WAIT_MS = 30_000L
        /** 10 s of a stopped service without a final status. */
        internal const val STOPPED_POLLS = 20
        internal const val MAX_WAIT_MS = FrameUpdateModel.STAGING_TIMEOUT_MS
        /** Long enough for the state to reach Home Assistant and the Manage page before the restart. */
        internal const val RESTART_NOTICE_MS = 3_000L
        /** A restart takes the process down within seconds; after this one didn't. */
        internal const val RESTART_WAIT_MS = 60_000L
    }
}
