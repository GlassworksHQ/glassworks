/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

/**
 * What the Security page's "Allow a new computer" card ([ManageUsbDebugging]) works with: the computer asking now,
 * the allowed list, Allow and Remove. Two kinds exist. [AdbComputers] answers Android's question itself, for a
 * panel whose adbd ignores `adb_keys` (the Mirror). A frame with a root side can keep `adb_keys` instead, as the
 * S1's frame agent does.
 */
interface UsbComputers {
    /** The computer asking now, unanswered, or null. */
    fun asking(): AdbKey?

    /**
     * The computers on the allowed list. Where the frame keeps `adb_keys` ([listsInstaller]), the computer that installed
     * Glassworks is on it too ([AdbComputers.Listed.installer]); elsewhere it isn't, and always gets in.
     */
    fun list(): List<AdbComputers.Listed>

    /**
     * Lets in the computer asking now, if it is still the one the page showed ([shown], its fingerprint). With
     * [remember] it goes on the list too. May block for a few seconds.
     */
    fun allowAsking(shown: String, remember: Boolean, who: String): AdbComputers.Allowed

    /** Takes the computer with [fingerprint] off the list. */
    fun remove(fingerprint: String, who: String): AdbComputers.Removed

    /** True when [list] holds the computer that installed Glassworks, which Remove can take off like any other. */
    val listsInstaller: Boolean get() = false

    /** Why the list doesn't decide who gets in right now (the panel's adb lets every computer in), or null when it does. */
    fun notice(): String? = null
}
