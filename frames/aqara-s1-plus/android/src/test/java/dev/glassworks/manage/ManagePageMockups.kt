package dev.glassworks.manage

import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.text
import dev.glassworks.manage.LogEntry
import dev.glassworks.manage.PaneRow
import dev.glassworks.pane.NOT_RUNNING
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.PageShell.Look
import org.junit.Assume.assumeTrue
import org.junit.Test
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.auth.UrlConnectionPoster
import java.io.File

/**
 * The Manage page (#33) as the real server answers it, with a signed-in fake browser, plus the QR pages
 * it links with: HTML files to render at desktop and phone width, dark and light
 * (tools in the #33 trail). Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*ManagePageMockups*' -Ps1MockupDir=<dir>
 */
class ManagePageMockups {
    private fun save(name: String, r: HttpResponse) {
        val out = File(System.getProperty("s1.mockupDir")!!, "manage").apply { mkdirs() }
        File(out, "$name.html").writeBytes(r.body)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", System.getProperty("s1.mockupDir") != null)
        val ha = "http://homeassistant.local:8123"
        val signIn = SignInServer(0, { ha }, HaTokenClient(UrlConnectionPoster()), { _, _ -> })
        val f = ManageFixture(qrPages = { req ->
            // The QR flow's done page, as /callback draws it after a sign-in.
            val r = if (req.path == "/callback") SignInServer.page(200, "OK", "Panel signed in", "It's loading your dashboard now. You can close this page.",
                Look.DONE, tile = SignInServer.haTile(ha, dot = true), footHtml = SignInServer.MANAGE_FOOT)
            else signIn.handle(SignInServer.Request(req.method, req.path, req.query, req.headers))
            HttpResponse(r.status, r.headers, r.body)
        })
        val t = f.now
        listOf(
            Triple(9 * 3600_000L + 41 * 60_000 + 7_000, LogEntry.Level.INFO, "Voice" to "Wake word heard (0.82)"),
            Triple(9 * 3600_000L + 41 * 60_000 + 6_000, LogEntry.Level.INFO, "App" to "Reloaded the dashboard"),
            Triple(9 * 3600_000L + 38 * 60_000 + 52_000, LogEntry.Level.WARNING, "Radio" to "ZHA reconnected after 4 s"),
            Triple(9 * 3600_000L + 38 * 60_000 + 48_000, LogEntry.Level.WARNING, "Radio" to "ZHA disconnected"),
            Triple(9 * 3600_000L + 12 * 60_000 + 30_000, LogEntry.Level.INFO, "Music" to "Sendspin stream started, FLAC 48 kHz"),
            Triple(8 * 3600_000L + 2 * 60_000 + 11_000, LogEntry.Level.INFO, "Screen" to "Woke: someone near (0.9 m)"),
            Triple(3 * 3600_000L + 14 * 60_000 + 55_000, LogEntry.Level.ERROR, "Wi-Fi" to "Lost the network for 12 s"),
            Triple(3 * 3600_000L + 14 * 60_000 + 43_000, LogEntry.Level.INFO, "Wi-Fi" to "Reconnected to Example Wi-Fi"),
        ).reversed().forEach { (ago, level, st) -> f.log.append(LogEntry(t - (10 * 3600_000L - ago), level, st.first, st.second)) }

        val anon = f.Browser()
        save("01-sign-in", anon.get("/"))
        f.haBase = null
        save("01b-sign-in-no-ha", f.Browser().get("/"))
        f.haBase = "http://192.0.2.10:8123"
        anon.post("/manage/code", mapOf("next" to "/"))
        save("01c-code-sign-in", anon.get("/manage/code?next=%2F&remember=1"))

        val b = f.Browser()
        b.signInWithHa()
        f.Browser(ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36").signInWithHa(remember = false)
        save("02-overview", b.get("/"))
        save("02b-overview-notice", b.get("/?done=restart"))
        save("03-pane-voice", b.get("/assist"))
        save("04-pane-radio", b.get("/radio"))
        save("05-dashboard", b.get("/dashboard"))
        save("06-log", b.get("/log"))
        save("06b-log-warnings", b.get("/log?level=warning"))
        save("07-security", b.get("/security"))
        b.post("/action/reboot", mapOf("csrf" to b.csrf()))
        save("07b-reboot-code", b.get("/confirm/reboot"))
        // #33: the Security section's rows (the app's CoreSettings has the same), then the reset's question and its code.
        val security = SettingsSection(ManagePages.SECURITY, "Security", listOf(
            SettingsItem.Action(dev.glassworks.settings.CoreSettings.SETUP_OVER_NETWORK, { "Open until 10:12. Open http://192.0.2.40:8099/setup on a computer on this network." }, "Allow", needsCode = true) { d -> d(null) },
            SettingsItem.Action(dev.glassworks.settings.CoreSettings.CLOSE_SETUP, { "Open until 10:12. Closing it ends setup on any computer that paired." }, "Close", needsCode = true) { d -> d(null) },
            SettingsItem.Action(dev.glassworks.settings.FactoryReset.TITLE, { dev.glassworks.settings.FactoryReset.DETAIL }, dev.glassworks.settings.FactoryReset.LABEL,
                SettingsItem.Confirm("${dev.glassworks.settings.FactoryReset.TITLE}?", dev.glassworks.settings.FactoryReset.CONFIRM_DETAIL, dev.glassworks.settings.FactoryReset.CONFIRM_ACTION),
                needsCode = true) { d -> d(null) },
        ))
        f.statusFn = { f.status.copy(sections = f.status.sections + security) }
        save("07c-security-actions", b.get("/security"))
        f.now += 31_000
        fun reset(extra: Map<String, String> = emptyMap()) = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to security.id,
            "key" to dev.glassworks.pane.SettingsChange.changeable(security).first { it.second === security.items[2] }.first, "value" to SettingsItem.Action.RUN) + extra)
        val question = reset()
        save("07d-reset-question", question)
        save("07e-reset-code", reset(mapOf("answer" to question.answer()!!)))
        f.statusFn = null
        save("08-about", b.get("/about"))
        save("09-wall-base", b.get("/wall-base"))
        // #466: All panels as the server draws it; panels-shots.mjs runs its script against fakes.
        val panels = b.get("/panels")
        save("13-all-panels", panels)
        File(System.getProperty("s1.mockupDir")!!, "manage/13-all-panels.csp").writeText(panels.headers.getValue("Content-Security-Policy"))
        // The QR flow and back: the done page offers Manage; signed in, every QR page's header leads back.
        save("10-qr-done", anon.get("/callback"))
        save("10b-qr-done-signed-in", b.get("/callback"))
        save("10c-qr-sign-in-signed-in", b.get("/signin"))
        save("10d-qr-sign-in", anon.get("/signin"))

