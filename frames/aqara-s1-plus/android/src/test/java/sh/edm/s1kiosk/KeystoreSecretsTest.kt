package sh.edm.s1kiosk

import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.platform.AppBuild
import dev.glassworks.secrets.SecretStore
import dev.glassworks.testing.FakeAndroidKeyStore
import dev.glassworks.testing.RepoFiles
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowLog
import sh.edm.s1kiosk.sendspin.PrefsSsStore
import sh.edm.s1kiosk.sendspin.SsPairingRecord

/**
 * The app's own openers with their default store, [SecretStore.keystore], over a software stand-in for
 * Android's key store ([FakeAndroidKeyStore]): what runs on a panel, from the plaintext golden
 * (`src/test/resources/secrets-golden`, made-up values). Covers the pane settings GlassworksRuntime
 * builds, a key entry that can't be read any more, and a key store outage, which must write nothing back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeystoreSecretsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val dir = File(app.dataDir, "shared_prefs")
    private val files = listOf("kiosk", "auth", "sendspin")
    private val token = "made-up-refresh-token-0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    private val identity = ByteArray(32) { (1 + it).toByte() }
    private val psk = ByteArray(32) { (0x40 + it).toByte() }

    @Before fun theOldFilesAndAKeyStore() {
        FakeAndroidKeyStore.install()
        dir.mkdirs()
        for (f in files) File(dir, "$f.xml").writeBytes(javaClass.getResourceAsStream("/secrets-golden/$f.xml")!!.readBytes())
    }

    @After fun noKeyStore() = FakeAndroidKeyStore.uninstall()

    private fun onDisk() = files.associateWith { File(dir, "$it.xml").readText() }

    /** A tablet with a camera, so the camera pane is the real CameraDefinition, as the app builds it. */
    private fun appCamera(): CameraDefinition {
        ShadowBuild.setManufacturer("Google"); ShadowBuild.setModel("Pixel Tablet"); ShadowBuild.setHardware("tangorpro")
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        val runtime = GlassworksRuntime(GenericAndroid(app), build = AppBuild("1.0.0", 10000, debug = false))
        return runtime.paneDefinitions(app).single { it.id == "camera" } as CameraDefinition
    }

    @Test fun theAppsCameraSettingsReadTheSealedPasswordBack() {
        KioskPrefs(app); PanePrefs(app); PrefsSsStore(app) // the update: everything sealed
        assertTrue(onDisk().getValue("kiosk").contains("name=\"camera_stream_password\">${SecretStore.MARK}"))
        assertFalse(onDisk().getValue("kiosk").contains("made-up-camera-pass-7"))
        assertEquals("made-up-user" to "made-up-camera-pass-7", appCamera().prefs.signIn)
    }

    @Test fun anUpdateFromSealedOrPlaintextKeepsEverySecret() {
        for (round in 1..2) { // 1: from the plaintext golden; 2: from what round 1 sealed
            assertEquals("round $round", token, KioskPrefs(app).auth?.refreshToken)
            assertArrayEquals(ByteArray(32) { (0x20 + it).toByte() }, KioskPrefs(app).noiseKeyStore.load())
            assertEquals("made-up-user" to "made-up-camera-pass-7", PanePrefs(app).camera.signIn)
            val ss = PrefsSsStore(app)
            assertArrayEquals(identity, ss.identity().privateKey)
            assertArrayEquals(psk, ss.pairingPsk())
            assertEquals("made-up-server", ss.records().single().serverId)
        }
        assertEquals("one key, made once", setOf(SecretStore.KEY_ALIAS), FakeAndroidKeyStore.keys.keys)
    }

    @Test fun aKeyEntryThatCantBeReadIsReplacedAndItsSecretsAreLost() {
        KioskPrefs(app); PrefsSsStore(app)
        val old = FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS)
        FakeAndroidKeyStore.unrecoverable = true
        ShadowLog.clear()
        val prefs = KioskPrefs(app)
        assertNull("signed out", prefs.auth)
        FakeAndroidKeyStore.unrecoverable = false
        assertNotEquals("a new key", old, FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS))
        assertFalse(prefs.hasAuth)
        assertNull(prefs.noiseKeyStore.load())
        assertTrue("unpaired", PrefsSsStore(app).records().isEmpty())
        val disk = onDisk().values.joinToString()
        assertFalse("no plaintext written", disk.contains(token))
        val logs = ShadowLog.getLogsForTag(SecretStore.TAG).map { it.msg }
        assertTrue(logs.toString(), logs.any { "made a new one" in it })
        assertTrue(logs.none { token in it })
        prefs.auth = sh.edm.s1kiosk.auth.StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20/", "made-up-new-token")
        assertEquals("signing in again works", "made-up-new-token", KioskPrefs(app).auth?.refreshToken)
    }

    @Test fun aKeyStoreOutageWritesNothingBack() {
        KioskPrefs(app); PanePrefs(app); PrefsSsStore(app)
        val sealed = onDisk()
        FakeAndroidKeyStore.down = true
        val prefs = KioskPrefs(app)
        assertNull("reads as signed out for now", prefs.auth)
        assertNull(PanePrefs(app).camera.signIn)
        val ss = PrefsSsStore(app)
        val standIn = ss.identity()
        assertFalse("a stand-in identity", standIn.privateKey.contentEquals(identity))
        assertTrue("the same one while it lasts", ss.identity() === standIn)
        assertFalse(ss.pairingPsk().contentEquals(psk))
        assertTrue(ss.records().isEmpty())
        ss.putRecord(SsPairingRecord("made-up-other", ByteArray(32), 2L), emptySet())
        ss.touchRecord("made-up-server", 3L)
        ss.removeRecord("made-up-server")
        PanePrefs(app).camera.signIn = "made-up-user" to "made-up-typed-in-outage"
        assertEquals("nothing replaced, nothing regenerated", sealed, onDisk())

        FakeAndroidKeyStore.down = false
        assertEquals(token, KioskPrefs(app).auth?.refreshToken)
        val back = PrefsSsStore(app)
        assertArrayEquals("the same identity", identity, back.identity().privateKey)
        assertArrayEquals(psk, back.pairingPsk())
        assertEquals("still paired", "made-up-server", back.records().single().serverId)
        assertEquals("made-up-user" to "made-up-camera-pass-7", PanePrefs(app).camera.signIn)
    }

    /** Every opener of a file with secrets goes through SecretFiles: opened any other way, it reads sealed text. */
    @Test fun onlySecretFilesOpensAFileWithSecrets() {
        val sources = RepoFiles.code(atLeast = 100, inModules = RepoFiles.appModules)
        val opens = sources.filter { "getSharedPreferences(" in it.readText() }.map { RepoFiles.rel(it).substringAfterLast('/') }.toSet()
        assertTrue("the scan sees SecretFiles", "SecretFiles.kt" in opens)
        // The others open only files without secrets: PanePrefs opens SecretFiles' files through it, the
        // wall base its energy counter (s1base), the timers their anchor (timers).
        assertEquals(setOf("SecretFiles.kt", "PanePrefs.kt", "WallBase.kt", "TimerOverlay.kt"), opens)
        for (literal in SecretFiles.SECRETS.keys) {
            val raw = sources.filter { it.name != "SecretFiles.kt" && Regex("""getSharedPreferences\(\s*"$literal"""").containsMatchIn(it.readText()) }
            assertTrue("$literal opened raw in ${raw.map(RepoFiles::rel)}", raw.isEmpty())
        }
        assertTrue("the camera's password is listed", dev.glassworks.camera.CameraPrefs.KEY_STREAM_PASSWORD in SecretFiles.SECRETS.getValue("kiosk"))
    }
}
