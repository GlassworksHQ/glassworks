package dev.glassworks.esphome

import java.security.MessageDigest

/**
 * Where the device keeps its API key (the Noise PSK) between runs: a file, preferences, a keystore.
 * Never log what passes through.
 */
public interface NoiseKeyStore {
    /** The stored 32-byte key, or null if the device is unprovisioned. */
    public fun load(): ByteArray?

    /** Persists [key] (null = clear) synchronously. Returns false if it could not be written. */
    public fun save(key: ByteArray?): Boolean

    /**
     * One read of the stored key that also tells "no key" from "a key I can't read now" (the device's
     * key store is down). [NoiseKeys] decides from it alone, so an outage that ends between two calls
     * can't make a device with a key look unprovisioned. The default, from [load], suits a store that is
     * always readable.
     */
    public fun read(): StoredKey = load()?.let { StoredKey.Present(it) } ?: StoredKey.Absent
}

/** What a [NoiseKeyStore] holds, from one read ([NoiseKeyStore.read]). */
public sealed interface StoredKey {
    /** The stored key. */
    public class Present(
        /** The 32-byte key as stored. */
        public val key: ByteArray,
    ) : StoredKey

    /** No key: the device is unprovisioned. */
    public data object Absent : StoredKey

    /** A key is stored but can't be read now: the server lets nobody in until it can ([NoiseKeys.locked]). */
    public data object Unreadable : StoredKey
}

/**
 * A [NoiseKeyStore] in memory, starting with [initial]: the key is lost when the process ends. For
 * tests, and for a fixed key.
 */
public class MemoryKeyStore(initial: ByteArray? = null) : NoiseKeyStore {
    @Volatile internal var key: ByteArray? = initial?.copyOf()
    override fun load(): ByteArray? = key?.copyOf()
    override fun save(key: ByteArray?): Boolean { this.key = key?.copyOf(); return true }
}

/** Outcome of a `NoiseEncryptionSetKeyRequest`. [message] is safe to log (no key material). */
internal data class SetKeyResult(val success: Boolean, val activate: Boolean, val message: String)

/**
 * Noise key state of the API server, modelled on ESPHome's (esphome 2026.10.2
 * `api_server.cpp` `save_noise_psk` / `update_noise_psk_` / `load_and_apply_noise_psk_` and
 * `api_connection.cpp` `send_noise_encryption_set_key_response_`):
 *
 * - **No key** (unprovisioned): the server accepts plaintext clients *and* Noise clients using the
 *   well-known all-zeros PSK (`aioesphomeapi.ZERO_NOISE_PSK`). DeviceInfo reports
 *   `api_encryption_supported` and `api_encryption_provisionable`.
 * - `NoiseEncryptionSetKeyRequest.key` is the **base64 text** of the 32-byte key (HA sends
 *   `base64.b64encode(secrets.token_bytes(32))`). It must decode to exactly 32 bytes and not be all
 *   zeros. An empty key clears the stored key. The same key as the current one is a no-op success.
 * - The new key is persisted before the response is sent, and **activated 100 ms later**
 *   ([activatePending]): from then on only Noise with that key is accepted, and every session opened
 *   before (plaintext, zero-PSK, or the old key) is disconnected.
 *
 * Thread-safe. Pass it to [ApiServer], which handles Home Assistant's key requests with it.
 *
 * @param store Where the key is saved. The constructor loads it.
 */
public class NoiseKeys(private val store: NoiseKeyStore) {

    private val lock = Any()

    /** Key used for new connections; null = unprovisioned. */
    private val first = store.read()

    @Volatile internal var active: ByteArray? = (first as? StoredKey.Present)?.key?.takeIf(::isUsableKey)?.copyOf()
        private set

    /** Last persisted key (may still be waiting for activation). */
    @Volatile private var saved: ByteArray? = active

    /** True when a key is active: only encrypted connections with it get in. */
    public val hasKey: Boolean get() = active != null

    /**
     * True while no key is active and none is stored unreadable, so Home Assistant may set one (ESPHome:
     * `api_encryption_provisionable = !noise_ctx.has_psk()`). Never while [locked].
     */
    public val provisionable: Boolean get() = active == null && !locked

