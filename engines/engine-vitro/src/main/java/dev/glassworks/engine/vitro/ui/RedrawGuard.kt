/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.view.View

/**
 * Whether [guard] (a [CardSurface] or a [ContainedCard]) is the card guard nearest to [target], the view that
 * changed: no other guard lies between them, [target] included.
 *
 * With hardware drawing, Android rebuilds only the display list of a view that changed, straight from its parent's
 * list, without running any parent's draw or `drawChild`, where the guards catch a throw. So the nearest guard asks to
 * record itself again ([View.invalidate]): its draw then runs the changed view's inside the guard's catch. The
 * guard's other views keep their lists, so the cost is the guard's own small list (its background and one entry per
 * child). A guard further up leaves it to the nearer one, so a change in a card inside a stack-in-card re-records that
 * card only, never the stack round it.
 */
internal fun isNearestGuard(guard: View, target: View): Boolean {
    var v: View? = target
    while (v != null && v !== guard) {
        if (v is CardSurface || v is ContainedCard) return false
        v = v.parent as? View
    }
    return v === guard
}
