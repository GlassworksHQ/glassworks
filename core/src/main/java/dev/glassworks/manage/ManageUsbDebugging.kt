/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.web.HttpResponse
import dev.glassworks.web.PageShell.esc

/**
 * "Allow a new computer" on the Security page (#435), where the app can answer "Allow USB debugging?" itself
 * ([AdbComputers]), or where a frame's root side keeps `adb_keys` (another [UsbComputers]): the computer asking now
 * with its fingerprint and Allow, and the allowed list with Remove.
 * Allowing and removing need a browser signed in with Home Assistant, which lets in administrators only; one
 * signed in with the panel's code sees the cards without their buttons. Each try goes to the Log with the
 * computer's fingerprint, never its key.
 */
class ManageUsbDebugging(
    private val computers: UsbComputers,
    /** A time as the Log shows it ([ManagePages.time]). */
    private val time: (Long) -> String,
    /** A line for the Log. */
    private val event: (String) -> Unit,
) {
    /** The two cards, after the Security page's own. [shown]: the `usb` mark of the last change ([Result]). */
    fun cards(v: ManagePages.Viewer, shown: String?): String {
        val result = Result.entries.firstOrNull { it.key == shown }
        val status = result?.let { """<div class="st${if (it.ok) "" else " bad"}"><i class="dot ${if (it.ok) "ok" else "bad"}"></i><span>${esc(it.text)}</span></div>""" }.orEmpty()
        val asking = computers.asking()
        val ask = when {
            asking == null -> """<div class="row static note"><span>${esc(NONE_ASKING)}</span></div>"""
            !v.homeAssistant -> """<div class="row static"><div><b>A computer is asking</b>${about(asking, null)}<span class="fix">${esc(NEEDS_HA)}</span></div></div>"""
            else -> """<form class="row usb" method="post" action="$ALLOW">${csrf(v)}<input type="hidden" name="fingerprint" value="${esc(asking.fingerprint)}">""" +
                """<div><b>A computer is asking</b>${about(asking, null)}${remember()}</div>""" +
                """<button class="pill sm primary" type="submit">Allow this computer</button></form>"""
        }
        val list = computers.list()
        val rows = if (list.isEmpty()) """<div class="row static note"><span>${esc(NONE_LISTED)}</span></div>""" else list.joinToString("") { l ->
            val remove = if (v.homeAssistant) """<form method="post" action="$REMOVE">${csrf(v)}<input type="hidden" name="fingerprint" value="${esc(l.key.fingerprint)}"><button class="pill sm" type="submit">Remove</button></form>""" else ""
            val added = if (l.addedMs > 0) "Added ${time(l.addedMs)}${if (l.by.isNotEmpty()) " by ${l.by}" else ""}" else ALLOWED_ELSEWHERE
            """<div class="row static usb"><div><b>${esc(l.key.name ?: "A computer")}</b>${about(l.key, added)}</div>$remove</div>"""
        }
        return """<section class="card list"><div class="lh"><b>Allow a new computer</b><span>USB debugging</span></div>$status$ask</section>""" +
            """<section class="card list"><div class="lh"><b>Allowed computers</b><span>${list.size}</span></div>$rows</section>$CSS"""
    }

    /** The "each time it connects" switch, where Allow can also let a computer in for this connection only. */
    private fun remember() = if (computers.alwaysRemembers) "" else
        """<label class="sw"><input type="checkbox" name="remember" value="1" checked> Let it in each time it connects</label>"""

    /** A computer's lines: [what] (or its name, when it gives one), then its fingerprint. */
    private fun about(key: AdbKey, what: String?): String {
        val line = what ?: key.name?.let { "Reported name: $it" }
        return (line?.let { "<span>${esc(it)}</span>" }.orEmpty()) + """<span class="fp">Fingerprint ${esc(key.fingerprint).replace(":", ":<wbr>")}</span>"""
    }

    private fun csrf(v: ManagePages.Viewer) = """<input type="hidden" name="csrf" value="${esc(v.csrf)}">"""

    /**
     * A signed-in POST to [path], one of this card's, or null when it isn't. [who] made it; [homeAssistant]: it
     * signed in with Home Assistant. Answers with the Security page and what happened ([Result]).
     */
    fun post(path: String, form: Map<String, String>, who: String, homeAssistant: Boolean): HttpResponse? {
        if (path != ALLOW && path != REMOVE) return null
        val fingerprint = form["fingerprint"].orEmpty()
        if (!FINGERPRINT.matches(fingerprint)) return HttpResponse.text(400, "That isn't a computer's fingerprint.")
        val what = if (path == ALLOW) "allow adb key $fingerprint over USB" else "remove adb key $fingerprint from the allowed list"
        if (!homeAssistant) {
            event("$who tried to $what: refused, that needs a Home Assistant sign-in")
            return done(Result.SIGN_IN)
        }
        if (path == REMOVE) return done(when (computers.remove(fingerprint, who)) {
            AdbComputers.Removed.DONE -> Result.REMOVED
            AdbComputers.Removed.NOT_LISTED -> Result.GONE
            AdbComputers.Removed.NOT_SAVED -> Result.NOT_REMOVED
            AdbComputers.Removed.NO_ANSWER -> Result.NO_ANSWER
        })
        return done(when (computers.allowAsking(fingerprint, computers.alwaysRemembers || form["remember"] == "1", who)) {
            AdbComputers.Allowed.ONCE -> Result.ONCE
            AdbComputers.Allowed.FROM_NOW_ON -> Result.FROM_NOW_ON
            AdbComputers.Allowed.CHANGED -> Result.CHANGED
            AdbComputers.Allowed.NONE -> Result.NONE
            AdbComputers.Allowed.FULL -> Result.FULL
            AdbComputers.Allowed.FAILED -> Result.FAILED
            AdbComputers.Allowed.REFUSED -> Result.REFUSED
            AdbComputers.Allowed.REFUSED_NONE_WAITING -> Result.REFUSED_NONE_WAITING
            AdbComputers.Allowed.UNCONFIRMED -> Result.UNCONFIRMED
            AdbComputers.Allowed.NOT_REMEMBERED -> Result.NOT_REMEMBERED
            AdbComputers.Allowed.NO_ANSWER -> Result.NO_ANSWER
        })
    }

    private fun done(r: Result) = HttpResponse.seeOther("/security?usb=${r.key}")

    /** What the last allow or remove did, as the page says it. */
    enum class Result(val key: String, val ok: Boolean, val text: String) {
        ONCE("once", true, "Allowed for this connection, and Android didn't refuse it. If the computer is still plugged in, it gets in within a few seconds."),
        FROM_NOW_ON("allowed", true, "Allowed, and Android didn't refuse it. If the computer is still plugged in, it gets in within a few seconds, and each time it connects."),
        REMOVED("removed", true, "Removed. That computer can't get in the next time it connects."),
        CHANGED("changed", false, "Another computer asked since this page showed. Check its fingerprint, then allow it again."),
        NONE("none", false, "No computer is asking now. Plug it in again, then reload this page."),
        FULL("full", false, "The list is full. Remove a computer first."),
        GONE("gone", false, "That computer isn't on the list."),
        FAILED("failed", false, "That didn't work. The Log says why."),
        UNCONFIRMED("unconfirmed", false, "The panel can't see Android's answer right now, so nothing was saved. Try again in a few seconds."),
        REFUSED_NONE_WAITING("notwaiting", false, "Android didn't let it in: no computer is waiting now. Plug it in again, then reload this page."),
        REFUSED("refused", false, "Android didn't let it in: another computer is the one waiting. Plug this one in again, then reload this page."),
        NOT_REMEMBERED("unsaved", false, "Allowed for this connection only: the panel couldn't save the list. The computer asks again next time."),
        NOT_REMOVED("notremoved", false, "Not removed: the panel couldn't save the list. The computer is still allowed. Try again."),
        NO_ANSWER("noanswer", false, "The panel didn't answer in time. Reload this page in a minute to see whether it took."),
        SIGN_IN("signin", false, NEEDS_HA),
    }

    companion object {
        const val ALLOW = "/security/usb/allow"
        const val REMOVE = "/security/usb/remove"
        private val FINGERPRINT = Regex("(?:[0-9A-F]{2}:){15}[0-9A-F]{2}")
        const val NEEDS_HA = "Allowing or removing a computer needs a Home Assistant administrator. Sign out, then sign in with Home Assistant."
        const val NONE_ASKING = "No computer is asking. Plug one in with USB debugging on and run adb devices: it shows as unauthorized there, and here."
        const val NONE_LISTED = "None yet. The computer that installed Glassworks always gets in."
        /** A listed computer nobody recorded adding on this page: let in on the panel's own screen. */
        const val ALLOWED_ELSEWHERE = "Allowed on the panel itself"
        private const val CSS = """<style>.row .fp{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}
.row.usb{flex-wrap:wrap;row-gap:10px}.row.usb>div{flex:1 1 280px;min-width:0}.row.usb .sw{display:flex;justify-content:flex-start;align-items:center;gap:10px;margin-top:8px;color:var(--muted);font-size:15px}</style>"""
    }
}
