/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import dev.glassworks.esphome.UpdateStateData
import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.FrameAgent
import dev.glassworks.frame.FrameRelease

/** The version of a frame's own parts on this device (#139), as the Device page and Home Assistant show it. */
sealed interface FrameVersion {
    val shown: String

    /** Whether [latest] is newer than this. A device from before the stamps is older than every release. */
    fun olderThan(latest: AppVersion): Boolean

    /**
     * Stamped by Kiln or the S1's loader. [bundle] false: the loader's record says no bundle is loaded (none yet, or a
     * bad one), so the device runs its boot image's own copies under the boot image's version, and that version's
     * bundle is still an update (S1 frame 1.0.0: the boot image and the first bundle are both 1.0.0). Home Assistant
     * can't compare the shown "1.0.0 (boot image)" with "1.0.0", so it offers the update, as for [BeforeStamps].
     */
    data class Stamped(val version: AppVersion, val bundle: Boolean = true) : FrameVersion {
        override val shown get() = if (bundle) version.toString() else "$version ($BOOT_IMAGE)"
        override fun olderThan(latest: AppVersion) = latest > version || (!bundle && latest == version)
    }

    /** Flashed before the stamps, or the stamp isn't a version. [image]: what it runs, when the frame can tell. */
    data class BeforeStamps(val image: String?) : FrameVersion {
        override val shown get() = "Before $FIRST" + image?.let { " ($it)" }.orEmpty()
        override fun olderThan(latest: AppVersion) = true
    }

    companion object {
        /** The first frame version: frames start at 1.0.0, with Glassworks 1.0.0. */
        const val FIRST = "1.0.0"
        const val BOOT_IMAGE = "boot image"

        fun of(release: FrameRelease): FrameVersion =
            AppVersion.parse(release.stamp)?.let(::Stamped) ?: BeforeStamps(release.image)
    }
}

/** A frame release's signed bundle (release.yml, from frame.json's `release.frame.assets`): the zip the app stages for the loader, and its `.sha256`. */
data class FrameBundleAssets(val zip: ReleaseAsset, val sha256: ReleaseAsset) {
    companion object {
        /** The Release workflow's name for [frameId]'s bundle at [version]. */
        fun zipName(frameId: String, version: AppVersion) = "frame-bundle-$frameId-$version.zip"

        /** The bundle in [r] for [frameId] at [version], or null when the release has none (the Mirror's notice-only releases). */
        fun of(r: GithubRelease, frameId: String, version: AppVersion): FrameBundleAssets? {
            val name = zipName(frameId, version)
            fun one(n: String) = r.assets.singleOrNull { it.name == n }?.takeIf { Releases.isReleaseDownload(it.url, r.tag, n) }
            val zip = one(name)?.takeIf { it.size in 1..FrameBundleZip.MAX_ZIP } ?: return null
            val sha = one("$name.sha256") ?: return null
            return FrameBundleAssets(zip, sha)
        }
    }
}

/**
 * A published frame release: `frames/<id>/v<version>` on GitHub Releases. [bundle]: its signed bundle, on a frame whose
 * releases carry one (the S1's); null on the others, which install some other way ([FrameRelease.install]).
 */
data class FrameOffer(
    val version: AppVersion, val tag: String, val notes: String, val releaseUrl: String, val bundle: FrameBundleAssets? = null,
) {
    companion object {
        fun tagPrefix(frameId: String) = "frames/$frameId/v"

        /** The newest stable release of [frameId] in [releases], or null. Drafts, pre-releases and other tags are skipped. */
        fun newest(releases: List<GithubRelease>, frameId: String): FrameOffer? {
            val prefix = tagPrefix(frameId)
            return releases.asSequence()
                .filter { !it.draft && !it.prerelease && it.tag.startsWith(prefix) }
                .mapNotNull { r -> AppVersion.parse(r.tag.removePrefix(prefix))?.takeUnless { it.isPreRelease }?.let { r to it } }
                .maxByOrNull { it.second }
                ?.let { (r, v) -> FrameOffer(v, r.tag, r.body, r.htmlUrl.takeIf { it.startsWith("https://") }.orEmpty(), FrameBundleAssets.of(r, frameId, v)) }
        }
    }
}

