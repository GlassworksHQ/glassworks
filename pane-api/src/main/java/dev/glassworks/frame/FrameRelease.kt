/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

/**
 * A frame whose own parts Glassworks versions and releases ([Frame.frameRelease], #139): the S1's boot-side services,
 * scripts and boot animation, or the Mirror's patched image. Its version is the frame version, not Android's. Each
 * device carries it as two system properties, which Kiln stamps into the Mirror's `build.prop` and the S1's loader sets
 * at boot. The releases are tagged `frames/<id>/v<version>` (docs/releasing.md, "Frame releases").
 */
class FrameRelease(
    /** frame.json's `id`, as [ID_PROP] says it on a stamped device. */
    val id: String,
    /** [VERSION_PROP] when [ID_PROP] is [id]; null on a device flashed before the stamps. */
    val stamp: String?,
    /** On a device without [stamp]: the image it runs, when the frame can tell ("Glass Panes image 2"). */
    val image: String? = null,
    /** How to install a frame update on this device, for Home Assistant's update card and the Device page; null when it installs from Home Assistant. */
    val install: String?,
) {
    companion object {
        const val ID_PROP = "ro.glassworks.frame.id"
        const val VERSION_PROP = "ro.glassworks.frame.version"

        /** [id]'s stamp among the system properties ([prop]): [VERSION_PROP] when [ID_PROP] names [id], else null. */
        fun stamp(id: String, prop: (String) -> String): String? =
            prop(VERSION_PROP).trim().takeIf { it.isNotEmpty() && prop(ID_PROP).trim() == id }

        /** Where Kiln is, for [install] texts. */
        const val KILN = "kiln.glassworks.dev"
    }
}
