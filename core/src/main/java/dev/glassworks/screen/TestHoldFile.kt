/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import java.io.File

/**
 * Where a [ScreenPolicy.testHold] outlives the app's process (#240): a test restarts the app, and the
 * new process must not sleep before the test can send the hold again. One line, `<end> <boot>`: the
 * end on `elapsedRealtime` in ms, and the boot it was set in (Android's boot count). It isn't a
 * setting: it sits in `noBackupFilesDir`, outside SharedPreferences and backups.
 */
internal class TestHoldFile(private val file: File) {
    /**
     * The end, if the hold is from this boot, not over, and no further than [maxMs] ahead (a boot count
     * Android doesn't have, -1, can't tell boots apart; a reboot resets elapsedRealtime, so an old end
     * would look far ahead). Anything else is deleted.
     */
    fun read(now: Long, boot: Long, maxMs: Long): Long? {
        val parts = runCatching { file.readText().trim().split(" ").map(String::toLong) }.getOrNull()
        val end = parts?.takeIf { it.size == 2 && it[1] == boot && it[0] > now && it[0] - now <= maxMs }?.get(0)
        if (end == null) clear()
        return end
    }

    fun write(end: Long, boot: Long) {
        runCatching { file.writeText("$end $boot") }
    }

    fun clear() {
        file.delete()
    }
}
