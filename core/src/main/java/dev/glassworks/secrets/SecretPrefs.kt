/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.secrets

import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * The settings file [raw] (named [file]) with its [secrets] sealed by [store]: the same file and the
 * same keys, so panels keep their settings across the update (PrefsPinTest). Code reads and writes
 * through it as through any SharedPreferences; only the stored text of a secret changes.
 *
 * - **Plaintext from an earlier version** is sealed in place when this is made, all of the file's
 *   secrets in one commit. A commit replaces the whole file at once, so a crash leaves either the old
 *   plaintext or the sealed values, and both read back. Running it again finds nothing to do.
 * - **A secret that no longer opens** (the key was lost: a restored backup, a vendor bug) reads as not
 *   set and is removed, so the panel asks for it again through its usual setup. It is logged once,
 *   without the value.
 * - **No key store now**: a sealed secret reads as not set but stays, and nothing replaces it ([canRead]
 *   tells a caller not to make a new one). A secret with no sealed value yet is stored as plaintext so
 *   the panel keeps working; it is sealed the next time this is made with a key store.
 */
class SecretPrefs(
    private val raw: SharedPreferences,
    private val file: String,
    private val secrets: Set<String>,
    private val store: SecretStore,
    private val warn: (String) -> Unit = { Log.w(SecretStore.TAG, it) },
) : SharedPreferences {
    /**
     * Each secret's last opened value, by key, with the sealed text it came from: the key store is a binder
     * call away, and settings are read often. One per key, so a replaced value isn't kept.
     */
    private val opened = ConcurrentHashMap<String, Pair<String, String>>()
    private val warned: MutableSet<Warning> = ConcurrentHashMap.newKeySet()
    private val listeners = HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    /** Each is logged once per file object, so a long outage doesn't fill the log. */
    private enum class Warning { LOST, CANT_READ, CANT_SEAL, KEPT_SEALED, NOT_SAVED }

    /** A secret as it reads now ([secret]). */
    sealed interface Secret {
        class Value(val text: String) : Secret
        /** Not stored, or it was lost and is removed now. */
        data object Absent : Secret
        /** Sealed, and the key store can't open it now: it may open later, so nothing may replace it. */
        data object Unavailable : Secret
    }

    init { sealPlaintext() }

    private fun scope(key: String) = "$file/$key"

    private fun warnOnce(w: Warning, message: String) { if (warned.add(w)) warn(message) }

    private fun commit(e: SharedPreferences.Editor, what: String) {
        if (!e.commit()) warnOnce(Warning.NOT_SAVED, "$file couldn't be written ($what); it is tried again next time")
    }

    private fun sealPlaintext() {
        val all = raw.all
        val plain = secrets.mapNotNull { k -> (all[k] as? String)?.takeIf { !SecretStore.isSealed(it) }?.let { k to it } }
        if (plain.isEmpty()) return
        val e = raw.edit()
        for ((k, v) in plain) e.putString(k, store.seal(v, scope(k)) ?: return)
        commit(e, "sealing the plaintext an earlier version left")
    }

    private fun read(key: String, stored: String?): Secret {
        if (stored == null) return Secret.Absent
        if (!SecretStore.isSealed(stored)) return Secret.Value(stored)
        opened[key]?.takeIf { it.first == stored }?.let { return Secret.Value(it.second) }
        return when (val o = store.open(stored, scope(key))) {
            is SecretStore.Opened.Value -> Secret.Value(o.text).also { opened[key] = stored to o.text }
            SecretStore.Opened.Lost -> {
                warnOnce(Warning.LOST, "$file/$key was sealed with a key this panel no longer has; it is removed and needs setting again")
                synchronized(this) { if (raw.all[key] == stored) commit(raw.edit().remove(key), "removing $key") }
                Secret.Absent
            }
            SecretStore.Opened.Unavailable -> {
                warnOnce(Warning.CANT_READ, "The key store isn't available; $file/$key reads as not set for now")
                Secret.Unavailable
            }
        }
    }

    private fun stored(key: String) = raw.all[key] as? String

    /**
     * False while [key] holds a sealed value the key store can't open now. A caller that would make a
     * new secret when it reads none (an identity, a pairing key) must not store it then: the old one may
     * open again once the key store is back.
     */
    fun canRead(key: String): Boolean = key !in secrets || read(key, stored(key)) != Secret.Unavailable

    /** The secret [key] from one read: its value, absent, or sealed and unreadable now. */
    fun secret(key: String): Secret = if (key in secrets) read(key, stored(key)) else raw.getString(key, null)?.let { Secret.Value(it) } ?: Secret.Absent

    override fun getString(key: String, defValue: String?): String? {
        val v = raw.getString(key, defValue)
        if (key !in secrets || v == null || v === defValue) return v
        return (read(key, v) as? Secret.Value)?.text ?: defValue
    }

    override fun contains(key: String): Boolean =
        if (key in secrets) read(key, stored(key)) is Secret.Value else raw.contains(key)

    /** The file with its secrets opened; a secret that doesn't open now is left out. */
    override fun getAll(): Map<String, *> = raw.all.mapNotNull { (k, v) ->
        when {
            k !in secrets || v !is String -> k to v
            else -> (read(k, v) as? Secret.Value)?.let { k to it.text }
        }
    }.toMap()

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = raw.getStringSet(key, defValues)
    override fun getInt(key: String, defValue: Int) = raw.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long) = raw.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float) = raw.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = raw.getBoolean(key, defValue)

    override fun edit(): SharedPreferences.Editor = Editor(raw.edit())

    /** The listener hears this file, not [raw], so it reads secrets opened. */
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        val p = synchronized(listeners) { listeners.getOrPut(l) { SharedPreferences.OnSharedPreferenceChangeListener { _, k -> l.onSharedPreferenceChanged(this, k) } } }
        raw.registerOnSharedPreferenceChangeListener(p)
    }

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(l) }?.let(raw::unregisterOnSharedPreferenceChangeListener)
    }

    /**
     * All or nothing. An edit that would replace or drop a secret that can't be read now ([canRead] false:
     * a put while the key store can't seal it, a remove, a clear) is refused whole: [commit] writes nothing
     * and returns false, and [apply] drops it. The secret stays for when the key store is back. The check
     * runs when the edit is written, under the lock, against the values stored then: another edit may have
     * stored a secret since this one was staged.
     */
    private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
        /** Secrets this edit drops (a remove, a null put) or replaces with plaintext; all of them on [clear]. */
        private val drops = HashSet<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (key !in secrets) { e.putString(key, value); return this }
            if (value == null) return remove(key)
            val sealed = store.seal(value, scope(key))
            if (sealed == null) {
                warnOnce(Warning.CANT_SEAL, "The key store isn't available; $file/$key is stored unsealed until it is")
                drops += key
            } else drops -= key
            e.putString(key, sealed ?: value)
            return this
        }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { e.putStringSet(key, values); return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { e.putInt(key, value); return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { e.putLong(key, value); return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { e.putFloat(key, value); return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { e.putBoolean(key, value); return this }
        override fun remove(key: String): SharedPreferences.Editor { if (key in secrets) drops += key; e.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { drops += secrets; e.clear(); return this }

        /** No secret this edit drops or overwrites with plaintext is one that is sealed and can't be read now. */
        private fun allowed(): Boolean {
            val held = drops.firstOrNull { k -> stored(k)?.let(SecretStore::isSealed) == true && !canRead(k) } ?: return true
            warnOnce(Warning.KEPT_SEALED, "The key store isn't available; $file/$held keeps its sealed value and the change isn't saved")
            return false
        }

        // Under the same lock as removing a lost value in read(), so neither undoes a value the other wrote.
        override fun commit(): Boolean = synchronized(this@SecretPrefs) { allowed() && e.commit() }
        override fun apply() = synchronized(this@SecretPrefs) { if (allowed()) e.apply() }
    }
}
