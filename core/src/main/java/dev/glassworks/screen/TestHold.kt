/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.content.Context
import android.os.SystemClock
import java.io.File

/** Where [ScreenPolicy.testHold] keeps itself, and what reads it without a policy. */
object TestHold {
    /**
     * The test hold's dashboard ([ScreenPolicy.testDashboard]) as its file has it now, for what reads it before the
     * policy starts: what the web engine starts with (the legacy engine holds pages to its origin, #198).
     */
    fun dashboard(context: Context): String? =
        file(context).read(SystemClock.elapsedRealtime(), boot(context), ScreenPolicy.TEST_HOLD_MAX_S * 1000L)?.dashboard

    internal fun file(context: Context) = TestHoldFile(File(context.applicationContext.noBackupFilesDir, "test_hold"))

    /** Which boot this is: a hold saved in another boot is not this one's. Android 7 added the count (-1 before). */
    internal fun boot(c: Context): Long = android.provider.Settings.Global.getInt(c.contentResolver, "boot_count", -1).toLong()
}
