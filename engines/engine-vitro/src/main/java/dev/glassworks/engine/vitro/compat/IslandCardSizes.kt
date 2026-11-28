/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import android.util.Log
import dev.glassworks.web.HaPageData
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What custom cards drawn in islands answered to `getCardSize()`, by their config. HA's masonry view weighs each card
 * by it (`computeCardSize`: the card's own answer, 1 when it has none or takes over 500 ms), and so decides which
 * column a card goes in and how many columns stay. [dev.glassworks.engine.vitro.model.HaCardSize] reads it.
 *
 * HA asks every card before it places any. Vitro can't: an island answers only once its web engine and the card's
 * script have loaded, seconds after the native cards could draw, and a masonry view places its cards in order, so
 * every card after an island depends on its answer. Waiting for it would hold back the first draw of the native cards.
 * So the answers are kept on disk too, for the Home Assistant the panel shows ([useStore]): from the second start on,
 * the view is placed once with the sizes its islands gave last time, and an island that answers the same changes
 * nothing. A card weighs 1 only until its island first says (tablet Vitro run 3: twelve island cards of size 2 filled
 * 4 columns in HA, 3 in Vitro), and a card that answers differently than last time is placed again, as before.
 *
 * On disk: one file in the [HaPageData.CARD_SIZES_CACHE] folder of the app's cache folder (the factory reset deletes
 * it), holding the Home Assistant it's for and each size by a digest of the card's config, never the config itself, at most
 * [MAX_STORED] of them, the oldest going first.
 */
object IslandCardSizes {
    private const val TAG = "Glassworks.Vitro"
    private const val MAX_STORED = 512

    /** This session's answers, by config. */
    private val sizes = ConcurrentHashMap<String, Double>()

    /** What islands of this Home Assistant answered before, by [digest] of the config, oldest first. Guarded by itself. */
    private val stored = LinkedHashMap<String, Double>()
    @Volatile private var storedCount = 0

    /** The file and the Home Assistant ([digest] of its origin) answers are kept for; null keeps nothing. */
    @Volatile private var store: Pair<File, String>? = null

    /** Moves on with each [useStore] and [clear]: a load or a write for an older one does nothing. */
    private val generation = AtomicInteger()
    private val writePending = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-card-sizes").apply { isDaemon = true } }

    /** What the card with config [raw] answered, this session or an earlier one, or null when no island has said. */
    fun size(raw: JSONObject): Double? {
        if (sizes.isEmpty() && storedCount == 0) return null
        val key = raw.toString()
        sizes[key]?.let { return it }
        // Only a custom card goes to an island: a built-in one is never stored, and costs no digest.
        if (storedCount == 0 || !raw.optString("type").startsWith("custom:")) return null
        val known = synchronized(stored) { stored[digest(key)] } ?: return null
        sizes.putIfAbsent(key, known)
        return known
    }

    /** Records [size] for the card with config [raw]. True when it's new or differs from what was known. */
    fun record(raw: JSONObject, size: Double): Boolean {
        val before = size(raw)
        sizes[raw.toString()] = size
        val target = store
        if (target != null) {
            val d = digest(raw.toString())
            val changed = synchronized(stored) {
                val old = stored.remove(d)
                stored[d] = size // last answered goes last: the oldest go first past MAX_STORED
                while (stored.size > MAX_STORED) stored.remove(stored.keys.first())
                storedCount = stored.size
                old != size
            }
            if (changed) scheduleWrite()
        }
        return before != size
    }

    /**
     * Keeps answers for the Home Assistant at [origin] in [cacheDir] (null: on disk for none), and reads what it kept
     * for it before, in the background. Called as the panel connects to a Home Assistant; another one's file starts
     * over. An answer this session already has wins over the kept one.
     */
    fun useStore(cacheDir: File?, origin: String?) {
        val target = if (cacheDir == null || origin.isNullOrEmpty()) null
        else File(File(cacheDir, HaPageData.CARD_SIZES_CACHE), "sizes.json") to digest(origin)
        if (target == store) return
        val gen = generation.incrementAndGet()
        synchronized(stored) {
            stored.clear()
            storedCount = 0
        }
        store = target
        if (target == null) return
        io.execute {
            val read = readStore(target.first, target.second) ?: return@execute
            synchronized(stored) {
                if (generation.get() != gen) return@execute
                // Read back oldest first; what this session answered meanwhile is newer.
                val newer = LinkedHashMap(stored)
                stored.clear()
                stored.putAll(read)
                for ((k, v) in newer) { stored.remove(k); stored[k] = v }
                while (stored.size > MAX_STORED) stored.remove(stored.keys.first())
                storedCount = stored.size
            }
        }
    }

    /** Forgets every size (another Home Assistant, tests), in memory only: the next [useStore] reads its own. */
    fun clear() {
        generation.incrementAndGet()
        sizes.clear()
        synchronized(stored) {
            stored.clear()
            storedCount = 0
        }
        store = null
    }

    /** Waits for the reads and writes queued so far (tests). */
    internal fun awaitIo() {
        io.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun scheduleWrite() {
        if (!writePending.compareAndSet(false, true)) return
        io.execute {
            writePending.set(false)
            val target = store ?: return@execute
            val snapshot = synchronized(stored) { LinkedHashMap(stored) }
            writeStore(target.first, target.second, snapshot)
        }
    }

    private fun readStore(file: File, origin: String): Map<String, Double>? = try {
        if (!file.isFile) null else {
            val json = JSONObject(file.readText())
            if (json.optString("origin") != origin) null else {
                val list = json.optJSONArray("sizes") ?: return null
                val out = LinkedHashMap<String, Double>()
                for (i in 0 until list.length()) {
                    val e = list.optJSONArray(i) ?: continue
                    val d = e.optString(0)
                    val v = e.optDouble(1, Double.NaN)
                    if (d.length == DIGEST_CHARS && v.isFinite() && v >= 0) out[d] = v
                }
                out
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Island card sizes: ignoring an unreadable store (${e.javaClass.simpleName})")
        null
    }

    private fun writeStore(file: File, origin: String, entries: Map<String, Double>) {
        try {
            val list = org.json.JSONArray()
            for ((d, v) in entries) list.put(org.json.JSONArray().put(d).put(v))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            file.parentFile?.mkdirs()
            tmp.writeText(JSONObject().put("origin", origin).put("sizes", list).toString())
            if (!tmp.renameTo(file)) {
                tmp.delete()
                Log.w(TAG, "Island card sizes: couldn't keep them")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Island card sizes: couldn't keep them (${e.javaClass.simpleName})")
        }
    }

    private const val DIGEST_CHARS = 32

    /** The first 16 bytes of the SHA-256 of [s], in hex: a config or an origin, never kept as itself. */
    private fun digest(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(DIGEST_CHARS)
        for (i in 0 until DIGEST_CHARS / 2) {
            val b = bytes[i].toInt() and 0xFF
            out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
