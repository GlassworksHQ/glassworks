/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.compat.CardCompat
import dev.glassworks.engine.vitro.compat.CardRoute
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.model.LovelaceParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Local-only: the checker on real dashboards (`VITRO_PARITY_DIR` holding `*-new.json`). Skipped elsewhere. */
class CompatCheckerLocalDashboardsTest {
    @Test
    fun realDashboardsRenderFullyNative() {
        val dir = System.getenv("VITRO_PARITY_DIR")?.let(::File)
        assumeTrue("VITRO_PARITY_DIR not set", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.endsWith("-new.json") }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        val failures = ArrayList<String>()
        for (f in files) {
            val report = CompatChecker.check(LovelaceParser.parseDashboard(JSONObject(f.readText())))
            println("== ${f.name}\n${report.format()}\n")
            if (report.islands.isNotEmpty()) failures += f.name
        }
        assertTrue("islands in $failures", failures.isEmpty())
    }
}

/** Probe for one card while developing. */
internal fun compatOf(json: String) = CardCompat.check(LovelaceParser.parseCard(JSONObject(json)))

class CompatCheckerSmokeTest {
    @Test
    fun unknownCustomCardFallsBack() {
        val v = compatOf("""{"type": "custom:acme-card"}""")
        assertEquals(CardRoute.ISLAND, v.route)
    }
}
