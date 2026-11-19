package dev.glassworks

import android.content.Context
import java.io.File
import dev.glassworks.secrets.SecretPrefs
import dev.glassworks.secrets.SecretStore

/**
 * The app's settings files that hold secrets, and the one way to open them ([open]), so every reader
 * of a secret goes through the store: KioskPrefs (`kiosk`, `auth`), PanePrefs (`kiosk`, which holds the
 * panes' settings) and the Sendspin store (`sendspin`). Opening one of these files any other way reads
 * sealed text (`SecretFilesConfinementTest` fails the build). Panels keep these names: never rename one.
 */
object SecretFiles {
    /** The keys each file keeps sealed. Same files, same keys: only the stored text changes. A new secret goes here. */
    val SECRETS: Map<String, Set<String>> = mapOf(
        "kiosk" to setOf("api_key", "camera_stream_password"),
        "auth" to setOf("refresh_token"),
        "sendspin" to setOf("identity", "pairing_psk", "records"),
    )

    /** The file [name] with its secrets sealed by [store]. Only for a file in [SECRETS]. */
    fun open(context: Context, name: String, store: SecretStore): SecretPrefs {
        val files = context.applicationContext.filesDir
        return SecretPrefs(
            context.getSharedPreferences(name, Context.MODE_PRIVATE), name,
            SECRETS[name] ?: throw IllegalArgumentException("'$name' holds no secrets"), store,
            handoverPending = { handoverPending(files) },
        )
    }

    /**
     * The marker a signing-key switch writes into the app's copied data when it will hand the secrets back
     * (`glassworks-switch.sh restore handover`, #584): `files/secrets-handover`, owned by the app. The installer's
     * `done` on the hand-over socket removes it ([endHandover]).
     */
    const val HANDOVER_MARKER = "secrets-handover"

    /** How long a marker counts after it was written: the installer hands over within a minute or two of the restore. */
    const val HANDOVER_WAIT_MS = 15 * 60_000L

    /** An installer will hand the secrets back: its marker is there and younger than [HANDOVER_WAIT_MS]. */
    fun handoverPending(filesDir: File, now: Long = System.currentTimeMillis()): Boolean {
        val f = File(filesDir, HANDOVER_MARKER)
        val age = now - f.lastModified()
        return f.isFile && age in 0 until HANDOVER_WAIT_MS
    }

    /** The installer is done handing over (or gave up): secrets that still don't open are lost, as after any reinstall. */
    fun endHandover(filesDir: File): Boolean {
        val f = File(filesDir, HANDOVER_MARKER)
        return !f.exists() || f.delete()
    }
}
