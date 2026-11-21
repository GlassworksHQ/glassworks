/* SPDX-License-Identifier: MIT
 *
 * Ported from android-ip-camera: aguilaair/android-ip-camera, branch onvif-rtsp-motion, commit
 * 7b5e6f28; a fork of DigitallyRefined/android-ip-camera. Copyright (c) 2025 DigitallyRefined and
 * contributors. The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: package; with no credentials, or a username without a password, every request that needs a
 * user is refused (upstream let everyone in); the panel's name in place of "AndroidIPCamera"; at most 64
 * headers and 32 KiB in a request head.
 * Includes upstream DigitallyRefined/android-ip-camera ca0085d1 (a HEAD answer says the Content-Length a GET would get,
 * and sends no body) and c58342b2 (WS-BaseNotification push: Subscribe, Renew, Unsubscribe and a Notify to the
 * consumer on each change). Glassworks changes to the push: the consumer must be on the local network (a private or
 * link-local address, or the subscriber's own), is resolved once and pinned, and gets plain HTTP only; its own cap of
 * [MAX_PUSH_SUBSCRIPTIONS] and at most [MAX_PUSH_TERM_MS] to a subscription; Notify goes out on its own threads with
 * short timeouts, in order per consumer; the log names the consumer's host, never its path. */

package dev.glassworks.camera.onvif

import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class OnvifDeviceInfo(
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val serial: String,
    val hardwareId: String,
    val mac: String,
    val name: String
)

data class OnvifStreamInfo(
    val rtspUrl: String,   // no credentials embedded
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
)

/**
 * Minimal ONVIF Profile S (+ PullPoint and WS-BaseNotification push events) device over plain HTTP/SOAP 1.2, plus
 * WS-Discovery. Targets Home Assistant's ONVIF integration, UniFi Protect third-party adoption, and hardware NVRs
 * that record on motion through `wsnt:Subscribe` and a pushed `wsnt:Notify`.
 * Pure JVM; the Android caller must hold a WifiManager.MulticastLock for discovery.
 */
