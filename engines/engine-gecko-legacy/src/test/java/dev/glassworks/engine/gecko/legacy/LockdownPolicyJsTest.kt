/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The lockdown extension's policy.js (#198): what a page may fetch, run under node:test from this module's test
 * resources (`lockdown/policy.test.mjs`). Without node the test is skipped on a desk and fails on CI (`CI` set),
 * where pr.yml installs node, as ContentScriptReadyTest does.
 */
class LockdownPolicyJsTest {
    private val policyJs = "src/main/assets/lockdown/policy.js"
    private val harness = "src/test/resources/lockdown/policy.test.mjs"

    private val module: File = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, policyJs).isFile }
        ?: error("$policyJs not found from ${File("").absolutePath} or any folder above it")

    private fun node(): String? = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { n ->
        runCatching { ProcessBuilder(n, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    @Test
    fun subresourcesLoadOnlyFromHomeAssistant() {
        val node = node()
        if (node == null) {
            if (System.getenv("CI") != null) fail("node isn't installed: pr.yml's app job sets it up (actions/setup-node)")
            assumeTrue("node isn't installed", false)
        }
        val p = ProcessBuilder(node, "--test", File(module, harness).path)
            .redirectErrorStream(true)
            .apply { environment()["POLICY_JS"] = File(module, policyJs).path }
            .start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { isDaemon = true; start() }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("node --test didn't finish within 60 s:\n$out")
        }
        reader.join(5_000)
        assertEquals(out.toString(), 0, p.exitValue())
    }
}
