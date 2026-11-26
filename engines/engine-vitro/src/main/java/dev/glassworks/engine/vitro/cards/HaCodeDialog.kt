/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.ui.VitroCardContext

/**
 * HA's `showEnterCodeDialog` (frontend 20261028.5), as cards that need an alarm's code open it: a title, a masked
 * code field (digits for a `number` code format) and a submit button named like the title ("Arm", "Disarm").
 * [onCode] runs on submit only, so a cancelled dialog sends nothing, and never with an empty code: submit stays
 * disabled until something is typed, as HA would only refuse the call.
 */
internal object HaCodeDialog {
    /** The last dialog shown, for tests. */
    @Volatile internal var lastShown: AlertDialog? = null

    fun ask(ctx: VitroCardContext, codeFormat: String?, title: String, onCode: (String) -> Unit) {
        val field = EditText(ctx.context).apply {
            inputType = if (codeFormat == "number") InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            hint = HaText.ui("ui.card.alarm_control_panel.code").ifEmpty { "Code" }
        }
        runCatching {
            val dialog = AlertDialog.Builder(ctx.context)
                .setTitle(title)
                .setView(field)
                .setNegativeButton(HaText.ui("ui.common.cancel").ifEmpty { "Cancel" }, null)
                .setPositiveButton(title) { _, _ ->
                    field.text?.toString().orEmpty().takeIf { it.isNotEmpty() }?.let(onCode)
                }
                .show()
            lastShown = dialog
            val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submit?.isEnabled = false
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(t: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(e: android.text.Editable?) { submit?.isEnabled = !e.isNullOrEmpty() }
            })
        }
    }
}