class OnvifServer(
    private val port: Int = 8000,
    private val host: () -> String,
    private val info: () -> OnvifDeviceInfo,
    private val stream: () -> OnvifStreamInfo,
    private val credentials: () -> Pair<String, String>?,
    private val snapshot: (() -> ByteArray?)? = null,
    private val onLog: (String) -> Unit = {},
    /** The time for Digest nonces' age ([NONCE_MS]); a test passes its own. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val discoveryPort: Int = 3702,
    private val enableDiscovery: Boolean = true,
    /** Glassworks: the panel's name, for the auth realm, the Server header and the host name fallback. */
    private val name: String = "Glassworks",
    /** Glassworks: a push consumer's host name to its addresses; a test passes its own. */
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** Glassworks: sends one Notify to a push consumer and returns the HTTP status; a test passes its own. */
    private val post: ((PushConsumer, ByteArray) -> Int)? = null,
    /**
     * Glassworks: whether an address is one of the panel's own interfaces, which no consumer may be; a test passes its
     * own. Fails closed: an address the lookup can't check counts as the panel's.
     */
    private val ownAddress: (InetAddress) -> Boolean = { a -> try { NetworkInterface.getByInetAddress(a) != null } catch (_: Exception) { true } },
) {
    companion object {
        private const val MAX_BODY = 1 shl 20
        private const val MAX_HEADERS = 64
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_SUBSCRIPTIONS = 16
        private const val MAX_QUEUED = 200
        private const val DEFAULT_SUB_MS = 10 * 60_000L
        private const val MAX_PULL_WAIT_MS = 60_000L
        private const val REFUSAL_LOG_MS = 60_000L
        /** A Digest nonce's life, as RTSP's (RtspAuth): 12 h. */
        private const val NONCE_MS = 12 * 3600_000L
        private const val TOPIC_DIALECT = "http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet"
        private const val TOPIC_CELL = "tns1:RuleEngine/CellMotionDetector/Motion"
        private const val TOPIC_ALARM = "tns1:VideoSource/MotionAlarm"
        private const val WSNT_BW = "http://docs.oasis-open.org/wsn/bw-2"
        /** Push subscriptions, apart from [MAX_SUBSCRIPTIONS] pull points, so a Subscribe flood can't push Home Assistant's pull point out. */
        internal const val MAX_PUSH_SUBSCRIPTIONS = 8
        /** The longest a push subscription lasts without a Renew: a consumer that went away stops getting Notify within this. */
        internal const val MAX_PUSH_TERM_MS = 60 * 60_000L
        /** Notify batches waiting for one consumer; past it the oldest go (a dead NVR can't hold memory). */
        private const val MAX_OUTBOX = 8
        private const val NOTIFY_CONNECT_MS = 2_000
        /** Writing a Notify and reading the answer's status line, together, once connected. */
        private const val NOTIFY_EXCHANGE_MS = 2_000L
        /** The watchdog closes the socket this long after the exchange's deadline: a read times out first, on its own. */
        private const val WATCHDOG_GRACE_MS = 500L
        /** A consumer URL longer than this is refused (the common URL limit of browsers and servers). */
        private const val MAX_CONSUMER_URL = 2_048
        /** After Subscribe, so the SubscribeResponse reaches the consumer first: some NVRs drop a Notify that comes before it. */
        private const val FIRST_NOTIFY_DELAY_MS = 250L

        /**
         * A push consumer may be on the local network only: a private (10/8, 172.16/12, 192.168/16, fc00::/7) or
         * link-local address, or [source], the subscriber's own address. Never loopback, a wildcard or multicast,
         * unless the subscriber is that address itself. The panel's own interface addresses are refused apart from
         * this, in [pushConsumer].
         */
        internal fun localConsumer(a: InetAddress, source: InetAddress?): Boolean {
            if (source != null && a == source) return true
            if (a.isAnyLocalAddress || a.isMulticastAddress || a.isLoopbackAddress) return false
            if (a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            return a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC
        }

        /** Operations ONVIF allows without credentials (plus GetCapabilities for Protect probing). */
        private val UNAUTHENTICATED = setOf(
            "GetSystemDateAndTime", "GetServices", "GetServiceCapabilities", "GetCapabilities",
            "GetWsdlUrl", "GetEndpointReference"
        )
    }

    internal data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/soap+xml; charset=utf-8",
        val headers: List<Pair<String, String>> = emptyList()
    ) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /**
     * Where a push subscription's Notify goes: [url] as the consumer sent it, sent to [address] (resolved once, at
     * Subscribe, so a name that later resolves elsewhere can't move it) on [port], with [hostHeader] and [target].
     */
    class PushConsumer internal constructor(
        val url: String,
        val address: InetAddress,
        val port: Int,
        val hostHeader: String,
        val target: String,
    ) {
        /** For the log: scheme, host and port, never the path (Home Assistant's webhook path is its webhook's key). */
        val label: String get() = "http://$hostHeader"
    }

    /** A pull point ([consumer] null) or a push subscription whose Notify goes to [consumer]. */
    private class Subscription(val id: String, @Volatile var expiresAt: Long, val consumer: PushConsumer? = null) {
        val queue = ArrayDeque<String>()
        val lock = Object()
        /** Push only: Notify bodies not sent yet, and whether a thread is sending them. Under [lock]. */
        val outbox = ArrayDeque<ByteArray>()
        var sending = false
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var stopped = false
    private var acceptThread: Thread? = null
    private var pool: ThreadPoolExecutor? = null
    private var discovery: OnvifDiscovery? = null
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val issuedNonces = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }
    /** Glassworks: was the constant "AndroidIPCamera". */
    private val realm = name.replace("\"", "")
    @Volatile private var motion = false
    /** Sends Notify to push consumers; made on the first push subscription, gone at [stop]. Under [notifierLock]. */
    private var notifier: ScheduledThreadPoolExecutor? = null
    /** Closes a Notify's socket at its deadline, on its own thread so it runs while both senders are stuck. */
    private var watchdog: ScheduledThreadPoolExecutor? = null
    private val notifierLock = Any()
    /**
     * One boundary for the motion state and who hears it: a new subscription (its cap, its insertion and its first
     * `Initialized`) and each change are queued in one order, so no consumer gets an old state after a newer one.
     * Only queueing happens under it, never network I/O.
     */
    private val eventLock = Any()
    // A Set, not ConcurrentHashMap.KeySetView, so clear() is Set's: KeySetView's own methods are API 24 (#198).
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** Actual bound TCP port (useful when constructed with port 0). */
    val localPort: Int get() = serverSocket?.localPort ?: port

    // ===================================================================== lifecycle

    @Synchronized
    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 50)
        serverSocket = ss
        running = true
        stopped = false
        pool = ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "OnvifHttp").apply { isDaemon = true }
        }
        acceptThread = Thread({ acceptLoop(ss) }, "OnvifAccept").apply { isDaemon = true; start() }
        onLog("ONVIF server listening on tcp/${ss.localPort}")
        if (enableDiscovery) {
            discovery = OnvifDiscovery(
                endpointUuid = { endpointUuid() },
                xaddr = { "${baseUrl()}/onvif/device_service" },
                scopes = { scopes() },
                port = discoveryPort,
                onLog = onLog
            ).also { it.start() }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        stopped = true
        discovery?.stop(); discovery = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        openSockets.forEach { try { it.close() } catch (_: Exception) {} }
        openSockets.clear()
        subscriptions.values.forEach { s -> synchronized(s.lock) { s.lock.notifyAll() } }
        subscriptions.clear()
        synchronized(notifierLock) {
            notifier?.shutdownNow(); notifier = null
            watchdog?.shutdownNow(); watchdog = null
        }
        pool?.shutdownNow(); pool = null
        try { acceptThread?.join(1000) } catch (_: Exception) {}
        acceptThread = null
    }

    /**
     * Publish motion state to every subscriber (both CellMotionDetector and MotionAlarm topics): queued for a pull
     * point, a Notify for a push consumer. Never waits on the network: a push only queues its Notify.
     */
    fun setMotion(active: Boolean) = synchronized(eventLock) {
        if (motion == active) return
        motion = active
        val now = System.currentTimeMillis()
        pruneExpired(now)
        val msgs = motionMessages(active, "Changed", now)
        for (s in subscriptions.values) deliver(s, msgs)
    }

    // ===================================================================== HTTP

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try { ss.accept() } catch (_: Exception) { if (running) continue else break }
            try {
                pool?.execute { serve(sock) } ?: sock.close()
            } catch (_: RejectedExecutionException) {
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    private val remoteHost = ThreadLocal<String>()
    /** The client's address, for a push consumer that is the subscriber itself. */
    private val remoteAddress = ThreadLocal<InetAddress?>()

    private fun serve(sock: Socket) {
        remoteHost.set(sock.inetAddress?.hostAddress ?: "?")
        remoteAddress.set(sock.inetAddress)
        openSockets += sock
        try {
            sock.soTimeout = 30_000
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            while (running) {
                val req = try { readRequest(input) } catch (_: SocketTimeoutException) { null } ?: break
                val resp = try {
                    handle(req.method, req.path, req.headers, req.body)
                } catch (e: Exception) {
                    onLog("ONVIF handler error: ${e.message}")
                    HttpResponse(500, OnvifXml.fault("ter:Action", "Internal error", sender = false).toByteArray())
                }
                val keepAlive = !req.headers["connection"].equals("close", true) && req.version != "HTTP/1.0"
                writeResponse(out, resp, keepAlive, head = req.method == "HEAD")
                if (!keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            openSockets -= sock
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private class Req(val method: String, val path: String, val version: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) throw IllegalStateException("header line too long")
        }
    }

    private fun readRequest(input: InputStream): Req? {
        var line = readLine(input) ?: return null
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            // Glassworks: a bound on the head, before sign-in can run.
            headerBytes += h.length
            if (headers.size >= MAX_HEADERS || headerBytes > MAX_HEADER_BYTES) throw IllegalStateException("request head too large")
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) throw IllegalStateException("body too large")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) break
            off += n
        }
        return Req(parts[0].uppercase(), parts[1], parts.getOrElse(2) { "HTTP/1.1" }, headers, body)
    }

    /** A [head] answer has the headers a GET would get, its Content-Length included, and no body (RFC 9110 9.3.2; upstream ca0085d1). */
    private fun writeResponse(out: OutputStream, r: HttpResponse, keepAlive: Boolean, head: Boolean = false) {
        val reason = when (r.status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 503 -> "Service Unavailable"; else -> "Internal Server Error" }
        val sb = StringBuilder("HTTP/1.1 ${r.status} $reason\r\n")
        sb.append("Content-Type: ${r.contentType}\r\n")
        sb.append("Content-Length: ${r.body.size}\r\n")
        sb.append("Server: ${name.replace(Regex("[\r\n]"), " ")} ONVIF\r\n")
        sb.append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
        r.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(r.body)
        out.flush()
    }

    // ===================================================================== dispatch

    internal fun handle(method: String, rawPath: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        val path = rawPath.substringBefore('?')
        if (path == "/onvif/snapshot.jpg" || path == "/snapshot.jpg") {
            if (method != "GET" && method != "HEAD") return HttpResponse(405, ByteArray(0), "text/plain")
            // Digest's stale nonce gets its fresh challenge here too (verifier 3965c4ee), as on the SOAP services.
            val auth = httpAuth(method, rawPath, headers)
            if (auth != AuthResult.OK) return unauthorized("text/plain", "Unauthorized".toByteArray(), stale = auth == AuthResult.STALE)
            val jpeg = try { snapshot?.invoke() } catch (_: Exception) { null }
                ?: return HttpResponse(503, "No snapshot available".toByteArray(), "text/plain")
            // HEAD gets GET's representation; writeResponse leaves the body out (upstream ca0085d1).
            return HttpResponse(200, jpeg, "image/jpeg", listOf("Cache-Control" to "no-store"))
        }
        if (method != "POST") {
            return HttpResponse(if (path.startsWith("/onvif")) 405 else 404, ByteArray(0), "text/plain")
        }
        val doc = OnvifXml.parse(String(body, Charsets.UTF_8))
            ?: return soapFault("ter:WellFormed", "Malformed SOAP request", 400)
        val env = doc.documentElement
        val header = OnvifXml.child(env, "Header")
        val bodyEl = OnvifXml.child(env, "Body") ?: return soapFault("ter:WellFormed", "Missing SOAP Body", 400)
        val op = OnvifXml.children(bodyEl).firstOrNull() ?: return soapFault("ter:WellFormed", "Empty SOAP Body", 400)
        val action = OnvifXml.localName(op)
        val ns = op.namespaceURI.orEmpty()
        val who = remoteHost.get() ?: "?"
        if (action != "PullMessages") onLog("ONVIF $action from $who")

        if (action !in UNAUTHENTICATED) {
            val auth = checkAuth(header, method, rawPath, headers)
            if (auth == AuthResult.BAD || auth == AuthResult.MISSING) refused(who, action, auth)
            // Events (PullMessages, Renew, CreatePullPointSubscription…): any refusal is a bare HTTP 401 with the
            // challenge and no SOAP body. Home Assistant's pull loop (2026.11.1, onvif-zeep-async 4.3.0) answers
            // a SOAP fault there by renewing and re-creating at once, every 0.75 s; a 401 without a body is a
            // TransportError to it, after which it waits 60 s. Its setup and config flow call the device and
            // media services first, which keep the SOAP fault: "auth failed" in the config flow, a reauth at setup.
            if (auth != AuthResult.OK && (ns == OnvifNs.TEV || ns == OnvifNs.WSNT)) {
                return unauthorized("text/plain; charset=utf-8", ByteArray(0), stale = auth == AuthResult.STALE)
            }
            when (auth) {
                AuthResult.OK -> {}
                AuthResult.MISSING -> return unauthorized(
                    "application/soap+xml; charset=utf-8",
                    OnvifXml.fault("ter:NotAuthorized", "Sender not authorized").toByteArray()
                )
                // A wrong sign-in gets a final SOAP fault (env:Sender, ter:NotAuthorized) and no new challenge, so
                // nothing invites a retry. Only a Digest nonce we no longer know gets one, marked stale.
                AuthResult.BAD -> return soapFault("ter:NotAuthorized", "Sender not authorized", 400)
                AuthResult.STALE -> return unauthorized(
                    "application/soap+xml; charset=utf-8",
                    OnvifXml.fault("ter:NotAuthorized", "Sender not authorized").toByteArray(),
                    stale = true,
                )
            }
        }

        val result: String? = when (ns) {
            OnvifNs.TDS -> device(action, op)
            OnvifNs.TRT -> media(action, op)
            OnvifNs.TR2 -> media2(action, op)
            OnvifNs.TEV -> events(action, op, path, header)
            OnvifNs.WSNT -> notification(action, op, path, header)
            OnvifNs.TIMG -> imaging(action)
            else -> null
        }
        if (result != null && result.startsWith("FAULT:")) {
            val (code, msg) = result.removePrefix("FAULT:").split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return soapFault(code, msg, 400)
        }
        if (result == null) onLog("ONVIF unsupported action $action ($ns) from $who")
        return if (result == null) soapFault("ter:ActionNotSupported", "Action $action not supported", 400)
        else HttpResponse(200, OnvifXml.envelope(result, wsntResponseHeader(ns, action)).toByteArray(Charsets.UTF_8))
    }

    /** WS-BaseNotification clients (hardware NVRs) expect the answer's `wsa:Action` (upstream c58342b2). */
    private fun wsntResponseHeader(ns: String, action: String): String = if (ns != OnvifNs.WSNT) "" else when (action) {
        "Subscribe" -> "<wsa:Action>$WSNT_BW/NotificationProducer/SubscribeResponse</wsa:Action>"
        "Renew" -> "<wsa:Action>$WSNT_BW/SubscriptionManager/RenewResponse</wsa:Action>"
        "Unsubscribe" -> "<wsa:Action>$WSNT_BW/SubscriptionManager/UnsubscribeResponse</wsa:Action>"
        else -> ""
    }

    private fun fault(code: String, msg: String) = "FAULT:$code|$msg"

    /** When each client's refusal was last logged. */
    private val refusalsLogged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Glassworks: says when a client's sign-in is refused, at most once a minute per client, never with the
     * username or password. Home Assistant's ONVIF integration answers a refused PullMessages by renewing
     * and re-creating its subscription every 0.75 s (the tablet run on 2026-11-14, with an entry that kept
     * an older app's sign-in); without this line the log showed only those Renew and Create calls.
     */
    private fun refused(who: String, action: String, why: AuthResult) {
        val now = System.currentTimeMillis()
        val last = refusalsLogged[who]
        if (last != null && now - last < REFUSAL_LOG_MS) return
        refusalsLogged[who] = now
        if (refusalsLogged.size > 64) refusalsLogged.entries.removeIf { now - it.value >= REFUSAL_LOG_MS }
        val reason = if (why == AuthResult.MISSING) "no sign-in sent, or none is set on the panel" else "the sign-in doesn't match the stream's"
        onLog("ONVIF $action from $who refused: $reason (said once a minute per client)")
    }

    private fun soapFault(code: String, msg: String, status: Int) =
        HttpResponse(status, OnvifXml.fault(code, msg, sender = status < 500).toByteArray(Charsets.UTF_8))

    private fun unauthorized(ct: String, body: ByteArray, stale: Boolean = false): HttpResponse {
        val nonce = OnvifXml.randomHex()
        synchronized(issuedNonces) { issuedNonces[nonce] = clock() }
        return HttpResponse(401, body, ct, listOf(
            "WWW-Authenticate" to "Digest realm=\"$realm\", qop=\"auth\", nonce=\"$nonce\", algorithm=MD5" + if (stale) ", stale=true" else "",
            "WWW-Authenticate" to "Basic realm=\"$realm\""
        ))
    }

    // ===================================================================== auth

    internal enum class AuthResult { OK, MISSING, BAD, STALE }

    private fun creds(): Pair<String, String>? = credentials()?.takeIf { it.first.isNotBlank() && it.second.isNotEmpty() }

    internal fun checkAuth(header: Element?, method: String, uri: String, headers: Map<String, String>): AuthResult {
        // Glassworks: no sign-in set means nobody gets in (upstream let everyone in).
        val c = creds() ?: return AuthResult.MISSING
        val token = OnvifXml.find(header, "UsernameToken")
        if (token != null) return if (verifyUsernameToken(token, c)) AuthResult.OK else AuthResult.BAD
        if (headers["authorization"] != null) return httpAuth(method, uri, headers)
        return AuthResult.MISSING
    }

    internal fun verifyUsernameToken(token: Element, c: Pair<String, String>): Boolean {
        val user = OnvifXml.text(token, "Username") ?: return false
        val pwEl = OnvifXml.find(token, "Password") ?: return false
        val pw = pwEl.textContent?.trim().orEmpty()
        if (!OnvifXml.constantTimeEquals(user, c.first)) return false
        val type = pwEl.getAttribute("Type").orEmpty()
        return if (type.endsWith("#PasswordText")) {
            OnvifXml.constantTimeEquals(pw, c.second)
        } else {
            // Default/#PasswordDigest. Clock skew is common on cameras, so Created isn't range-checked.
            val nonce = OnvifXml.text(token, "Nonce")?.let { OnvifBase64.decode(it) } ?: return false
            val created = OnvifXml.text(token, "Created") ?: return false
            OnvifXml.constantTimeEquals(pw, OnvifXml.passwordDigest(nonce, created, c.second))
        }
    }

    /**
     * HTTP Basic or Digest. Digest is checked against the nonce the client sent: the right password with a
     * nonce we didn't issue or have forgotten (the stream turned off and on, or it aged out) is STALE, so
     * the client gets a fresh challenge and signs in again once (RFC 7616 `stale=true`); a wrong one is BAD.
     */
    private fun httpAuth(method: String, uri: String, headers: Map<String, String>): AuthResult {
        val c = creds() ?: return AuthResult.MISSING
        val auth = headers["authorization"] ?: return AuthResult.MISSING
        return when {
            auth.startsWith("Basic ", true) -> {
                val dec = OnvifBase64.decode(auth.substring(6).trim())?.toString(Charsets.UTF_8) ?: return AuthResult.BAD
                val i = dec.indexOf(':')
                val ok = i > 0 && OnvifXml.constantTimeEquals(dec.substring(0, i), c.first) &&
                    OnvifXml.constantTimeEquals(dec.substring(i + 1), c.second)
                if (ok) AuthResult.OK else AuthResult.BAD
            }
            auth.startsWith("Digest ", true) -> {
                val p = parseAuthParams(auth.substring(7))
                if (p["username"] != c.first) return AuthResult.BAD
                // Our realm only, and the request's own path, as RTSP checks (#239 verifier f41eab5d).
                if (p["realm"] != realm) return AuthResult.BAD
                val digestUri = p["uri"] ?: return AuthResult.BAD
                if (!sameResource(digestUri, uri)) return AuthResult.BAD
                val nonce = p["nonce"] ?: return AuthResult.BAD
                val ha1 = OnvifXml.md5Hex("${c.first}:$realm:${c.second}")
                val ha2 = OnvifXml.md5Hex("$method:$digestUri")
                val expected = if (p["qop"] != null)
                    OnvifXml.md5Hex("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:${p["qop"]}:$ha2")
                else OnvifXml.md5Hex("$ha1:$nonce:$ha2")
                val issued = synchronized(issuedNonces) { issuedNonces[nonce] }
                when {
                    !OnvifXml.constantTimeEquals(expected, p["response"].orEmpty()) -> AuthResult.BAD
                    // Not ours, forgotten, or older than RTSP's 12 h: the right password signs in again once.
                    issued == null || clock() - issued > NONCE_MS -> AuthResult.STALE
                    else -> AuthResult.OK
                }
            }
            else -> AuthResult.BAD
        }
    }

    /**
     * The Digest `uri` names the resource requested ([request]: its path and query): as sent, or in
     * absolute form (`http://host:port/path`, RFC 7616 allows it) with the same path.
     */
    private fun sameResource(digestUri: String, request: String): Boolean {
        val abs = Regex("^https?://[^/]*(/.*)?$", RegexOption.IGNORE_CASE).find(digestUri)
        val path = if (abs != null) abs.groupValues[1].ifEmpty { "/" } else digestUri
        return path == request
    }

    private fun parseAuthParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^\s,]+))""").findAll(s).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
        return out
    }

    // ===================================================================== helpers

    private fun baseUrl() = "http://${host()}:$localPort"
    private fun endpointUuid() = info().let { OnvifDiscovery.uuidFor(it.mac.ifBlank { it.serial }) }
    private fun scopes() = info().let { OnvifDiscovery.defaultScopes(it.name, it.model) }
    private fun e(s: String?) = OnvifXml.esc(s)

    private fun rtspPort(): Int = try { URI(stream().rtspUrl).port.takeIf { it > 0 } ?: 554 } catch (_: Exception) { 554 }

    private fun macColon(): String {
        val hex = info().mac.filter { it.isLetterOrDigit() }.lowercase()
        return if (hex.length == 12) hex.chunked(2).joinToString(":") else info().mac
    }

    private fun profileTokens() = listOf("main", "sub")

    private fun dateTimeXml(prefix: String, ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return "<tt:$prefix><tt:Time><tt:Hour>${cal.get(java.util.Calendar.HOUR_OF_DAY)}</tt:Hour><tt:Minute>${cal.get(java.util.Calendar.MINUTE)}</tt:Minute><tt:Second>${cal.get(java.util.Calendar.SECOND)}</tt:Second></tt:Time>" +
            "<tt:Date><tt:Year>${cal.get(java.util.Calendar.YEAR)}</tt:Year><tt:Month>${cal.get(java.util.Calendar.MONTH) + 1}</tt:Month><tt:Day>${cal.get(java.util.Calendar.DAY_OF_MONTH)}</tt:Day></tt:Date></tt:$prefix>"
    }

    // ===================================================================== device service

    private fun device(action: String, op: Element): String? {
        val i = info()
        val b = baseUrl()
        return when (action) {
            "GetSystemDateAndTime" -> {
                val now = System.currentTimeMillis()
                "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>" +
                    "<tt:DateTimeType>NTP</tt:DateTimeType><tt:DaylightSavings>false</tt:DaylightSavings>" +
                    "<tt:TimeZone><tt:TZ>GMT0</tt:TZ></tt:TimeZone>" +
                    dateTimeXml("UTCDateTime", now) + dateTimeXml("LocalDateTime", now) +
                    "</tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"
            }
            "GetDeviceInformation" -> "<tds:GetDeviceInformationResponse>" +
                "<tds:Manufacturer>${e(i.manufacturer)}</tds:Manufacturer><tds:Model>${e(i.model)}</tds:Model>" +
                "<tds:FirmwareVersion>${e(i.firmware)}</tds:FirmwareVersion><tds:SerialNumber>${e(i.serial)}</tds:SerialNumber>" +
                "<tds:HardwareId>${e(i.hardwareId)}</tds:HardwareId></tds:GetDeviceInformationResponse>"
            "GetCapabilities" -> "<tds:GetCapabilitiesResponse><tds:Capabilities>" +
                "<tt:Device><tt:XAddr>$b/onvif/device_service</tt:XAddr>" +
                "<tt:Network><tt:IPFilter>false</tt:IPFilter><tt:ZeroConfiguration>false</tt:ZeroConfiguration><tt:IPVersion6>false</tt:IPVersion6><tt:DynDNS>false</tt:DynDNS></tt:Network>" +
                "<tt:System><tt:DiscoveryResolve>false</tt:DiscoveryResolve><tt:DiscoveryBye>true</tt:DiscoveryBye><tt:RemoteDiscovery>false</tt:RemoteDiscovery><tt:SystemBackup>false</tt:SystemBackup><tt:SystemLogging>false</tt:SystemLogging><tt:FirmwareUpgrade>false</tt:FirmwareUpgrade>" +
                "<tt:SupportedVersions><tt:Major>2</tt:Major><tt:Minor>60</tt:Minor></tt:SupportedVersions></tt:System>" +
                "<tt:Security><tt:TLS1.1>false</tt:TLS1.1><tt:TLS1.2>false</tt:TLS1.2><tt:OnboardKeyGeneration>false</tt:OnboardKeyGeneration><tt:AccessPolicyConfig>false</tt:AccessPolicyConfig><tt:X.509Token>false</tt:X.509Token><tt:SAMLToken>false</tt:SAMLToken><tt:KerberosToken>false</tt:KerberosToken><tt:RELToken>false</tt:RELToken></tt:Security>" +
                "</tt:Device>" +
                "<tt:Events><tt:XAddr>$b/onvif/events_service</tt:XAddr><tt:WSSubscriptionPolicySupport>true</tt:WSSubscriptionPolicySupport><tt:WSPullPointSupport>true</tt:WSPullPointSupport><tt:WSPausableSubscriptionManagerInterfaceSupport>false</tt:WSPausableSubscriptionManagerInterfaceSupport></tt:Events>" +
                "<tt:Imaging><tt:XAddr>$b/onvif/imaging_service</tt:XAddr></tt:Imaging>" +
                "<tt:Media><tt:XAddr>$b/onvif/media_service</tt:XAddr><tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities>" +
                "<tt:Extension><tt:ProfileCapabilities><tt:MaximumNumberOfProfiles>2</tt:MaximumNumberOfProfiles></tt:ProfileCapabilities></tt:Extension></tt:Media>" +
                "</tds:Capabilities></tds:GetCapabilitiesResponse>"
            "GetServices" -> {
                val withCaps = OnvifXml.text(op, "IncludeCapability").equals("true", true)
                fun svc(ns: String, path: String, caps: String, major: Int = 2, minor: Int = 60) =
                    "<tds:Service><tds:Namespace>$ns</tds:Namespace><tds:XAddr>$b$path</tds:XAddr>" +
                        (if (withCaps) "<tds:Capabilities>$caps</tds:Capabilities>" else "") +
                        "<tds:Version><tt:Major>$major</tt:Major><tt:Minor>$minor</tt:Minor></tds:Version></tds:Service>"
                "<tds:GetServicesResponse>" +
                    svc(OnvifNs.TDS, "/onvif/device_service", deviceServiceCaps()) +
                    svc(OnvifNs.TRT, "/onvif/media_service", mediaServiceCaps()) +
                    svc(OnvifNs.TR2, "/onvif/media2_service", media2ServiceCaps()) +
                    svc(OnvifNs.TEV, "/onvif/events_service", eventServiceCaps()) +
                    svc(OnvifNs.TIMG, "/onvif/imaging_service", "<timg:Capabilities ImageStabilization=\"false\"/>") +
                    "</tds:GetServicesResponse>"
            }
            "GetServiceCapabilities" -> "<tds:GetServiceCapabilitiesResponse>${deviceServiceCaps()}</tds:GetServiceCapabilitiesResponse>"
            "GetScopes" -> "<tds:GetScopesResponse>" + scopes().joinToString("") {
                "<tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>${e(it)}</tt:ScopeItem></tds:Scopes>"
            } + "</tds:GetScopesResponse>"
            "GetHostname" -> "<tds:GetHostnameResponse><tds:HostnameInformation><tt:FromDHCP>false</tt:FromDHCP>" +
                "<tt:Name>${e(i.name.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').ifEmpty { "glassworks-camera" })}</tt:Name></tds:HostnameInformation></tds:GetHostnameResponse>"
            "GetNetworkInterfaces" -> "<tds:GetNetworkInterfacesResponse><tds:NetworkInterfaces token=\"eth0\">" +
                "<tt:Enabled>true</tt:Enabled><tt:Info><tt:Name>wlan0</tt:Name><tt:HwAddress>${e(macColon())}</tt:HwAddress><tt:MTU>1500</tt:MTU></tt:Info>" +
                "<tt:IPv4><tt:Enabled>true</tt:Enabled><tt:Config><tt:Manual><tt:Address>${e(host())}</tt:Address><tt:PrefixLength>24</tt:PrefixLength></tt:Manual><tt:DHCP>true</tt:DHCP></tt:Config></tt:IPv4>" +
                "</tds:NetworkInterfaces></tds:GetNetworkInterfacesResponse>"
            "GetNetworkProtocols" -> "<tds:GetNetworkProtocolsResponse>" +
                "<tds:NetworkProtocols><tt:Name>HTTP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>$localPort</tt:Port></tds:NetworkProtocols>" +
                "<tds:NetworkProtocols><tt:Name>RTSP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>${rtspPort()}</tt:Port></tds:NetworkProtocols>" +
                "</tds:GetNetworkProtocolsResponse>"
            "GetDNS" -> "<tds:GetDNSResponse><tds:DNSInformation><tt:FromDHCP>true</tt:FromDHCP></tds:DNSInformation></tds:GetDNSResponse>"
            "GetNTP" -> "<tds:GetNTPResponse><tds:NTPInformation><tt:FromDHCP>true</tt:FromDHCP></tds:NTPInformation></tds:GetNTPResponse>"
            "GetNetworkDefaultGateway" -> "<tds:GetNetworkDefaultGatewayResponse><tds:NetworkGateway/></tds:GetNetworkDefaultGatewayResponse>"
            "GetZeroConfiguration" -> "<tds:GetZeroConfigurationResponse><tds:ZeroConfiguration><tt:InterfaceToken>eth0</tt:InterfaceToken><tt:Enabled>false</tt:Enabled></tds:ZeroConfiguration></tds:GetZeroConfigurationResponse>"
            "GetDiscoveryMode" -> "<tds:GetDiscoveryModeResponse><tds:DiscoveryMode>Discoverable</tds:DiscoveryMode></tds:GetDiscoveryModeResponse>"
            "GetUsers" -> "<tds:GetUsersResponse>" + (creds()?.let { "<tds:User><tt:Username>${e(it.first)}</tt:Username><tt:UserLevel>Administrator</tt:UserLevel></tds:User>" } ?: "") + "</tds:GetUsersResponse>"
            "GetWsdlUrl" -> "<tds:GetWsdlUrlResponse><tds:WsdlUrl>http://www.onvif.org/</tds:WsdlUrl></tds:GetWsdlUrlResponse>"
            "GetRelayOutputs" -> "<tds:GetRelayOutputsResponse/>"
            "GetEndpointReference" -> "<tds:GetEndpointReferenceResponse><tds:GUID>${e(endpointUuid())}</tds:GUID></tds:GetEndpointReferenceResponse>"
            else -> null
        }
    }

    private fun deviceServiceCaps() =
        "<tds:Capabilities><tds:Network IPFilter=\"false\" ZeroConfiguration=\"false\" IPVersion6=\"false\" DynDNS=\"false\" Dot11Configuration=\"false\" HostnameFromDHCP=\"false\" NTP=\"0\"/>" +
            "<tds:Security TLS1.0=\"false\" TLS1.1=\"false\" TLS1.2=\"false\" OnboardKeyGeneration=\"false\" AccessPolicyConfig=\"false\" DefaultAccessPolicy=\"false\" Dot1X=\"false\" RemoteUserHandling=\"false\" X.509Token=\"false\" SAMLToken=\"false\" KerberosToken=\"false\" UsernameToken=\"true\" HttpDigest=\"true\" RELToken=\"false\"/>" +
            "<tds:System DiscoveryResolve=\"false\" DiscoveryBye=\"true\" RemoteDiscovery=\"false\" SystemBackup=\"false\" SystemLogging=\"false\" FirmwareUpgrade=\"false\" HttpFirmwareUpgrade=\"false\" HttpSystemBackup=\"false\" HttpSystemLogging=\"false\" HttpSupportInformation=\"false\"/>" +
            "</tds:Capabilities>"

    // ===================================================================== media (ver10)

    private fun mediaServiceCaps() =
        "<trt:Capabilities SnapshotUri=\"${snapshot != null}\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\">" +
            "<trt:ProfileCapabilities MaximumNumberOfProfiles=\"2\"/>" +
            "<trt:StreamingCapabilities RTPMulticast=\"false\" RTP_TCP=\"true\" RTP_RTSP_TCP=\"true\" NonAggregateControl=\"false\" NoRTSPStreaming=\"false\"/>" +
            "</trt:Capabilities>"

    private fun videoSourceConfig(tag: String = "tt:VideoSourceConfiguration"): String {
        val s = stream()
        return "<$tag token=\"vsc0\"><tt:Name>VideoSourceConfig</tt:Name><tt:UseCount>2</tt:UseCount><tt:SourceToken>vs0</tt:SourceToken>" +
            "<tt:Bounds x=\"0\" y=\"0\" width=\"${s.width}\" height=\"${s.height}\"/></$tag>"
    }

    private fun videoEncoderConfig(token: String, tag: String = "tt:VideoEncoderConfiguration"): String {
        val s = stream()
        val fps = s.fps.coerceAtLeast(1)
        return "<$tag token=\"vec_$token\"><tt:Name>${if (token == "main") "MainStream" else "SubStream"}</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:Resolution>" +
            "<tt:Quality>5</tt:Quality><tt:RateControl><tt:FrameRateLimit>$fps</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>${s.bitrateKbps}</tt:BitrateLimit></tt:RateControl>" +
            "<tt:H264><tt:GovLength>${fps}</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>" +
            "<tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address><tt:Port>0</tt:Port><tt:TTL>1</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast>" +
            "<tt:SessionTimeout>PT60S</tt:SessionTimeout></$tag>"
    }

    private fun profileXml(token: String, tag: String = "trt:Profiles"): String =
        "<$tag token=\"$token\" fixed=\"true\"><tt:Name>${if (token == "main") "MainStream" else "SubStream"}</tt:Name>" +
            videoSourceConfig() + videoEncoderConfig(token) + "</$tag>"

    private fun profileToken(op: Element): String? = OnvifXml.text(op, "ProfileToken")

    private fun streamUri(): String = stream().rtspUrl

    private fun media(action: String, op: Element): String? {
        val s = stream()
        return when (action) {
            "GetServiceCapabilities" -> "<trt:GetServiceCapabilitiesResponse>${mediaServiceCaps()}</trt:GetServiceCapabilitiesResponse>"
            "GetProfiles" -> "<trt:GetProfilesResponse>${profileTokens().joinToString("") { profileXml(it) }}</trt:GetProfilesResponse>"
            "GetProfile" -> {
                val t = profileToken(op) ?: "main"
                if (t !in profileTokens()) fault("ter:NoProfile", "Unknown profile $t")
                else "<trt:GetProfileResponse>${profileXml(t, "trt:Profile")}</trt:GetProfileResponse>"
            }
            "GetVideoSources" -> "<trt:GetVideoSourcesResponse><trt:VideoSources token=\"vs0\"><tt:Framerate>${s.fps}</tt:Framerate>" +
                "<tt:Resolution><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:Resolution></trt:VideoSources></trt:GetVideoSourcesResponse>"
            "GetVideoSourceConfigurations" -> "<trt:GetVideoSourceConfigurationsResponse>${videoSourceConfig("trt:Configurations")}</trt:GetVideoSourceConfigurationsResponse>"
            "GetVideoSourceConfiguration" -> "<trt:GetVideoSourceConfigurationResponse>${videoSourceConfig("trt:Configuration")}</trt:GetVideoSourceConfigurationResponse>"
            "GetCompatibleVideoSourceConfigurations" -> "<trt:GetCompatibleVideoSourceConfigurationsResponse>${videoSourceConfig("trt:Configurations")}</trt:GetCompatibleVideoSourceConfigurationsResponse>"
            "GetVideoEncoderConfigurations" -> "<trt:GetVideoEncoderConfigurationsResponse>${profileTokens().joinToString("") { videoEncoderConfig(it, "trt:Configurations") }}</trt:GetVideoEncoderConfigurationsResponse>"
            "GetCompatibleVideoEncoderConfigurations" -> "<trt:GetCompatibleVideoEncoderConfigurationsResponse>${profileTokens().joinToString("") { videoEncoderConfig(it, "trt:Configurations") }}</trt:GetCompatibleVideoEncoderConfigurationsResponse>"
            "GetVideoEncoderConfiguration" -> {
                val t = OnvifXml.text(op, "ConfigurationToken")?.removePrefix("vec_") ?: "main"
                "<trt:GetVideoEncoderConfigurationResponse>${videoEncoderConfig(if (t in profileTokens()) t else "main", "trt:Configuration")}</trt:GetVideoEncoderConfigurationResponse>"
            }
            "GetVideoEncoderConfigurationOptions" -> "<trt:GetVideoEncoderConfigurationOptionsResponse><trt:Options>" +
                "<tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max></tt:QualityRange>" +
                "<tt:H264><tt:ResolutionsAvailable><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:ResolutionsAvailable>" +
                "<tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>300</tt:Max></tt:GovLengthRange><tt:FrameRateRange><tt:Min>1</tt:Min><tt:Max>${s.fps.coerceAtLeast(1)}</tt:Max></tt:FrameRateRange>" +
                "<tt:EncodingIntervalRange><tt:Min>1</tt:Min><tt:Max>1</tt:Max></tt:EncodingIntervalRange><tt:H264ProfilesSupported>Main</tt:H264ProfilesSupported></tt:H264>" +
                "</trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>"
            "SetVideoEncoderConfiguration" -> "<trt:SetVideoEncoderConfigurationResponse/>"
            "GetGuaranteedNumberOfVideoEncoderInstances" -> "<trt:GetGuaranteedNumberOfVideoEncoderInstancesResponse><trt:TotalNumber>2</trt:TotalNumber><trt:H264>2</trt:H264></trt:GetGuaranteedNumberOfVideoEncoderInstancesResponse>"
            "GetStreamUri" -> "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>${e(streamUri())}</tt:Uri>" +
                "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>"
            "GetSnapshotUri" -> if (snapshot == null) fault("ter:ActionNotSupported", "No snapshot") else
                "<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>${e(baseUrl())}/onvif/snapshot.jpg</tt:Uri>" +
                    "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetSnapshotUriResponse>"
            "GetAudioSources" -> "<trt:GetAudioSourcesResponse/>"
            "GetAudioSourceConfigurations" -> "<trt:GetAudioSourceConfigurationsResponse/>"
            "GetAudioEncoderConfigurations" -> "<trt:GetAudioEncoderConfigurationsResponse/>"
            "GetAudioOutputs" -> "<trt:GetAudioOutputsResponse/>"
            "GetVideoAnalyticsConfigurations" -> "<trt:GetVideoAnalyticsConfigurationsResponse/>"
            "GetMetadataConfigurations" -> "<trt:GetMetadataConfigurationsResponse/>"
            "GetOSDs" -> "<trt:GetOSDsResponse/>"
            "SetSynchronizationPoint" -> "<trt:SetSynchronizationPointResponse/>"
            else -> null
        }
    }

    // ===================================================================== media2 (ver20)

    private fun media2ServiceCaps() =
        "<tr2:Capabilities SnapshotUri=\"${snapshot != null}\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\" Mask=\"false\" SourceMask=\"false\">" +
            "<tr2:ProfileCapabilities MaximumNumberOfProfiles=\"2\" ConfigurationsSupported=\"VideoSource VideoEncoder\"/>" +
            "<tr2:StreamingCapabilities RTSPStreaming=\"true\" RTPMulticast=\"false\" RTP_RTSP_TCP=\"true\" NonAggregateControl=\"false\" AutoStartMulticast=\"false\"/>" +
            "</tr2:Capabilities>"

    private fun media2EncoderConfig(token: String, tag: String): String {
        val s = stream()
        return "<$tag token=\"vec_$token\" GovLength=\"${s.fps.coerceAtLeast(1)}\" Profile=\"Main\"><tt:Name>${if (token == "main") "MainStream" else "SubStream"}</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:Resolution>" +
            "<tt:RateControl ConstantBitRate=\"false\"><tt:FrameRateLimit>${s.fps}</tt:FrameRateLimit><tt:BitrateLimit>${s.bitrateKbps}</tt:BitrateLimit></tt:RateControl>" +
            "<tt:Quality>5</tt:Quality></$tag>"
    }

    private fun media2(action: String, op: Element): String? = when (action) {
        "GetServiceCapabilities" -> "<tr2:GetServiceCapabilitiesResponse>${media2ServiceCaps()}</tr2:GetServiceCapabilitiesResponse>"
        "GetProfiles" -> {
            val want = OnvifXml.text(op, "Token")
            "<tr2:GetProfilesResponse>" + profileTokens().filter { want == null || it == want }.joinToString("") { t ->
                "<tr2:Profiles token=\"$t\" fixed=\"true\"><tr2:Name>${if (t == "main") "MainStream" else "SubStream"}</tr2:Name><tr2:Configurations>" +
                    videoSourceConfig("tr2:VideoSource") + media2EncoderConfig(t, "tr2:VideoEncoder") +
                    "</tr2:Configurations></tr2:Profiles>"
            } + "</tr2:GetProfilesResponse>"
        }
        "GetVideoSourceConfigurations" -> "<tr2:GetVideoSourceConfigurationsResponse>${videoSourceConfig("tr2:Configurations")}</tr2:GetVideoSourceConfigurationsResponse>"
        "GetVideoEncoderConfigurationOptions" -> {
            val s = stream()
            val fps = s.fps.coerceAtLeast(1)
            "<tr2:GetVideoEncoderConfigurationOptionsResponse><tr2:Options GovLengthRange=\"1 300\" FrameRatesSupported=\"$fps\" " +
                "ProfilesSupported=\"Main\" ConstantBitRateSupported=\"false\" GuaranteedInstances=\"2\">" +
                "<tt:Encoding>H264</tt:Encoding><tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max></tt:QualityRange>" +
                "<tt:ResolutionsAvailable><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:ResolutionsAvailable>" +
                "<tt:BitrateRange><tt:Min>${(s.bitrateKbps / 4).coerceAtLeast(64)}</tt:Min><tt:Max>${s.bitrateKbps * 2}</tt:Max></tt:BitrateRange>" +
                "</tr2:Options></tr2:GetVideoEncoderConfigurationOptionsResponse>"
        }
        // The stream is fixed by the app's own settings; accept (and ignore) client tuning so
        // NVRs that "auto-configure" the encoder don't treat the camera as broken.
        "SetVideoEncoderConfiguration" -> "<tr2:SetVideoEncoderConfigurationResponse/>"
        "GetVideoEncoderInstances" -> "<tr2:GetVideoEncoderInstancesResponse><tr2:Info><tr2:Codec><tr2:Encoding>H264</tr2:Encoding><tr2:Number>2</tr2:Number></tr2:Codec><tr2:Total>2</tr2:Total></tr2:Info></tr2:GetVideoEncoderInstancesResponse>"
        "GetVideoEncoderConfigurations" -> "<tr2:GetVideoEncoderConfigurationsResponse>${profileTokens().joinToString("") { media2EncoderConfig(it, "tr2:Configurations") }}</tr2:GetVideoEncoderConfigurationsResponse>"
        "GetStreamUri" -> "<tr2:GetStreamUriResponse><tr2:Uri>${e(streamUri())}</tr2:Uri></tr2:GetStreamUriResponse>"
        "GetSnapshotUri" -> if (snapshot == null) fault("ter:ActionNotSupported", "No snapshot")
            else "<tr2:GetSnapshotUriResponse><tr2:Uri>${e(baseUrl())}/onvif/snapshot.jpg</tr2:Uri></tr2:GetSnapshotUriResponse>"
        "GetAudioSourceConfigurations", "GetAudioEncoderConfigurations", "GetMetadataConfigurations", "GetAnalyticsConfigurations" ->
            "<tr2:${action}Response/>"
        "SetSynchronizationPoint" -> "<tr2:SetSynchronizationPointResponse/>"
        else -> null
    }

    // ===================================================================== imaging (minimal)

    private fun imaging(action: String): String? = when (action) {
        "GetServiceCapabilities" -> "<timg:GetServiceCapabilitiesResponse><timg:Capabilities ImageStabilization=\"false\"/></timg:GetServiceCapabilitiesResponse>"
        "GetImagingSettings" -> "<timg:GetImagingSettingsResponse><timg:ImagingSettings/></timg:GetImagingSettingsResponse>"
        "GetOptions" -> "<timg:GetOptionsResponse><timg:ImagingOptions/></timg:GetOptionsResponse>"
        "GetMoveOptions" -> "<timg:GetMoveOptionsResponse><timg:MoveOptions/></timg:GetMoveOptionsResponse>"
        "GetStatus" -> "<timg:GetStatusResponse><timg:Status/></timg:GetStatusResponse>"
        else -> null
    }

    // ===================================================================== events

    private fun eventServiceCaps() =
        "<tev:Capabilities WSSubscriptionPolicySupport=\"true\" WSPullPointSupport=\"true\" WSPausableSubscriptionManagerInterfaceSupport=\"false\" " +
            "MaxNotificationProducers=\"$MAX_PUSH_SUBSCRIPTIONS\" MaxPullPoints=\"$MAX_SUBSCRIPTIONS\" PersistentNotificationStorage=\"false\"/>"

    private fun messageDescription(source: List<Pair<String, String>>, data: Pair<String, String>) =
        "<tt:MessageDescription IsProperty=\"true\"><tt:Source>" +
            source.joinToString("") { (n, t) -> "<tt:SimpleItemDescription Name=\"$n\" Type=\"$t\"/>" } +
            "</tt:Source><tt:Data><tt:SimpleItemDescription Name=\"${data.first}\" Type=\"${data.second}\"/></tt:Data></tt:MessageDescription>"

    private fun eventProperties() = "<tev:GetEventPropertiesResponse>" +
        "<tev:TopicNamespaceLocation>http://www.onvif.org/onvif/ver10/topics/topicns.xml</tev:TopicNamespaceLocation>" +
        "<wsnt:FixedTopicSet>true</wsnt:FixedTopicSet>" +
        "<wstop:TopicSet>" +
        "<tns1:RuleEngine wstop:topic=\"false\"><tns1:CellMotionDetector wstop:topic=\"false\"><tns1:Motion wstop:topic=\"true\">" +
        messageDescription(
            listOf("VideoSourceConfigurationToken" to "tt:ReferenceToken", "VideoAnalyticsConfigurationToken" to "tt:ReferenceToken", "Rule" to "xs:string"),
            "IsMotion" to "xs:boolean"
        ) + "</tns1:Motion></tns1:CellMotionDetector></tns1:RuleEngine>" +
        "<tns1:VideoSource wstop:topic=\"false\"><tns1:MotionAlarm wstop:topic=\"true\">" +
        messageDescription(listOf("Source" to "tt:ReferenceToken"), "State" to "xs:boolean") +
        "</tns1:MotionAlarm></tns1:VideoSource>" +
        "</wstop:TopicSet>" +
        "<wsnt:TopicExpressionDialect>$TOPIC_DIALECT</wsnt:TopicExpressionDialect>" +
        "<wsnt:TopicExpressionDialect>http://docs.oasis-open.org/wsnt/t-1/TopicExpression/ConcreteSet</wsnt:TopicExpressionDialect>" +
        "<tev:MessageContentFilterDialect>http://www.onvif.org/ver10/tev/messageContentFilter/ItemFilter</tev:MessageContentFilterDialect>" +
        "<tev:MessageContentSchemaLocation>http://www.onvif.org/onvif/ver10/schema/onvif.xsd</tev:MessageContentSchemaLocation>" +
        "</tev:GetEventPropertiesResponse>"

    private fun notificationMessage(topic: String, utc: String, op: String, source: List<Pair<String, String>>, data: Pair<String, String>) =
        "<wsnt:NotificationMessage><wsnt:Topic Dialect=\"$TOPIC_DIALECT\">$topic</wsnt:Topic><wsnt:Message>" +
            "<tt:Message UtcTime=\"$utc\" PropertyOperation=\"$op\"><tt:Source>" +
            source.joinToString("") { (n, v) -> "<tt:SimpleItem Name=\"$n\" Value=\"$v\"/>" } +
            "</tt:Source><tt:Data><tt:SimpleItem Name=\"${data.first}\" Value=\"${data.second}\"/></tt:Data></tt:Message>" +
            "</wsnt:Message></wsnt:NotificationMessage>"

    private fun motionMessages(active: Boolean, op: String, now: Long): List<String> {
        val utc = OnvifXml.isoUtc(now)
        return listOf(
            notificationMessage(
                TOPIC_CELL, utc, op,
                listOf("VideoSourceConfigurationToken" to "vsc0", "VideoAnalyticsConfigurationToken" to "vac0", "Rule" to "MotionDetectorRule"),
                "IsMotion" to active.toString()
            ),
            notificationMessage(TOPIC_ALARM, utc, op, listOf("Source" to "vs0"), "State" to active.toString())
        )
    }

    private fun enqueue(s: Subscription, msgs: List<String>) {
        synchronized(s.lock) {
            msgs.forEach { s.queue.addLast(it) }
            while (s.queue.size > MAX_QUEUED) s.queue.removeFirst()
            s.lock.notifyAll()
        }
    }

    /** A pull point queues [msgs]; a push subscription sends them as a Notify. */
    private fun deliver(s: Subscription, msgs: List<String>, delayMs: Long = 0) {
        if (s.consumer != null) push(s, s.consumer, msgs, delayMs) else enqueue(s, msgs)
    }

    private fun notifier(): ScheduledThreadPoolExecutor? = synchronized(notifierLock) {
        if (stopped) return null
        if (watchdog == null) watchdog = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "OnvifNotifyWatchdog").apply { isDaemon = true } }
        notifier ?: ScheduledThreadPoolExecutor(2) { r -> Thread(r, "OnvifNotify").apply { isDaemon = true } }
            .also { notifier = it }
    }

    /**
     * Queues a Notify for [s] and, unless one is already sending, a thread to send its queue in order. The caller
     * never waits: a dead consumer costs only the notifier's threads, [NOTIFY_CONNECT_MS] + [NOTIFY_READ_MS] a Notify.
     */
    private fun push(s: Subscription, c: PushConsumer, msgs: List<String>, delayMs: Long) {
        val body = notifyXml(c.url, msgs).toByteArray(Charsets.UTF_8)
        val start = synchronized(s.lock) {
            s.outbox.addLast(body)
            while (s.outbox.size > MAX_OUTBOX) s.outbox.removeFirst()
            (!s.sending).also { if (it) s.sending = true }
        }
        if (!start) return
        val ex = notifier()
        try {
            ex ?: throw RejectedExecutionException()
            ex.schedule({ sendOutbox(s, c) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            synchronized(s.lock) { s.outbox.clear(); s.sending = false }
        }
    }

    /**
     * Sends one Notify of [s]'s, then queues itself again behind the other consumers' turns while more wait, so a
     * slow consumer holds a sender for one exchange at a time, not for its whole outbox. [Subscription.sending] stays
     * set until the outbox is empty, which keeps each consumer's Notify in order.
     */
    private fun sendOutbox(s: Subscription, c: PushConsumer) {
        val body = synchronized(s.lock) {
            // Gone (Unsubscribe, expired, the server stopped): what's left isn't sent.
            if (stopped || subscriptions[s.id] !== s || s.expiresAt < System.currentTimeMillis()) s.outbox.clear()
            s.outbox.pollFirst().also { if (it == null) s.sending = false }
        } ?: return
        try {
            val status = (post ?: ::httpPost)(c, body)
            if (status !in 200..299) onLog("ONVIF Notify to ${c.label} answered HTTP $status")
        } catch (e: Exception) {
            onLog("ONVIF Notify to ${c.label} failed: ${e.javaClass.simpleName}")
        }
        try {
            val ex = notifier() ?: throw RejectedExecutionException()
            ex.execute { sendOutbox(s, c) }
        } catch (_: RejectedExecutionException) {
            synchronized(s.lock) { s.outbox.clear(); s.sending = false }
        }
    }

    private fun notifyXml(to: String, msgs: List<String>): String {
        // Notify is the NotificationConsumer's operation in WS-BaseNotification 1.3's WSDL (upstream named the producer).
        val header = "<wsa:Action>$WSNT_BW/NotificationConsumer/Notify</wsa:Action>" +
            "<wsa:MessageID>urn:uuid:${UUID.randomUUID()}</wsa:MessageID>" +
            "<wsa:To>${e(to)}</wsa:To>"
        return OnvifXml.envelope("<wsnt:Notify>${msgs.joinToString("")}</wsnt:Notify>", header)
    }

    /**
     * One plain HTTP/1.1 POST on a socket of our own, to the address pinned at Subscribe: no DNS again, no redirect,
     * no proxy. Returns the status, or -1 for an answer that isn't HTTP. Once connected, writing the request and
     * reading the answer's status line must end within [NOTIFY_EXCHANGE_MS], however slowly the consumer reads or
     * answers: reads are bounded by the time left, and the [watchdog] closes the socket [WATCHDOG_GRACE_MS] after the
     * deadline, which ends a write that blocks on a consumer that reads nothing.
     */
    private fun httpPost(c: PushConsumer, body: ByteArray): Int = Socket().use { sock ->
        sock.connect(InetSocketAddress(c.address, c.port), NOTIFY_CONNECT_MS)
        val deadline = System.currentTimeMillis() + NOTIFY_EXCHANGE_MS
        val guard = try {
            synchronized(notifierLock) { watchdog }?.schedule({ try { sock.close() } catch (_: Exception) {} }, NOTIFY_EXCHANGE_MS + WATCHDOG_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) { null }
        try { exchange(sock, c, body, deadline) } finally { guard?.cancel(false) }
    }

    private fun exchange(sock: Socket, c: PushConsumer, body: ByteArray, deadline: Long): Int {
        sock.tcpNoDelay = true
        val head = "POST ${c.target} HTTP/1.1\r\nHost: ${c.hostHeader}\r\n" +
            "Content-Type: application/soap+xml; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
        val input = sock.getInputStream()
        val line = StringBuilder()
        while (line.length < 256) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw SocketTimeoutException("no answer")
            sock.soTimeout = left.toInt()
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            line.append(b.toChar())
        }
        val parts = line.toString().trim().split(' ')
        return if (parts.size >= 2 && parts[0].startsWith("HTTP/")) parts[1].toIntOrNull() ?: -1 else -1
    }

    /**
     * The consumer a Subscribe names, or why it is refused. Plain `http` only (no TLS client here: a
     * consumer that wants https gets a fault, and Home Assistant then keeps its pull point). The host is resolved
     * now and the first address on the local network ([localConsumer]) that isn't one of the panel's own interfaces
     * ([ownAddress]; a subscriber on the panel itself may name it) is kept.
     */
    internal fun pushConsumer(address: String, source: InetAddress?): Pair<PushConsumer?, String> {
        if (address.length > MAX_CONSUMER_URL) return null to "ConsumerReference address too long"
        val uri = try { URI(address) } catch (_: Exception) { return null to "Invalid ConsumerReference address" }
        if (!uri.scheme.equals("http", true)) return null to "ConsumerReference must be http"
        if (uri.rawUserInfo != null) return null to "ConsumerReference must not carry a sign-in"
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null to "Invalid ConsumerReference address"
        val port = if (uri.port in 1..65535) uri.port else if (uri.port == -1) 80 else return null to "Invalid ConsumerReference port"
        val target = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        if (target.any { it <= ' ' || it.code == 0x7F }) return null to "Invalid ConsumerReference address"
        val addrs = try { resolve(host.removePrefix("[").removeSuffix("]")) } catch (_: Exception) { emptyList() }
        val pinned = addrs.firstOrNull { localConsumer(it, source) && (it == source || !ownAddress(it)) }
            ?: return null to "ConsumerReference must be on the local network"
        val hostHeader = if (uri.port == -1) host else "$host:$port"
        return PushConsumer(address, pinned, port, hostHeader, target) to ""
    }

    private fun pruneExpired(now: Long) {
        subscriptions.values.filter { it.expiresAt < now }.forEach { s ->
            subscriptions.remove(s.id)
            synchronized(s.lock) { s.lock.notifyAll() }
        }
    }

    /** Find the subscription addressed by the request path or (fallback) the wsa:To header: a pull point or a push one. */
    private fun subscriptionFor(path: String, header: Element?): Subscription? {
        fun idFrom(value: String): String = value.substringAfter("/onvif/pullpoint/", "")
            .ifEmpty { value.substringAfter("/onvif/subscription/", "") }
            .substringBefore('?').trim('/').substringBefore('/')
        val id = idFrom(path).ifEmpty { idFrom(OnvifXml.text(header, "To").orEmpty()) }
        return if (id.isEmpty()) null else subscriptions[id]
    }

    /** Makes room for one more subscription of a kind: the one of that kind ending soonest goes at the cap. */
    private fun makeRoom(push: Boolean, cap: Int) {
        val same = subscriptions.values.filter { (it.consumer != null) == push }
        if (same.size >= cap) same.minByOrNull { it.expiresAt }?.let { old ->
            subscriptions.remove(old.id)
            synchronized(old.lock) { old.lock.notifyAll() }
        }
    }

    private fun events(action: String, op: Element, path: String, header: Element?): String? {
        val now = System.currentTimeMillis()
        pruneExpired(now)
        return when (action) {
            "GetServiceCapabilities" -> "<tev:GetServiceCapabilitiesResponse>${eventServiceCaps()}</tev:GetServiceCapabilitiesResponse>"
            "GetEventProperties" -> eventProperties()
            "CreatePullPointSubscription" -> {
                val term = OnvifXml.parseTermination(OnvifXml.text(op, "InitialTerminationTime"), now) ?: (now + DEFAULT_SUB_MS)
                val sub = synchronized(eventLock) {
                    makeRoom(push = false, cap = MAX_SUBSCRIPTIONS)
                    Subscription(OnvifXml.randomHex(8), term.coerceAtLeast(now + 10_000)).also {
                        subscriptions[it.id] = it
                        enqueue(it, motionMessages(motion, "Initialized", now))
                    }
                }
                onLog("ONVIF pull-point subscription ${sub.id} created")
                "<tev:CreatePullPointSubscriptionResponse><tev:SubscriptionReference>" +
                    "<wsa:Address>${baseUrl()}/onvif/pullpoint/${sub.id}</wsa:Address></tev:SubscriptionReference>" +
                    "<wsnt:CurrentTime>${OnvifXml.isoUtc(now)}</wsnt:CurrentTime><wsnt:TerminationTime>${OnvifXml.isoUtc(sub.expiresAt)}</wsnt:TerminationTime>" +
                    "</tev:CreatePullPointSubscriptionResponse>"
            }
            "PullMessages" -> {
                val sub = subscriptionFor(path, header) ?: return fault("ter:InvalidArgVal", "Unknown subscription")
                val timeout = (OnvifXml.text(op, "Timeout")?.let { OnvifXml.parseDuration(it) } ?: 10_000L).coerceIn(0L, MAX_PULL_WAIT_MS)
                val limit = (OnvifXml.text(op, "MessageLimit")?.toIntOrNull() ?: 100).coerceAtLeast(1)
                val deadline = now + timeout
                val msgs = ArrayList<String>()
                synchronized(sub.lock) {
                    while (sub.queue.isEmpty() && !stopped && subscriptions.containsKey(sub.id)) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) break
                        // stop() wakes this wait, then interrupts the pool's threads; ART throws for an interrupt that
                        // lands before the lock is taken back, though the wait was woken first. Either way it ends.
                        try { sub.lock.wait(left) } catch (_: InterruptedException) { break }
                    }
                    while (msgs.size < limit && sub.queue.isNotEmpty()) msgs += sub.queue.removeFirst()
                }
                val t = System.currentTimeMillis()
                val summary = msgs.joinToString(",") { m ->
                    val v = Regex("Name=\"(?:IsMotion|State)\" Value=\"(\\w+)\"").find(m)?.groupValues?.get(1)
                    (if (m.contains("CellMotionDetector")) "Cell=" else "Alarm=") + v
                }
                onLog("ONVIF PullMessages from ${remoteHost.get()} timeout=${timeout}ms waited=${t - now}ms -> ${msgs.size} [$summary]")
                "<tev:PullMessagesResponse><tev:CurrentTime>${OnvifXml.isoUtc(t)}</tev:CurrentTime>" +
                    "<tev:TerminationTime>${OnvifXml.isoUtc(sub.expiresAt)}</tev:TerminationTime>" +
                    msgs.joinToString("") + "</tev:PullMessagesResponse>"
            }
            "SetSynchronizationPoint" -> {
                val sub = subscriptionFor(path, header)
                synchronized(eventLock) {
                    (sub?.let { listOf(it) } ?: subscriptions.values.toList()).forEach { deliver(it, motionMessages(motion, "Initialized", now)) }
                }
                "<tev:SetSynchronizationPointResponse/>"
            }
            "Seek" -> fault("ter:ActionNotSupported", "Seek not supported")
            else -> null
        }
    }

    /** A push subscription's end: what the consumer asked for, at least 10 s and at most [MAX_PUSH_TERM_MS] away. */
    private fun pushEnd(asked: Long?, now: Long) = (asked ?: (now + DEFAULT_SUB_MS)).coerceIn(now + 10_000, now + MAX_PUSH_TERM_MS)

    /**
     * WS-BaseNotification operations: Subscribe on the events service (push, upstream c58342b2), and Renew and
     * Unsubscribe on a pull point's or a push subscription's address. Signed in as every event call is ([handle]).
     */
    private fun notification(action: String, op: Element, path: String, header: Element?): String? {
        val now = System.currentTimeMillis()
        pruneExpired(now)
        return when (action) {
            "Subscribe" -> {
                val address = OnvifXml.find(op, "ConsumerReference")?.let { OnvifXml.text(it, "Address") }?.trim().orEmpty()
                val (consumer, why) = pushConsumer(address, remoteAddress.get())
                if (consumer == null) {
                    onLog("ONVIF push subscription from ${remoteHost.get() ?: "?"} refused: $why")
                    return fault("ter:InvalidArgVal", why)
                }
                val end = pushEnd(OnvifXml.parseTermination(OnvifXml.text(op, "InitialTerminationTime"), now), now)
                // The name was resolved above, outside the lock; only the cap, the insertion and the first Notify's
                // queueing are inside, in one order with motion changes.
                val sub = synchronized(eventLock) {
                    makeRoom(push = true, cap = MAX_PUSH_SUBSCRIPTIONS)
                    Subscription(OnvifXml.randomHex(8), end, consumer).also {
                        subscriptions[it.id] = it
                        // The state now, as a NotificationProducer sends it: the NVR starts from the right alarm state.
                        deliver(it, motionMessages(motion, "Initialized", now), FIRST_NOTIFY_DELAY_MS)
                    }
                }
                onLog("ONVIF push subscription ${sub.id} to ${consumer.label}")
                "<wsnt:SubscribeResponse><wsnt:SubscriptionReference>" +
                    "<wsa:Address>${e(baseUrl())}/onvif/subscription/${sub.id}</wsa:Address></wsnt:SubscriptionReference>" +
                    "<wsnt:CurrentTime>${OnvifXml.isoUtc(now)}</wsnt:CurrentTime><wsnt:TerminationTime>${OnvifXml.isoUtc(sub.expiresAt)}</wsnt:TerminationTime>" +
                    "</wsnt:SubscribeResponse>"
            }
            "Renew" -> {
                val sub = subscriptionFor(path, header) ?: return fault("ter:InvalidArgVal", "Unknown subscription")
                val asked = OnvifXml.parseTermination(OnvifXml.text(op, "TerminationTime"), now)
                sub.expiresAt = if (sub.consumer != null) pushEnd(asked, now) else (asked ?: (now + DEFAULT_SUB_MS)).coerceAtLeast(now + 10_000)
                "<wsnt:RenewResponse><wsnt:TerminationTime>${OnvifXml.isoUtc(sub.expiresAt)}</wsnt:TerminationTime>" +
                    "<wsnt:CurrentTime>${OnvifXml.isoUtc(now)}</wsnt:CurrentTime></wsnt:RenewResponse>"
            }
            "Unsubscribe" -> {
                val sub = subscriptionFor(path, header) ?: return fault("ter:InvalidArgVal", "Unknown subscription")
                subscriptions.remove(sub.id)
                synchronized(sub.lock) { sub.lock.notifyAll() }
                "<wsnt:UnsubscribeResponse/>"
            }
            else -> null
        }
    }
}