/**
 * The Frame update entity (#139): this device's frame version and the newest release of its frame, and on a frame that
 * [installs] its bundles from here (the S1 with the loader), the install: download, check, stage, restart
 * ([FrameInstaller] drives it). Elsewhere nothing installs from here, and the card says how ([FrameRelease.install]).
 *
 * ```
 * IDLE --install, newer release with a bundle--> DOWNLOADING (%) --> CHECKING --> STAGING --> RESTARTING
 *   any phase --failure / timeout--> IDLE with "Install of frame X failed: why" on top of the summary
 * ```
 * A staged bundle loads at the restart, which ends this process; the new version comes from the loader's stamp at the
 * next start. Each install is an [Attempt]; calls for one that already ended are ignored. Thread-safe.
 */
class FrameUpdateModel(
    private val release: FrameRelease,
    private val title: String,
    /** Whether Install works on this device ([installs]); false: it says how instead. */
    val installs: Boolean = false,
) {
    enum class Phase { IDLE, DOWNLOADING, CHECKING, STAGING, RESTARTING }

    data class Attempt(val id: Int, val offer: FrameOffer, val bundle: FrameBundleAssets)

    /** What [begin] did: started [Started.attempt], or refused, and why ([Refused.reason], a sentence for the card). */
    sealed interface Begin {
        class Started(val attempt: Attempt) : Begin
        class Refused(val reason: String) : Begin
    }

    private enum class Kind { NONE, INFO, ERROR }

    @Volatile var installed: FrameVersion = FrameVersion.of(release)
        private set

    @Volatile private var checked = false
    @Volatile var offer: FrameOffer? = null
        private set

    private var attempt: Attempt? = null
    private var nextId = 1
    private var phase = Phase.IDLE
    private var phaseSince = 0L
    private var percent = 0
    private var status = ""
    private var kind = Kind.NONE

    @get:Synchronized val currentPhase: Phase get() = phase

    /** An install is running: the app update waits for it, and it for the app update. */
    val busy: Boolean get() = currentPhase != Phase.IDLE

    val updateAvailable: Boolean get() = newer() != null

    /** The last offer when it is newer than [installed], read once so a check landing meanwhile can't mix two offers. */
    private fun newer(): FrameOffer? = offer?.takeIf { installed.olderThan(it.version) }

    /** A check succeeded; [offer] is null when the frame has no release yet. */
    fun onChecked(offer: FrameOffer?) {
        this.offer = offer
        checked = true
    }

    /**
     * At start, on a frame that [installs]: the loader's [record]. No bundle loaded: the stamp is the boot image's own
     * version ([FrameVersion.Stamped.bundle]). A bundle staged and not loaded: an update whose restart didn't happen.
     */
    @Synchronized fun onLoaderRecord(record: FrameBundleState?) {
        if (record == null) return
        (installed as? FrameVersion.Stamped)?.let { if (record.loaded == null) installed = it.copy(bundle = false) }
        val staged = record.staged
        if (staged != null && staged != record.loaded && phase == Phase.IDLE && kind == Kind.NONE) {
            setStatus("Frame $staged is staged. It loads at the next restart.", Kind.INFO)
        }
    }

    /**
     * Starts an install of the newer release, or says why not. [busy]: why the device can't now (an app update), or
     * null. A refusal the person should see stays on the card until the next try; "up to date" doesn't.
     */
    @Synchronized fun begin(now: Long, busy: String?): Begin {
        if (!installs) return Begin.Refused(release.install ?: NOT_HERE)
        if (phase != Phase.IDLE) return Begin.Refused(ALREADY)
        if (busy != null) return refuse(busy)
        if (!checked) return Begin.Refused(NOT_CHECKED)
        val o = newer() ?: return Begin.Refused(UP_TO_DATE)
        val b = o.bundle ?: return refuse("Frame ${o.version} has no bundle for this device.")
        val a = Attempt(nextId++, o, b)
        attempt = a
        enter(Phase.DOWNLOADING, now)
        setStatus("Downloading frame ${o.version}", Kind.INFO)
        return Begin.Started(a)
    }

    private fun refuse(reason: String): Begin.Refused {
        setStatus("Not installed: $reason", Kind.ERROR)
        return Begin.Refused(reason)
    }

    /** Whether [a] is still the running install. */
    @Synchronized fun isActive(a: Attempt): Boolean = attempt === a && phase != Phase.IDLE

    /** Download progress (0..1). True when the whole percent changed (worth publishing). */
    @Synchronized fun onProgress(a: Attempt, fraction: Double): Boolean {
        if (!isActive(a) || phase != Phase.DOWNLOADING) return false
        val p = (fraction.coerceIn(0.0, 1.0) * 100).toInt()
        if (p == percent) return false
        percent = p
        return true
    }

    /** [a] moved on to [p], with the card's line [line]. */
    @Synchronized fun onPhase(a: Attempt, p: Phase, now: Long, line: String) {
        if (!isActive(a) || p == Phase.IDLE) return
        enter(p, now)
        setStatus(line, Kind.INFO)
    }

    /** Ends [a] with an error; false if it had already ended. */
    @Synchronized fun onFailed(a: Attempt, reason: String): Boolean {
        if (!isActive(a)) return false
        fail(reason)
        return true
    }

    /** Ends an install stuck in one phase for too long; returns why, or null if nothing expired. */
    @Synchronized fun expire(now: Long): String? {
        val limit = when (phase) {
            Phase.IDLE -> return null
            Phase.DOWNLOADING -> UpdateModel.DOWNLOAD_TIMEOUT_MS
            Phase.CHECKING, Phase.RESTARTING -> UpdateModel.INSTALL_TIMEOUT_MS
            Phase.STAGING -> STAGING_TIMEOUT_MS
        }
        if (now - phaseSince < limit) return null
        val why = "timed out while ${phase.name.lowercase()}"
        fail(why)
        return why
    }

    /** The Device page's Frame version row: the installed version, and the newer one with how to install it. */
    fun detail(): String {
        val o = newer() ?: return installed.shown
        return "${installed.shown}. ${o.version} is out" + release.install?.let { ". $it" }.orEmpty()
    }

    /** The Device page's Frame update row, on a frame that [installs]: what Install does now, or how the last one went. */
    @Synchronized fun installLine(): String {
        val o = newer()
        return when {
            phase == Phase.DOWNLOADING -> "$status: $percent%"
            phase != Phase.IDLE || status.isNotEmpty() -> status
            !checked -> "Not checked yet"
            o == null -> "Up to date"
            o.bundle == null -> "Frame ${o.version} has no bundle for this device"
            else -> "Frame ${o.version} is out. Installing it restarts the panel once"
        }
    }

    @Synchronized fun view(): UpdateStateData {
        val o = newer()
        val top = listOfNotNull(release.install, status.takeIf { it.isNotEmpty() }).joinToString("\n")
        return UpdateStateData(
            missingState = !checked,
            inProgress = phase != Phase.IDLE,
            hasProgress = phase == Phase.DOWNLOADING,
            progress = if (phase == Phase.DOWNLOADING) percent.toFloat() else 0f,
            currentVersion = installed.shown,
            // Up to date (or not checked yet): latest = installed, which Home Assistant shows as no update (UpdateModel.view).
            latestVersion = o?.version?.toString() ?: installed.shown,
            title = title,
            releaseSummary = when {
                o != null -> Releases.summary(o.notes, top)
                status.isNotEmpty() -> Releases.summary("", status)
                else -> ""
            },
            releaseUrl = o?.releaseUrl.orEmpty(),
        )
    }

    private fun fail(reason: String) {
        val v = attempt?.offer?.version
        attempt = null
        enter(Phase.IDLE, phaseSince)
        setStatus("Install of frame ${v ?: "the update"} failed: $reason", Kind.ERROR)
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseSince = now
        if (p != Phase.DOWNLOADING) percent = 0
    }

    private fun setStatus(s: String, k: Kind) {
        status = s
        kind = k
    }

    companion object {
        /**
         * Whether [release]'s bundles install from Home Assistant on this device: the frame says it installs from there
         * ([FrameRelease.install] null: the S1 with its loader) and its agent declares [AgentCapability.FRAME_BUNDLE]
         * (frame.json's `agent.capabilities`). Frame data only, no device read: the release must also carry a bundle.
         */
        fun installs(release: FrameRelease, agent: FrameAgent): Boolean =
            release.install == null && AgentCapability.FRAME_BUNDLE in agent.declared

        /** The loader copies up to 96 MiB and checks each file: more time than an APK install. */
        const val STAGING_TIMEOUT_MS = 15 * 60_000L
        const val NOT_HERE = "Frame updates don't install from here on this device."
        const val ALREADY = "A frame update is installing already."
        const val NOT_CHECKED = "The panel hasn't checked for frame updates yet. Try again in a minute."
        const val UP_TO_DATE = "The frame is up to date."
    }
}
