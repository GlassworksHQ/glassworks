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
class DeviceFrame(
    val id: String,
    val brand: String,
    val modelName: String,
    private val mark: (prop: (String) -> String) -> Boolean,
    /** The frame's own release line (#139) on a device with these system properties, or null when it has none. */
    val release: (prop: (String) -> String) -> FrameRelease? = { null },
    /** frame.json's `camera.maxPixels` ([Frame.cameraMaxPixels]), or null when it sets none. */
    val cameraMaxPixels: Int? = null,
    /** frame.json's `camera.sensorDegrees` ([Frame.cameraSensorDegrees]), or null when it sets none. */
    val cameraSensorDegrees: Int? = null,
    /** How the frame's screen stands ([Frame.orientation]), or null to turn as Android says ([Orientation.ANY]). */
    val orientation: Orientation? = null,
    /** How the frame's screen sleeps ([Frame.sleepMode]). */
    val sleepMode: SleepMode = SleepMode.LOCK,
) {
    /**
     * frame.json's `name`: [brand], a space, then [modelName], the product's model as Home Assistant's device info gives
     * it. Not frame.json's `model`, which is the 3D body.
     */
    val name: String get() = "$brand $modelName"

    companion object {
        /**
         * The original lululemon Mirror. Its `boot` partition is signed and locked, so every Mirror keeps lululemon's
         * boot image, whatever `system` it runs; that image's fingerprint starts `mirror/mirror/` (read on the unit,
         * frames/lululemon-mirror/research/mirror-root.md). `ro.product.model` ("MSM8916 for arm64") and
         * `ro.build.fingerprint` (`Android/msm8916_64/…`) come from the community `system` image and name the
         * Qualcomm reference board, which other MSM8916 devices share.
         */
        val LULULEMON_MIRROR = DeviceFrame(
            "lululemon-mirror", "lululemon", "Studio Mirror",
            mark = { prop -> prop("ro.board.platform") == "msm8916" && prop("ro.bootimage.build.fingerprint").startsWith("mirror/mirror/") },
            // Kiln stamps the version into build.prop at flash time. Nothing installs on the Mirror from Home Assistant.
            release = { prop ->
                FrameRelease("lululemon-mirror", FrameRelease.stamp("lululemon-mirror", prop), GLASS_PANES[prop("ro.build.fingerprint")], MIRROR_INSTALL)
            },
            // 720p: about 950 MB of RAM, and the page's process was killed while the camera ran at 1080p (#679).
            cameraMaxPixels = 1280 * 720,
            // Its camera says 0 degrees, but with the screen at rotation 3 that turned every still upside down: the
            // sensor sits at 180 (frames/lululemon-mirror/research/camera-stock.md).
            cameraSensorDegrees = 180,
            // Upright is rotation 3 (frames/lululemon-mirror/research/image-patch.md). Kiln's first boot locks it, but
            // Android 6 turns auto-rotate on and rotation to 0 on every update of the app (#686), so the app asks
            // for portrait itself.
            orientation = Orientation.PORTRAIT_FIXED,
            // Android's display off leaves the TC358775 LVDS bridge out of sync, with lines across the screen until
            // the power is cut; lululemon's own app only ever turns the backlight to 0
            // (frames/lululemon-mirror/research/display-sleep-lines.md).
            sleepMode = SleepMode.BACKLIGHT_OFF,
        )

        /**
         * The Glass Panes images by their `ro.build.fingerprint`, for a Mirror flashed before Kiln stamped the frame
         * version (frames/lululemon-mirror/research/image-patch.md has both).
         */
        val GLASS_PANES = mapOf(
            "Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11041605:userdebug/test-keys" to "Glass Panes image 1",
            "Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11120023:userdebug/test-keys" to "Glass Panes image 2",
            "Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11212248:userdebug/test-keys" to "Glass Panes image 3",
        )

        const val MIRROR_INSTALL = "To install it: flash the device again with Kiln, at ${FrameRelease.KILN}"

        val ALL: List<DeviceFrame> = listOf(LULULEMON_MIRROR)

        /** The device frame whose mark this device carries, by its system properties ([prop]); null for any other device. */
        fun of(prop: (String) -> String): DeviceFrame? = ALL.firstOrNull { it.mark(prop) }

        /** An Android system property, "" when it isn't set or can't be read. */
        fun systemProperty(name: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
        }.getOrNull().orEmpty()
    }
}
