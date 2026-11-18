/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import dev.glassworks.esphome.UpdateStateData
import dev.glassworks.frame.FrameRelease

/** The version of a frame's own parts on this device (#139), as the Device page and Home Assistant show it. */
sealed interface FrameVersion {
    val shown: String

    /** Whether [latest] is newer than this. A device from before the stamps is older than every release. */
    fun olderThan(latest: AppVersion): Boolean

    /** Stamped by Kiln or the S1's loader. */
    data class Stamped(val version: AppVersion) : FrameVersion {
        override val shown get() = version.toString()
        override fun olderThan(latest: AppVersion) = latest > version
    }

    /** Flashed before the stamps, or the stamp isn't a version. [image]: what it runs, when the frame can tell. */
    data class BeforeStamps(val image: String?) : FrameVersion {
        override val shown get() = "Before $FIRST" + image?.let { " ($it)" }.orEmpty()
        override fun olderThan(latest: AppVersion) = true
    }

    companion object {
        /** The first frame version: frames start at 1.0.0, with Glassworks 1.0.0. */
        const val FIRST = "1.0.0"

        fun of(release: FrameRelease): FrameVersion =
            AppVersion.parse(release.stamp)?.let(::Stamped) ?: BeforeStamps(release.image)
    }
}

/** A published frame release: `frames/<id>/v<version>` on GitHub Releases. It has no assets this app installs. */
data class FrameOffer(val version: AppVersion, val tag: String, val notes: String, val releaseUrl: String) {
    companion object {
        fun tagPrefix(frameId: String) = "frames/$frameId/v"

        /** The newest stable release of [frameId] in [releases], or null. Drafts, pre-releases and other tags are skipped. */
        fun newest(releases: List<GithubRelease>, frameId: String): FrameOffer? {
            val prefix = tagPrefix(frameId)
            return releases.asSequence()
                .filter { !it.draft && !it.prerelease && it.tag.startsWith(prefix) }
                .mapNotNull { r -> AppVersion.parse(r.tag.removePrefix(prefix))?.takeUnless { it.isPreRelease }?.let { r to it } }
                .maxByOrNull { it.second }
                ?.let { (r, v) -> FrameOffer(v, r.tag, r.body, r.htmlUrl.takeIf { it.startsWith("https://") }.orEmpty()) }
        }
    }
}

/**
 * The Frame update entity (#139): this device's frame version and the newest release of its frame. Nothing installs
 * from here yet; the card says how ([FrameRelease.install]). Thread-safe.
 */
class FrameUpdateModel(private val release: FrameRelease, private val title: String) {
    val installed: FrameVersion = FrameVersion.of(release)

    @Volatile private var checked = false
    @Volatile var offer: FrameOffer? = null
        private set

    val updateAvailable: Boolean get() = newer() != null

    /** The last offer when it is newer than [installed], read once so a check landing meanwhile can't mix two offers. */
    private fun newer(): FrameOffer? = offer?.takeIf { installed.olderThan(it.version) }

    /** A check succeeded; [offer] is null when the frame has no release yet. */
    fun onChecked(offer: FrameOffer?) {
        this.offer = offer
        checked = true
    }

    /** The Device page's Frame version row: the installed version, and the newer one with how to install it. */
    fun detail(): String {
        val o = newer() ?: return installed.shown
        return "${installed.shown}. ${o.version} is out" + release.install?.let { ". $it" }.orEmpty()
    }

    fun view(): UpdateStateData {
        val o = newer()
        return UpdateStateData(
            missingState = !checked,
            inProgress = false,
            hasProgress = false,
            progress = 0f,
            currentVersion = installed.shown,
            // Up to date (or not checked yet): latest = installed, which Home Assistant shows as no update (UpdateModel.view).
            latestVersion = o?.version?.toString() ?: installed.shown,
            title = title,
            releaseSummary = if (o == null) "" else Releases.summary(o.notes, release.install.orEmpty()),
            releaseUrl = o?.releaseUrl.orEmpty(),
        )
    }
}
