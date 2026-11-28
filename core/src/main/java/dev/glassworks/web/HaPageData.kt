/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import android.content.Context
import java.io.File

/**
 * What the app keeps from Home Assistant's pages outside the browser engine's own profile, so the factory reset can
 * take it all (#33). Vitro made each of these, and they stay on disk after Vitro is turned off:
 * - the copy of HA's `localStorage` ([HaPageStorage]);
 * - the island server's port ([ISLAND_PREFS]): forgetting it gives the islands a new origin, so whatever cards kept in
 *   the old one's storage can't be read by the next Home Assistant's cards;
 * - HA's frontend strings, cached per frontend version and language ([FRONTEND_CACHE], in the app's cache folder).
 */
object HaPageData {
    /** Vitro's island server keeps its port here (VitroEngine). */
    const val ISLAND_PREFS = "vitro_island"

    /** Vitro's cache of HA's frontend strings, under the app's cache folder (VitroEngine, HaFrontend). */
    const val FRONTEND_CACHE = "vitro-frontend"

    /** Removes all of it. False when a file stayed. */
    fun forget(context: Context): Boolean {
        val app = context.applicationContext
        val storage = HaPageStorage.of(app).clear()
        val island = app.getSharedPreferences(ISLAND_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val cache = File(app.cacheDir, FRONTEND_CACHE).let { !it.exists() || it.deleteRecursively() }
        return storage && island && cache
    }
}
