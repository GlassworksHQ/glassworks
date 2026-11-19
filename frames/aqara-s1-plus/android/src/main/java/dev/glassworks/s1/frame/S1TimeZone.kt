package dev.glassworks.s1.frame

import dev.glassworks.frame.TimeZoneAgent
import dev.glassworks.radio.RadioHelper
import dev.glassworks.radio.ShellProps
import dev.glassworks.radio.SysProps

/**
 * The S1 agent's `time-zone` (frames/aqara-s1-plus/docs/radio.md, Time zone): the wanted zone in [PROP], which the
 * root helper (v4) applies and puts back whenever it drifts, and its report in [PROP_STATUS].
 */
class S1TimeZone(private val props: SysProps = ShellProps) : TimeZoneAgent {
    override fun wanted() = props.get(PROP)

    override fun want(zone: String) = props.set(PROP, zone)

    override fun status() = props.get(PROP_STATUS)

    override fun current() = props.get(CURRENT)

    override fun version(): Int? = props.get(RadioHelper.PROP_VERSION).trim().toIntOrNull()

    companion object {
        const val PROP = "persist.s1kiosk.timezone"
        const val PROP_STATUS = "sys.s1kiosk.timezone.status"

        /** Android's own zone. */
        const val CURRENT = "persist.sys.timezone"
    }
}
