/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Node for the island page's JavaScript tests. Without node they're skipped on a desk and fail on CI (`CI` set), where
 * pr.yml installs it. A run that stalls is killed and fails the test, never hangs the suite.
 */
internal object IslandNode {
    private val candidates = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")

    /** A node that runs, or null. */
    fun find(): String? = candidates.firstOrNull { n ->
        runCatching {
            val p = ProcessBuilder(n, "--version").redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
            val done = p.waitFor(10, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            done && p.exitValue() == 0
        }.getOrDefault(false)
    }

    /** A node, or the test is skipped (on a desk) or fails (on CI). */
    fun require(): String {
        val node = find()
        if (node == null) {
            if (System.getenv("CI") != null) fail("node isn't installed: pr.yml's app job sets it up (actions/setup-node)")
            assumeTrue("node isn't installed", false)
        }
        return node!!
    }

    /**
     * Kills [p]'s descendants. Through `ProcessHandle` by reflection: android.jar, which the unit tests compile
     * against, has no `Process.toHandle()`, while the JDK they run on (17) does.
     */
    private fun killDescendants(p: Process) {
        runCatching {
            val handleType = Class.forName("java.lang.ProcessHandle")
            val handle = Process::class.java.getMethod("toHandle").invoke(p)
            @Suppress("UNCHECKED_CAST")
            val all = (handleType.getMethod("descendants").invoke(handle) as java.util.stream.Stream<Any>)
                .collect(java.util.stream.Collectors.toList())
            all.forEach { child -> runCatching { handleType.getMethod("destroyForcibly").invoke(child) } }
        }
    }

    /**
     * Runs node with [args] (and [env] added to its environment) and gives its exit code and output (stdout and stderr). The output goes to a file, so a
     * node that stalls can't block the read: after [timeoutSeconds] it is killed and the test fails.
     */
    fun run(node: String, args: List<String>, env: Map<String, String> = emptyMap(), timeoutSeconds: Long = 60): Pair<Int, String> {
        val log = File.createTempFile("island-node", ".log")
        try {
            val pb = ProcessBuilder(listOf(node) + args).redirectErrorStream(true).redirectOutput(log)
            pb.environment().putAll(env)
            val p = pb.start()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // `node --test` runs each file in a child: those go first (taken before the runner dies and they're
                // reparented), then the runner.
                killDescendants(p)
                p.destroyForcibly()
                p.waitFor(5, TimeUnit.SECONDS)
                fail("node ${args.joinToString(" ")} didn't finish in $timeoutSeconds s:\n${log.readText()}")
            }
            return p.exitValue() to log.readText()
        } finally {
            log.delete()
        }
    }
}
