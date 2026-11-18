package dev.glassworks.manage

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.glassworks.manage.HaSignIn
import dev.glassworks.manage.LogEntry
import dev.glassworks.manage.LogcatTail
import dev.glassworks.manage.ManageHost
import dev.glassworks.manage.ManageLog
import dev.glassworks.manage.ManagePage
import dev.glassworks.manage.ManageServer
import dev.glassworks.manage.PaneRow
import dev.glassworks.manage.paneRow
import dev.glassworks.pane.CantRun
import dev.glassworks.manage.PanelActions
import dev.glassworks.manage.PanelStatus
import dev.glassworks.manage.SessionStore
import dev.glassworks.manage.Tile
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.shown
import dev.glassworks.platform.Platform
import dev.glassworks.platform.WifiLink
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import dev.glassworks.web.PageShell
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.MainActivity
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.HaGatewayClient
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.auth.WsHaSocket
import dev.glassworks.device.PanelDevice
import dev.glassworks.settings.CoreSettings
import dev.glassworks.settings.PanelBlueprints
import dev.glassworks.settings.S1Settings
import dev.glassworks.setup.SetupState
import dev.glassworks.update.UpdateManager
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.util.Locale

/**
 * An app's Manage page (#33): the core's [ManageServer] on port 8099 with the QR-code pages ([SignInServer])
 * behind it, what the page shows about this panel, and its actions. Made by [HaAuth.startServer], which owns
 * the port. Every app on the core serves this one; what only one app has (the S1's radio link and root side)
 * comes in [own] (#23).
 */