    /**
     * A key is stored but can't be read now ([StoredKey.Unreadable]): the server accepts nobody, not
     * plaintext and not the zero PSK, and isn't provisionable, until it reads the key. A device that has
     * a key never falls back to plaintext because its key store is down.
     */
    @Volatile public var locked: Boolean = first is StoredKey.Unreadable
        private set

    /** A copy of the latest key, including one saved but not yet active, or null for none. */
    public val latest: ByteArray? get() = saved?.copyOf()

    /**
     * While [locked], reads the stored key again. True when that ended the lock: the key is [active] now,
     * or it is gone for good and the device is unprovisioned.
     */
    internal fun unlock(): Boolean = synchronized(lock) {
        if (!locked) return false
        when (val r = store.read()) {
            is StoredKey.Present -> r.key.takeIf(::isUsableKey)?.let { k -> active = k.copyOf(); saved = k.copyOf() }
            StoredKey.Absent -> {}
            StoredKey.Unreadable -> return false
        }
        locked = false
        true
    }

    internal fun handleSetKey(field: ByteArray): SetKeyResult = synchronized(lock) {
        if (locked) return SetKeyResult(false, false, "the stored encryption key can't be read right now")
        if (field.isEmpty()) {
            if (saved == null && active == null) return SetKeyResult(true, false, "key cleared (no key was set)")
            if (!store.save(null)) return SetKeyResult(false, false, "failed to clear encryption key")
            saved = null
            return SetKeyResult(true, true, "key cleared")
        }
        val key = decodeKeyField(field) ?: return SetKeyResult(false, false, "invalid encryption key length")
        if (key.all { it == 0.toByte() }) return SetKeyResult(false, false, "rejecting all-zero encryption key")
        if (saved?.contentEquals(key) == true) {
            // ESPHome logs "New PSK matches old" and reports success without re-activating.
            return SetKeyResult(true, active?.contentEquals(key) != true, "key unchanged (${fingerprint(key)})")
        }
        if (!store.save(key)) return SetKeyResult(false, false, "failed to save encryption key")
        saved = key
        SetKeyResult(true, true, "key set (fingerprint ${fingerprint(key)})")
    }

    /** Makes the saved key the active one. Returns true if the active key changed. */
    internal fun activatePending(): Boolean = synchronized(lock) {
        val next = saved
        if (sameKey(next, active)) return false
        active = next?.copyOf()
        true
    }

    /**
     * Replaces the key from the panel's own settings (adb extras / settings dialog), persisted by
     * the caller already. Returns true if the active key changed.
     */
    internal fun replace(key: ByteArray?): Boolean = synchronized(lock) {
        // No key read because the store can't read it now: not a removed key. Keep what is active.
        if (key == null && store.read() is StoredKey.Unreadable) {
            if (active == null) locked = true
            return false
        }
        val k = key?.takeIf(::isUsableKey)?.copyOf()
        saved = k
        locked = false
        if (sameKey(k, active)) return false
        active = k
        true
    }

    /** Key helpers. */
    public companion object {
        /** The well-known provisioning PSK (aioesphomeapi `ZERO_NOISE_PSK`). */
        internal val ZERO_PSK = ByteArray(32)

        internal fun isUsableKey(k: ByteArray): Boolean = k.size == 32 && k.any { it != 0.toByte() }

        private fun sameKey(a: ByteArray?, b: ByteArray?) =
            if (a == null || b == null) a == null && b == null else a.contentEquals(b)

        /** Decodes the base64 text of a `NoiseEncryptionSetKeyRequest.key`; null unless 32 bytes. */
        internal fun decodeKeyField(field: ByteArray): ByteArray? =
            decodeBase64(String(field, Charsets.US_ASCII).trim())?.takeIf { it.size == 32 }

        /** First 4 hex digits of SHA-256(key): enough to tell keys apart in logs, reveals nothing. */
        public fun fingerprint(key: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(key).take(2).joinToString("") { "%02x".format(it) }
    }
}
