package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.secrets.SecretStore
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.speaker.PrefsSsStore
import dev.glassworks.KioskPrefs
import dev.glassworks.glassworks

/**
 * The update from a build that stored secrets in plaintext (`src/test/resources/secrets-golden`, made-up
 * values: the keys are counting bytes, the addresses RFC 5737). Installed over it, the app seals every
 * secret in the same file under the same key, reads each one back unchanged, and loses no sign-in, Noise
 * key or pairing. Robolectric has no Android Keystore, so a software key stands in for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecretsMigrationTest {
    private val app = RuntimeEnvironment.getApplication()
    private var key: SecretKey = aesKey()
    private val store = SecretStore { key }
    private val dir = File(app.dataDir, "shared_prefs")

    private val plaintext = listOf(
        "made-up-refresh-token-0f1e2d3c4b5a69788796a5b4c3d2e1f0", "made-up-camera-pass-7",
        "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=", // api_key
        "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA", // sendspin identity
        "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8", // pairing_psk
        "YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8", // a record's psk
    )

    @Before fun installTheOldFiles() {
        dir.mkdirs()
        for (f in listOf("kiosk", "auth", "sendspin")) {
            File(dir, "$f.xml").writeBytes(javaClass.getResourceAsStream("/secrets-golden/$f.xml")!!.readBytes())
        }
    }

    private fun onDisk() = listOf("kiosk", "auth", "sendspin").joinToString("\n") { File(dir, "$it.xml").readText() }

    @Test fun theUpdateSealsEverySecretAndKeepsEachOne() {
        val before = onDisk()
        assertTrue("the golden has them in plaintext", plaintext.all { it in before })

        val prefs = KioskPrefs(app, store)
        val ss = PrefsSsStore(app, store)

        val auth = prefs.auth!!
        assertEquals("still signed in to Home Assistant", "made-up-refresh-token-0f1e2d3c4b5a69788796a5b4c3d2e1f0", auth.refreshToken)
        assertEquals("http://192.0.2.10:8123", auth.haBase)
        assertEquals("http://192.0.2.20/", auth.clientId)
        assertTrue(prefs.hasAuth)
        assertArrayEquals("the same Noise key", ByteArray(32) { (0x20 + it).toByte() }, prefs.noiseKeyStore.load())
        assertEquals("made-up-user" to "made-up-camera-pass-7", PanePrefs(app, store).camera.signIn)
        assertArrayEquals("the same Sendspin identity", ByteArray(32) { (1 + it).toByte() }, ss.identity().privateKey)
        assertArrayEquals(ByteArray(32) { (0x40 + it).toByte() }, ss.pairingPsk())
        val record = ss.records().single()
        assertEquals("made-up-server", record.serverId)
        assertArrayEquals("still paired", ByteArray(32) { (0x60 + it).toByte() }, record.psk)
        assertEquals("the other settings as they were", "http://192.0.2.10:8123", prefs.haUrl)
        assertEquals(40, ss.volume)

        val after = onDisk()
        for (p in plaintext) assertFalse("no plaintext left: $p", p in after)
        assertTrue("the same keys, sealed", listOf("api_key", "camera_stream_password", "refresh_token", "identity", "pairing_psk", "records")
            .all { Regex("""name="$it">${Regex.escape(SecretStore.MARK)}""").containsMatchIn(after) })
    }

    @Test fun runningItAgainChangesNothing() {
        KioskPrefs(app, store); PrefsSsStore(app, store)
        val once = onDisk()
        KioskPrefs(app, store); PrefsSsStore(app, store)
        assertEquals(once, onDisk())
        assertEquals("made-up-refresh-token-0f1e2d3c4b5a69788796a5b4c3d2e1f0", KioskPrefs(app, store).auth?.refreshToken)
    }

    @Test fun aLostKeyMeansSignedOutAndUnpairedNotACrash() {
        KioskPrefs(app, store); PrefsSsStore(app, store)
        key = aesKey()
        val prefs = KioskPrefs(app, store)
        assertNull("signed out", prefs.auth)
        assertFalse(prefs.hasAuth)
        assertNull("no Noise key: Home Assistant provisions a new one", prefs.noiseKeyStore.load())
        assertNull(PanePrefs(app, store).camera.signIn)
        val ss = PrefsSsStore(app, store)
        assertTrue("unpaired", ss.records().isEmpty())
        assertFalse("a new identity", ss.identity().privateKey.contentEquals(ByteArray(32) { (1 + it).toByte() }))
        prefs.auth = dev.glassworks.auth.StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20/", "made-up-new-token")
        assertEquals("signing in again works", "made-up-new-token", KioskPrefs(app, store).auth?.refreshToken)
    }

    /** Sealed secrets mean nothing without this panel's key: no backup or device transfer may carry them. */
    @Test fun nothingLeavesInABackupOrATransfer() {
        val info = app.applicationInfo
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val manifest = File(dev.glassworks.testing.RepoFiles.s1App, "src/main/AndroidManifest.xml").readText()
        assertTrue("Android 12+ transfers ignore allowBackup", manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val rules = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(dev.glassworks.testing.RepoFiles.s1App, "src/main/res/xml/data_extraction_rules.xml")).documentElement
        assertEquals("nothing is included", 0, rules.getElementsByTagName("include").length)
        fun excluded(section: String): Set<String> {
            val ex = (rules.getElementsByTagName(section).item(0) as org.w3c.dom.Element).getElementsByTagName("exclude")
            return (0 until ex.length).map { (ex.item(it) as org.w3c.dom.Element).getAttribute("domain") }.toSet()
        }
        val all = setOf("root", "file", "database", "sharedpref", "external")
        assertEquals(all, excluded("cloud-backup"))
        assertEquals(all, excluded("device-transfer"))
    }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