        // #243: a long HA user name in the nav's foot (made up). The phone's menu, open, shows the same foot.
        f.user = dev.glassworks.manage.HaUser("Alexandra Montgomery-Fitzgerald", isAdmin = true)
        val long = f.Browser()
        long.signInWithHa()
        val page = long.get("/")
        save("12-long-name", page)
        val open = page.text().replace("<details class=\"menu\">", "<details class=\"menu\" open>")
        org.junit.Assert.assertNotEquals("the phone's menu opened in the mock-up", page.text(), open)
        save("12b-long-name-menu", HttpResponse(page.status, page.headers, open.toByteArray()))
        f.user = dev.glassworks.manage.HaUser("Alex", isAdmin = true)

        // #67: every pane has its on/off. Music off; Time zone and Diagnostics have only their switch.
        val on = SettingsItem.Switch("Time zone", "Keeps the zone Aqara resets at boot", { true }) { _, d -> d(null) }
        f.status = f.status.copy(
            panes = f.status.panes.map { if (it.id == "sendspin") it.copy(state = PaneState(PaneState.Kind.OFF, "Turned off")) else it } + listOf(
                PaneRow("time-zone", "Time zone", PaneState(PaneState.Kind.RUNNING, "Keeps the zone Aqara resets at boot", "On"), "time-zone"),
                PaneRow("diagnostics", "Diagnostics", PaneState(PaneState.Kind.RUNNING, "CPU, temperature, memory and Wi-Fi in Home Assistant", "On"), "diagnostics"),
            ),
            sections = f.status.sections + SettingsSection("time-zone", "Time zone", listOf(SettingsItem.Note { NOT_RUNNING }), master = on),
        )
        save("11-overview-panes-on-off", b.get("/"))
        save("11b-pane-off", b.get("/sendspin"))
        save("11c-pane-switch-only", b.get("/time-zone"))

