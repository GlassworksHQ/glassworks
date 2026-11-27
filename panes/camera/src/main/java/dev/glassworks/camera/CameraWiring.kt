/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.camera.CameraHub
import dev.glassworks.camera.CameraIdentity
import dev.glassworks.camera.CameraPane
import dev.glassworks.camera.CameraStream
import dev.glassworks.camera.CameraUsePickers
import dev.glassworks.camera.FrameEgress
import dev.glassworks.camera.stillGate
import dev.glassworks.esphome.EspHomeLog
import dev.glassworks.frame.Input
import dev.glassworks.Glassworks
import dev.glassworks.PaneOverlay
import dev.glassworks.device.DeviceHost

/**
 * The camera pane (#117) as an app builds it: its definition, the pane for the device, and the privacy dot. The S1 app
 * (S1Panes) and the generic app (GenericParts) both build it here, so the two can't drift apart. In the shell's
 * package: it builds on the app's runtime and device host, which the pane's own code (dev.glassworks) never names.
 */
object CameraWiring {

    fun granted(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** The definition, with its cards and Android's permission. On a frame without touch its setup step never waits. */
    fun definition(context: Context, glassworks: Glassworks, file: SharedPreferences): CameraDefinition = CameraDefinition(
        file,
        granted = { granted(context) },
        pickers = CameraUsePickers { c -> CameraUseCards(c) },
        touch = { glassworks.frame.input == Input.TOUCH },
    )

    /** The pane, on a device with a camera only: elsewhere its definition is CantRun (#23), and this finds none. */
    fun pane(glassworks: Glassworks, host: DeviceHost): CameraPane? {
        val paneContext = host.paneContext
        val ctx = paneContext.context
        val identity = paneContext.device
        val main = Handler(Looper.getMainLooper())
        val def = glassworks.paneDefinitions(ctx).filterIsInstance<CameraDefinition>().firstOrNull() ?: return null
        val hub = CameraHub(
            ctx,
            maxPixels = glassworks.frame.cameraMaxPixels ?: CameraHub.MAX_PIXELS,
            frameSensorDegrees = glassworks.frame.cameraSensorDegrees,
        )
        host.onStop { hub.release() }
        val egress = FrameEgress(SystemClock::elapsedRealtime, { ms, r -> main.postDelayed(r, ms) }) { leaving ->
            EspHomeLog.i("Camera frames ${if (leaving) "leaving the panel" else "no longer leaving"}")
            glassworks.cameraLeaving = leaving
            main.post { glassworks.onCameraLeaving?.invoke() }
        }
        var pane: CameraPane? = null
        // The pane's own judgement: no still while it is off or Android doesn't allow the camera.
        val gate = stillGate(hub, { pane?.stillPolicy() ?: def.prefs.policy.copy(privacy = true) }, egress)
        val stream = CameraStream(
            ctx, hub, egress,
            identity = { CameraIdentity(identity.friendlyName, "Glassworks ${glassworks.build.versionName}", identity.macAddress) },
            host = host::ipAddress,
            snapshot = gate,
        )
        return CameraPane(
            paneContext, def.prefs, hub, hub, stream, egress,
            permitted = { granted(ctx) },
            ownSensor = { glassworks.presence.ownSensor(ctx) },
            toScreen = glassworks.presence::offer,
            exec = host.exec,
            release = hub::idle,
            facts = hub,
        ).also { pane = it }
    }

    /** The privacy dot, drawn above the menus: it follows images leaving the panel, on the main thread. */
    fun dot(glassworks: Glassworks, activity: Activity): PaneOverlay {
        val dot = CameraDot(activity)
        glassworks.onCameraLeaving = { dot.show(glassworks.cameraLeaving) }
        dot.show(glassworks.cameraLeaving)
        return object : PaneOverlay {
            override val view = dot
            override val layoutParams = dot.layoutParams()
            override val aboveMenus = true
            override fun destroy() { glassworks.onCameraLeaving = null }
        }
    }
}
