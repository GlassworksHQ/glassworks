package dev.glassworks.s1.frame

import dev.glassworks.frame.FrameRelease
import dev.glassworks.frame.RadioSetting
import dev.glassworks.frame.RadioStatus
import dev.glassworks.s1.radio.AqaraRadio
import dev.glassworks.s1.radio.RadioHelper
import dev.glassworks.radio.RadioMode
import dev.glassworks.radio.RadioNames
import dev.glassworks.update.UpdateHelper

/**
 * Every name the S1 app uses for its root side, in one of two sets (#549, #139): [OLD], the 0.4.3 boot image's
 * `s1kiosk` names, and [LOADER], the frame loader image's Glassworks names. [of] picks one from
 * `ro.glassworks.frame.id`, which only the loader sets. This is the app's one choice between the two; the panes ask
 * the agent and never see either. Remove [OLD] the release after panels have the loader (no-legacy-compat).
 */
class S1Names(
    val radio: RadioNames,
    val bundleService: String,
    val bundleStatus: String,
    /** Where the app stages a bundle, in its files dir. */
    val bundleStage: String,
    /** The root-owned state file of the installed bundle. */
    val bundleState: String,
    /** The manifest's first line of a bundle this image's installer takes. */
    val bundleFormat: String,
    /** This boot's sign that the bundle installer is in the boot image: a property and its value. */
    val bundleMarker: Pair<String, String>,
    val updateService: String,
    val updateStatus: String,
    val updateVersion: String,
    val timeZone: String,
    val timeZoneStatus: String,
    val portalStatus: String,
    val hotspotNameStatus: String,
    val bluetoothFile: String,
    /** What the Manage page's diagnostics list under "Root side". */
    val diagnostics: List<String>,
) {
    companion object {
        /** The S1's frame id, as the loader sets it in [FrameRelease.ID_PROP]. */
        const val FRAME_ID = "aqara-s1-plus"

        /** [LOADER] on an image whose loader set the frame id, else [OLD]. [prop] reads a system property. */
        fun of(prop: (String) -> String): S1Names = if (prop(FrameRelease.ID_PROP).trim() == FRAME_ID) LOADER else OLD

        /** Boot images up to 0.4.3: the root helper `s1kiosk_radio`, contract 4. */
        val OLD = S1Names(
            radio = RadioHelper.NAMES,
            bundleService = RadioHelper.BUNDLE_SERVICE,
            bundleStatus = RadioHelper.PROP_BUNDLE_STATUS,
            bundleStage = RadioHelper.BUNDLE_STAGE_DIR,
            bundleState = RadioHelper.BUNDLE_STATE_FILE,
            bundleFormat = "S1RADIO-BUNDLE 1",
            bundleMarker = RadioHelper.PROP_BUNDLE_LOADER to "1",
            updateService = UpdateHelper.SERVICE,
            updateStatus = UpdateHelper.PROP_STATUS,
            updateVersion = UpdateHelper.PROP_VERSION,
            timeZone = "persist.s1kiosk.timezone",
            timeZoneStatus = "sys.s1kiosk.timezone.status",
            portalStatus = "sys.s1kiosk.portal.status",
            hotspotNameStatus = "sys.s1kiosk.hotspot_name.status",
            bluetoothFile = "/data/s1kiosk-pub/bluetooth-address",
            diagnostics = listOf(
                "sys.s1kiosk.radio.status", "sys.s1kiosk.radio.version", "sys.s1kiosk.radio.fw", "sys.s1kiosk.net.status",
                "sys.s1kiosk.update.version", "sys.s1kiosk.timezone.status", "sys.s1kiosk.portal.status", "persist.s1kiosk.radio_mode",
                "persist.s1kiosk.radio_zigbee",
            ),
        )

        /**
         * The frame loader image (S1 frame 1.0.0, #549): the frame agent `glassworks_agent`, contract 5
         * (frames/aqara-s1-plus/docs/radio.md, "The frame agent, contract v5"). The app's own files keep their paths.
         */
        val LOADER = S1Names(
            radio = RadioNames(
                service = "glassworks_agent",
                version = "sys.glassworks.agent.version",
                status = mapOf(
                    RadioStatus.MODE to "sys.glassworks.radio.status",
                    RadioStatus.ALLOWED to "sys.glassworks.radio.allowed",
                    RadioStatus.PEER to "sys.glassworks.radio.peer",
                    RadioStatus.THREAD_STORED to "sys.glassworks.radio.thread_stored",
                    RadioStatus.THREAD_LEAVE to "sys.glassworks.radio.thread_leave",
                    RadioStatus.ZIGBEE to "sys.glassworks.radio.zigbee",
                    RadioStatus.FIRMWARE to "sys.glassworks.radio.fw",
                    RadioStatus.FIRMWARE_TARGETS to "sys.glassworks.radio.fw_targets",
                    RadioStatus.SOURCE to "sys.glassworks.agent.source",
                    RadioStatus.BUNDLE to "sys.glassworks.bundle.state",
                ),
                settings = mapOf(
                    RadioSetting.MODE to "persist.glassworks.radio_mode",
                    RadioSetting.ZIGBEE_STACK to "persist.glassworks.radio_zigbee",
                    RadioSetting.ALLOW to "persist.glassworks.radio_allow",
                ),
                files = RadioHelper.NAMES.files,
                vendorStopOrder = AqaraRadio.STOP_ORDER,
                vendorWanted = { id -> AqaraRadio.servicesFor(RadioMode.fromId(id) ?: RadioMode.OFF) },
            ),
            bundleService = "glassworks_bundle",
            bundleStatus = "sys.glassworks.bundle.status",
            bundleStage = "frame/bundle-in",
            bundleState = "/data/glassworks-pub/frame-bundle",
            bundleFormat = "GLASSWORKS-BUNDLE 1",
            bundleMarker = FrameRelease.ID_PROP to FRAME_ID,
            updateService = "glassworks_update",
            updateStatus = "sys.glassworks.update.status",
            updateVersion = "sys.glassworks.update.version",
            timeZone = "persist.glassworks.timezone",
            timeZoneStatus = "sys.glassworks.timezone.status",
            portalStatus = "sys.glassworks.portal.status",
            hotspotNameStatus = "sys.glassworks.hotspot_name.status",
            bluetoothFile = "/data/glassworks-pub/bluetooth-address",
            diagnostics = listOf(
                "sys.glassworks.radio.status", "sys.glassworks.agent.version", "sys.glassworks.radio.fw", "sys.glassworks.net.status",
                "sys.glassworks.update.version", "sys.glassworks.timezone.status", "sys.glassworks.portal.status", "persist.glassworks.radio_mode",
                "persist.glassworks.radio_zigbee", "ro.glassworks.frame.version", "sys.glassworks.bundle.state", "sys.glassworks.adb.status",
            ),
        )
    }
}
