/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import dev.glassworks.net.HappyEyeballs
import dev.glassworks.net.HttpClient
import java.io.IOException

/** What fetching a notification's picture gave. */
sealed class ImageFetch {
    class Ok(val bytes: ByteArray, val contentType: String) : ImageFetch() {
        override fun toString() = "Ok(${bytes.size} bytes, $contentType)"
    }

    data class Failed(val reason: String) : ImageFetch()
}

/** Fetches a notification's picture. Blocking: call it off the main thread. */
fun interface NotificationImages {
    fun fetch(url: String): ImageFetch
}

/** One HTTP GET, as [HaImages] uses it (a fake in tests). It must not follow redirects. */
fun interface HttpGet {
    class Response(val status: Int, val contentType: String?, val body: ByteArray, val tooLarge: Boolean)

    /** Throws [IOException] when Home Assistant can't be reached. */
    fun get(url: String, headers: Map<String, String>, maxBytes: Int): Response
}

/**
 * Pictures from the panel's own Home Assistant: a camera's `/api/camera_proxy/camera.…`, `/local/…`
 * or `/media/…`, fetched with the panel's sign-in. The sign-in only reads here (pane rule 4).
 *
 * - Only URLs on [haBase] ([HaUrls]), checked again here, so the token never goes to another host.
 * - No redirects: a redirect could carry the token elsewhere.
 * - Only JPEG, PNG, WebP and GIF ([TYPES]), at most [maxBytes].
 *
 * [token] gives an access token, or null when the panel isn't signed in; it may block. Never logs
 * the token.
 */
class HaImages(
    private val haBase: () -> String?,
    private val token: () -> String?,
    private val http: HttpGet = UrlConnectionGet(),
    private val maxBytes: Int = MAX_BYTES,
) : NotificationImages {

    override fun fetch(url: String): ImageFetch {
        if (HaUrls.resolve(haBase(), url) != url) return ImageFetch.Failed("not on this panel's Home Assistant")
        val t = token() ?: return ImageFetch.Failed("the panel isn't signed in to Home Assistant")
        val r = try {
            http.get(url, mapOf("Authorization" to "Bearer $t", "Accept" to "image/*"), maxBytes)
        } catch (e: IOException) {
            return ImageFetch.Failed("can't reach Home Assistant (${e.javaClass.simpleName})")
        }
        val type = r.contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            r.status == 401 || r.status == 403 -> ImageFetch.Failed("Home Assistant refused the panel's sign-in")
            r.status in 300..399 -> ImageFetch.Failed("Home Assistant answered with a redirect")
            r.status != 200 -> ImageFetch.Failed("Home Assistant answered ${r.status}")
            r.tooLarge -> ImageFetch.Failed("the picture is larger than ${maxBytes / 1024 / 1024} MiB")
            type == null || type !in TYPES -> ImageFetch.Failed("not a picture the panel shows (${type ?: "no type"})")
            else -> ImageFetch.Ok(r.body, type)
        }
    }

    companion object {
        /** A camera snapshot is well under this; the S1 has little RAM to spare. */
        const val MAX_BYTES = 4 * 1024 * 1024

        /** Bitmaps Android decodes. No SVG: it's a document, not a picture, and can carry scripts and links. */
        val TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}

/**
 * [HttpGet] over [HttpClient], which connects as a browser does ([HappyEyeballs]): no redirects, 5 s to connect
 * and 10 s for each read.
 */
class UrlConnectionGet(connectMs: Int = 5_000, readMs: Int = 10_000, connector: HappyEyeballs = HappyEyeballs()) : HttpGet {
    private val http = HttpClient(connector, connectMs, readMs)

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpGet.Response {
        val r = http.request("GET", url, headers, maxBytes = maxBytes, bodyIf = { it == 200 })
        val type = r.header("Content-Type")
        if (r.status != 200) return HttpGet.Response(r.status, type, ByteArray(0), false)
        return HttpGet.Response(r.status, type, if (r.tooLarge) ByteArray(0) else r.body, r.tooLarge)
    }
}
