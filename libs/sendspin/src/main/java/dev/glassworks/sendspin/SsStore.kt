package dev.glassworks.sendspin

import org.json.JSONArray
import org.json.JSONObject

/**
 * A pairing with one server (Sendspin spec "Pairing Records"): the long-term key both sides keep. [psk]
 * is a credential: never log it.
 */
public data class SsPairingRecord(
    /** The server's id. */
    public val serverId: String,
    /** The 32-byte long-term pre-shared key. */
    public val psk: ByteArray,
    /** When the player last used it, in milliseconds since the epoch: the least recently used goes first. */
    public val lastUsedMs: Long,
) {
    internal val pskId: String get() = SsCrypto.pskId(psk)

    /** The same server and key; [lastUsedMs] aside. */
    override fun equals(other: Any?): Boolean = other is SsPairingRecord && other.serverId == serverId && other.psk.contentEquals(psk)

    /** From [serverId]. */
    override fun hashCode(): Int = serverId.hashCode()
}

/**
 * Where the player keeps its secrets, pairings and output settings, so they survive a restart. Values
 * are credentials: never log them. Implementations must be thread-safe. [MemoryStore] keeps nothing
 * across a restart; an app keeps them in its own storage, preferably encrypted.
 */
public interface SsStore {
    /** The player's identity, made ([SsIdentity.generate]) and stored on first use. */
    public fun identity(): SsIdentity

    /** The 32-byte pairing key in the pairing token: random, made and stored on first use, kept after a pairing. */
    public fun pairingPsk(): ByteArray

    /**
     * Whether the stored identity and pairing key can be read now. False during a key store outage, when
     * [identity] and [pairingPsk] give stand-ins that are never stored: no pairing token is handed out then.
     */
    public fun secretsReadable(): Boolean = true

    /** The stored pairings. */
    public fun records(): List<SsPairingRecord>

    /**
     * Stores [r], replacing any record for the same server, and drops the least recently used beyond
     * [MAX_RECORDS], never one in [protectedServerIds]. [merge] does this to a list.
     */
    public fun putRecord(r: SsPairingRecord, protectedServerIds: Set<String> = emptySet())

    /** Forgets the pairing with [serverId]. */
    public fun removeRecord(serverId: String)

    /** Sets the pairing with [serverId] as used at [nowMs]. */
    public fun touchRecord(serverId: String, nowMs: Long)

    /** The output delay the server set, in milliseconds (0 to 5000). */
    public var outputDelayMs: Int

    /** The volume, 0 to 100. */
    public var volume: Int

    /** Whether the player is muted. */
    public var muted: Boolean

    /** The server that last played to the player, or null. */
    public var lastPlaybackServerId: String?

    /** Helpers for implementations. */
    public companion object {
        /** Spec: a client MUST be able to store at least 5 pairing records. */
        public const val MAX_RECORDS: Int = 8

        /** [list] as one JSON string, to store. [decodeRecords] reads it back. */
        public fun encodeRecords(list: List<SsPairingRecord>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("server_id", it.serverId).put("psk", SsCrypto.b64url(it.psk)).put("used", it.lastUsedMs)) }
        }.toString()

        /** The records in [s] from [encodeRecords]; empty for null, empty or unreadable text. */
        public fun decodeRecords(s: String?): List<SsPairingRecord> = try {
            if (s.isNullOrEmpty()) emptyList() else JSONArray(s).let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.getJSONObject(i)
                    val psk = SsCrypto.unb64url(o.getString("psk"))
                    if (psk.size != 32) null else SsPairingRecord(o.getString("server_id"), psk, o.optLong("used"))
                }
            }
        } catch (_: Exception) { emptyList() }

        /** [list] with [r] in place of any record for its server, then the least recently used dropped (never one in [protectedIds]) down to [MAX_RECORDS]. */
        public fun merge(list: List<SsPairingRecord>, r: SsPairingRecord, protectedIds: Set<String>): List<SsPairingRecord> {
            val out = list.filter { it.serverId != r.serverId }.toMutableList()
            out += r
            while (out.size > MAX_RECORDS) {
                val victim = out.filter { it.serverId != r.serverId && it.serverId !in protectedIds }.minByOrNull { it.lastUsedMs } ?: break
                out.remove(victim)
            }
            return out
        }
    }
}

/**
 * An [SsStore] in memory: a new identity, pairing key and no pairings on every start, so the server pairs
 * again after each restart. For tests, and to try the player out.
 */
public class MemoryStore(
    private val id: SsIdentity = SsIdentity.generate(),
    private val pairing: ByteArray = SsCrypto.randomBytes(32),
) : SsStore {
    private var recs = listOf<SsPairingRecord>()
    override fun identity(): SsIdentity = id
    override fun pairingPsk(): ByteArray = pairing.copyOf()
    @Synchronized override fun records(): List<SsPairingRecord> = recs
    @Synchronized override fun putRecord(r: SsPairingRecord, protectedServerIds: Set<String>) { recs = SsStore.merge(recs, r, protectedServerIds) }
    @Synchronized override fun removeRecord(serverId: String) { recs = recs.filter { it.serverId != serverId } }
    @Synchronized override fun touchRecord(serverId: String, nowMs: Long) { recs = recs.map { if (it.serverId == serverId) it.copy(lastUsedMs = nowMs) else it } }
    @Volatile override var outputDelayMs: Int = 0
    @Volatile override var volume: Int = 50
    @Volatile override var muted: Boolean = false
    @Volatile override var lastPlaybackServerId: String? = null
}
