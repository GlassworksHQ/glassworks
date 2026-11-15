package sh.edm.s1kiosk.sendspin

import android.content.Context
import dev.glassworks.secrets.SecretPrefs
import dev.glassworks.secrets.SecretStore
import dev.glassworks.sendspin.SsIdentity
import dev.glassworks.sendspin.SsPairingRecord
import dev.glassworks.sendspin.SsStore
import sh.edm.s1kiosk.SecretFiles
import java.security.SecureRandom
import java.util.Base64

/** [SsStore] in the app's private SharedPreferences (`sendspin.xml`). Secrets are never logged. */
class PrefsSsStore(context: Context, secrets: SecretStore = SecretStore.keystore()) : SsStore {
    /** The identity's private key, the pairing key and the records' keys are sealed ([SecretPrefs]). */
    private val sp = SecretFiles.open(context.applicationContext, "sendspin", secrets)
    @Volatile private var cachedIdentity: SsIdentity? = null
    /** Stand-ins while the key store can't open the stored ones: used, never stored, never cached for good. */
    @Volatile private var standInIdentity: SsIdentity? = null
    @Volatile private var standInPsk: ByteArray? = null

    @Synchronized override fun identity(): SsIdentity {
        cachedIdentity?.let { return it }
        // Asked first: a true answer keeps the opened value for the read below, so an outage that ends
        // between the two can't look like "no identity yet" and make a new one.
        if (!sp.canRead("identity")) return standInIdentity ?: SsIdentity.generate().also { standInIdentity = it }
        val saved = sp.getString("identity", null)?.let { runCatching { SsIdentity(unb64url(it)) }.getOrNull() }
        val id = saved ?: SsIdentity.generate().also { sp.edit().putString("identity", b64url(it.privateKey)).commit() }
        cachedIdentity = id
        return id
    }

    @Synchronized override fun pairingPsk(): ByteArray {
        if (!sp.canRead("pairing_psk")) return standInPsk ?: randomBytes(32).also { standInPsk = it }
        sp.getString("pairing_psk", null)?.let { s -> runCatching { unb64url(s) }.getOrNull()?.takeIf { it.size == 32 }?.let { return it } }
        val psk = randomBytes(32)
        sp.edit().putString("pairing_psk", b64url(psk)).commit()
        return psk
    }

    override fun secretsReadable(): Boolean = sp.canRead("identity") && sp.canRead("pairing_psk")

    @Synchronized override fun records() = SsStore.decodeRecords(sp.getString("records", null))

    /**
     * Rewrites the pairings from [records], unless the stored ones can't be read now. Then [records] is
     * empty, and if the key store came back between that read and the write, the empty list would replace
     * every pairing. A true [SecretPrefs.canRead] keeps the opened value, so the read below sees it.
     */
    private fun writeRecords(commit: Boolean, change: (List<SsPairingRecord>) -> List<SsPairingRecord>) {
        if (!sp.canRead("records")) return
        val e = sp.edit().putString("records", SsStore.encodeRecords(change(records())))
        if (commit) e.commit() else e.apply()
    }

    @Synchronized override fun putRecord(r: SsPairingRecord, protectedServerIds: Set<String>) =
        writeRecords(commit = true) { SsStore.merge(it, r, protectedServerIds) }

    @Synchronized override fun removeRecord(serverId: String) =
        writeRecords(commit = true) { list -> list.filter { it.serverId != serverId } }

    @Synchronized override fun touchRecord(serverId: String, nowMs: Long) =
        writeRecords(commit = false) { list -> list.map { if (it.serverId == serverId) it.copy(lastUsedMs = nowMs) else it } }

    override var outputDelayMs: Int
        get() = sp.getInt("output_delay_ms", 0)
        set(v) = sp.edit().putInt("output_delay_ms", v.coerceIn(0, 5000)).apply()
    override var volume: Int
        get() = sp.getInt("volume", 60)
        set(v) = sp.edit().putInt("volume", v.coerceIn(0, 100)).apply()
    override var muted: Boolean
        get() = sp.getBoolean("muted", false)
        set(v) = sp.edit().putBoolean("muted", v).apply()
    override var lastPlaybackServerId: String?
        get() = sp.getString("last_playback_server", null)
        set(v) = sp.edit().putString("last_playback_server", v).apply()
}

// Base64url without padding, as the library writes keys (and as this file always stored them).
private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
private fun unb64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))
private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
