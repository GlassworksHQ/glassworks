/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.view.View
import org.robolectric.util.ReflectionHelpers

/**
 * What a shown window does on its first pass and Robolectric doesn't: its views read it as VISIBLE (a view attached
 * later sees it on attach) and get `onWindowVisibilityChanged(VISIBLE)`. Robolectric leaves an activity's window at
 * GONE without a callback, so code that waits for a visible window (a live camera) would never start.
 */
internal fun showWindow(root: View) {
    val info = ReflectionHelpers.getField<Any?>(root, "mAttachInfo") ?: return
    ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE)
    root.dispatchWindowVisibilityChanged(View.VISIBLE)
}

/** The window goes away (the screen off, the app in the background), as [showWindow] shows it. */
internal fun hideWindow(root: View) {
    val info = ReflectionHelpers.getField<Any?>(root, "mAttachInfo") ?: return
    ReflectionHelpers.setField(info, "mWindowVisibility", View.GONE)
    root.dispatchWindowVisibilityChanged(View.GONE)
}
