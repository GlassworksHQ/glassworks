package dev.glassworks.radio

import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.BundleAgent
import dev.glassworks.frame.RadioAgent
import dev.glassworks.frame.RadioFile
import dev.glassworks.frame.RadioSetting
import dev.glassworks.frame.RadioStatus
import java.io.File

/**
 * Where a frame's radio agent keeps each request and report, for an agent the app reaches through Android's system
 * properties, init services and its own files dir (docs/frame-agent.md#transport). The frame gives the names; the
 * radio pane holds none of its own. Every [RadioStatus], [RadioSetting] and [RadioFile] has one.
 */
class RadioNames(
    /** The agent's init service. */
    val service: String,
    /** The property with the radio contract's version. */
    val version: String,
    val status: Map<RadioStatus, String>,
    val settings: Map<RadioSetting, String>,
    /** Paths in the app's files dir. */
    val files: Map<RadioFile, String>,
    /** The vendor's own radio services, consumers first: stopped in this order, started in the reverse. */
    val vendorStopOrder: List<String>,
    /** Each vendor service's wanted state (true = running) in a mode, by the mode's id. */
    val vendorWanted: (modeId: String) -> Map<String, Boolean>,
) {
    init {
        require(status.keys == RadioStatus.entries.toSet()) { "a name for every radio status" }
        require(settings.keys == RadioSetting.entries.toSet()) { "a name for every radio setting" }
        require(files.keys == RadioFile.entries.toSet()) { "a path for every radio file" }
    }
}

/** A [RadioAgent] over Android's properties and init services, by [names] (the S1's: its `RadioHelper`). */
class PropsRadio(private val props: SysProps, private val names: RadioNames) : RadioAgent {
    override val name: String get() = names.service

    override fun state() = props.get("init.svc.${names.service}")

    override fun version(): Int = props.get(names.version).trim().toIntOrNull() ?: 1

    override fun status(s: RadioStatus) = props.get(names.status.getValue(s))

    override fun peek(s: RadioStatus) = props.peek(names.status.getValue(s))

    override fun setting(s: RadioSetting) = props.get(names.settings.getValue(s))

    override fun set(s: RadioSetting, value: String) = props.set(names.settings.getValue(s), value)

    override fun restart() = props.set("ctl.restart", names.service)

    override fun file(f: RadioFile) = names.files.getValue(f)

    /**
     * The radio capabilities as this agent's version gives them: the radio once the agent is installed, the firewall
     * from [RadioReports.FIREWALL_VERSION], firmware from [RadioReports.BUNDLE_VERSION]. Null for any other capability.
     */
    fun offers(c: AgentCapability): Boolean? = when (c) {
        AgentCapability.RADIO_MODE -> state().isNotEmpty()
        AgentCapability.RADIO_FIREWALL -> state().isNotEmpty() && version() >= RadioReports.FIREWALL_VERSION
        AgentCapability.RADIO_FIRMWARE -> state().isNotEmpty() && version() >= RadioReports.BUNDLE_VERSION
        else -> null
    }

    override fun applyVendor(modeId: String, log: (String) -> Unit): List<String> {
        val wanted = names.vendorWanted(modeId)
        for (svc in names.vendorStopOrder) if (wanted[svc] == false) control(svc, run = false, log)
        for (svc in names.vendorStopOrder.reversed()) if (wanted[svc] == true) control(svc, run = true, log)
        return wanted.filter { (svc, run) -> !isInState(svc, run) }.keys.toList()
    }

    override fun vendorState(service: String) = props.get("init.svc.$service")

    private fun isInState(svc: String, run: Boolean): Boolean {
        val s = vendorState(svc)
        return if (run) s == "running" else s == "stopped" || s.isEmpty()
    }

    private fun control(svc: String, run: Boolean, log: (String) -> Unit) {
        if (isInState(svc, run)) return
        props.set(if (run) "ctl.start" else "ctl.stop", svc)
        for (i in 0 until WAIT_STEPS) {
            if (isInState(svc, run)) return
            props.sleep(WAIT_STEP_MS)
        }
        log("$svc did not reach '${if (run) "running" else "stopped"}' (now '${vendorState(svc)}')")
    }

    private companion object {
        const val WAIT_STEPS = 20
        const val WAIT_STEP_MS = 100L
    }
}

/**
 * A [BundleAgent] over Android's properties and an init service: the app stages the bundle in [stageDir], sets
 * [statusProp] to `requested` and starts [service]; the state comes from [stateFile], trusted only when root owns it
 * and its folder ([ownerUid], without following symlinks): any app may set a property.
 */
class PropsBundle(
    private val props: SysProps,
    private val service: String,
    private val statusProp: String,
    override val stageDir: String,
    private val stateFile: File,
    private val ownerUid: (File) -> Int? = ::lstatUid,
    override val format: String = "S1RADIO-BUNDLE 1",
) : BundleAgent {
    override fun state(): String? {
        if (ownerUid(stateFile) != 0 || stateFile.parentFile?.let(ownerUid) != 0) return null
        return runCatching { stateFile.readText() }.getOrNull()
    }

    override fun request() {
        props.set(statusProp, "requested")
        props.set("ctl.start", service)
    }

    override fun running() = props.get("init.svc.$service") == "running"

    override fun status() = props.get(statusProp)

    /**
     * init's `sys.powerctl`, set through [props] as the app starts [service] (`ctl.start`): the restart the boot writer
     * does as root (`setprop sys.powerctl reboot,glassworks-loader`), with a reason of its own.
     */
    override fun restart() = props.set("sys.powerctl", RESTART)

    companion object {
        /** What [restart] sets: a reboot, and why (`reboot,<reason>`, which init logs as it shuts down). */
        const val RESTART = "reboot,glassworks-frame-update"

        fun lstatUid(f: File): Int? = try { android.system.Os.lstat(f.path).st_uid } catch (t: Throwable) { null }
    }
}
