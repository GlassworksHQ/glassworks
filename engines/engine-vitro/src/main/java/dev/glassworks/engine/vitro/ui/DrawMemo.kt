/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

/**
 * The last text built from one number, kept until the number changes, so `onDraw` formats nothing on a redraw with
 * the same value. The key is a primitive (no boxing) and [of] is inline (its builder lambda allocates nothing).
 */
class NumberText {
    @PublishedApi internal var key = 0.0
    @PublishedApi internal var text = ""
    @PublishedApi internal var valid = false

    inline fun of(value: Double, build: (Double) -> String): String {
        if (!valid || value != key) {
            key = value
            text = build(value)
            valid = true
        }
        return text
    }

    /** Forgets the text, e.g. when something else it depends on (a unit) changed. */
    fun reset() { valid = false }
}

/** The last text built from up to two keys (strings or other references), compared by equality. */
class KeyedText {
    @PublishedApi internal var a: Any? = null
    @PublishedApi internal var b: Any? = null
    @PublishedApi internal var text = ""
    @PublishedApi internal var valid = false

    inline fun of(keyA: Any?, keyB: Any?, build: () -> String): String {
        if (!valid || keyA != a || keyB != b) {
            a = keyA
            b = keyB
            text = build()
            valid = true
        }
        return text
    }
}