open class AppManage(
    context: Context, private val runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, private val prefs: KioskPrefs,
    /** The version the page shows ([dev.glassworks.platform.AppBuild.shown]); a test passes a test build's. */
    private val version: String = runtime.build.shown,
    private val own: Own = Own(),
) : ManagePage {

    /** What one app adds to the page. The defaults add nothing. */
    class Own(
        /** Extra links on a pane's row, by pane id: (text, path). */
        val paneLinks: (String) -> List<Pair<String, String>> = { emptyList() },
        /** Paths the QR-code pages serve beyond the dashboard's sign-in. */
        val qrPaths: Set<String> = emptySet(),
        /** Lines the diagnostics file ends with. */
        val diagnostics: StringBuilder.() -> Unit = {},
        /** The app's own log tags, in words, before [AppManage.logSource]'s. */
        val logSource: (String) -> String? = { null },
        /** The About section's licence line. */
        val licence: String = "MPL-2.0 and MIT (Open source licences has each)",
    )
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "manage")
    val log = ManageLog(File(dir, "log"))
    private val tail = LogcatTail(log, { tag -> own.logSource(tag) ?: logSource(tag) }, onError = { Log.w(TAG, it) })

    private val host = Host()

    /** The panes' Blueprints cards; the Manage page links them in a new tab. */
    private val blueprints = PanelBlueprints.forPanel(app, runtime.build, prefs)

    val server = ManageServer(
        host = host,
        haBase = { prefs.haBase },
        setUp = ::setUp,
        sessions = SessionStore(File(dir, "sessions.json")),
        code = runtime.panelCode,
        signIn = HaSignIn(HaGatewayClient(tokens) { url -> WsHaSocket.connect(url) }, warn = { Log.w(TAG, it) }),
        log = log,
        qrPaths = setOf("/signin", SignInServer.CONTINUE, "/callback") + own.qrPaths,
        qr = { req -> qr(signIn, req) },
        ownHosts = ::ownHosts,
        event = { msg -> Log.i(TAG, msg); runCatching { log.append(LogEntry(System.currentTimeMillis(), LogEntry.Level.INFO, "Manage", msg)) } },
    )

    override fun http(port: Int) = HttpServer(port, server::handle, log = { Log.d(TAG, it) })

    /** What the Manage page shows now: its Overview, and the panel menu's. */
    override fun status(): PanelStatus = host.status()

    /** The diagnostics file before redaction; tests read it. */
    fun diagnostics(): String = host.diagnostics()

    override val actions: PanelActions get() = host.actions

    override fun startLog() = tail.start()

    override fun stopLog() = tail.stop()

    private inner class Host : ManageHost {
        override val actions: PanelActions = Actions()

        override val name = PageShell.PRODUCT_NAME

        /** The model [PanelDevice] gives Home Assistant (its `DeviceIdentity.model`); `ManageModelPinTest` keeps the two the same. */
        override val model: String get() = deviceModel(runtime.frame.hardware.model, runtime.build.product)

        override fun status(): PanelStatus {
            val defs = runtime.paneDefinitions(app)
            // The panel menu's own model (#242): the same sections, items and saves.
            val model = S1Settings.model(runtime, prefs, defs, S1Settings.hasDeviceSettings(runtime, app), fullScreen = ::fullScreen, version = version,
                // Links in a new tab here: nothing opens on the panel.
                blueprints = { id -> blueprints.item(id) {} }, context = app)
            val panes = model.sections.filter { it.id in model.panes }
            return PanelStatus(
                name = name,
                frame = model.frame,
                version = version,
                // The app's, as the line it sits on is the app's: the device's is in the diagnostics (#263).
                uptimeMs = Platform.processStartElapsedMs()?.let { SystemClock.elapsedRealtime() - it } ?: 0L,
                haBase = prefs.haBase,
                tiles = listOf(haTile(), wifiTile(), engineTile(), memoryTile()),
                // The panes the panel's rail lists, no more: one the frame can't run has no section, so it isn't shown (#23).
                panes = panes.map { paneRow(it.id, it, own.paneLinks(it.id)) },
                sections = model.sections,
                // After the About section's own items (the version, the licences).
                about = listOf(
                    "Frame" to runtime.frame.hardware.let { "${it.manufacturer} ${it.model}" },
                    "Android" to Build.VERSION.RELEASE,
                    "Engine" to runtime.engine.name.ifEmpty { "Unknown" },
                    "Licence" to own.licence,
                    "Source" to "github.com/GlassworksHQ/glassworks",
                ),
                legacy = runtime.engine.lockedDown,
            )
        }

        /** On the main thread, as the panel's menu changes settings. */
        override fun onMain(f: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) f() else Handler(Looper.getMainLooper()).post(f)
        }

        override fun diagnostics(): String = buildString {
            val s = status()
            appendLine("Version: ${runtime.build.product} ${s.version} (${runtime.build.versionCode})")
            appendLine("Frame: ${runtime.frame.board}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}")
            appendLine("Device up: ${SystemClock.elapsedRealtime() / 1000} s; app up ${s.uptimeMs / 1000} s")
            appendLine("Home Assistant: ${s.haBase ?: "none"}; panel signed in: ${HaAuth.signedIn}${if (HaAuth.movedAway) " (moved)" else ""}; added: ${prefs.haSeen}")
            for (t in s.tiles) appendLine("${t.label}: ${t.value} (${t.detail})")
            appendLine()
            appendLine("Panes:")
            for (p in s.panes) appendLine("  ${p.id}: ${p.state.kind} ${p.state.detail}")
            // Not shown anywhere, so say here why each is missing.
            for (d in runtime.paneDefinitions(app).filterIsInstance<CantRun>()) {
                appendLine("  ${d.id}: HIDDEN, the frame lacks ${(d.requires - runtime.frame.provides).joinToString { it.id }}")
            }
            appendLine()
            appendLine("Settings:")
            for (sec in s.sections) {
                appendLine("  [${sec.id}] ${sec.title}")
                for (line in itemLines(sec.items)) appendLine("    $line")
            }
            own.diagnostics(this)
        }
    }

    /** Full screen changed: the running dashboard follows, as the adb extras make it ([MainActivity] pushes its config). */
    private fun fullScreen() {
        runtime.commandSink?.invoke(Intent().putExtra("hide_header", prefs.hideHeader).putExtra("hide_sidebar", prefs.hideSidebar))
    }

    /** A section's items as the diagnostics file lists them. */
    fun itemLines(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        fun v(f: () -> Any?) = runCatching { f()?.toString().orEmpty() }.getOrDefault("?")
        when (i) {
            is SettingsItem.Text -> listOf("${i.title}: ${v(i.value)}")
            is SettingsItem.Switch -> listOf("${i.title}: ${v(i.value)}")
            is SettingsItem.Slider -> listOf("${i.title}: ${v { i.shown(i.value()) }}")
            is SettingsItem.Duration -> listOf("${i.title}: ${v { i.shown(i.value()) }}")
            is SettingsItem.TimeRange -> listOf("${i.title}: ${v { i.shown(i.value()) }}")
            // Who the dashboard is signed in as stays on the screens: the file says whether it is set.
            is SettingsItem.Info -> if (v(i.title) == CoreSettings.ACCOUNT_TITLE) listOf("${CoreSettings.ACCOUNT_TITLE}: set")
                else listOf("${v(i.title)}: ${v(i.detail)}")
            is SettingsItem.Note -> listOf(v(i.text))
            is SettingsItem.Group -> listOf("${i.title}:") + itemLines(i.items).map { "  $it" }
            is SettingsItem.Choice -> listOf("${i.title}: ${v(i.value)}")
            // Its password is shown on request only (Show), never here: whether one is set.
            is SettingsItem.SignIn -> listOf("${i.title}: ${v { i.shown(i.isSet()) }}")
            is SettingsItem.Status -> listOf(v(i.text))
            is SettingsItem.States -> listOf("${i.title}:") + runCatching { i.shown() }.getOrNull()
                ?.let { s -> listOfNotNull(s.note?.let { "  $it" }) + s.rows.map { "  ${it.name}: ${it.value}" } }.orEmpty()
            is SettingsItem.Tiles -> runCatching { i.tiles() }.getOrDefault(emptyList()).map { "${it.name}: ${it.value} ${it.unit}".trim() }
            // What each button does now, without its links (they carry the Home Assistant address).
            is SettingsItem.Blueprints -> listOf("${dev.glassworks.pane.BLUEPRINTS}:") + runCatching { i.card(dev.glassworks.pane.BlueprintSurface.PANEL) }.getOrNull()
                ?.let { c -> listOfNotNull(c.note?.let { "  $it" }) + c.rows.map { (bp, a) -> "  ${bp.name}: ${a::class.simpleName}" } }.orEmpty()
            is SettingsItem.Action -> listOf("${i.title}: ${v(i.detail)}")
            // A native view's typed twin, as the Manage page shows it; never a secret (SettingsItem.Native).
            is SettingsItem.Native -> itemLines(dev.glassworks.pane.SettingsChange.twinOf(i))
            // Its twin too: what a link opens is the person's (the notifications' words), not the panel's state.
            is SettingsItem.Link -> emptyList()
        }
    }

    private fun haTile(): Tile {
        val base = prefs.haBase
        val host = base?.substringAfter("://")?.substringBefore('/')?.substringBefore(':') ?: "No address yet"
        val connected = runtime.homeAssistantConnected()
        val value = when {
            connected -> "Connected"
            prefs.haSeen -> "Not connected"
            else -> "Not added yet"
        }
        // The dashboard's own sign-in is the QR flow: offer it here while the panel has none.
        return if (!HaAuth.signedIn && base != null) Tile("Home Assistant", value, "Dashboard not signed in · Sign in", if (connected) Tile.Tone.WORKING else Tile.Tone.OFF, href = "/signin")
        else Tile("Home Assistant", value, host, if (connected) Tile.Tone.OK else if (prefs.haSeen) Tile.Tone.PROBLEM else Tile.Tone.OFF)
    }

    private fun wifiTile(): Tile {
        val link = try {
            WifiLink.of(app)
        } catch (e: RuntimeException) {
            // The query failed, which says nothing about the link: not "Not connected".
            Log.w(TAG, "can't read the Wi-Fi link", e)
            return Tile("Wi-Fi", "Unknown", "", null)
        } ?: return Tile("Wi-Fi", "Not connected", "", Tile.Tone.PROBLEM)
        return Tile("Wi-Fi", link.ssid ?: "Connected", listOfNotNull(link.rssi?.let { "$it dBm".replace('-', '−') }, link.ipv4).joinToString(" · "), Tile.Tone.OK)
    }

    private fun engineTile(): Tile = engineTile(runtime.engine.name)

    private fun memoryTile(): Tile {
        val mi = ActivityManager.MemoryInfo()
        runCatching { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi) }
        fun gb(b: Long) = String.format(Locale.ENGLISH, "%.1f", b / 1e9).removeSuffix(".0")
        if (mi.totalMem <= 0) return Tile("Memory", "Unknown", "", null)
        return Tile("Memory", "${gb(mi.totalMem - mi.availMem)} of ${gb(mi.totalMem)} GB", "Free ${gb(mi.availMem)} GB", null)
    }

    private inner class Actions : PanelActions {
        private val main = Handler(Looper.getMainLooper())
        private val dpm get() = app.getSystemService(DevicePolicyManager::class.java)

        private fun toActivity(extra: String, delayMs: Long = 0): String? {
            val i = Intent(app, runtime.components.mainActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(extra, true)
            main.postDelayed({
                val sink = runtime.commandSink
                if (sink != null) sink(i) else runCatching { app.startActivity(i) }.onFailure { Log.w(TAG, "can't start the activity", it) }
            }, delayMs)
            return null
        }

        override fun reloadDashboard() = toActivity("reload")

        // After the page's answer has gone out: the restart ends this process, and the HTTP worker with it.
        override fun restartApp() = toActivity("restart", ACTION_DELAY_MS)

        /** A privileged system app holds REBOOT (the Mirror's, flashed into /system/priv-app, #198). */
        private val holdsReboot get() = app.checkSelfPermission(android.Manifest.permission.REBOOT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        private val deviceOwner get() = runCatching { dpm?.isDeviceOwnerApp(app.packageName) == true }.getOrDefault(false)

        override val canReboot: Boolean get() = holdsReboot || (deviceOwner && Platform.hasDeviceOwnerReboot)

        override fun reboot(): String? {
            if (!canReboot) return "The app can only reboot the panel as its device owner (Android 7 or newer), or as a system app."
            main.postDelayed({
                runCatching {
                    if (holdsReboot) app.getSystemService(PowerManager::class.java)?.reboot(null)
                    else dpm?.reboot(ComponentName(app, runtime.components.admin))
                }.onFailure { Log.w(TAG, "reboot failed", it) }
            }, ACTION_DELAY_MS)
            return null
        }

        override fun checkForUpdates(): String? {
            val u = runtime.updates ?: return "The update service isn't running."
            u.onCommand(UpdateManager.CMD_CHECK)
            return null
        }
    }

    /**
     * The panel is set up, so `/` is the Manage page and not the QR sign-in: its dashboard is signed in,
     * or setup has passed Home Assistant ([SetupState.setUpBefore]: added, keyed or skipped), which also
     * covers a panel that signed in on its own screen ("Sign in on this panel instead") and so holds no
     * HA credentials. Every QR code names its own path (`/signin`, `/thread`), so none of them needs `/`.
     */
    fun setUp(): Boolean =
        HaAuth.signedIn || SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped)

    /** The panel's IP addresses now, `localhost`, its ESPHome name and its Android host name, as `.local`. */
    private fun ownHosts(): Set<String> {
        val out = hashSetOf("localhost")
        runtime.device?.nodeName?.let { out += "$it.local" }
        runCatching {
            for (i in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) for (a in i.inetAddresses.toList()) {
                a.hostAddress?.let { out += it.substringBefore('%').lowercase() }
            }
        }.onFailure { Log.w(TAG, "can't list the panel's addresses", it) }
        prop("net.hostname").lowercase().takeIf { it.isNotEmpty() }?.let { out += it; out += "$it.local" }
        return out
    }

    private fun qr(signIn: SignInServer, req: HttpRequest): HttpResponse {
        val r = signIn.handle(SignInServer.Request(req.method, req.path, req.query, req.headers))
        return HttpResponse(r.status, r.headers, r.body)
    }

    companion object {
        const val TAG = "Glassworks.Manage"

        /** "Panel Hub S1 Plus (S1 Kiosk)": the device's model as Home Assistant shows it. */
        fun deviceModel(model: String, product: String) = "$model ($product)"

        /** How long a restart or reboot waits, so the page that started it reaches the browser first. */
        const val ACTION_DELAY_MS = 1_000L

        /**
         * The Engine tile from the engine's name: "GeckoView 159" over "Version 159.0". The legacy engine's name ends in
         * a label ("GeckoView 144.0 (legacy, Home Assistant only)"), which the Legacy chip explains instead (#29).
         */
        fun engineTile(name: String): Tile {
            val m = Regex("""^(.*?) ((\d+)(?:\.\d+)*)""").find(name)
                ?: return Tile("Engine", name.ifEmpty { "Unknown" }, "", Tile.Tone.OK)
            val (product, version, major) = m.destructured
            return Tile("Engine", "$product $major", "Version $version", Tile.Tone.OK)
        }

        /** An Android system property, "" when unset or unreadable. */
        fun prop(key: String): String = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
        }.getOrDefault("")

        /** The core's log tags, in words, for the Manage log. The rest (Gecko, Android) stays out; an app adds its own ([Own.logSource]). */
        fun logSource(tag: String): String? = when {
            tag.startsWith("Glassworks.Screen") -> "Screen"
            tag.startsWith("Glassworks.Wifi") -> "Wi-Fi"
            tag.startsWith("Glassworks.Update") -> "Updates"
            tag.startsWith("Glassworks.Improv") || tag.startsWith("Glassworks.SetupChannel") -> "Setup"
            tag.startsWith("Glassworks.Voice") -> "Voice"
            tag == "Glassworks.Sendspin" -> "Music"
            tag == "Glassworks.Auth" -> "Sign-in"
            tag == "Glassworks.Discovery" -> "Home Assistant"
            tag == PanelDevice.ESPHOME_LOG_TAG -> "Home Assistant"
            tag == TAG -> null
            tag == "Glassworks" || tag.startsWith("Glassworks.") -> "App"
            else -> null
        }
    }
}
