/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import dev.glassworks.bluetoothproxy.ProxyPhase.BLUETOOTH_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.LISTENING
import dev.glassworks.bluetoothproxy.ProxyPhase.LOCATION_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.NEEDS_KEY
import dev.glassworks.bluetoothproxy.ProxyPhase.NO_BLE
import dev.glassworks.bluetoothproxy.ProxyPhase.NO_PERMISSION
import dev.glassworks.bluetoothproxy.ProxyPhase.OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.RETRYING
import dev.glassworks.bluetoothproxy.ProxyPhase.WAITING
import dev.glassworks.esphome.bluetoothproxy.ProxyStatus
import dev.glassworks.pane.IN_HOME_ASSISTANT
import dev.glassworks.pane.SettingsItem

/**
 * The Bluetooth proxy page's words (#121's approved page), once, for the frame's own view and for its
 * typed twin on the Manage page (#269): the status line, the card when something stops it, the three
 * tiles, the mode (#246), the note while it's off, and its entities as Home Assistant shows them. Pure.
 */
object ProxyPage {

    /** The line under the heading, or "" when the page shows none (off, no Bluetooth LE). */
    fun line(phase: ProxyPhase, s: ProxyStatus?): String = when (phase) {
        LISTENING -> "Listening for Home Assistant" + (s?.devicesPerMinute?.takeIf { it > 0 }?.let { " · $it devices nearby" } ?: "")
        WAITING -> "Waiting for Home Assistant. The radio stays quiet until it listens."
        NEEDS_KEY -> NEEDS_KEY_LINE
        BLUETOOTH_OFF -> "Bluetooth is off"
        LOCATION_OFF -> "Location is off"
        NO_PERMISSION -> "A permission is missing"
        RETRYING -> "Android stopped the scan. ${retry(s)}."
        OFF, NO_BLE -> ""
    }

    fun retry(s: ProxyStatus?) = s?.retryInMs?.let { "Trying again in ${(it + 999) / 1000} s" } ?: "Trying again shortly"

    /** The card when something stops the proxy: its title, its words, and its button's, or null for none. */
    class Card(val title: String, val text: String, val action: String?)

    fun card(phase: ProxyPhase): Card? = when (phase) {
        BLUETOOTH_OFF -> Card("Turn Bluetooth on to listen", "The proxy uses the panel's own Bluetooth. Turning it on doesn't pair anything.", "Turn on Bluetooth")
        LOCATION_OFF -> Card(
            "Turn location on to listen",
            "Android hands Bluetooth results to apps only while location is on. The panel doesn't use or share its location.",
            "Turn on location",
        )
        NO_PERMISSION -> Card("The app can't scan", "It needs the location permission to hear Bluetooth devices. The installer grants it.", null)
        else -> null
    }

    /** Whether the page shows its tiles. */
    fun tiles(phase: ProxyPhase) = phase in TILES

    const val ADS = "Advertisements"
    const val ADS_UNIT = "a minute"
    const val SCANNER = "Scanner"

    /** The advertisements tile: the count a minute, and the line under it. */
    fun ads(s: ProxyStatus?): Pair<String, String> =
        (s?.advertisementsPerMinute ?: 0).toString() to if (s != null && s.devicesPerMinute > 0) "From ${s.devicesPerMinute} devices" else "None just now"

    /** The scanner tile: On or Off, and the line under it. */
    fun scanner(phase: ProxyPhase, s: ProxyStatus?): Pair<String, String> = (if (s?.scanning == true) "On" else "Off") to when (phase) {
        LISTENING -> "Listening all the time"
        WAITING, NEEDS_KEY -> "Not needed yet"
        RETRYING -> retry(s)
        else -> "Off"
    }

    const val CONNECTIONS = "Connections"

    /** The connections tile: the count, its unit, the line under it, and how full the slots are (null: no meter). */
    class Connections(val value: String, val unit: String, val line: String, val meter: Float?)

    fun connections(connect: Boolean, c: ProxyConnections?): Connections = when {
        !connect -> Connections("—", "", "None in listen only", null)
        c == null -> Connections("0", "of $SLOTS", "None in use", 0f)
        else -> Connections(c.inUse.toString(), "of ${c.limit}", if (c.inUse > 0) "In use now" else "None in use", c.inUse.toFloat() / c.limit)
    }

    /** Whether the page offers the mode: while the proxy is on and can run. */
    fun mode(phase: ProxyPhase) = phase != OFF && phase != NO_BLE

    const val MODE = "Mode"
    const val LISTEN = "listen"
    const val CONNECT = "connect"
    /** The two modes: id, name, and the line under it. */
    val MODES = listOf(
        Triple(LISTEN, "Listen only", "Sensors that broadcast: thermometers, plant sensors, tags."),
        Triple(CONNECT, "Listen and connect", "Also locks and devices Home Assistant has to connect to. Up to $SLOTS at once."),
    )

