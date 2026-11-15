/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.SharedPreferences

/**
 * The camera pane's settings, in the file `PaneContext.prefs("camera")` gives it. Panels keep these
 * keys: never rename them. The stream's password is never logged.
 */
class CameraPrefs(private val sp: SharedPreferences) {

    /** The pane is on ([CameraDefinition.switch] writes it). Off until someone turns it on. */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, false)

    var use: CameraUse
        get() = when (sp.getString(KEY_USE, null)) { STORED_CAMERA -> CameraUse.PRESENCE_AND_SNAPSHOTS; else -> CameraUse.PRESENCE }
        set(v) {
            // Presence only clears the stream switch, so going back to snapshots doesn't open the ports without a tap.
            sp.edit().putString(KEY_USE, if (v == CameraUse.PRESENCE_AND_SNAPSHOTS) STORED_CAMERA else STORED_PRESENCE)
                .apply { if (v == CameraUse.PRESENCE) putBoolean(KEY_ONVIF, false) }
                .apply()
        }

    /** Someone picked the camera's use (setup's Camera step, the page, Home Assistant): until then it is Presence only by default. */
    val useChosen: Boolean get() = sp.contains(KEY_USE)

    var privacy: Boolean
        get() = sp.getBoolean(KEY_PRIVACY, false)
        set(v) { sp.edit().putBoolean(KEY_PRIVACY, v).apply() }

    var onvif: Boolean
        get() = sp.getBoolean(KEY_ONVIF, false)
        set(v) { sp.edit().putBoolean(KEY_ONVIF, v).apply() }

    var sensitivity: Int
        get() = sp.getInt(KEY_SENSITIVITY, CameraPresence.DEFAULT_SENSITIVITY).coerceIn(1, 10)
        set(v) { sp.edit().putInt(KEY_SENSITIVITY, v.coerceIn(1, 10)).apply() }

    /**
     * The stream's sign-in, or null until both parts are set. Only the person sets it, and no surface
     * shows the password again (maintainer, 2026-11-15): the panel never makes one it couldn't show.
     */
    var signIn: Pair<String, String>?
        get() {
            val user = sp.getString(KEY_STREAM_USER, null)?.takeIf { it.isNotBlank() }
            val pass = sp.getString(KEY_STREAM_PASSWORD, null)?.takeIf { it.isNotEmpty() }
            return if (user != null && pass != null) user to pass else null
        }
        set(v) { sp.edit().putString(KEY_STREAM_USER, v?.first).putString(KEY_STREAM_PASSWORD, v?.second).commit() }

    val policy: CameraPolicy get() = CameraPolicy(privacy, use, onvif)

    companion object {
        const val KEY_ENABLED = "camera_enabled"
        const val KEY_USE = "camera_use"
        const val KEY_PRIVACY = "camera_privacy"
        const val KEY_ONVIF = "onvif_stream"
        const val KEY_SENSITIVITY = "camera_sensitivity"
        const val KEY_STREAM_USER = "camera_stream_user"
        const val KEY_STREAM_PASSWORD = "camera_stream_password"

        /** The user name the sign-in sheet starts with while none is set: easy to type into Home Assistant or Protect. */
        const val DEFAULT_USER = "glassworks"

        /** The keys the running pane reacts to. */
        val KEYS = setOf(KEY_USE, KEY_PRIVACY, KEY_ONVIF, KEY_SENSITIVITY, KEY_STREAM_USER, KEY_STREAM_PASSWORD)

        /**
         * What [KEY_USE] holds, as first written: panels keep their choice. Never shown: people read
         * [USE_PRESENCE] and [USE_CAMERA] (the camera use was renamed on 2026-11-14, its stored value wasn't).
         */
        const val STORED_PRESENCE = "Presence only"
        const val STORED_CAMERA = "Presence + snapshots"

        /** The use's names, as the cards, setup and Home Assistant's `camera_use` select show them. */
        const val USE_PRESENCE = "Presence only"
        const val USE_CAMERA = "Presence + camera"

        /** The second card's line. */
        const val USE_CAMERA_LINE = "Snapshots for Home Assistant, and the stream for Protect or Frigate if you turn it on."
    }
}
