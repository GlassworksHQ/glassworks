package dev.glassworks

import android.content.Context
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
    fun open(context: Context, name: String, store: SecretStore): SecretPrefs = SecretPrefs(
        context.getSharedPreferences(name, Context.MODE_PRIVATE), name,
        SECRETS[name] ?: throw IllegalArgumentException("'$name' holds no secrets"), store,
    )
}