    /** The note while it's off or can't run, or null. */
    fun note(phase: ProxyPhase): String? = when (phase) {
        NO_BLE -> NO_BLE_NOTE
        OFF -> OFF_NOTE
        else -> null
    }

    /** Whether the page lists its entities (not without Bluetooth LE). */
    fun inHomeAssistant(phase: ProxyPhase) = phase != NO_BLE

    const val ADS_ENTITY = "Bluetooth advertisements"
    const val STATUS_ENTITY = "Bluetooth proxy status"

    /** Its two entities' states as Home Assistant shows them; [UNAVAILABLE] while it's off. */
    fun entities(phase: ProxyPhase, s: ProxyStatus?): List<Pair<String, String>> = listOf(
        ADS_ENTITY to if (phase == OFF) UNAVAILABLE else "${s?.advertisementsPerMinute ?: 0} a minute",
        STATUS_ENTITY to (phase.entityText ?: UNAVAILABLE),
    )

    /**
     * The page as typed items, read again with the page, for every surface but the panel's own view. The
     * card's button turns Bluetooth or location on, as the panel's does, or says why it couldn't. No device
     * address is ever on the page.
     */
    fun twin(model: BluetoothProxyModel): List<SettingsItem> {
        val phase = model.phase()
        val s = model.status()
        val items = ArrayList<SettingsItem>()
        line(phase, s).takeIf { it.isNotEmpty() }?.let { items += SettingsItem.Status({ line(model.phase(), model.status()) }, { false }, { model.phase() == RETRYING }) }
        card(phase)?.let { c ->
            items += if (c.action == null) SettingsItem.Info({ c.title }, { c.text })
            else SettingsItem.Action(c.title, { c.text }, c.action) { done ->
                val ok = if (phase == BLUETOOTH_OFF) model.turnBluetoothOn() else model.turnLocationOn()
                done(if (ok) null else REFUSED)
            }
        }
        if (tiles(phase)) items += SettingsItem.Tiles(3) {
            val st = model.status()
            val (n, adsLine) = ads(st)
            val (on, scanLine) = scanner(model.phase(), st)
            val c = connections(model.connect, model.connections())
            listOf(
                SettingsItem.Tile(ADS, n, ADS_UNIT, adsLine, history = st?.lastHour?.map { it.toFloat() }),
                SettingsItem.Tile(SCANNER, on, detail = scanLine),
                SettingsItem.Tile(CONNECTIONS, c.value, c.unit, c.line, meter = c.meter),
            )
        }
        if (mode(phase)) items += SettingsItem.Choice(
            MODE,
            value = { if (model.connect) CONNECT else LISTEN },
            options = { MODES.map { (id, name, _) -> id to name } },
        ) { id, done ->
            model.connect = id == CONNECT
            done(null)
        }
        // The panel draws both modes with their lines; the Choice's row names the picked one. These rows say
        // what each does, so a browser explains both too.
        if (mode(phase)) for ((_, name, line) in MODES) items += SettingsItem.Info({ name }, { line })
        note(phase)?.let { n -> items += SettingsItem.Note { n } }
        if (inHomeAssistant(phase)) {
            items += SettingsItem.States(IN_HOME_ASSISTANT) { entities(model.phase(), model.status()).map { (k, v) -> SettingsItem.StateRow(k, v) } }
            items += SettingsItem.Note { FOOTNOTE }
        }
        return items
    }

    private val TILES = setOf(LISTENING, WAITING, NEEDS_KEY, RETRYING)
    /** Connection slots, as the engine's `BluetoothConnectionEngine.DEFAULT_LIMIT`. */
    private const val SLOTS = dev.glassworks.esphome.bluetoothproxy.BluetoothConnectionEngine.DEFAULT_LIMIT
    const val UNAVAILABLE = "Unavailable"
    const val NEEDS_KEY_LINE = "Waiting for Home Assistant: it needs this panel's encryption key. Home Assistant 2026.11 and later sets it when it adds the panel."
    const val OFF_NOTE = "The Bluetooth proxy is off. The panel doesn't scan, and Home Assistant no longer hears devices through it."
    const val NO_BLE_NOTE = "Home Assistant can still use other Bluetooth proxies, such as an ESP32 near the sensors."
    const val REFUSED = "This panel doesn't let apps do that. Turn it on in the panel's own settings."
    const val FOOTNOTE = "Home Assistant finds the proxy by itself, under Bluetooth. Addresses go only to Home Assistant; the panel doesn't keep or log them."
}
