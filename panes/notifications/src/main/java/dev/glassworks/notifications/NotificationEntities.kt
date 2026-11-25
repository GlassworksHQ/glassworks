/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import dev.glassworks.esphome.ActionArg
import dev.glassworks.esphome.ActionArgType
import dev.glassworks.esphome.ActionResponse
import dev.glassworks.esphome.ApiAction
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.EntityCategory
import dev.glassworks.esphome.SwitchEntity

/**
 * The notifications pane's Home Assistant entities (#118), made once by [NotificationsPane].
 *
 * - `notify`, the action HA lists as `esphome.<panel>_notify`, with `message`, `title` and `data`
 *   ([NotifyRequest]). It answers with success or the problem in words (`STATUS`), so a mistake shows
 *   in the script's trace instead of disappearing.
 * - `do_not_disturb`, a config switch, on by default in HA because people automate it (bedtime).
 *
 * A tapped button goes to HA as a bus event ([ActionReport]), not an event entity: HA refuses an
 * event type the entity didn't list up front (`event/entity.py` `_trigger_event`, 2026.11), and an
 * action id is whatever the script chose.
 */
class NotificationEntities(
    /** One `notify` call: `message`, `title`, `data`. Throws to fail the call with its message. */
    onNotify: (message: String, title: String, data: String) -> Unit,
    getDoNotDisturb: () -> Boolean,
    setDoNotDisturb: (Boolean) -> Unit,
) {
    val notify = ApiAction(
        "notify",
        args = listOf(
            ActionArg("message", ActionArgType.STRING),
            ActionArg("title", ActionArgType.STRING),
            ActionArg("data", ActionArgType.STRING),
        ),
        supportsResponse = ActionResponse.STATUS,
    ) { call ->
        onNotify(call.string("message"), call.string("title"), call.string("data"))
        null
    }.apply {
        // What shows on the screen comes from Home Assistant with the key only (#847): a plaintext client could be
        // anyone on the LAN, and a notification can carry buttons and an image. The call fails with the reason.
        authOnly = true
    }

    val doNotDisturb = SwitchEntity(
        "do_not_disturb", "Do not disturb", "mdi:bell-off-outline", EntityCategory.CONFIG,
        get = getDoNotDisturb, set = setDoNotDisturb,
    )

    val all: List<ApiEntity> = listOf(notify, doNotDisturb)
}
