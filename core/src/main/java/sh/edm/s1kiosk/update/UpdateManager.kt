package sh.edm.s1kiosk.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi
import dev.glassworks.update.InstallRights
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import sh.edm.s1kiosk.Glassworks

/**
 * App updates from GitHub Releases, shown and installed through Home Assistant's update entity
 * (docs/esphome.md, docs/releasing.md).
 *
 * - **Check:** [Releases.LIST_URL] (this app's newest release with an APK for one of the device's ABIs, [Releases.newest]) shortly after start once there is a network, then every 24 h,
 *   and on HA's "check" command (at most once a minute). ETag'd, so unchanged answers are cheap;
 *   failures and rate limits back off ([CheckSchedule]) and are logged once, not on every retry.
 * - **Install:** download the `.sha256` and the APK over HTTPS into `files/update/`, verify the
 *   digest, then the APK's package, version and signing certificate against this app ([ApkCheck]),
 *   then hand `files/update/pending.apk` to the boot image's root service ([UpdateHelper]); without
 *   it (older boot image, generic Android), Android's PackageInstaller. That asks for confirmation on
 *   the screen, except on Android 12+ where the app updates itself without a tap when Android allows it.
 *
 * Everything runs on one "s1-update" thread. [onChange] fires (on that thread) whenever the entity's
 * state may have changed.
 */
