/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.widget.TextView
import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.camera.CameraUseCards
import java.io.File

/**
 * The second use is "Presence + camera" (maintainer, 2026-11-14: "snapshots" read as leaving the
 * Protect stream out). Nothing a person reads (the app, docs/, the README, the changelog) says
 * "Presence + snapshots"; only the stored value does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraWordingTest {
    private val repo = RepoFiles.root

    @Test fun theCardsSayPresencePlusCamera() {
        val cards = CameraUseCards(RuntimeEnvironment.getApplication())
        fun texts(v: android.view.View): List<String> = when (v) {
            is TextView -> listOf(v.text.toString())
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
            else -> emptyList()
        }
        val all = texts(cards)
        assertTrue(all.toString(), "Presence + camera" in all)
        assertTrue(all.toString(), "Snapshots for Home Assistant, and the stream for Protect or Frigate if you turn it on." in all)
        assertEquals("Needs Presence + camera.", CameraPage.NEEDS_CAMERA)
    }

    @Test fun noUserFacingTextSaysPresencePlusSnapshots() {
        val old = "Presence + snapshots"
        val files = RepoFiles.sources(atLeast = 200) { it.extension in setOf("kt", "xml", "json") } +
            File(repo, "docs").walkTopDown().filter { it.isFile && it.extension == "md" }.toList() +
            listOf(File(repo, "CHANGELOG.md"), File(repo, "README.md"))
        val found = files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> old in l && "STORED_CAMERA =" !in l }.map { (i, l) -> "${RepoFiles.rel(f)}:${i + 1}: ${l.trim()}" }
        }
        assertEquals("only the stored value may keep the old words", emptyList<String>(), found)
    }
}
