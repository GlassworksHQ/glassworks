package sh.edm.s1kiosk.settings

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.glassworks.pane.Blueprint
import dev.glassworks.pane.BlueprintAccess
import dev.glassworks.pane.BlueprintLinks
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.blueprintCard
import dev.glassworks.platform.AppBuild
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.FrontendUrls
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.HaBlueprintClient
import sh.edm.s1kiosk.auth.WsHaSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The panes' Blueprints cards (pane.json's `blueprints`, from the app's [Blueprint.ASSET]): the list, and
 * what the panel's own Home Assistant sign-in may do with them ([BlueprintAccess]). The check only reads
 * and runs on its own thread; [access] answers at once with the last answer and starts a new check when
 * that one is older than [FRESH_MS], so a page that is open picks up an import within seconds. An answer
 * is about one Home Assistant and one sign-in ([context]): when either changes, it is Checking again.
 * MainActivity and the Manage page each keep one.
 */
class PanelBlueprints(
    catalog: () -> List<Blueprint>,
    /** The git ref the links point at ([BlueprintLinks.ref]). */
    private val ref: String,
    /** The dashboard's Home Assistant, where the links go. */
    private val haBase: () -> String?,
    /** The check itself; blocks. Runs on this class's thread. */
    private val check: () -> BlueprintAccess,
    /** What an answer is about: the dashboard's Home Assistant and the panel's sign-in. */
    private val context: () -> Any? = haBase,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Answer(val context: Any?, val access: BlueprintAccess, val at: Long)

    private val byPane: Map<String, List<Blueprint>> by lazy {
        runCatching(catalog).onFailure { Log.w(TAG, "no blueprint list", it) }.getOrDefault(emptyList()).groupBy { it.pane }
    }

    @Volatile private var answer: Answer? = null
    private val checking = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "gw-blueprints").apply { isDaemon = true } }

    /**
     * The last answer for this [context]; a new check starts when it is older than [FRESH_MS], or there is
     * none for this context yet (Checking until it answers).
     */
    fun access(): BlueprintAccess {
        val now = context()
        val a = answer?.takeIf { it.context == now }
        if ((a == null || clock() - a.at > FRESH_MS) && checking.compareAndSet(false, true)) {
            exec.execute {
                val about = context()
                val access = runCatching(check).getOrElse { BlueprintAccess.Off(HaBlueprintClient.UNREACHABLE) }
                answer = Answer(about, access, clock())
                checking.set(false)
                // Home Assistant or the sign-in changed while it ran: check again for the new one.
                if (context() != about) access()
            }
        }
        return a?.access ?: BlueprintAccess.Checking
    }

    /** [pane]'s Blueprints card, or null when it has none. [open] takes a link the panel opens in its dashboard. */
    fun item(pane: String, open: (String) -> Unit): SettingsItem.Blueprints? {
        val list = byPane[pane] ?: return null
        return SettingsItem.Blueprints(list, { surface -> blueprintCard(list, surface, access(), haBase(), ref) }, open)
    }

    companion object {
        private const val TAG = "S1Kiosk.Blueprints"
        const val FRESH_MS = 15_000L
        const val NOT_SIGNED_IN = "The panel isn't signed in to Home Assistant, so it can't open the import."
        const val OTHER_HA = "The panel is signed in to another Home Assistant than its dashboard's."

        /** The panel's: its catalog asset, the app's version, the dashboard's address and [HaAuth]'s sign-in. */
        fun forPanel(context: Context, build: AppBuild, prefs: KioskPrefs): PanelBlueprints {
            val app = context.applicationContext
            val client = HaBlueprintClient { url -> WsHaSocket.connect(url) }
            return PanelBlueprints(
                catalog = { app.assets.open(Blueprint.ASSET).use { Blueprint.parse(it.readBytes().toString(Charsets.UTF_8)) } },
                ref = BlueprintLinks.ref(build),
                haBase = { prefs.haBase },
                context = { prefs.haBase to (if (HaAuth.signedIn) HaAuth.signedInBase else null) },
                check = check@{
                    HaAuth.init(app)
                    if (!HaAuth.signedIn) return@check BlueprintAccess.Off(NOT_SIGNED_IN)
                    val base = HaAuth.signedInBase ?: return@check BlueprintAccess.Off(NOT_SIGNED_IN)
                    if (!FrontendUrls.sameOrigin(base, prefs.haBase)) return@check BlueprintAccess.Off(OTHER_HA)
                    // The token comes on the auth thread; the check runs here, so it never holds that thread up.
                    val done = CountDownLatch(1)
                    var access: HaAuth.Access? = null
                    HaAuth.accessToken(force = false) { a -> access = a; done.countDown() }
                    done.await(15, TimeUnit.SECONDS)
                    when (val a = access) {
                        is HaAuth.Access.Ok -> client.check(base, a.accessToken)
                        is HaAuth.Access.Failed -> BlueprintAccess.Off(if (a.signedOut) NOT_SIGNED_IN else HaBlueprintClient.UNREACHABLE)
                        null -> BlueprintAccess.Off(HaBlueprintClient.UNREACHABLE)
                    }
                },
            )
        }
    }
}