class UpdateManager(
    context: Context,
    private val runtime: Glassworks,
    private val onChange: () -> Unit,
) {
    private val ctx = context.applicationContext
    /** The S1 boot image's root update service; null on a frame without one, which installs with PackageInstaller. */
    private val root = runtime.frame.rootUpdates
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "s1-update").apply { isDaemon = true } }
    val model = UpdateModel(runtime.build.versionName, runtime.build.product)
    private val schedule = CheckSchedule()
    private var etag: String? = null
    private var cachedOffer: UpdateOffer? = null
    private var lastCheckProblem: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var stopped = false

    private val updateDir: File get() = File(ctx.filesDir, UpdateHelper.PENDING_DIR)

    fun start() {
        runtime.updates = this
        exec.execute {
            cleanup()
            root?.lastFailure()?.let { model.onInfo("Last update failed: $it") }
            model.installNote = when {
                root == null -> if (installsWithoutTap()) NO_TAP_NOTE else CONFIRM_NOTE
                root.present() -> ""
                else -> NO_HELPER_NOTE
            }
            onChange()
        }
        registerNetwork()
        exec.scheduleWithFixedDelay({ tick() }, FIRST_CHECK_DELAY_S, TICK_S, TimeUnit.SECONDS)
    }

    fun stop() {
        stopped = true
        if (runtime.updates === this) runtime.updates = null
        netCallback?.let { cb ->
            runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        netCallback = null
        exec.shutdownNow()
    }

    /** HA's update entity command (`UpdateCommand`: 1 install, 2 check). */
    fun onCommand(command: Int) {
        when (command) {
            CMD_INSTALL -> exec.execute { install() }
            CMD_CHECK -> exec.execute {
                if (schedule.tryManual(SystemClock.elapsedRealtime())) check("requested by Home Assistant")
                else Log.i(TAG, "check requested again within a minute; ignored")
            }
            else -> Log.w(TAG, "unknown update command $command")
        }
    }

    fun statusText(): String = model.statusText()

    // ------------------------------------------------------------------ check

    /**
     * Wi-Fi coming (back): a check that failed for want of a network is retried right away instead of
     * after its backoff (15 min and up). Android reports a network as available before it has been
     * validated (DNS may not work yet), so validation counts too; bursts of callbacks are debounced into
     * one retry [NETWORK_SETTLE_S] after the last one, and [CheckSchedule.onNetworkBack] allows one
     * retry per 30 s.
     */
    private fun registerNetwork() {
        val validated = java.util.Collections.synchronizedSet(HashSet<Network>())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkBack("available")
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) {
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) && validated.add(network)) {
                    networkBack("validated")
                }
            }
            override fun onLost(network: Network) { validated.remove(network) }
        }
        // An internet request below Android 7, which has no default-network callback (#198).
        if (runCatching { Platform.registerDefaultNetworkCallback(ctx.getSystemService(ConnectivityManager::class.java), cb) }.isSuccess) {
            netCallback = cb
        }
    }

    @Volatile private var netPending: java.util.concurrent.ScheduledFuture<*>? = null

    private fun networkBack(why: String) {
        if (stopped) return
        netPending?.cancel(false)
        netPending = runCatching {
            exec.schedule({
                if (schedule.onNetworkBack(SystemClock.elapsedRealtime())) Log.i(TAG, "network $why: retrying the failed update check now")
                tick()
            }, NETWORK_SETTLE_S, TimeUnit.SECONDS)
        }.getOrNull()
    }

    private fun tick() {
        try {
            val now = SystemClock.elapsedRealtime()
            model.expire(now)?.let { why ->
                Log.w(TAG, "install $why")
                onChange()
            }
            if (schedule.due(now) && model.currentPhase == UpdateModel.Phase.IDLE) check("scheduled")
        } catch (t: Throwable) {
            Log.w(TAG, "update tick failed", t) // never let the scheduled task die
        }
    }

    private fun check(reason: String) {
        val now = SystemClock.elapsedRealtime()
        val result = try {
            fetchLatest()
        } catch (e: IOException) {
            CheckResult.Failed("network: ${e.javaClass.simpleName}${e.message?.let { ": ${it.take(80)}" } ?: ""}",
                network = GithubHttp.isNetworkError(e))
        } catch (e: Exception) {
            CheckResult.Failed("${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
        when (result) {
            is CheckResult.Found -> {
                schedule.onSuccess(now)
                cachedOffer = result.offer
                model.onChecked(result.offer)
                problemCleared()
                val o = result.offer
                Log.i(TAG, "check ($reason): latest ${o?.version ?: "none"}, installed ${runtime.build.versionName}" +
                    if (model.updateAvailable) " -> update available" else "")
            }
            is CheckResult.Failed -> {
                schedule.onFailure(now, result.retryAfterMs, result.network)
                model.onCheckFailed(result.reason)
                problem("update check failed (${schedule.failures}x, next in " +
                    "${(schedule.nextAt - now) / 60_000} min): ${result.reason}")
            }
        }
        onChange()
    }

    private sealed class CheckResult {
        /** [offer] null: the repo has no usable release (none published, a draft/pre-release, odd assets). */
        class Found(val offer: UpdateOffer?) : CheckResult()
        class Failed(val reason: String, val retryAfterMs: Long? = null, val network: Boolean = false) : CheckResult()
    }

    private fun fetchLatest(): CheckResult {
        val c = open(Releases.LIST_URL)
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            etag?.let { c.setRequestProperty("If-None-Match", it) }
            val code = c.responseCode
            GithubHttp.rateLimitWaitMs(code, { c.getHeaderField(it) }, System.currentTimeMillis())?.let {
                return CheckResult.Failed("GitHub rate limit (HTTP $code)", it)
            }
            return when (code) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> CheckResult.Found(cachedOffer)
                HttpURLConnection.HTTP_NOT_FOUND -> { etag = null; CheckResult.Found(null) } // no release yet
                HttpURLConnection.HTTP_OK -> {
                    val body = readCapped(c.inputStream, MAX_JSON_BYTES).toString(Charsets.UTF_8)
                    val offer = try {
                        val pick = Releases.newest(Releases.parseList(body), Platform.supportedAbis, runtime.releases)
                        pick.problem?.let { problem("newest release not usable: $it") }
                        pick.offer
                    } catch (e: ReleaseFormatException) {
                        problem("release list not usable: ${e.message}")
                        null
                    }
                    etag = c.getHeaderField("ETag")
                    CheckResult.Found(offer)
                }
                else -> CheckResult.Failed("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun problem(msg: String) {
        if (msg.substringAfter("): ") != lastCheckProblem?.substringAfter("): ")) Log.w(TAG, msg)
        lastCheckProblem = msg
    }

    private fun problemCleared() {
        if (lastCheckProblem != null) Log.i(TAG, "update check works again")
        lastCheckProblem = null
    }

    // ------------------------------------------------------------------ install

    private fun install() {
        val now = SystemClock.elapsedRealtime()
        val a = model.beginInstall(now)
        if (a == null) {
            Log.i(TAG, "install ignored: ${model.statusText()}")
            return
        }
        Log.i(TAG, "installing ${a.offer.version} (${a.offer.apk.size} bytes)")
        onChange()
        try {
            installAttempt(a)
        } catch (e: Exception) {
            fail(a, "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        } finally {
            if (!model.isActive(a)) cleanup()
        }
    }

    private fun installAttempt(a: UpdateModel.Attempt) {
        val o = a.offer
        val dir = updateDir
        cleanup()
        if (!dir.isDirectory && !dir.mkdirs()) return fail(a, "cannot create ${dir.path}")
        val need = o.apk.size.coerceAtLeast(0) * 2 + FREE_SPACE_MARGIN
        if (dir.usableSpace < need) return fail(a, "not enough storage (${dir.usableSpace / 1_048_576} MiB free)")

        val shaText = get(o.sha256.url).let { c ->
            try {
                checkOk(c)
                readCapped(c.inputStream, Releases.MAX_SHA_BYTES).toString(Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }
        val expected = Releases.parseSha256(shaText, o.apk.name) ?: return fail(a, "malformed ${o.sha256.name}")
        o.apk.digest?.let { gh ->
            if (!gh.contentEquals(expected)) return fail(a, "${o.sha256.name} disagrees with GitHub's digest of ${o.apk.name}")
        }

        val part = File(dir, "download.part")
        val digest = download(a, part) ?: return // failed or superseded
        if (!digest.contentEquals(expected)) {
            return fail(a, "SHA-256 mismatch (got ${Releases.hex(digest).take(16)}…, release says ${Releases.hex(expected).take(16)}…)")
        }
        model.onVerifying(a, SystemClock.elapsedRealtime())
        onChange()
        apkRefusal(part, o.version)?.let { return fail(a, it) }

        val pending = File(dir, UpdateHelper.PENDING_NAME)
        if (!part.renameTo(pending)) return fail(a, "cannot move the APK into place")
        Log.i(TAG, "verified ${o.apk.name} (sha256 ${Releases.hex(digest)})")
        installVerified(a, pending)
    }

    /** The verified APK at [pending]: through the frame's root update service when its boot image has one, else PackageInstaller. */
    private fun installVerified(a: UpdateModel.Attempt, pending: File) {
        val helper = root?.takeIf { it.present() } ?: return installWithPackageInstaller(a, pending)
        helper.install(
            a.offer.version.toString(),
            installing = { model.onInstalling(a, SystemClock.elapsedRealtime(), it); onChange() },
            over = { expired(a) || stopped },
        )?.let { fail(a, it) }
    }

    /** Streams the APK into [part], reporting progress; returns its SHA-256, or null after a failure. */
    private fun download(a: UpdateModel.Attempt, part: File): ByteArray? {
        val o = a.offer
        val c = get(o.apk.url)
        try {
            checkOk(c)
            val len = c.contentLength.toLong() // an Int is enough under MAX_APK_BYTES; contentLengthLong is API 24 (#198)
            if (len > Releases.MAX_APK_BYTES) { fail(a, "download too large ($len bytes)"); return null }
            if (o.apk.size > 0 && len > 0 && len != o.apk.size) { fail(a, "size $len differs from the release's ${o.apk.size}"); return null }
            val total = if (len > 0) len else o.apk.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPublish = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (expired(a)) return null
                        done += n
                        if (done > Releases.MAX_APK_BYTES || (total > 0 && done > total)) { fail(a, "download larger than announced"); return null }
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        val t = SystemClock.elapsedRealtime()
                        if (total > 0 && model.onProgress(a, done.toDouble() / total) && t - lastPublish >= PROGRESS_PUBLISH_MS) {
                            lastPublish = t
                            onChange()
                        }
                    }
                    out.fd.sync()
                }
            }
            if (total > 0 && done != total) { fail(a, "download incomplete ($done of $total bytes)"); return null }
            model.onProgress(a, 1.0)
            onChange()
            return md.digest()
        } finally {
            c.disconnect()
        }
    }

    /** PackageManager facts about [apk] vs this app, through [ApkCheck]. */
    @Suppress("DEPRECATION")
    private fun apkRefusal(apk: File, expected: AppVersion): String? {
        val pm = ctx.packageManager
        // Both flags: on Android 9/10, getPackageArchiveInfo with only GET_SIGNING_CERTIFICATES leaves
        // signingInfo null for a downloaded (not installed) APK, so a signed release looked unsigned
        // (found on the panel with 0.4.0). GET_SIGNATURES still fills `signatures` there.
        val flags = if (Platform.hasSigningInfo) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.path, flags)?.let(::facts)
        val installed = facts(pm.getPackageInfo(ctx.packageName, flags))
        return ApkCheck.refusal(archive, installed, expected)
    }

    @Suppress("DEPRECATION")
    private fun facts(p: PackageInfo): ApkFacts {
        val modern = if (Platform.hasSigningInfo) p.signingInfo?.apkContentsSigners else null
        val signers = ApkCheck.pickSigners(
            modern?.map { it.toByteArray() },
            p.signatures?.map { it.toByteArray() },
        )
        val code = if (Platform.hasLongVersionCode) p.longVersionCode else p.versionCode.toLong()
        return ApkFacts(p.packageName.orEmpty(), p.versionName, code, signers)
    }

    // ------------------------------------------------------------------ PackageInstaller fallback

    /**
     * No root service (generic Android, or an S1 boot image from before it existed): a PackageInstaller
     * session. Android asks for confirmation on the screen ([UpdateInstallReceiver] shows it and
     * [onWaitingForTap] says so in HA), unless the app is device owner, or on Android 12+ the session
     * asks for no user action and Android allows it ([sessionParams]).
     */
    private fun installWithPackageInstaller(a: UpdateModel.Attempt, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(sessionParams(ctx.packageName, apk.length(), Platform))
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or Platform.mutablePendingIntentFlag
                val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateInstallReceiver::class.java), flags)
                model.onInstalling(a, SystemClock.elapsedRealtime(), when {
                    installsWithoutTap() -> "Installing ${a.offer.version} without a tap"
                    root == null -> "Installing ${a.offer.version}: confirm on the screen"
                    else -> "Installing ${a.offer.version}: confirm on the panel's screen (no root update service in this boot image)"
                })
                onChange()
                s.commit(pending.intentSender)
            }
            apk.delete() // the session holds its own copy

        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            throw e
        }
    }

    /**
     * Whether a PackageInstaller session can install with nobody at the screen: on Android 12+ the session asks
     * for no user action ([sessionParams]); a privileged app (`INSTALL_PACKAGES`, the Mirror's) never needs one.
     * Either way Android may still ask, and [UpdateInstallReceiver] then shows the confirmation.
     */
    private fun installsWithoutTap(): Boolean = Platform.canInstallWithoutUserAction || InstallRights.installsPackages(ctx)

    /** From [UpdateInstallReceiver]: Android asks for a tap before it installs (STATUS_PENDING_USER_ACTION). */
    internal fun onWaitingForTap() = exec.execute {
        if (model.onWaitingForTap()) {
            Log.i(TAG, "package installer: waiting for a tap on the screen")
            onChange()
        }
    }

    /** From [UpdateInstallReceiver]: the session failed or was declined. */
    internal fun onSessionFailed(message: String) = exec.execute {
        if (model.failActive("package installer: $message")) {
            Log.w(TAG, "package installer: $message")
            cleanup()
            onChange()
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * True if [a] is over: failed, or stuck past its phase's time limit. The periodic tick can't
     * enforce the limit while this thread is busy with [a], so the loops call this.
     */
    private fun expired(a: UpdateModel.Attempt): Boolean {
        model.expire(SystemClock.elapsedRealtime())?.let { why ->
            Log.w(TAG, "install of ${a.offer.version} $why")
            onChange()
        }
        return !model.isActive(a)
    }

    private fun fail(a: UpdateModel.Attempt, reason: String) {
        if (model.onFailed(a, reason)) {
            Log.w(TAG, "install of ${a.offer.version} failed: $reason")
            onChange()
        }
    }

    private fun cleanup() {
        updateDir.listFiles()?.forEach { it.delete() }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true // https -> https only (HttpURLConnection never switches protocol)
        c.setRequestProperty("User-Agent", "${runtime.releases.id}/${runtime.build.versionName} (+https://github.com/${Releases.REPO})")
        return c
    }

    /** A release download: HTTPS only, GitHub redirects to its CDN (also HTTPS). */
    private fun get(url: String): HttpURLConnection {
        if (!url.startsWith("https://")) throw IOException("refusing non-HTTPS download $url")
        return open(url).apply { setRequestProperty("Accept", "application/octet-stream") }
    }

    private fun checkOk(c: HttpURLConnection) {
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for ${c.url.path.substringAfterLast('/')}")
        if (c.url.protocol != "https") throw IOException("redirected to ${c.url.protocol}")
    }

    companion object {
        const val TAG = "S1Kiosk.Update"
        /** `UpdateCommand` (aioesphomeapi api.proto). */
        const val CMD_INSTALL = 1
        const val CMD_CHECK = 2
        const val NO_HELPER_NOTE =
            "Installing asks for confirmation on the panel's screen: this panel's boot image has no root update service yet."
        /** A frame with no root update service at all (generic Android) below Android 12. */
        const val CONFIRM_NOTE = "Installing asks for confirmation on the screen."
        /** The same on Android 12+, where the app updates itself unless Android asks for a tap. */
        const val NO_TAP_NOTE = "Installs without a tap, unless Android asks for one on the screen."
        private const val FIRST_CHECK_DELAY_S = 30L
        private const val TICK_S = 15 * 60L
        private const val NETWORK_SETTLE_S = 10L
        /**
         * The release list's cap. A release was 7 kB on average and 10 kB at most (2026-11-16, 14 releases), so a
         * full page of [Releases.LIST_URL]'s 100 is about 1 MB: 4 MB leaves room for longer notes and more assets.
         */
        internal const val MAX_JSON_BYTES = 4 * 1024 * 1024
        private const val FREE_SPACE_MARGIN = 32L * 1024 * 1024
        private const val PROGRESS_PUBLISH_MS = 1_000L

        /**
         * The PackageInstaller session for an update of [packageName]. On Android 12+ ([platform])
         * it asks for no user action; below, it is the session the app has always used.
         */
        internal fun sessionParams(packageName: String, size: Long, platform: PlatformApi): PackageInstaller.SessionParams =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(size)
                if (platform.canInstallWithoutUserAction) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

        private fun readCapped(input: InputStream, max: Int): ByteArray = input.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw IOException("response larger than $max bytes")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }
}
