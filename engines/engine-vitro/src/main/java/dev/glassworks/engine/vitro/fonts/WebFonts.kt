/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.fonts

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.platform.Platform
import dev.glassworks.web.HaPageData
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The web fonts a dashboard's page would load, for the families its text asks for.
 *
 * Home Assistant puts each Lovelace resource of `type: css` in the page's `<head>` as a stylesheet (`loadCSS`), so
 * its `@font-face` rules (and those of the sheets it `@import`s, such as Google Fonts' CSS) declare fonts for the
 * whole page. Vitro reads the same sheets and fetches a face the first time a family it declares is drawn, as a
 * browser does with `font-display: swap`: the text draws in the next family of its list meanwhile, and every
 * listener hears once the font has arrived, to draw again.
 *
 * Cheap on a small panel:
 * - **No font is fetched until it's drawn.** The stylesheets are read the first time text asks for a family (a sheet
 *   may declare even "Roboto"), and a face only when a weight and style of it is drawn. A dashboard with no `type: css`
 *   resource makes no request.
 * - Stylesheets and fonts load on one background thread, and both are kept on disk ([configure]'s cache folder), so a
 *   restart draws from disk: the sheets as they were last read, and every face read before, are loaded before the
 *   first view asks. The sheets are read again once per run, in the background, in case they changed.
 * - Caps: [MAX_SHEETS] stylesheets of [MAX_CSS_BYTES], [MAX_FACES] font files of [MAX_FONT_BYTES] per server, and
 *   [MAX_DISK_BYTES] on disk. A font past a cap draws in its fallback, as a font that failed to load does.
 * - A parsed `Typeface` per face, weight and style, made once.
 *
 * Formats: TrueType and OpenType load as they are, WOFF 1.0 unwraps to sfnt ([Sfnt]). WOFF2 isn't decoded; a face
 * with a WOFF or TTF source next to it uses that. Google Fonts serves TrueType to a plain `Mozilla/5.0` user agent,
 * so a Google Fonts stylesheet never gets to WOFF2.
 *
 * Main thread, except the work it hands to [executor].
 */
object WebFonts {
    sealed class Lookup {
        class Ready(val typeface: Typeface) : Lookup()

        /** The family is (or may be) a web font that hasn't arrived: draw the next family, a redraw follows. */
        object Loading : Lookup()

        /** No web font by that name: the browser would skip it too. */
        object Absent : Lookup()
    }

    /** Reads a URL, at most [maxBytes]; null on any failure or past the cap. Tests swap it for fixtures. */
    fun interface Fetcher {
        fun fetch(url: String, maxBytes: Int): Fetched?
    }

    /** What a [Fetcher] read: the bytes and the URL they came from in the end (after redirects). */
    class Fetched(val bytes: ByteArray, val url: String)

    const val MAX_SHEETS = 8
    const val MAX_CSS_BYTES = 512 * 1024
    const val MAX_FACES = 12
    const val MAX_FONT_BYTES = 4 * 1024 * 1024
    const val MAX_DISK_BYTES = 24L * 1024 * 1024

    /** A plain browser user agent: Google Fonts answers it with TrueType, not WOFF2. Nothing about the panel. */
    private const val USER_AGENT = "Mozilla/5.0"
    private const val TAG = "Glassworks.Vitro"

    var fetcher: Fetcher = Fetcher(::httpFetch)
    var executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vitro-fonts").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    var postToMain: (Runnable) -> Unit = { mainHandler.post(it) }

    /**
     * Font work started and not yet back on the main thread. A testability hook: the parity harness waits for zero
     * before it draws (#793), as it does for images.
     */
    val pending = AtomicInteger()

    private enum class Sheets { NONE, PRELOADING, CACHED, LOADING, FRESH }

    private sealed class Face {
        object Loading : Face()
        object Failed : Face()
        class Ready(val file: File, val base: Typeface) : Face()
    }

    private var cssUrls: List<String> = emptyList()
    private var cacheDir: File? = null
    private var generation = 0

    /** [generation], readable from the loader thread: a job of an older one fetches and pins nothing more. */
    @Volatile private var liveGeneration = 0
    private var sheets = Sheets.NONE
    private var sheetsText: String? = null
    private var faces: Map<String, List<FontFace>> = emptyMap()
    private val loads = HashMap<String, Face>()
    private val variants = HashMap<String, Typeface>()
    /** Families drawn in their fallback while the sheets were read, with the weights and styles asked of them. */
    private val waiting = HashMap<String, MutableSet<Pair<Int, Boolean>>>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var notifyQueued = false

    /** Called (main thread) after a font arrives or the stylesheets change what a drawn family resolves to. */
    /**
     * Whether any stylesheet is configured: without one, [lookup] is Absent for every family and starts nothing, so
     * a font list's face doesn't depend on the loader ([VitroFonts.typeface] keeps it per list).
     */
    val hasSheets: Boolean get() = cssUrls.isNotEmpty()

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /**
     * The stylesheets of [resources] (`type: css`, resolved against [haBase] as HA's frontend does) and where to keep
     * fonts on disk. A different list forgets every font of the previous one.
     */
    fun configure(haBase: String, resources: List<LovelaceResource>, cacheDir: File?) {
        val urls = sheetUrls(haBase, resources)
        val dir = cacheDir?.let { File(it, HaPageData.FONTS_CACHE) } // the factory reset clears it
        if (urls == cssUrls && dir == this.cacheDir) return
        reset()
        cssUrls = urls
        this.cacheDir = dir
        if (urls.isEmpty() || dir == null) return
        sheets = Sheets.PRELOADING
        val gen = generation
        submit({
            trimDisk(dir)
            val text = readSheets(urls, dir, network = false, gen = gen)
            val parsed = text?.let { parseAll(it) }.orEmpty()
            // Every face read before has its file here: load them now, so the first view finds them ready.
            val ready = HashMap<String, Face>()
            for (face in parsed.values.flatten()) {
                val key = faceKey(face) ?: continue
                if (key in ready || ready.size >= MAX_FACES) continue
                // Only the face's first source counts here: a later one on disk is a guess until the earlier ones fail,
                // which loadFace finds out, in order.
                val url = face.supportedUrls.first()
                val file = fontFile(dir, url)
                val tf = if (file.isFile) loadTypeface(file) else null
                if (tf != null) {
                    pin(file, gen)
                    ready[key] = Face.Ready(file, tf)
                }
            }
            ({
                if (gen == generation && sheets == Sheets.PRELOADING) {
                    if (text != null) {
                        sheetsText = text
                        faces = parsed
                        loads.putAll(ready)
                    }
                    sheets = if (text != null) Sheets.CACHED else Sheets.NONE
                    // Families drawn in their fallback while this ran: start them now, or read the sheets for them.
                    val asked = takeWaiting()
                    val unknown = asked.filterKeys { it !in faces }
                    unknown.forEach { (family, wanted) -> waiting.getOrPut(family) { HashSet() } += wanted }
                    if (unknown.isNotEmpty()) startSheets()
                    resume(asked - unknown.keys)
                }
            })
        }, onError = {
            // The preload is only a head start: carry on as if the disk had nothing.
            if (gen == generation && sheets == Sheets.PRELOADING) {
                sheets = Sheets.NONE
                if (waiting.isNotEmpty()) startSheets()
            }
        })
    }

    /**
     * Whether the loader is set up for [haBase]'s [resources] now: a view whose dashboard it isn't (another view, a
     * different server) mustn't redraw for its fonts, or the redraw would set it up for itself and away from the view
     * on screen.
     */
    fun isConfiguredFor(haBase: String, resources: List<LovelaceResource>): Boolean = sheetUrls(haBase, resources) == cssUrls

    private fun sheetUrls(haBase: String, resources: List<LovelaceResource>): List<String> {
        val base = haBase.trimEnd('/') + "/"
        return resources.filter { it.type == "css" }.mapNotNull { CssFontFaces.resolve(base, it.url) }.distinct()
    }

    /** Forgets everything (a different server, or a test). */
    fun reset() {
        generation++
        cssUrls = emptyList()
        cacheDir = null
        sheets = Sheets.NONE
        sheetsText = null
        faces = emptyMap()
        loads.clear()
        variants.clear()
        waiting.clear()
        // Under the pins' lock: a job of the old generation checks it there before it pins anything.
        synchronized(pinned) {
            liveGeneration = generation
            pinned.clear()
        }
        // A notice still queued for the old fonts runs anyway; a new one mustn't wait for it.
        notifyQueued = false
    }

    /**
     * [family] (any case, unquoted) at CSS [weight] and style, if a stylesheet declares it. The first ask of a family
     * no sheet read so far declares starts reading the sheets (a sheet may declare even "Roboto" or "Inter", which then
     * wins over the built-in one, as a page's `@font-face` wins over an installed font).
     */
    fun lookup(family: String, weight: Int, italic: Boolean): Lookup {
        if (cssUrls.isEmpty()) return Lookup.Absent
        val key = family.lowercase()
        val declared = faces[key]?.filter { it.source != null }.orEmpty()
        if (declared.isEmpty()) {
            return when (sheets) {
                Sheets.FRESH -> Lookup.Absent
                Sheets.PRELOADING, Sheets.LOADING -> {
                    waiting.getOrPut(key) { HashSet() } += weight to italic
                    Lookup.Loading
                }
                Sheets.NONE, Sheets.CACHED -> {
                    waiting.getOrPut(key) { HashSet() } += weight to italic
                    startSheets()
                    Lookup.Loading
                }
            }
        }
        // Drawn from sheets read off disk: read them again once this run, in case they changed.
        if (sheets == Sheets.CACHED) startSheets()
        val face = match(candidates(declared, Platform.hasTypefaceBuilder), weight, italic) ?: return Lookup.Absent
        val id = faceKey(face) ?: return Lookup.Absent
        return when (val state = loads[id]) {
            null -> {
                if (loads.size >= MAX_FACES) return Lookup.Absent
                startFace(id, face.supportedUrls)
                Lookup.Loading
            }
            Face.Loading -> Lookup.Loading
            Face.Failed -> Lookup.Absent
            is Face.Ready -> Lookup.Ready(variant(state, id, face, weight, italic))
        }
    }

    /**
     * The faces to match from: all of them where a font can be drawn at a variation ([canVary]), else the static
     * ones, so the nearest static weight is drawn rather than a variable font's default instance (all of them when
     * the family is only variable).
     */
    fun candidates(faces: List<FontFace>, canVary: Boolean): List<FontFace> =
        if (canVary) faces else faces.filter { !it.variable }.ifEmpty { faces }

    /** A face's identity in [loads]: its drawable sources, in order (null when it has none). */
    private fun faceKey(face: FontFace): String? = face.supportedUrls.takeIf { it.isNotEmpty() }?.joinToString("\n")

    /**
     * The face a browser picks for [weight] and [italic] (CSS Fonts 4, 5.2): the style first (an italic face for
     * italic, else a normal one to slant), then the weight: an exact match or a range holding it; for 400–500 the
     * nearest heavier up to 500, then lighter, then heavier; below 400 lighter first; above 500 heavier first.
     */
    fun match(faces: List<FontFace>, weight: Int, italic: Boolean): FontFace? {
        if (faces.isEmpty()) return null
        val styled = faces.filter { it.italic == italic }.ifEmpty { faces }
        val latin = styled.filter { it.coversLatin }.ifEmpty { styled }
        // Of two rules with the same descriptors, the later one wins, as in the browser.
        latin.lastOrNull { weight in it.weightMin..it.weightMax }?.let { return it }
        // Sorts are stable: from the reversed list, the later of two faces at the same weight comes first.
        val lighter = latin.asReversed().filter { it.weightMax < weight }.sortedByDescending { it.weightMax }
        val heavier = latin.asReversed().filter { it.weightMin > weight }.sortedBy { it.weightMin }
        return when {
            weight in 400..500 -> heavier.firstOrNull { it.weightMin <= 500 } ?: lighter.firstOrNull() ?: heavier.firstOrNull()
            weight < 400 -> lighter.firstOrNull() ?: heavier.firstOrNull()
            else -> heavier.firstOrNull() ?: lighter.firstOrNull()
        }
    }

    private fun variant(ready: Face.Ready, url: String, face: FontFace, weight: Int, italic: Boolean): Typeface {
        // A variable font that can't be varied draws its default instance, taken to be the regular one.
        val varies = face.variable && Platform.hasTypefaceBuilder
        val effective = if (face.variable && !varies) 400 else weight.coerceIn(face.weightMin, face.weightMax)
        // The browser's font-synthesis: bold when 600+ is asked of a lighter face, a slant when no italic face exists.
        val fakeBold = weight >= 600 && effective < 600
        val fakeItalic = italic && !face.italic
        // Everything that builds the Typeface: one file can be a static alias and a variable family at once.
        val key = "$url|$effective|$varies|${face.italic}|$fakeBold|$fakeItalic"
        variants[key]?.let { return it }
        var tf = ready.base
        if (varies) {
            tf = runCatching {
                Typeface.Builder(ready.file).setFontVariationSettings("'wght' $effective").setWeight(effective)
                    .setItalic(face.italic).build()
            }.getOrNull()?.takeUnless { it === Typeface.DEFAULT } ?: tf
        }
        val style = (if (fakeBold) Typeface.BOLD else 0) or (if (fakeItalic) Typeface.ITALIC else 0)
        if (style != 0) tf = Typeface.create(tf, style)
        variants[key] = tf
        return tf
    }

    private fun startSheets() {
        if (sheets == Sheets.LOADING || sheets == Sheets.FRESH || sheets == Sheets.PRELOADING) return
        sheets = Sheets.LOADING
        val gen = generation
        val urls = cssUrls
        val dir = cacheDir
        submit({
            val text = readSheets(urls, dir, network = true, gen = gen)
            val parsed = text?.let { parseAll(it) }
            ({
                if (gen == generation) {
                    sheets = Sheets.FRESH
                    val asked = takeWaiting()
                    if (text != null && text != sheetsText) {
                        val changed = sheetsText != null
                        sheetsText = text
                        faces = parsed.orEmpty()
                        if (changed) {
                            // Different sheets: faces may now point elsewhere. Drawn text picks them up on the redraw.
                            val keep = faces.values.flatten().mapNotNull(::faceKey).toSet()
                            val dropped = loads.filterKeys { it !in keep }.values.filterIsInstance<Face.Ready>().map { it.file }
                            loads.keys.retainAll(keep)
                            // Their files may be evicted now, unless a face still loaded uses one.
                            val used = loads.values.filterIsInstance<Face.Ready>().map { it.file }.toSet()
                            synchronized(pinned) { dropped.filter { it !in used }.forEach { pinned -= it } }
                            variants.clear()
                            notifyChanged()
                        }
                    }
                    resume(asked)
                }
            })
        }, onError = {
            if (gen == generation && sheets == Sheets.LOADING) {
                sheets = Sheets.FRESH
                waiting.clear()
            }
        })
    }

    private fun takeWaiting(): Map<String, Set<Pair<Int, Boolean>>> = HashMap(waiting).also { waiting.clear() }

    /**
     * Families drawn in their fallback while the sheets were read: now declared, their faces start loading straight
     * away (one redraw when they arrive, not one for the sheets and another for the font), or draw now from disk.
     */
    private fun resume(asked: Map<String, Set<Pair<Int, Boolean>>>) {
        var ready = false
        for ((family, wanted) in asked) {
            if (family !in faces) continue
            for ((weight, italic) in wanted) if (lookup(family, weight, italic) is Lookup.Ready) ready = true
        }
        if (ready) notifyChanged()
    }

    private fun startFace(key: String, urls: List<String>) {
        loads[key] = Face.Loading
        val gen = generation
        val dir = cacheDir
        fun settle(result: Face) {
            // A sheet that changed meanwhile may have dropped this face: it stays dropped.
            if (gen != generation || loads[key] !== Face.Loading) return
            loads[key] = result
            // One redraw for the fonts asked together: the last of them to arrive calls it.
            if (loads.values.none { it === Face.Loading }) notifyChanged()
        }
        submit({
            val ready = loadFace(urls, dir, gen)
            ({ settle(ready ?: Face.Failed) })
        }, onError = { settle(Face.Failed) })
    }

    /**
     * The font files this server's faces use, added on the loader's thread as soon as one is loaded or written (a
     * face whose result hasn't reached the main thread yet included): never evicted to make room for another.
     */
    private val pinned: MutableSet<File> = java.util.Collections.synchronizedSet(HashSet())

    /** Pins [file] for the job of generation [gen], unless the loader was reset since it started. */
    private fun pin(file: File, gen: Int) = synchronized(pinned) { if (gen == liveGeneration) pinned += file }

    /**
     * The first of [urls] that loads, as the browser tries a face's sources in order: from disk, else fetched, made
     * sfnt and kept. A source that fails to download or isn't a font Vitro can draw (WOFF2 behind a URL with no hint)
     * moves on to the next.
     */
    private fun loadFace(urls: List<String>, dir: File?, gen: Int): Face.Ready? {
        for (url in urls) {
            // A different server since this job started: nothing more to do for the old one.
            if (gen != liveGeneration) return null
            val file = dir?.let { fontFile(it, url) } ?: File.createTempFile("vitro-font", ".font").apply { deleteOnExit() }
            if (dir != null && file.isFile) {
                file.setLastModified(System.currentTimeMillis())
                pin(file, gen)
                loadTypeface(file)?.let { return Face.Ready(file, it) }
                pinned -= file
            }
            val bytes = fetch(url, MAX_FONT_BYTES)?.bytes ?: continue
            val sfnt = Sfnt.toSfnt(bytes, MAX_FONT_BYTES)
            if (sfnt == null) {
                Log.w(TAG, "Web font source isn't TrueType, OpenType or WOFF: trying the next")
                continue
            }
            val saved = try {
                if (dir != null) publish(dir, file, sfnt, pinFor = gen) else file.writeBytes(sfnt).let { true }
            } catch (e: Exception) {
                Log.w(TAG, "Web font not saved: ${e.javaClass.simpleName}")
                false
            }
            if (!saved) continue
            loadTypeface(file)?.let { return Face.Ready(file, it) }
        }
        return null
    }

    /**
     * Writes [bytes] to [file] in the cache folder only if the folder stays under [MAX_DISK_BYTES]: the files used
     * longest ago go first, never a [pinned] one. False when it can't fit: the font then draws in its fallback, like
     * one past any other cap. [pinFor] pins [file] once written, for that generation's job. Loader thread only.
     */
    private fun publish(dir: File, file: File, bytes: ByteArray, pinFor: Int? = null): Boolean {
        dir.mkdirs()
        val others = dir.listFiles()?.filter { it.isFile && it != file && !it.name.endsWith(".part") }.orEmpty()
        var total = others.sumOf { it.length() } + bytes.size
        val keep = synchronized(pinned) { pinned.toSet() }
        for (f in others.filter { it !in keep }.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES) break
            val size = f.length()
            // Only a file that's really gone makes room.
            if (f.delete()) total -= size
        }
        if (total > MAX_DISK_BYTES) return false
        val tmp = File(file.path + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return false
        }
        pinFor?.let { pin(file, it) }
        return true
    }

    /**
     * [file] as a Typeface, or null (and the file deleted) when it isn't one. Android 10 answers a file it can't load
     * with [Typeface.DEFAULT] instead of throwing: that's a failure too, so the next source gets its turn.
     */
    private fun loadTypeface(file: File): Typeface? {
        val tf = try {
            Typeface.createFromFile(file)
        } catch (_: Exception) {
            null
        }
        if (tf == null || tf === Typeface.DEFAULT) {
            file.delete()
            return null
        }
        return tf
    }

    /**
     * Every sheet of [urls] and the ones they import, joined in the order the browser applies them (an imported sheet
     * where its `@import` stands, before the rules of the sheet that imports it), or null when none could be read.
     * A cached copy that can't be read is a cache miss.
     */
    private fun readSheets(urls: List<String>, dir: File?, network: Boolean, gen: Int): String? {
        val out = StringBuilder()
        // Each sheet is read once ([MAX_SHEETS] of them), but every place it's imported counts, in order: the cascade
        // sees `common.css` again after `a.css`'s overrides when `b.css` imports it too. Only a cycle is cut.
        val read = HashMap<String, Pair<String, String>?>()
        val open = HashSet<String>()
        var occurrences = 0
        var any = false
        // A cached sheet keeps the URL it came from in the end (its first line): its URLs resolve against that.
        fun cached(file: File?): Pair<String, String>? = file?.takeIf { it.isFile }?.let { f ->
            runCatching { f.readText() }.getOrNull()?.let { it.substringBefore('\n') to it.substringAfter('\n', "") }
        }
        fun load(url: String): Pair<String, String>? {
            if (url in read) return read[url]
            if (read.size >= MAX_SHEETS) return null
            val file = dir?.let { File(it, hash(url) + ".css") }
            val sheet = if (network) {
                fetch(url, MAX_CSS_BYTES)?.let { f ->
                    val t = f.bytes.toString(Charsets.UTF_8)
                    if (dir != null && file != null) runCatching { publish(dir, file, (f.url + "\n" + t).toByteArray()) }
                    f.url to t
                } ?: cached(file)
            } else {
                cached(file)
            }
            read[url] = sheet
            return sheet
        }
        fun visit(url: String) {
            if (gen != liveGeneration || url in open || occurrences >= MAX_SHEET_OCCURRENCES) return
            val (base, text) = load(url) ?: return
            occurrences++
            any = true
            open += url
            // After a redirect, the sheet's URLs resolve against where it came from, as in the browser.
            CssFontFaces.parse(text, base).imports.forEach(::visit)
            open -= url
            // Each sheet's URLs resolve against that sheet: kept as a marker the parser splits on.
            out.append(SHEET_MARK).append(base).append('\n').append(text).append('\n')
        }
        urls.forEach(::visit)
        return if (any) out.toString() else null
    }

    private fun parseAll(joined: String): Map<String, List<FontFace>> {
        val all = ArrayList<FontFace>()
        for (part in joined.split(SHEET_MARK)) {
            if (part.isBlank()) continue
            val url = part.substringBefore('\n')
            all += CssFontFaces.parse(part.substringAfter('\n'), url).faces
        }
        return all.groupBy { it.family }
    }

    private fun fetch(url: String, maxBytes: Int): Fetched? {
        if (url.startsWith("data:", ignoreCase = true)) return decodeDataUri(url, maxBytes)?.let { Fetched(it, url) }
        val t0 = System.nanoTime()
        return fetcher.fetch(url, maxBytes).also {
            // No part of the URL: a query or a path can hold something private.
            note("fetch: ${it?.bytes?.size ?: "failed"} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** The last few things the loader did, with no address in them: what [describe] shows when a test times out. */
    private val trace = ArrayDeque<String>()

    private fun note(what: String) = synchronized(trace) {
        trace.addLast("${System.nanoTime() / 1_000_000 % 100_000} ${Thread.currentThread().name}: $what")
        while (trace.size > 40) trace.removeFirst()
    }

    /** The loader's state, for a test that waited too long: sheets, each face, what's waiting, and the last steps. */
    fun describe(): String =
        "sheets=$sheets faces=${faces.keys} loads=${loads.values.map { it.javaClass.simpleName }} waiting=${waiting.keys} " +
            "pending=${pending.get()} notifyQueued=$notifyQueued executor=${executor.javaClass.name} trace=" + synchronized(trace) { trace.joinToString(" | ") }

    private fun notifyChanged() {
        note("notify (queued already: $notifyQueued)")
        if (notifyQueued) return
        notifyQueued = true
        // Counted until the listeners have run: the redraw it starts (images included) is part of the font's work.
        pending.incrementAndGet()
        postToMain(Runnable {
            try {
                notifyQueued = false
                listeners.toList().forEach { it() }
            } finally {
                pending.decrementAndGet()
            }
        })
    }

    /**
     * Runs [work] on [executor] and its result on the main thread. If [work] throws, [onError] runs there instead, so
     * the loader never stays in a state that waits for an answer that won't come.
     */
    private fun submit(work: () -> (() -> Unit), onError: () -> Unit) {
        pending.incrementAndGet()
        val queuedAt = System.nanoTime()
        note("job queued on ${executor.javaClass.name}")
        try {
            executor.execute {
                val startedAt = System.nanoTime()
                note("job started after ${(startedAt - queuedAt) / 1_000_000} ms in the queue")
                val done = try {
                    work()
                } catch (t: Throwable) {
                    Log.w(TAG, "Web font work failed: ${t.javaClass.simpleName}")
                    note("job threw ${t.javaClass.simpleName}")
                    onError
                }
                note("job ran ${(System.nanoTime() - startedAt) / 1_000_000} ms, result posted")
                postToMain(Runnable {
                    try {
                        done()
                    } finally {
                        pending.decrementAndGet()
                    }
                })
            }
        } catch (t: Throwable) {
            pending.decrementAndGet()
            onError()
        }
    }

    private fun fontFile(dir: File, url: String) = File(dir, hash(url) + ".font")

    /** Keeps the cache folder under [MAX_DISK_BYTES], dropping the files used longest ago. */
    private fun trimDisk(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > MAX_DISK_BYTES || f.name.endsWith(".part")) f.delete()
        }
    }

    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** A `data:` URI's bytes (base64 or percent-encoded), or null past [maxBytes]. */
    fun decodeDataUri(uri: String, maxBytes: Int): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        val data = uri.substring(comma + 1)
        val bytes = try {
            if (meta.endsWith(";base64", ignoreCase = true)) Base64.decode(data, Base64.DEFAULT)
            else URLDecoder.decode(data.replace("+", "%2B"), "ISO-8859-1").toByteArray(Charsets.ISO_8859_1)
        } catch (_: Exception) {
            return null
        }
        return bytes.takeIf { it.size <= maxBytes }
    }

    /**
     * A plain GET, as the browser's `<link>` and `@font-face` requests are: no Home Assistant token (HA serves
     * `/local/` and `/hacsfiles/` without one, and a third-party host must never see it), and no cookies.
     */
    private fun httpFetch(url: String, maxBytes: Int): Fetched? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        // The timeouts bound each wait; this bounds the whole request, so a slow trickle can't hold the one loader
        // thread for minutes. Checked between reads, and enforced while one blocks (connecting, the headers, a body
        // read): at the deadline the connection is closed under it, and the request fails.
        val deadline = System.nanoTime() + REQUEST_DEADLINE_MS * 1_000_000L
        val cutOff = DEADLINES.schedule({ runCatching { conn.disconnect() } }, REQUEST_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            if (conn.responseCode != 200) null
            else if (conn.contentLength > maxBytes) null
            else conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes || System.nanoTime() > deadline) return null
                }
                // conn.url is where the redirects ended.
                Fetched(out.toByteArray(), conn.url.toString())
            }
        } finally {
            cutOff.cancel(false)
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private const val SHEET_MARK = "\u0000sheet:"

    /** How many times sheets may appear in the cascade in all, imports repeated included. */
    private const val MAX_SHEET_OCCURRENCES = 32

    /** The longest a stylesheet or font request may take in all. */
    private const val REQUEST_DEADLINE_MS = 30_000L

    /** Closes a request still running at its deadline ([httpFetch]). */
    private val DEADLINES = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vitro-font-deadline").apply { isDaemon = true }
    }
}
