package dev.glassworks

import android.content.Context
import android.content.SharedPreferences
import dev.glassworks.secrets.SecretStore

/**
 * Where the apps on the core keep each pane's settings: the file, by pane id ([FILES]). S1 Kiosk's PanePrefs
 * and the generic app (#23) both open them here, so a pane's settings are in the same place in every app.
 */
object PaneFiles {
    /**
     * The settings file of each pane, by pane id. Every pane's settings stay in `kiosk.xml`, where they were
     * before panes: panels update in place, so no stored value may move.
     */
    val FILES = mapOf(
        "sendspin" to "kiosk", "assist" to "kiosk", "wall-base" to "kiosk", "radio" to "kiosk",
        "time-zone" to "kiosk", "diagnostics" to "kiosk", "bluetooth-proxy" to "kiosk", "notifications" to "kiosk", "camera" to "kiosk",
    )

    /**
     * The file that holds the settings of the pane with id [namespace]. One with secrets ([SecretFiles]) opens
     * through the store, so the camera's password reads back as it was set.
     */
    fun open(context: Context, namespace: String, secrets: SecretStore = SecretStore.keystore()): SharedPreferences {
        val name = FILES[namespace] ?: throw IllegalArgumentException("No settings file for pane '$namespace'")
        return if (name in SecretFiles.SECRETS) SecretFiles.open(context, name, secrets) else context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}
