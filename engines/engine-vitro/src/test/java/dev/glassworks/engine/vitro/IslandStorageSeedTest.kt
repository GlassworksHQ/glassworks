/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * An island starts its card's `localStorage` from Home Assistant's on this panel (#881). The page gets HA's copy before
 * any card script runs ([WebIslandCardView.storageSeedScript]), and the merge ([WebIslandCardView.ISLAND_STORAGE_JS])
 * runs under node against a Map-backed `Storage` ([IslandNode]: skipped on a desk without node, failed on CI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IslandStorageSeedTest {

    private fun ctx(seed: JSONObject?, resources: List<LovelaceResource> = emptyList()) = VitroCardContext(
        context = RuntimeEnvironment.getApplication(),
        haBase = "http://homeassistant.local:8123",
        store = EntityStore(),
        theme = ThemeResolver.resolve(ThemeCatalog(), true),
        resources = resources,
        accessToken = { "made-up-token" },
        callService = { _, _, _, _ -> },
        navigate = {},
        openMoreInfo = {},
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
        pageStorage = { seed },
    )

    private val card = LovelaceParser.parseCard(JSONObject("""{"type": "custom:tablet-home-card"}"""))

    @Test
    fun theSeedRunsBeforeAnyCardScript() {
        val seed = JSONObject().put("thc_snooze", """{"h_1":99}""")
        val html = WebIslandCardView.buildIslandHtml(
            ctx(seed, listOf(LovelaceResource("/local/tablet-home-card.js", "module"))), card,
        )
        val at = html.indexOf("vitroSeedStorage(window.localStorage")
        assertTrue("the seed is in the page", at > 0)
        assertTrue("before the card's script", at < html.indexOf("/local/tablet-home-card.js"))
        // A JSON string: `/` comes escaped, the same string in JavaScript.
        assertTrue("with HA's origin", html.contains("""vitroSeedStorage(window.localStorage, "http:\/\/homeassistant.local:8123", """))
    }

    @Test
    fun noCopyMeansNoSeed() {
        assertFalse(WebIslandCardView.buildIslandHtml(ctx(null), card).contains("vitroSeedStorage(window"))
    }

    @Test
    fun aValueCantEndTheScript() {
        val seed = JSONObject().put("note", "</script><script>alert(1)</script><!--")
        val script = WebIslandCardView.storageSeedScript(ctx(seed))
        assertEquals("one script element", 1, Regex("</script", RegexOption.IGNORE_CASE).findAll(script).count())
        assertFalse(script.contains("<!--"))
    }

    // ---- The merge, under node ----

    @Test
    fun theMergeKeepsWhatTheIslandChangedAndTakesWhatHaChanged() {
        val node = IslandNode.require()
        val harness = File.createTempFile("island-storage", ".mjs")
        try {
            harness.writeText(WebIslandCardView.ISLAND_STORAGE_JS + HARNESS)
            val (code, out) = IslandNode.run(node, listOf("--test", harness.path))
            assertEquals(out, 0, code)
        } finally {
            harness.delete()
        }
    }

    @Test
    fun aNodeThatStallsFailsTheTestInsteadOfHangingIt() {
        val node = IslandNode.require()
        val script = File.createTempFile("island-stall", ".mjs")
        try {
            // Writes a line, then never ends: the run must not wait for its output's end.
            script.writeText("console.log('started'); setInterval(() => {}, 1000);\n")
            val started = System.nanoTime()
            val failure = runCatching { IslandNode.run(node, listOf(script.path), timeoutSeconds = 2) }.exceptionOrNull()
            assertTrue("a stalled node fails the test: $failure", failure is AssertionError && "didn't finish" in failure.message.orEmpty())
            assertTrue("with its output", "started" in failure!!.message.orEmpty())
            assertTrue("within the timeout", System.nanoTime() - started < 20_000_000_000L)
        } finally {
            script.delete()
        }
    }

    private companion object {
        /** node:test cases for `vitroSeedStorage`, appended to it. A Map stands for the island's `localStorage`. */
        const val HARNESS = """
import { test } from 'node:test';
import assert from 'node:assert/strict';

class MemStorage {
  constructor(items) { this.m = new Map(Object.entries(items || {})); }
  getItem(k) { return this.m.has(k) ? this.m.get(k) : null; }
  setItem(k, v) { this.m.set(k, String(v)); }
  removeItem(k) { this.m.delete(k); }
  own() { const o = Object.fromEntries(this.m); delete o.__vitro_ha_storage; return o; }
}
const HA = 'http://homeassistant.local:8123';

test('a first island takes HA copy and keeps its own keys', () => {
  const s = new MemStorage({ island_only: '1' });
  vitroSeedStorage(s, HA, { thc_snooze: '{"h_1":99}', dockedSidebar: '"always_hidden"' });
  assert.deepEqual(s.own(), { island_only: '1', thc_snooze: '{"h_1":99}', dockedSidebar: '"always_hidden"' });
});

test('an app restart keeps what the island changed since (the storage survives, the same seed comes again)', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { thc_snooze: '{"h_1":99}' });
  s.setItem('thc_snooze', '{"h_1":99,"h_2":120}'); // snoozed in the island
  vitroSeedStorage(s, HA, { thc_snooze: '{"h_1":99}' }); // the next start: HA's copy didn't change
  assert.equal(s.getItem('thc_snooze'), '{"h_1":99,"h_2":120}');
});

test('a key HA changed since is taken again', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { thc_snooze: 'a' });
  s.setItem('thc_snooze', 'island');
  vitroSeedStorage(s, HA, { thc_snooze: 'b' });
  assert.equal(s.getItem('thc_snooze'), 'b');
});

test('a key HA dropped goes, unless the island changed it since', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { gone: '1', kept: '1' });
  s.setItem('kept', '2');
  vitroSeedStorage(s, HA, {});
  assert.equal(s.getItem('gone'), null);
  assert.equal(s.getItem('kept'), '2');
});

test('another Home Assistant: its copy wins every key it has', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { k: 'same' });
  vitroSeedStorage(s, 'http://192.0.2.10:8123', { k: 'same' });
  s.setItem('k', 'island');
  vitroSeedStorage(s, 'http://192.0.2.20:8123', { k: 'same' });
  assert.equal(s.getItem('k'), 'same');
});

test('another Home Assistant: what the earlier one put in and nobody changed goes, the island own values stay', () => {
  const s = new MemStorage({ mine: 'island' });
  vitroSeedStorage(s, HA, { snooze: 'on', tab: '1' });
  s.setItem('tab', '2'); // the card changed it in the island
  vitroSeedStorage(s, 'http://192.0.2.10:8123', {});
  assert.equal(s.getItem('snooze'), null);
  assert.equal(s.getItem('tab'), '2');
  assert.equal(s.getItem('mine'), 'island');
});

test('a record that does not fit leaves the island as it was, and the next start seeds it with its record', () => {
  const s = new MemStorage({ k: 'island' });
  const put = s.setItem;
  s.setItem = function (k, v) { if (k === '__vitro_ha_storage') throw new Error('QuotaExceededError'); put.call(this, k, v); };
  assert.equal(vitroSeedStorage(s, HA, { k: 'ha', n: '1' }), 0);
  assert.deepEqual(s.own(), { k: 'island' }, 'no HA value without its record');
  assert.equal(s.getItem('__vitro_ha_storage'), null);
  s.setItem = put;
  vitroSeedStorage(s, HA, { k: 'ha', n: '1' });
  assert.deepEqual(s.own(), { k: 'ha', n: '1' });
  s.setItem('n', 'card'); // the card changes a value HA's copy put in
  vitroSeedStorage(s, HA, { k: 'ha', n: '1' });
  assert.equal(s.getItem('n'), 'card', 'the island change stays: the record says HA put in 1');
});

test('a value that does not fit, with no room to correct the record, drops the record', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { a: '1' });
  let records = 0;
  const put = s.setItem;
  s.setItem = function (k, v) {
    if (k === 'b') throw new Error('QuotaExceededError');
    if (k === '__vitro_ha_storage' && ++records > 1) throw new Error('QuotaExceededError');
    put.call(this, k, v);
  };
  vitroSeedStorage(s, HA, { a: '1', b: '2' });
  assert.equal(s.getItem('__vitro_ha_storage'), null, 'never a record claiming a value the island lacks');
  s.setItem = put;
  vitroSeedStorage(s, HA, { a: '1', b: '2' });
  assert.deepEqual(s.own(), { a: '1', b: '2' });
});

test('a correction that does not fit keeps the record less the failed keys, so the island changes stay its own', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { a: '1', r: 'x' });
  s.setItem('a', 'card'); // the card changes a value HA's copy put in
  // A full storage: a record longer than the one stored doesn't fit, and the removal of r fails.
  const put = s.setItem;
  const remove = s.removeItem;
  s.setItem = function (k, v) {
    if (k === '__vitro_ha_storage' && String(v).length > (this.getItem(k) || '').length) throw new Error('QuotaExceededError');
    put.call(this, k, v);
  };
  s.removeItem = function () { throw new Error('nope'); };
  vitroSeedStorage(s, HA, { a: '1' }); // HA dropped r
  const kept = JSON.parse(s.getItem('__vitro_ha_storage'));
  assert.deepEqual(kept.items, { a: '1' }, 'the record stays, without the key that failed');
  s.setItem = put;
  s.removeItem = remove;
  vitroSeedStorage(s, HA, { a: '1' });
  assert.equal(s.getItem('a'), 'card', 'the island change survives the failed correction');
});

test('no copy leaves the island as it is', () => {
  const s = new MemStorage({ a: '1' });
  assert.equal(vitroSeedStorage(s, HA, null), 0);
  assert.deepEqual(Object.fromEntries(s.m), { a: '1' });
});

test('a replacement that does not fit keeps the old value as HA\'s, so a later removal still removes it', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { k: 'a' });
  const put = s.setItem;
  s.setItem = function (k, v) { if (k === 'k') throw new Error('QuotaExceededError'); put.call(this, k, v); };
  vitroSeedStorage(s, HA, { k: 'a much longer value' });
  assert.equal(s.getItem('k'), 'a');
  s.setItem = put;
  vitroSeedStorage(s, HA, {}); // HA dropped it; the island never changed it
  assert.equal(s.getItem('k'), null);
});

test('a removal that fails is tried again at the next start', () => {
  const s = new MemStorage();
  vitroSeedStorage(s, HA, { k: 'a' });
  const remove = s.removeItem;
  s.removeItem = function () { throw new Error('nope'); };
  vitroSeedStorage(s, HA, {});
  assert.equal(s.getItem('k'), 'a');
  s.removeItem = remove;
  vitroSeedStorage(s, HA, {});
  assert.equal(s.getItem('k'), null);
});

test('a full storage drops the keys that do not fit and keeps going', () => {
  const s = new MemStorage();
  s.setItem = function (k, v) { if (k === 'big') throw new Error('QuotaExceededError'); this.m.set(k, String(v)); };
  vitroSeedStorage(s, HA, { big: 'x', small: 'y' });
  assert.equal(s.getItem('small'), 'y');
  // Room again at the next start: the key it couldn't store is taken then, though HA's copy didn't change.
  s.setItem = function (k, v) { this.m.set(k, String(v)); };
  vitroSeedStorage(s, HA, { big: 'x', small: 'y' });
  assert.equal(s.getItem('big'), 'x');
});
"""
    }
}