        // #435: "Allow a new computer", on a panel whose app may answer adb (the Mirror's). Made-up keys.
        fun key(seed: Int, name: String): dev.glassworks.manage.AdbKey {
            val bytes = kotlin.random.Random(seed).nextBytes(524).also { it[0] = 64; it[1] = 0; it[2] = 0; it[3] = 0 }
            return dev.glassworks.manage.AdbKey.parse(java.util.Base64.getEncoder().encodeToString(bytes) + " " + name)!!
        }
        val computers = dev.glassworks.manage.AdbComputers(File(f.dir, "adb-computers.json"), allow = { null }, event = {}, clock = { t })
        val u = ManageFixture(usb = dev.glassworks.manage.ManageUsbDebugging(computers, dev.glassworks.manage.ManagePages(clock = { t })::time, {}))
        u.now = t
        val listed = key(7, "sam@desktop")
        computers.asked(listed)
        computers.allowAsking(listed.fingerprint, remember = true, who = "Alex")
        computers.asked(key(8, "alex@laptop"))
        val ub = u.Browser().apply { signInWithHa() }
        save("13-security-allow-computer", ub.get("/security"))
        save("13b-security-changed", ub.get("/security?usb=changed"))
        val uc = u.Browser().apply {
            get("/")
            post("/manage/code", mapOf("next" to "/"))
            post("/manage/code", mapOf("next" to "/", "code" to u.shown.last()!!.code))
        }
        save("13c-security-code-sign-in", uc.get("/security"))
        ub.post(dev.glassworks.manage.ManageUsbDebugging.ALLOW, mapOf("csrf" to ub.csrf(), "fingerprint" to key(8, "alex@laptop").fingerprint, "remember" to "1"))
        save("13d-security-allowed", ub.get("/security?usb=allowed"))
        // Android refused: the computer still asks. Another computer waits, or none does.
        computers.asked(key(9, "jo@laptop"))
        save("13e-security-refused", ub.get("/security?usb=refused"))
        save("13f-security-not-waiting", ub.get("/security?usb=notwaiting"))

        // The S1: its frame agent keeps adb_keys, so Kiln's key is listed. A fake
        // agent's files, made-up keys.
        val pub = File(f.dir, "s1-adb-pub").apply { mkdirs() }
        fun line(kind: String, k: dev.glassworks.manage.AdbKey) = "$kind ${k.fingerprint} ${k.base64} ${k.name}\n"
        val asking = key(10, "alex@laptop")
        File(pub, "adb-asking").writeText("${t / 1000} ${asking.fingerprint} ${asking.base64} ${asking.name}\n")
        File(pub, "adb-keys").writeText(line("installer", key(11, "kiln@glassworks")) + line("added", key(12, "sam@desktop")))
        // The agent's adbd key check: on (the list decides), then still off (until the next restart, or not loaded).
        var auth = "on"
        val s1 = dev.glassworks.s1.manage.S1AdbComputers(pub, File(f.dir, "s1-adb/request"), File(f.dir, "s1-adb/notes.json"), {}, clock = { t }, authStatus = { auth })
        val s = ManageFixture(usb = dev.glassworks.manage.ManageUsbDebugging(s1, dev.glassworks.manage.ManagePages(clock = { t })::time, {}))
        s.now = t
        val sb = s.Browser().apply { signInWithHa() }
        save("13g-security-s1-allow-computer", sb.get("/security"))
        auth = "on after a restart"
        save("13h-security-s1-check-after-restart", sb.get("/security"))
        auth = "off: Magisk loads no modules here"
        save("13i-security-s1-check-off", sb.get("/security"))
    }
}
