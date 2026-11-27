/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.vitro.test

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactories
import dev.glassworks.engine.vitro.camera.CameraStreamFactory
import dev.glassworks.engine.vitro.camera.CameraStreamHost
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The camera end-to-end test's view into Vitro's camera streams (tools/vitro-camera-e2e): it wraps every registered
 * [CameraStreamFactories] provider, so each stream the stream choice opens reports its life to logcat, tag
 * `VitroCamE2E`, one `key=value` line per event:
 *
 *   open id=3 entity=camera.x kind=HLS live=1          the choice built a stream (live: streams not closed yet)
 *   start id=3 | active id=3 value=false               its calls from the choice
 *   first_frame id=3 entity=camera.x kind=HLS since_start_ms=812 since_open_ms=830
 *   frames id=3 rendered=15 total=120                  once a second while pictures are drawn (Listener.onFrameRendered)
 *   error id=3 ... reason=... | ended id=3 ...         what it reported
 *   close id=3 entity=camera.x kind=HLS total=120 live=0   closed: its decoder, sockets and threads are freed
 *
 * The stream the choice gets is a [Proxy] with every interface the real one has, so a card's `stream as? …` checks
 * for optional interfaces (its video size, say) see what they would without the probe. Every call goes through as
 * it came; only the listener is teed. A provider registered later (the WebRTC player's) is wrapped on the next
 * [install]. Main thread, as the seam is. This debug test APK only.
 */
internal object CameraProbe {
    private const val TAG = "VitroCamE2E"
    private val main = Handler(Looper.getMainLooper())
    private val wrappers = HashMap<CameraStreamKind, (CameraStreamHost) -> CameraStreamFactory>()
    private var ids = 0
    private var live = 0

    /** Wraps each kind's provider that isn't wrapped yet. Cheap; call it as often as a new provider may appear. */
    fun install() {
        for (kind in CameraStreamKind.values()) {
            val current = CameraStreamFactories.provider(kind) ?: continue
            if (wrappers[kind] === current) continue
            val wrapper: (CameraStreamHost) -> CameraStreamFactory = { host ->
                val inner = current(host)
                object : CameraStreamFactory {
                    override fun create(entityId: String, kind: CameraStreamKind): CameraStream? =
                        inner.create(entityId, kind)?.let { Probed(it, entityId, kind).proxy }

                    override fun prewarm() = inner.prewarm()
                }
            }
            wrappers[kind] = wrapper
            CameraStreamFactories.register(kind, wrapper)
            Log.i(TAG, "wrap kind=$kind")
        }
    }

    /** Every interface [c] and its superclasses implement, and theirs. */
    private fun interfaces(c: Class<*>): Array<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        fun add(i: Class<*>) {
            if (out.add(i)) i.interfaces.forEach(::add)
        }
        var k: Class<*>? = c
        while (k != null) {
            k.interfaces.forEach(::add)
            k = k.superclass
        }
        return out.toTypedArray()
    }

    private class Probed(private val inner: CameraStream, private val entity: String, private val kind: CameraStreamKind) : InvocationHandler {
        private val id = ++ids
        private val openedAt = SystemClock.elapsedRealtime()
        private var total = 0
        private var lastReported = 0
        private var closed = false
        private var choice: CameraStream.Listener? = null

        val proxy: CameraStream = Proxy.newProxyInstance(inner.javaClass.classLoader, interfaces(inner.javaClass), this) as CameraStream

        private val tick = object : Runnable {
            override fun run() {
                if (closed) return
                if (total != lastReported) {
                    Log.i(TAG, "frames id=$id rendered=${total - lastReported} total=$total")
                    lastReported = total
                }
                main.postDelayed(this, 1000)
            }
        }

        private val tee = object : CameraStream.Listener {
            override fun onFirstFrame(sinceStartMs: Long) {
                total++
                Log.i(TAG, "first_frame id=$id entity=$entity kind=$kind since_start_ms=$sinceStartMs " +
                    "since_open_ms=${SystemClock.elapsedRealtime() - openedAt}")
                choice?.onFirstFrame(sinceStartMs)
            }

            override fun onFrameRendered() {
                total++
                choice?.onFrameRendered()
            }

            override fun onVideoSize() {
                choice?.onVideoSize()
            }

            override fun onSurfaceReady() {
                choice?.onSurfaceReady()
            }

            override fun onSurfaceLost() {
                choice?.onSurfaceLost()
            }

            override fun onError(reason: String) {
                Log.i(TAG, "error id=$id entity=$entity kind=$kind reason=${reason.replace(Regex("\\s+"), " ").take(200)}")
                choice?.onError(reason)
            }

            override fun onEnded() {
                Log.i(TAG, "ended id=$id entity=$entity kind=$kind")
                choice?.onEnded()
            }
        }

        init {
            live++
            Log.i(TAG, "open id=$id entity=$entity kind=$kind live=$live")
            inner.listener = tee
        }

        override fun invoke(self: Any, method: Method, args: Array<out Any?>?): Any? {
            val a = args ?: emptyArray()
            when (method.name) {
                "setListener" -> if (method.declaringClass == CameraStream::class.java) {
                    choice = a[0] as CameraStream.Listener?
                    return null
                }
                "getListener" -> if (method.declaringClass == CameraStream::class.java) return choice
                "start" -> {
                    Log.i(TAG, "start id=$id")
                    main.removeCallbacks(tick)
                    main.postDelayed(tick, 1000)
                }
                "setActive" -> Log.i(TAG, "active id=$id value=${a[0]}")
                "close" -> if (!closed) {
                    closed = true
                    live--
                    main.removeCallbacks(tick)
                    // Frames drawn since the last tick are logged now, or they'd go unreported with the tick.
                    if (total != lastReported) {
                        Log.i(TAG, "frames id=$id rendered=${total - lastReported} total=$total")
                        lastReported = total
                    }
                }
                "equals" -> return self === a[0]
                "hashCode" -> return System.identityHashCode(self)
            }
            val result = try {
                method.invoke(inner, *a)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            if (method.name == "close") Log.i(TAG, "close id=$id entity=$entity kind=$kind total=$total live=$live")
            return result
        }
    }
}
