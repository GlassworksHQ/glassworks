/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.LovelaceParser
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What the entity store holds when it follows a whole dashboard (G-performance L3, fix B): its heap and HA's snapshot
 * on the wire, for Clarity's reference dashboard and a synthetic 1,000-entity one. A measure, not a check: heap after
 * GC is noisy, so it runs only when asked, and writes `store-measure.txt` into the directory it is given:
 *
 * ```
 * # Clarity's dashboard and states, as tools/perf/dashboards.py serves them (read-only, writes two JSON files):
 * (cd tools/perf && python3 -I -c "import sys,json; sys.path.insert(0,'.'); import dashboards as d; \
 *   json.dump(d.lovelace(d.DASHBOARD), open('$DIR/clarity-lovelace.json','w')); \
 *   json.dump({k: {'s': v['state'], 'a': v['attributes'], 'lc': v['last_changed']} for k, v in d.states().items()}, open('$DIR/clarity-states.json','w'))")
 * ./gradlew :engine-vitro:testDebugUnitTest --tests '*DashboardStoreMeasure*' -PvitroStoreMeasure=$DIR
 * ```
 *
 * The heap is the JVM's (64-bit, compressed references): ART on a 32-bit panel has 4-byte references too, and
 * smaller object headers, so it's an upper bound for the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DashboardStoreMeasure {
    private val dir = System.getProperty("vitro.storeMeasure")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun usedAfterGc(): Long {
        repeat(6) {
            System.gc()
            Thread.sleep(40)
        }
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory()
    }

    /** Bytes of heap one store holds after applying [snapshot] (HA's `a` map): averaged over a few copies. */
    private fun storeBytes(snapshot: String, copies: Int = 16): Long {
        val held = ArrayList<EntityStore>(copies)
        val before = usedAfterGc()
        repeat(copies) {
            // A fresh parse each: the store keeps the parsed strings, which another copy's mustn't share.
            held += EntityStore().also { it.applyCompressedEvent(JSONObject().put("a", JSONObject(snapshot)), snapshotScope = emptySet()) }
        }
        val after = usedAfterGc()
        check(held.all { it.size() > 0 })
        return (after - before) / copies
    }

    private fun only(states: JSONObject, ids: Set<String>): String {
        val out = JSONObject()
        for (id in ids) states.optJSONObject(id)?.let { out.put(id, it) }
        return out.toString()
    }

    private fun line(out: StringBuilder, what: String, entities: Int, snapshot: String) {
        val heap = storeBytes(snapshot)
        out.append(String.format(java.util.Locale.US, "%-44s %5d entities  wire %7.1f KB  heap %7.1f KB  (%4d B/entity heap, %4d B/entity wire)%n",
            what, entities, snapshot.length / 1024.0, heap / 1024.0, if (entities > 0) heap / entities else 0, if (entities > 0) snapshot.length / entities else 0))
    }

    @Test
    fun measure() {
        assumeTrue("set -PvitroStoreMeasure=<dir> to measure", dir != null)
        val out = StringBuilder()
        val lovelace = File(dir, "clarity-lovelace.json")
        val statesFile = File(dir, "clarity-states.json")
        if (lovelace.isFile && statesFile.isFile) {
            val config = LovelaceParser.parseDashboard(JSONObject(lovelace.readText()))
            val states = JSONObject(statesFile.readText())
            val t0 = System.nanoTime()
            val union = LinkedHashSet<String>()
            config.views.forEach { union += CardRouter.referencedEntities(it) }
            val collectMs = (System.nanoTime() - t0) / 1e6
            val known = union.filterTo(LinkedHashSet()) { states.has(it) }
            out.append("Clarity dashboard: ${config.views.size} views, ${union.size} referenced ids (${known.size} with a state), collected in ${"%.1f".format(collectMs)} ms on the JVM\n")
            for (v in config.views) {
                val refs = CardRouter.referencedEntities(v).filterTo(LinkedHashSet()) { states.has(it) }
                line(out, "  view ${v.path} (${v.cards.size} cards)", refs.size, only(states, refs))
            }
            line(out, "  whole dashboard (fix B)", known.size, only(states, known))
            line(out, "  every entity of the fake home", states.length(), states.toString())
        } else {
            out.append("No Clarity files in $dir: the synthetic dashboard only\n")
        }
        // A synthetic 1,000-entity dashboard: sensors and lights as HA reports them.
        val synthetic = JSONObject()
        for (i in 0 until 1_000) {
            val o = if (i % 5 < 3) {
                JSONObject().put("s", "%.1f".format(java.util.Locale.US, 15 + i % 17 * 0.7)).put("a", JSONObject()
                    .put("friendly_name", "Sensor $i temperature").put("unit_of_measurement", "°C")
                    .put("device_class", "temperature").put("state_class", "measurement"))
            } else {
                JSONObject().put("s", if (i % 2 == 0) "on" else "off").put("a", JSONObject()
                    .put("friendly_name", "Light $i").put("supported_color_modes", org.json.JSONArray().put("brightness"))
                    .put("color_mode", "brightness").put("brightness", 128).put("supported_features", 32))
            }
            o.put("lc", 1_794_500_000.0 + i)
            synthetic.put(if (i % 5 < 3) "sensor.synthetic_$i" else "light.synthetic_$i", o)
        }
        line(out, "synthetic 1,000-entity dashboard", 1_000, synthetic.toString())
        File(dir, "store-measure.txt").writeText(out.toString())
        println(out)
    }
}
