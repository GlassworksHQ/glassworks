package sh.edm.s1kiosk.radio

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.auth.HaSocket
import sh.edm.s1kiosk.auth.HaThreadClient
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.ThreadJoin
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.setup.ChannelState
import sh.edm.s1kiosk.setup.SetupChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream

/**
 * `radio.thread_join` (#166): the installer starts the join from Home Assistant, the same join as the
 * panel's and HA's buttons, and follows it in the radio's `threadJoin` state. Nothing it sends back, and
 * nothing logged on the way, carries the dataset or any key in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RadioThreadJoinTest {

    @get:Rule val tmp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private val panePrefs get() = PanePrefs(app)

    /** Home Assistant over its websocket: one preferred Thread network, or the answers a test asks for. */
    private class FakeHa(private val admin: Boolean = true, private val preferred: Boolean = true) : HaSocket {
        private val inbox = ArrayDeque(listOf("""{"type":"auth_required","ha_version":"2026.11.0"}"""))
        override fun send(text: String) {
            val m = JSONObject(text)
            if (m.getString("type") == "auth") { inbox += """{"type":"auth_ok"}"""; return }
            val id = m.getInt("id")
            inbox += when {
                !admin -> """{"id":$id,"type":"result","success":false,"error":{"code":"unauthorized"}}"""
                m.getString("type") == "thread/list_datasets" ->
                    """{"id":$id,"type":"result","success":true,"result":{"datasets":[{"dataset_id":"B","network_name":"$NETWORK","channel":15,"preferred":$preferred}]}}"""
                else -> """{"id":$id,"type":"result","success":true,"result":{"tlv":"$TLV"}}"""
            }
        }
        override fun receive(): String? = inbox.removeFirstOrNull()
        override fun close() {}
    }

    /** Runs the join's network work when the test says so, so `fetching` can be seen. */
    private val queued = ArrayList<Runnable>()
    private fun runQueued() { while (queued.isNotEmpty()) queued.removeAt(0).run() }

    private var ha: () -> HaSocket = { FakeHa() }
    private var fetches = 0
    private val join = ThreadJoin(
        client = HaThreadClient { fetches++; ha() },
        tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = error("no token calls on the panel's own sign-in")
            override fun get(url: String, maxBytes: Int) = error("unexpected GET $url")
        }),
        haBase = { "http://homeassistant.local:8123" },
        panelToken = { done -> done("PANEL-TOKEN") },
        exec = { queued += it },
    )

    private var signedIn = true
    private val init = TestInit(helper = true)
    private val radioLog = ArrayList<String>()
    private lateinit var radio: RadioController
    private lateinit var definition: RadioDefinition
    private lateinit var channel: SetupChannel

    /** Every line the installer got, and everything the app logged. */
    private val seen = ArrayList<String>()
    private val stdout = ByteArrayOutputStream()
    private val realOut = System.out

    @Before fun panel() {
        System.setOut(PrintStream(stdout, true))
        ShadowLog.clear()
        threadRouter("ready to join: waiting for a Thread dataset")
        radio = RadioController(init, load = { panePrefs.radio.mode }, store = { panePrefs.radio.mode = it }, inline = true, log = { radioLog += it }, filesDir = tmp.root)
            .also { it.start() }
        // As RadioPane wires it: the join hands the dataset to this radio.
        join.deliver = { r -> radio.joinThreadNetwork(r.tlv) }
        definition = RadioDefinition(
            panePrefs.radio, { false }, TestReadout(radio, KioskPrefs(app)), RunningRadio(),
            threadJoin = { join }, signedIn = { signedIn },
        )
        channel = SetupChannel(object : SetupChannel.Backend {
            override fun state() = ChannelState(IP, ChannelState.Ha(null, "http://homeassistant.local:8123", signedIn, null))
            override fun setHa(url: String) = SetupChannel.HaChange()
        }, listOf(definition.step), log = { seen += it })
    }

    @After fun noSecretsAnywhere() {
        System.setOut(realOut)
        val all = seen + radioLog + ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" } + stdout.toString()
        for (secret in listOf(TLV, NETWORK_KEY, PSKC, "PANEL-TOKEN")) {
            all.forEach { line -> assertFalse("leaked into: $line", line.contains(secret, ignoreCase = true)) }
        }
    }

    /** The helper's status in Thread router mode. */
    private fun threadRouter(detail: String) {
        panePrefs.radio.mode = RadioMode.THREAD_ROUTER.id
        init.props[RadioHelper.PROP_MODE] = RadioMode.THREAD_ROUTER.id
        init.props[RadioHelper.PROP_STATUS] = "ok thread_router: $detail"
    }

    private fun send(line: String): JSONObject = channel.handle(line).first.also { seen += it }.let(::JSONObject)

    private fun state(): JSONObject = send("""{"id":0,"op":"state"}""").getJSONObject("state").getJSONObject("radio")

    private fun joinState(): JSONObject = state().getJSONObject("threadJoin")

    private fun startJoin(): JSONObject = send("""{"id":1,"op":"radio.thread_join"}""")

    @Test
    fun theInstallerFollowsTheJoinToARouter() {
        assertEquals("idle", joinState().getString("phase"))
        assertFalse("no role before a dataset", joinState().has("role"))

        val r = startJoin()
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("fetching", r.getJSONObject("state").getJSONObject("radio").getJSONObject("threadJoin").getString("phase"))

        runQueued()
        assertEquals("the radio got the dataset", TLV, File(tmp.root, RadioHelper.DATASET_FILE).readText())
        assertEquals("joining", joinState().getString("phase"))

        threadRouter("detached: looking for network \"$NETWORK\"")
        radio.refresh()
        assertEquals("joining", joinState().getString("phase"))
        assertEquals("detached", joinState().getString("role"))

        threadRouter("joined as child, network \"$NETWORK\"")
        radio.refresh()
        assertEquals("joined", joinState().getString("phase"))
        assertEquals("child", joinState().getString("role"))

        threadRouter("joined as router, network \"$NETWORK\"")
        radio.refresh()
        assertEquals(JSONObject("""{"phase":"joined","role":"router"}""").toString(), joinState().toString())
        assertEquals(1, fetches)
    }

    @Test
    fun aJoinAlreadyRunningIsLeftToRun() {
        startJoin()
        val again = startJoin()
        assertTrue(again.getBoolean("ok"))
        runQueued()
        assertEquals(1, fetches)
        assertEquals("joining", joinState().getString("phase"))
    }

    @Test
    fun aDashboardUserNeedsAnAdministratorOnce() {
        ha = { FakeHa(admin = false) }
        startJoin()
        runQueued()
        val s = joinState()
        assertEquals("needs_admin", s.getString("phase"))
        assertEquals("the panel's Home Assistant user isn't an administrator", s.getString("reason"))
        assertEquals("http://$IP:8099/thread", s.getString("adminUrl"))
        assertFalse(File(tmp.root, RadioHelper.DATASET_FILE).exists())
    }

    @Test
    fun theQrCodeRunsOut() {
        val s = RadioDefinition.threadJoinJson(RadioMode.THREAD_ROUTER, ThreadJoin.State.NeedsAdmin(until = 10, why = "x"), null, { IP }, now = 10)!!
        assertEquals("failed", s.getString("phase"))
        assertEquals("nobody signed in as an administrator in time; start again", s.getString("reason"))
        assertFalse(s.has("adminUrl"))
    }

    @Test
    fun failuresSayWhyInPlainWords() {
        ha = { FakeHa(preferred = false) }
        startJoin()
        runQueued()
        assertEquals(
            JSONObject().put("phase", "failed")
                .put("reason", "Home Assistant knows 1 Thread network but none is preferred (Settings → Devices & services → Thread)").toString(),
            joinState().toString(),
        )

        ha = { throw IOException("connection refused") }
        startJoin()
        runQueued()
        assertEquals("can't reach Home Assistant (connection refused)", joinState().getString("reason"))
    }

    @Test
    fun refusedOutsideThreadRouter() {
        panePrefs.radio.mode = RadioMode.OFF.id
        init.props[RadioHelper.PROP_MODE] = RadioMode.OFF.id
        init.props[RadioHelper.PROP_STATUS] = "ok off: Aqara radio services stopped, radio idle"
        radio.request(RadioMode.OFF.label)
        val r = startJoin()
        assertFalse(r.getBoolean("ok"))
        assertEquals(RadioDefinition.JOIN_NOT_ROUTER, r.getString("error"))
        assertTrue(join.state is ThreadJoin.State.Idle)
        assertFalse("no join state outside Thread router", state().has("threadJoin"))
    }

    @Test
    fun refusedWithoutTheSignIn() {
        signedIn = false
        val r = startJoin()
        assertFalse(r.getBoolean("ok"))
        assertEquals(RadioDefinition.JOIN_NOT_SIGNED_IN, r.getString("error"))
        assertTrue(join.state is ThreadJoin.State.Idle)
        assertEquals(0, fetches)
    }

    @Test
    fun refusedWithoutTheRadio() {
        val d = RadioDefinition(panePrefs.radio, { false }, TestReadout(null, KioskPrefs(app)), RunningRadio(), threadJoin = { join }, signedIn = { true })
        assertEquals("The radio controller isn't running yet; try again in a moment.", d.step.ops.getValue("thread_join").run(JSONObject()))
        assertTrue(join.state is ThreadJoin.State.Idle)
    }

    @Test
    fun thePanelsButtonStartsTheSameJoin() {
        definition.run(RadioFollowUp.Request.Act(RadioFollowUp.Action.THREAD_JOIN_HA), "settings menu", {}, {})
        assertEquals("fetching", joinState().getString("phase"))
        assertTrue("the installer's request joins the running join", startJoin().getBoolean("ok"))
        runQueued()
        assertEquals(1, fetches)
    }

    private companion object {
        const val IP = "192.0.2.10"
        const val NETWORK = "demo-thread"
        /** A made-up active dataset: its network key and PSKc are in it as TLVs 5 and 4. */
        const val NETWORK_KEY = "00112233445566778899aabbccddeeff"
        const val PSKC = "ffeeddccbbaa99887766554433221100"
        const val TLV = "0e080000000000010000" + "000300000f" + "0510" + NETWORK_KEY + "0410" + PSKC + "030b64656d6f2d746872656164"
    }
}
