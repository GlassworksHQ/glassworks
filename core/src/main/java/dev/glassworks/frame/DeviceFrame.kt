/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

/**
 * A device frame (frames/README.md, kind `device`) that runs the generic app and is one product, which the app
 * recognises by a [mark] the device carries. On one, [GenericAndroid] goes by the frame instead of its ROM's
 * `Build` values: frame.json's `id` ([id]) and `name` ([name]) for Home Assistant, its `brand` ([brand]) as the maker.
 * A mark must never match another device: a wrong name is worse than the ROM's. `DeviceFrameTest` checks each against
 * its frame.json.
 */
class DeviceFrame(val id: String, val brand: String, val model: String, private val mark: (prop: (String) -> String) -> Boolean) {
    /** frame.json's `name`: [brand], a space, then [model]. */
    val name: String get() = "$brand $model"

    companion object {
        /**
         * The original lululemon Mirror. Its `boot` partition is signed and locked, so every Mirror keeps lululemon's
         * boot image, whatever `system` it runs; that image's fingerprint starts `mirror/mirror/` (read on the unit,
         * frames/lululemon-mirror/research/mirror-root.md). `ro.product.model` ("MSM8916 for arm64") and
         * `ro.build.fingerprint` (`Android/msm8916_64/…`) come from the community `system` image and name the
         * Qualcomm reference board, which other MSM8916 devices share.
         */
        val LULULEMON_MIRROR = DeviceFrame("lululemon-mirror", "lululemon", "Studio Mirror") { prop ->
            prop("ro.board.platform") == "msm8916" && prop("ro.bootimage.build.fingerprint").startsWith("mirror/mirror/")
        }

        val ALL: List<DeviceFrame> = listOf(LULULEMON_MIRROR)

        /** The device frame whose mark this device carries, by its system properties ([prop]); null for any other device. */
        fun of(prop: (String) -> String): DeviceFrame? = ALL.firstOrNull { it.mark(prop) }

        /** An Android system property, "" when it isn't set or can't be read. */
        fun systemProperty(name: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
        }.getOrNull().orEmpty()
    }
}
