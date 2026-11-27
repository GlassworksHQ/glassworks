/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.util.Log
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.BinarySensorEntity
import dev.glassworks.esphome.EntityCategory
import dev.glassworks.esphome.SelectEntity
import dev.glassworks.esphome.SwitchEntity
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The camera pane (#117): presence from the camera, the camera in Home Assistant, and the opt-in ONVIF
 * stream. Runs only on a device with a camera ([CameraDefinition]).
 *
 * What runs follows [CameraPlan], made again from the settings whenever one changes and every
 * [CHECK_S] seconds (Android's permission and the camera can change without telling the pane). Each
 * pass converges on the plan, so a pass that changes nothing does nothing.
 *
 * [hardware] is the camera ([CameraHub] on a device), [stream] the ports ([CameraStream]). [permitted]
 * says whether Android allows the camera, [ownSensor] whether the frame's presence sensor is on this
 * device, and [toScreen] gives the screen policy a reading (main thread).
 */
class CameraPane(
    private val ctx: PaneContext,
    private val prefs: CameraPrefs,
    private val hardware: FrameSource,
    private val stills: StillCamera,
    private val stream: StreamOutput,
    val egress: FrameEgress,
    private val permitted: () -> Boolean,
    private val ownSensor: () -> Boolean,
    private val toScreen: (near: Boolean, raw: Float) -> Unit,
    private val exec: ScheduledExecutorService,
    /** Called when nothing may use the camera any more (privacy, no permission, the pane stopping): [CameraHub.idle]. */
    private val release: () -> Unit = {},
    /** Which camera and its rate, for the page: [CameraHub]. */
    private val facts: CameraFacts? = null,
    private val presenceFactory: (FrameSource, onMotion: (Boolean) -> Unit, onProblem: (String) -> Unit) -> CameraPresence =
        { source, onMotion, onProblem -> CameraPresence(source, { prefs.sensitivity }, onProblem = onProblem, onMotion = onMotion) },
) : Pane, CameraReadout {
    override val id = ID

    @Volatile var plan = CameraPlan.NOTHING
        private set
    /** Why the camera isn't doing what its settings ask, in words for the person at the panel; null when it is. */
    @Volatile var problem: String? = null
        private set
    @Volatile private var near: Boolean? = null
    /** When [near] last changed (time of day), for the page. */
    @Volatile private var nearSince: Long? = null
    @Volatile private var streaming = false

    private var presence: CameraPresence? = null
    private var check: ScheduledFuture<*>? = null
    /** Between [start] and [stop]: a periodic check that was already waiting when [stop] ran does nothing. */
    @Volatile private var running = false

    /**
     * The policy a still is judged by now: the settings, but as privacy while the pane isn't running or
     * Android doesn't allow the camera. The camera entity and the ONVIF snapshot both use it; they are
     * asked on their own threads, also while the pane is off.
     */
    fun stillPolicy(): CameraPolicy = if (running && permitted()) policy else policy.copy(privacy = true)

    private val policy get() = prefs.policy

    /** What the camera's page shows ([CameraPage]), read in one go. Any thread. */
    override fun reading(): CameraReading {
        val p = policy
        val open = streaming
        return CameraReading(
            use = p.use, privacy = p.privacy, onvif = p.onvif && p.stillsMayLeave, signedIn = prefs.signIn != null,
            running = running, problem = problem, detecting = plan.detect, near = near, nearSince = nearSince,
            leaving = egress.leaving, lastStill = egress.lastStillAt, streaming = open,
            watchers = if (open) stream.watchers() else emptyList(),
            // 0 only without facts; null from the camera means its own rate (streaming with no 15 fps range).
            lens = facts?.lens, frameRate = if (facts == null) 0 else facts.frameRate(),
            streamFit = stream.fit(),
        )
    }

    override fun address(): StreamAddress? = stream.address()

    override fun entities(): List<ApiEntity> = listOf(
        snapshotCamera(stills, ::stillPolicy, egress),
        BinarySensorEntity("camera_presence", "Camera presence", "mdi:motion-sensor", deviceClass = "occupancy") { near },
        // authOnly (#847): a LAN client on a panel with no ESPHome key yet mustn't turn the camera's privacy off.
        SwitchEntity("camera_privacy", "Camera privacy", "mdi:camera-off", get = { prefs.privacy }, set = { prefs.privacy = it })
            .apply { authOnly = true },
        // authOnly, as privacy and the stream: a LAN client on a panel with no ESPHome key yet mustn't turn snapshots on.
        SelectEntity(
            "camera_use", "Camera use", "mdi:cctv", disabledByDefault = true,
            options = listOf(CameraPrefs.USE_PRESENCE, CameraPrefs.USE_CAMERA),
            get = { if (prefs.use == CameraUse.PRESENCE) CameraPrefs.USE_PRESENCE else CameraPrefs.USE_CAMERA },
            // Home Assistant reads these options at every connect (an update reconnects), so it never sends the old name.
            set = { prefs.use = if (it == CameraPrefs.USE_CAMERA) CameraUse.PRESENCE_AND_SNAPSHOTS else CameraUse.PRESENCE },
        ).apply { authOnly = true },
        SwitchEntity(
            "onvif_stream", "ONVIF stream", "mdi:video-wireless", EntityCategory.CONFIG, disabledByDefault = true,
            // Refused in Presence only (the approved design), and on a camera that can't stream: the switch springs back.
            get = { prefs.onvif && streamMayTurnOn() },
            set = { on -> prefs.onvif = on && streamMayTurnOn(); ctx.publishStates() },
        ).apply { authOnly = true },
        sensitivityEntity({ prefs.sensitivity }, { prefs.sensitivity = it }),
    )

    @Synchronized override fun start() {
        running = true
        reconcile()
        check = exec.scheduleWithFixedDelay({ reconcile() }, CHECK_S, CHECK_S, TimeUnit.SECONDS)
    }

    @Synchronized override fun stop() {
        running = false
        check?.cancel(false); check = null
        apply(CameraPlan.NOTHING, null)
        release()
        near = null
        nearSince = null
        problem = null
    }

    override fun onEvent(event: PanelEvent) {
        if (event is PanelEvent.SettingChanged && event.key in CameraPrefs.KEYS) reconcile()
    }

    /** Converges on the plan for the settings and the device as they are now. */
    @Synchronized fun reconcile() {
        if (!running) return
        val allowed = permitted()
        // The stream never runs without a sign-in: turned on without one, it waits until the person sets one.
        val signIn = prefs.signIn
        val next = CameraPlan.of(policy, allowed, ownSensor(), signIn != null, stream.fit() is StreamFit.Size)
        if (!allowed && !policy.privacy) problem = NOT_ALLOWED
        else if (problem == NOT_ALLOWED) problem = null
        apply(next, signIn)
        if (!allowed || policy.privacy) release() // a still's linger mustn't keep the camera open
    }

    /** Internal for a test: its own guard keeps the stream off without a sign-in, whatever [next] says. */
    @Synchronized internal fun apply(next: CameraPlan, signIn: Pair<String, String>?) {
        if (next.detect && presence == null) {
            presence = presenceFactory(hardware, ::onMotion) { why -> problem = why; log("presence: $why") }.also { p ->
                p.start(ctx.context) { isNear, raw -> onReading(isNear, raw) }
            }
        } else if (!next.detect && presence != null) {
            presence?.stop(ctx.context); presence = null
            near = null
            nearSince = null
            if (plan.feedScreen) toScreen(false, -1f)
        }
        if (next.stream && signIn != null) {
            streaming = stream.start(signIn)
            if (!streaming) problem = STREAM_FAILED else if (problem == STREAM_FAILED) problem = null
        } else if (streaming) {
            stream.stop(); streaming = false
            if (problem == STREAM_FAILED) problem = null
        }
        if (next != plan) log("plan: detect=${next.detect} screen=${next.feedScreen} stream=${next.stream}")
        plan = next
        ctx.publishStates()
    }

    private fun streamMayTurnOn() = policy.stillsMayLeave && stream.fit() is StreamFit.Size

    private fun onReading(isNear: Boolean, raw: Float) {
        if (raw >= 0f && problem != null && problem != NOT_ALLOWED && problem != STREAM_FAILED) problem = null
        val changed = near != isNear
        if (changed) nearSince = System.currentTimeMillis()
        near = isNear
        if (plan.feedScreen) toScreen(isNear, raw)
        if (changed) ctx.publishStates()
    }

    private fun onMotion(active: Boolean) {
        if (streaming) stream.setMotion(active)
    }

    private fun log(s: String) = Log.i(TAG, s)

    companion object {
        const val ID = "camera"
        private const val TAG = "Glassworks.Camera"
        const val CHECK_S = 10L
        const val NOT_ALLOWED = "Android hasn't allowed the camera"
        const val STREAM_FAILED = "The stream couldn't open its ports"
    }
}
