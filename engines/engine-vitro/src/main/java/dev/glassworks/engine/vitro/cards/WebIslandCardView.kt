/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.util.Base64
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tier 3 Custom Card Escape Hatch ("GeckoView Micro-Web Island").
 *
 * Uses zero `android.webkit.WebView`: every Tier 3 island runs through Glassworks' wrapped
 * `Engine` (GeckoView) pointed at Vitro's loopback [VitroIslandServer], keeping CSS/JS rendering
 * 100% consistent across Android 6 (Mirror), Android 10 (S1 Plus), and Android 15+ while skipping
 * Home Assistant's 10+ MB frontend SPA.
 *
 * Communicates over a loopback WebSocket (`/__vitro/ws/<slotId>`) with a dependency-tracking
 * `hass.states` Proxy, `hass.callWS`, `hass.callService`, `hass.config`, and theme `:root` vars.
 */
class WebIslandCardView(
    ctx: VitroCardContext,
    private val config: CardConfig,
    private val fillViewport: Boolean = false,
) : CardSurface(ctx) {
    private val watchedEntities = CopyOnWriteArraySet<String>()
    private var slotId: String? = null
    private var islandPage: Page? = null
    @Volatile private var sendToIsland: ((JSONObject) -> Unit)? = null

    init {
        if (fillViewport) {
            background = null
            clipToOutline = false
        }

        val server = ctx.islandServer
        val opener = ctx.openIslandPage
        if (server != null && opener != null) {
            lateinit var idRef: String
            val handler = object : VitroIslandServer.SlotHandler {
                override val html: String
                    get() = buildIslandHtml(ctx, config, fillViewport, slotId = idRef)

                override fun onConnected(send: (JSONObject) -> Unit) {
                    sendToIsland = send
                    seedInitialStates(send)
                }

                override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
                    handleIslandMessage(msg, send)
                }

                override fun onDisconnected() {
                    sendToIsland = null
                }
            }
            val (id, url) = server.registerSlot(handler)
            idRef = id
            slotId = id

            val page = opener(url)
            if (page != null) {
                islandPage = page
                val h = if (fillViewport) LayoutParams.MATCH_PARENT else ctx.px(160f)
                addView(page.view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, h))
                page.load(url)

                track(ctx.store.subscribeAny { changed ->
                    val send = sendToIsland ?: return@subscribeAny
                    val hits = changed.filter { it in watchedEntities }
                    if (hits.isEmpty()) return@subscribeAny
                    val patch = JSONObject()
                    for (eid in hits) ctx.store[eid]?.let { patch.put(eid, it.toHassJson()) }
                    if (patch.length() > 0) {
                        send(JSONObject().put("type", "patch").put("states", patch))
                    }
                })
            } else {
                server.unregisterSlot(id)
                slotId = null
                showPlaceholder()
            }
        } else {
            showPlaceholder()
        }
    }

    private fun showPlaceholder() {
        val note = TextView(ctx.context).apply {
            text = "Custom card island: ${config.type}"
            setTextColor(ctx.theme.secondaryTextColor)
            setPadding(ctx.px(14f), ctx.px(14f), ctx.px(14f), ctx.px(14f))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun seedInitialStates(send: (JSONObject) -> Unit) {
        val syntheticView = ViewConfig(0, "", null, null, "panel", null, null, 1, emptyList(), listOf(config), emptyList())
        val refs = CardRouter.referencedEntities(syntheticView)
        val seed = JSONObject()
        for (id in refs) {
            watchedEntities.add(id)
            ctx.store[id]?.let { seed.put(id, it.toHassJson()) }
        }
        if (seed.length() > 0) {
            send(JSONObject().put("type", "patch").put("states", seed))
        }
    }

    private fun handleIslandMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
        when (msg.optString("type")) {
            "watch" -> {
                val id = msg.optString("entity_id", "")
                if (id.isNotBlank() && watchedEntities.add(id)) {
                    ctx.store[id]?.let { state ->
                        send(JSONObject().put("type", "patch").put("states", JSONObject().put(id, state.toHassJson())))
                    }
                }
            }
            "resize" -> {
                if (fillViewport) return
                val cssH = msg.optInt("height", 160).coerceIn(48, 1200)
                val pxH = ctx.px(cssH.toFloat())
                ctx.main.post {
                    val v = islandPage?.view ?: return@post
                    if (v.layoutParams?.height != pxH) {
                        v.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH)
                    }
                }
            }
            "callService" -> {
                val domain = msg.optString("domain", "")
                val service = msg.optString("service", "")
                if (domain.isNotEmpty() && service.isNotEmpty()) {
                    ctx.callService(domain, service, msg.optJSONObject("data"), null)
                }
            }
            "callWs" -> {
                val callId = msg.optInt("id", -1)
                val inner = msg.optJSONObject("msg") ?: return
                ctx.callWs(inner) { ok, result, error ->
                    val reply = JSONObject().put("type", "wsReply").put("id", callId).put("ok", ok)
                    if (ok) reply.put("result", result ?: JSONObject.NULL)
                    else reply.put("error", error ?: JSONObject().put("message", "callWS failed"))
                    send(reply)
                }
            }
            "moreInfo" -> {
                val id = msg.optString("entity_id", "")
                if (id.isNotBlank()) ctx.main.post { ctx.openMoreInfo(id) }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sendToIsland = null
        slotId?.let { ctx.islandServer?.unregisterSlot(it) }
        slotId = null
        islandPage = null
    }

    companion object {
        private val VALID_BASE64 = Regex("""^[A-Za-z0-9+/=\s]+$""")

        /** Decodes a `data:` URI inline Lovelace JS resource into raw module source, or null for normal URLs. */
        fun decodeInlineModule(url: String): String? {
            if (!url.startsWith("data:", ignoreCase = true)) return null
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma).lowercase()
            val payload = url.substring(comma + 1)
            return runCatching {
                if (";base64" in meta) {
                    if (payload.isBlank() || !VALID_BASE64.matches(payload)) return null
                    String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    java.net.URLDecoder.decode(payload, "UTF-8")
                }
            }.getOrNull()
        }

        /**
         * Builds the ~5 KB self-contained Lovelace card Micro-Host HTML for [config].
         * Runs inside GeckoView over [VitroIslandServer] with zero HA SPA overhead.
         */
        fun buildIslandHtml(
            ctx: VitroCardContext,
            config: CardConfig,
            fillViewport: Boolean = false,
            slotId: String = "s0",
        ): String {
            val tag = config.type.removePrefix("custom:")
            val cssVars = ctx.theme.toCssRootBlock()
            val resourceScripts = ctx.resources.joinToString("\n") { r ->
                val inlineJs = decodeInlineModule(r.url)
                when {
                    inlineJs != null -> "<script type=\"module\">\n${inlineJs.replace("</script", "<\\/script")}\n</script>"
                    r.type == "css" -> """<link rel="stylesheet" href=${JSONObject.quote(r.url)}>"""
                    r.type == "module" -> """<script type="module" src=${JSONObject.quote(r.url)}></script>"""
                    else -> """<script src=${JSONObject.quote(r.url)}></script>"""
                }
            }
            val cfgJson = config.raw.toString()
            val haCfgJson = ctx.haConfig().toString()
            val tokenJson = JSONObject.quote(ctx.accessToken().orEmpty())
            val heightRule = if (fillViewport) "height: 100vh; width: 100vw;" else ""
            return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
$cssVars
html, body { margin: 0; padding: 0; background: transparent; color: var(--primary-text-color); font-family: Inter, system-ui, sans-serif; overflow: hidden; $heightRule }
#mount { width: 100%; ${if (fillViewport) "height: 100vh;" else ""} }
ha-card { display: block; background: var(--ha-card-background, var(--card-background-color)); border-radius: var(--ha-card-border-radius, 12px); color: var(--primary-text-color); }
</style>
<script>
// Minimal HA custom-element shims for GeckoView Micro-Islands (zero HA SPA).
if (!customElements.get('ha-card')) {
  customElements.define('ha-card', class extends HTMLElement {});
}
if (!customElements.get('ha-icon')) {
  const iconCache = new Map();
  customElements.define('ha-icon', class extends HTMLElement {
    static get observedAttributes() { return ['icon']; }
    connectedCallback() { this._render(); }
    attributeChangedCallback() { this._render(); }
    set icon(v) { this.setAttribute('icon', v || ''); }
    get icon() { return this.getAttribute('icon') || ''; }
    _render() {
      const sz = getComputedStyle(this).getPropertyValue('--mdc-icon-size') || '24px';
      this.style.display = 'inline-flex';
      this.style.alignItems = 'center';
      this.style.justifyContent = 'center';
      this.style.width = sz;
      this.style.height = sz;
      const name = (this.getAttribute('icon') || 'mdi:home').replace(/^mdi:/, '');
      const cached = iconCache.get(name);
      if (cached) { this.innerHTML = cached; return; }
      fetch('/__vitro/icon/' + encodeURIComponent(name))
        .then((r) => r.ok ? r.text() : '')
        .then((svg) => {
          if (svg) {
            const styled = svg.replace('<svg ', '<svg style="width:100%;height:100%" ');
            iconCache.set(name, styled);
            this.innerHTML = styled;
          }
        })
        .catch(() => {});
    }
  });
}
window.loadCardHelpers = async () => ({
  createCardElement: (c) => {
    const t = (c.type || '').startsWith('custom:') ? c.type.slice(7) : 'div';
    const el = document.createElement(t);
    if (el.setConfig) el.setConfig(c);
    return el;
  }
});
</script>
$resourceScripts
</head>
<body>
<div id="mount"></div>
<script type="module">
const rawStates = {};
const haConfig = $haCfgJson;
const accessToken = $tokenJson;
const wsPending = new Map();
const outQueue = [];
let wsId = 1;
let sock = null;

function sendBridge(obj) {
  const line = JSON.stringify(obj) + '\n';
  if (sock && sock.readyState === WebSocket.OPEN) sock.send(line);
  else outQueue.push(line);
}

const statesProxy = new Proxy(rawStates, {
  get(target, prop) {
    if (typeof prop === 'string' && prop.includes('.')) {
      sendBridge({ type: 'watch', entity_id: prop });
    }
    return target[prop];
  }
});

function makeHass() {
  return {
    states: statesProxy,
    config: haConfig,
    themes: { darkMode: ${ctx.theme.dark} },
    language: 'en',
    auth: { data: { access_token: accessToken } },
    hassUrl: (path) => path ? new URL(path, location.origin).href : location.origin,
    callService(domain, service, data) {
      sendBridge({ type: 'callService', domain, service, data: data || {} });
      return Promise.resolve();
    },
    callWS(msg) {
      return new Promise((resolve, reject) => {
        const id = wsId++;
        wsPending.set(id, { resolve, reject });
        sendBridge({ type: 'callWs', id, msg: msg || {} });
      });
    },
    formatEntityState(s) { return s ? String(s.state) : ''; }
  };
}

const tag = ${JSONObject.quote(tag)};
const cfg = $cfgJson;
let cardEl = null;

function connectBridge() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  sock = new WebSocket(proto + '//' + location.host + '/__vitro/ws/' + ${JSONObject.quote(slotId)});
  sock.onopen = () => {
    while (outQueue.length) sock.send(outQueue.shift());
  };
  sock.onmessage = (ev) => {
    const lines = String(ev.data || '').split('\n').filter(Boolean);
    for (const line of lines) {
      let m;
      try { m = JSON.parse(line); } catch (e) { continue; }
      if (m.type === 'patch' && m.states) {
        Object.assign(rawStates, m.states);
        if (cardEl) cardEl.hass = makeHass();
      } else if (m.type === 'wsReply') {
        const entry = wsPending.get(m.id);
        if (!entry) continue;
        wsPending.delete(m.id);
        if (m.ok) entry.resolve(m.result);
        else entry.reject(m.error || new Error('callWS failed'));
      }
    }
  };
  sock.onclose = () => setTimeout(connectBridge, 1500);
}

async function boot() {
  connectBridge();
  try { await Promise.race([customElements.whenDefined(tag), new Promise(r => setTimeout(r, 4000))]); } catch (e) {}
  cardEl = document.createElement(tag);
  if (typeof cardEl.setConfig === 'function') cardEl.setConfig(cfg);
  cardEl.hass = makeHass();
  document.getElementById('mount').appendChild(cardEl);
  cardEl.addEventListener('hass-more-info', (ev) => {
    const id = ev && ev.detail && ev.detail.entityId;
    if (id) sendBridge({ type: 'moreInfo', entity_id: id });
  });
  const reportSize = () => {
    const h = Math.ceil(document.getElementById('mount').getBoundingClientRect().height || 120);
    sendBridge({ type: 'resize', height: h });
  };
  if (window.ResizeObserver) new ResizeObserver(reportSize).observe(document.getElementById('mount'));
  setTimeout(reportSize, 60);
}
boot();
</script>
</body>
</html>
""".trimIndent()
        }
    }
}
