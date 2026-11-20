package dev.glassworks.s1.frame

import dev.glassworks.frame.TimeZoneAgent
import dev.glassworks.radio.ShellProps
import dev.glassworks.radio.SysProps

/**
 * The S1 agent's `time-zone` (frames/aqara-s1-plus/docs/radio.md, Time zone): the wanted zone in [S1Names.timeZone],
 * which the root helper (v4) applies and puts back whenever it drifts, and its report in [S1Names.timeZoneStatus].
 */
class S1TimeZone(private val props: SysProps = ShellProps, private val names: S1Names = S1Names.of(props::peek)) : TimeZoneAgent {
    override fun wanted() = props.get(names.timeZone)

    override fun want(zone: String) = props.set(names.timeZone, zone)

    override fun status() = props.get(names.timeZoneStatus)

    override fun current() = props.get(CURRENT)

    override fun version(): Int? = props.get(names.radio.version).trim().toIntOrNull()

    companion object {
        /** Android's own zone. */
        const val CURRENT = "persist.sys.timezone"
    }
}
