/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.util.AtomicFile
import dev.glassworks.secrets.SecretStore
import org.json.JSONObject
import java.io.File

/** What a [HiddenApHotspot] start changes, to put back: the device's own hotspot settings ([ApControls]' text) and whether Wi-Fi was on. */
data class ApBefore(val config: String?, val wifiOn: Boolean) {
    override fun toString() = "ApBefore(wifiOn=$wifiOn)" // the settings hold the device's own hotspot key
}

/**
 * Where a start's [ApBefore] outlives the app. Android keeps an AP up after the app that started it dies, so a run that
 * dies with it on leaves this for the next run to put back (#197's undo).
 */
interface ApUndo {
    /** What [read] found. [Unreadable]: a record is there but can't be opened now (the key store is out); it stays. */
    sealed interface Kept {
        data object None : Kept

        data object Unreadable : Kept

        data class Found(val before: ApBefore) : Kept
    }

    fun read(): Kept

    fun write(before: ApBefore)

    fun clear()
}

/**
 * [ApUndo] in one file, sealed by [secrets]: the device's own hotspot settings hold its key. In `noBackupFilesDir`, and
 * only while the setup hotspot is on. When the key store can't seal, nothing is kept: a run that ends still puts things
 * back, and only a run that dies can't.
 */
class ApUndoFile(file: File, private val secrets: SecretStore, private val log: (String) -> Unit = {}) : ApUndo {
    private val file = AtomicFile(file)

    /** The record. One that never opens (another key, a broken file) is dropped, as if there were none. */
    override fun read(): ApUndo.Kept {
        val stored = runCatching { String(file.readFully(), Charsets.UTF_8) }.getOrNull() ?: return ApUndo.Kept.None
        val opened = secrets.open(stored, SCOPE)
        if (opened is SecretStore.Opened.Unavailable) {
            log("setup hotspot: the key store is out; its undo record waits")
            return ApUndo.Kept.Unreadable
        }
        val before = (opened as? SecretStore.Opened.Value)?.let { v ->
            runCatching { JSONObject(v.text).let { ApBefore(it.optString(CONFIG).takeIf(String::isNotEmpty), it.getBoolean(WIFI_ON)) } }.getOrNull()
        }
        if (before == null) {
            log("setup hotspot: its undo record doesn't open; dropped")
            clear()
            return ApUndo.Kept.None
        }
        return ApUndo.Kept.Found(before)
    }

    override fun write(before: ApBefore) {
        val text = JSONObject().put(WIFI_ON, before.wifiOn).put(CONFIG, before.config.orEmpty()).toString()
        val sealed = secrets.seal(text, SCOPE) ?: return log("setup hotspot: the key store can't seal; no undo record kept")
        val out = runCatching { file.startWrite() }.getOrNull() ?: return log("setup hotspot: its undo record can't be written")
        runCatching { out.write(sealed.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
            .onFailure { file.failWrite(out); log("setup hotspot: its undo record can't be written") }
    }

    override fun clear() = file.delete()

    private companion object {
        const val SCOPE = "setup-hotspot-undo"
        const val WIFI_ON = "wifiOn"
        const val CONFIG = "config"
    }
}
