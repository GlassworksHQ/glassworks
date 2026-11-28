package dev.glassworks.s1

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
import dev.glassworks.auth.StoredAuth
import dev.glassworks.speaker.PrefsSsStore
import dev.glassworks.sendspin.SsPairingRecord
import dev.glassworks.KioskPrefs
import dev.glassworks.SecretFiles
import dev.glassworks.SignInNotStored
import dev.glassworks.applyApiKeyExtras
import dev.glassworks.glassworks

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
        val runtime = KioskApp.runtime(GenericAndroid(app), build = AppBuild("1.0.0", 10000, debug = false))
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

    @Test fun aKeyEntryThatIsGoneForGoodIsReplacedAndItsSecretsAreLost() {
        KioskPrefs(app); PrefsSsStore(app)
        val old = FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS)
        FakeAndroidKeyStore.unrecoverable = 17 // KEY_PERMANENTLY_INVALIDATED
        ShadowLog.clear()
        val prefs = KioskPrefs(app)
        assertNull("signed out", prefs.auth)
        FakeAndroidKeyStore.unrecoverable = null
        assertNotEquals("a new key", old, FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS))
        assertFalse(prefs.hasAuth)
        assertNull(prefs.noiseKeyStore.load())
        assertTrue("unpaired", PrefsSsStore(app).records().isEmpty())
        val disk = onDisk().values.joinToString()
        assertFalse("no plaintext written", disk.contains(token))
        val logs = ShadowLog.getLogsForTag(SecretStore.TAG).map { it.msg }
        assertTrue(logs.toString(), logs.any { "made a new one" in it })
        assertTrue(logs.none { token in it })
        prefs.auth = dev.glassworks.auth.StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20/", "made-up-new-token")
        assertEquals("signing in again works", "made-up-new-token", KioskPrefs(app).auth?.refreshToken)
    }

    /** Android 10's lookup answers "no key" when a check fails for a moment; making a key then would delete the real one. */
    @Test fun aLookupThatFindsNoKeyForAMomentNeverReplacesIt() {
        KioskPrefs(app); PrefsSsStore(app)
        val sealed = onDisk()
        val key = FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS)
        FakeAndroidKeyStore.hidden.set(2)
        assertNull("opening: reads as signed out for now", KioskPrefs(app).auth)
        assertEquals("opening never makes a key", key, FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS))
        assertEquals(sealed, onDisk())
        FakeAndroidKeyStore.hidden.set(0)
        assertEquals(token, KioskPrefs(app).auth?.refreshToken)
        // Both lookups answer "no key"; the key store still lists it (containsAlias), so none is made.
        FakeAndroidKeyStore.hidden.set(2)
        assertTrue("not stored now", runCatching { PanePrefs(app).camera.signIn = "made-up-user" to "made-up-new-pass" }.isFailure)
        assertEquals("sealing never replaces a key the key store still lists", key, FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS))
        assertEquals(sealed, onDisk())
        assertEquals(token, KioskPrefs(app).auth?.refreshToken)
        assertEquals("made-up-user" to "made-up-camera-pass-7", PanePrefs(app).camera.signIn)
    }

    /** The key's entry is simply absent: an outage at first; the next sign-in makes a new key, and the old values go. */
    @Test fun anAbsentKeyRecoversThroughTheNextSignIn() {
        KioskPrefs(app); PrefsSsStore(app)
        FakeAndroidKeyStore.keys.clear() // wiped by the system
        val prefs = KioskPrefs(app)
        assertNull("reads as signed out", prefs.auth)
        val store = prefs.noiseKeyStore as dev.glassworks.esphome.ReadableNoiseKeyStore
        assertTrue("the ESPHome API stays locked for now", store.read() is dev.glassworks.esphome.StoredKey.Unreadable)
        assertTrue("nothing made yet", FakeAndroidKeyStore.keys.isEmpty())
        prefs.auth = StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20/", "made-up-new-token")
        assertEquals("the sign-in made a new key", setOf(SecretStore.KEY_ALIAS), FakeAndroidKeyStore.keys.keys)
        assertEquals("made-up-new-token", KioskPrefs(app).auth?.refreshToken)
        assertTrue("the old API key is gone: unprovisioned", store.read() is dev.glassworks.esphome.StoredKey.Absent)
        assertTrue(dev.glassworks.esphome.NoiseKeys(KioskPrefs(app).noiseKeyStore).provisionable)
        val ss = PrefsSsStore(app)
        assertTrue("Sendspin can pair again", ss.secretsReadable())
        assertFalse(ss.identity().privateKey.contentEquals(identity))
    }

    /** A cause-less error from getKey that a freshly loaded key store doesn't repeat is passing: the key and its secrets stay. */
    @Test fun aBareErrorThatDoesNotRepeatKeepsTheKey() {
        KioskPrefs(app); PrefsSsStore(app)
        val sealed = onDisk()
        val key = FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS)
        FakeAndroidKeyStore.bare.set(1)
        assertNull("reads as signed out for now", KioskPrefs(app).auth)
        assertEquals("the same key", key, FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS))
        assertEquals("nothing removed", sealed, onDisk())
        assertEquals(token, KioskPrefs(app).auth?.refreshToken)
    }

    /** `--es api_key` during an outage isn't stored, so `--ez api_encryption true` with it isn't applied either. */
    @Test fun anAdbKeyThatCantBeStoredLeavesEncryptionAsItWas() {
        for (f in files) File(dir, "$f.xml").delete()
        KioskPrefs(app)
        FakeAndroidKeyStore.down = true
        val prefs = KioskPrefs(app)
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        assertTrue(applyApiKeyExtras(prefs, key, encryption = true)!!.contains("not stored"))
        assertFalse("encryption isn't turned on without its key", prefs.apiEncryption)
        assertFalse("nothing in the clear", File(dir, "kiosk.xml").let { it.exists() && key in it.readText() })
        FakeAndroidKeyStore.down = false
        assertNull(applyApiKeyExtras(prefs, key, encryption = true))
        assertTrue(prefs.apiEncryption)
        assertArrayEquals(ByteArray(32) { 7 }, KioskPrefs(app).noiseKeyStore.load())
    }

    /** The key store comes back between "can it be read?" and the read: the identity must not be made again. */
    @Test fun anOutageEndingMidCallNeverReplacesTheIdentity() {
        PrefsSsStore(app).identity()
        var calls = 0
        val flaky = SecretStore {
            calls++
            if (calls == 1) null else java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(SecretStore.KEY_ALIAS, null) as javax.crypto.SecretKey
        }
        val ss = PrefsSsStore(app, flaky)
        ss.identity(); ss.pairingPsk()
        assertArrayEquals("the stored identity is still the old one", identity, PrefsSsStore(app).identity().privateKey)
        assertArrayEquals(psk, PrefsSsStore(app).pairingPsk())
        assertArrayEquals("and the same store reads it now", identity, ss.identity().privateKey)
    }

    /** Fail closed (maintainer, 2026-11-15): during an outage no secret is stored, not even one never sealed. */
    @Test fun anOutageStoresNoSecretNeverSealedEither() {
        for (f in files) File(dir, "$f.xml").delete() // a panel set up from scratch: nothing stored yet
        KioskPrefs(app); PrefsSsStore(app) // the key store is made
        FakeAndroidKeyStore.down = true
        val prefs = KioskPrefs(app)
        assertTrue(runCatching { prefs.auth = StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20/", "made-up-new-token") }.exceptionOrNull() is SignInNotStored)
        assertFalse("Home Assistant hears the key wasn't set", prefs.noiseKeyStore.save(ByteArray(32) { 9 }))
        assertFalse(prefs.apiEncryption)
        val keys = dev.glassworks.esphome.NoiseKeys(prefs.noiseKeyStore)
        assertTrue("unprovisioned stays unprovisioned", keys.provisionable)
        val def = CameraDefinition(PanePrefs(app).paneFile("camera")).apply { switch.on = true }
        val item = def.settings().single().items.filterIsInstance<dev.glassworks.pane.SettingsItem.Group>().flatMap { it.items }
            .filterIsInstance<dev.glassworks.pane.SettingsItem.SignIn>().single()
        var answer: String? = "none"
        item.save("made-up-user" to "made-up-first-pass") { answer = it }
        assertEquals(dev.glassworks.camera.CameraPrefs.NOT_SAVED, answer)
        val disk = files.filter { File(dir, "$it.xml").exists() }.joinToString { File(dir, "$it.xml").readText() }
        for (v in listOf("made-up-new-token", "made-up-first-pass", "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=")) assertFalse("nothing in the clear: $v", v in disk)
    }

    @Test fun anEmptyCameraPasswordDuringAnOutageSaysNotSaved() {
        KioskPrefs(app); PanePrefs(app)
        val def = CameraDefinition(PanePrefs(app).paneFile("camera")).apply { switch.on = true }
        FakeAndroidKeyStore.down = true
        val item = def.settings().single().items.filterIsInstance<dev.glassworks.pane.SettingsItem.Group>().flatMap { it.items }
            .filterIsInstance<dev.glassworks.pane.SettingsItem.SignIn>().single()
        assertTrue("still set, though unreadable now", item.isSet())
        var answer: String? = "none"
        dev.glassworks.pane.SettingsChange.signIn(item, "made-up-user2", "") { answer = it }
        assertEquals("not \"Type a password\": there is one, it just can't be read now", dev.glassworks.camera.CameraPrefs.NOT_SAVED, answer)
        FakeAndroidKeyStore.down = false
        assertEquals("made-up-user" to "made-up-camera-pass-7", PanePrefs(app).camera.signIn)
    }

    /**
     * Home Assistant asks for a Sendspin pairing token (SendspinTokenSource): none while the identity can't
     * be read, as after a boot during an outage. (An identity already read stays in memory and is real.)
     */
    @Test fun noPairingTokenDuringAnOutage() {
        KioskPrefs(app); PrefsSsStore(app)
        val ctx = dev.glassworks.pane.PaneContext(
            app, PanePrefs(app)::paneFile,
            dev.glassworks.esphome.DeviceIdentity("test", "Test", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
            publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
            build = AppBuild("1.0.0", 10000, debug = false),
        )
        FakeAndroidKeyStore.down = true
        val pane = dev.glassworks.speaker.SendspinPane(ctx, haBase = { null })
        val server = dev.glassworks.esphome.ApiServer(0, { ctx.device }, emptyList<dev.glassworks.esphome.ApiEntity>(), null as ByteArray?)
        pane.bind(server)
        pane.start()
        try {
            val tokens = server.sendspin!!
            val during = tokens.pairingToken()
            assertEquals(dev.glassworks.esphome.SendspinApi.STATUS_NOT_READY, during.first)
            assertNull("no token for a stand-in identity", during.second)
            FakeAndroidKeyStore.down = false
            assertEquals(dev.glassworks.esphome.SendspinApi.STATUS_OK, tokens.pairingToken().first)
        } finally {
            pane.stop()
        }
    }

    @Test fun aKeyEntryThatFailsForAMomentIsKept() {
        KioskPrefs(app); PrefsSsStore(app)
        val sealed = onDisk()
        val old = FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS)
        FakeAndroidKeyStore.unrecoverable = 4 // SYSTEM_ERROR: Android wraps it in UnrecoverableKeyException too
        assertNull("reads as signed out for now", KioskPrefs(app).auth)
        assertEquals("the same key", old, FakeAndroidKeyStore.keys.getValue(SecretStore.KEY_ALIAS))
        assertEquals("nothing removed", sealed, onDisk())
        FakeAndroidKeyStore.unrecoverable = null
        assertEquals(token, KioskPrefs(app).auth?.refreshToken)
    }

    /** The key store comes back between reading the pairings and writing them: the empty read must not replace them. */
    @Test fun pairingsAreNeverReplacedByAListReadDuringAnOutage() {
        PrefsSsStore(app).records().single()
        var calls = 0
        val flaky = SecretStore {
            calls++
            if (calls == 1) null else java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(SecretStore.KEY_ALIAS, null) as javax.crypto.SecretKey
        }
        val ss = PrefsSsStore(app, flaky)
        ss.touchRecord("made-up-server", 9L)
        ss.putRecord(SsPairingRecord("made-up-other", ByteArray(32), 2L), emptySet())
        val back = PrefsSsStore(app).records()
        assertTrue("still paired with the stored server: $back", back.any { it.serverId == "made-up-server" })
    }

    @Test fun aCameraSignInThatCantBeStoredSaysSo() {
        KioskPrefs(app); PanePrefs(app)
        val def = CameraDefinition(PanePrefs(app).paneFile("camera")).apply { switch.on = true }
        FakeAndroidKeyStore.down = true
        val item = def.settings().single().items.filterIsInstance<dev.glassworks.pane.SettingsItem.Group>().flatMap { it.items }
            .filterIsInstance<dev.glassworks.pane.SettingsItem.SignIn>().single()
        var answer: String? = "none"
        item.save("made-up-user" to "made-up-typed-in-outage") { answer = it }
        assertEquals(dev.glassworks.camera.CameraPrefs.NOT_SAVED, answer)
        answer = "none"
        item.save(null) { answer = it }
        assertEquals("a clear isn't saved either", dev.glassworks.camera.CameraPrefs.NOT_SAVED, answer)
        FakeAndroidKeyStore.down = false
        assertEquals("made-up-user" to "made-up-camera-pass-7", PanePrefs(app).camera.signIn)
    }

    @Test fun aKeyStoreOutageWritesNothingBack() {
        KioskPrefs(app); PanePrefs(app)
        val ss = PrefsSsStore(app)
        val sealed = onDisk()
        FakeAndroidKeyStore.down = true
        val prefs = KioskPrefs(app)
        assertNull("reads as signed out for now", prefs.auth)
        assertNull(PanePrefs(app).camera.signIn)
        assertFalse("no pairing token for a stand-in", ss.secretsReadable())
        val standIn = ss.identity()
        assertFalse("a stand-in identity", standIn.privateKey.contentEquals(identity))
        assertTrue("the same one while it lasts", ss.identity() === standIn)
        assertFalse(ss.pairingPsk().contentEquals(psk))
        assertTrue(ss.records().isEmpty())
        ss.putRecord(SsPairingRecord("made-up-other", ByteArray(32), 2L), emptySet())
        ss.touchRecord("made-up-server", 3L)
        ss.removeRecord("made-up-server")
        assertTrue("a camera sign-in isn't stored, and says so", runCatching { PanePrefs(app).camera.signIn = "made-up-user" to "made-up-typed-in-outage" }.isFailure)
        assertTrue(runCatching { PanePrefs(app).camera.signIn = null }.isFailure)
        assertEquals("nothing replaced, nothing removed, nothing regenerated", sealed, onDisk())

        FakeAndroidKeyStore.down = false
        assertEquals(token, KioskPrefs(app).auth?.refreshToken)
        assertTrue(ss.secretsReadable())
        assertArrayEquals("the same store object reads the real identity again", identity, ss.identity().privateKey)
        assertArrayEquals(psk, ss.pairingPsk())
        assertEquals("still paired", "made-up-server", ss.records().single().serverId)
        assertEquals("made-up-user" to "made-up-camera-pass-7", PanePrefs(app).camera.signIn)
    }

    @Test fun aSignInOrSignOutDuringAnOutageFailsAndKeepsTheStoredOne() {
        KioskPrefs(app)
        val sealed = onDisk().getValue("auth")
        FakeAndroidKeyStore.down = true
        val prefs = KioskPrefs(app)
        val signIn = runCatching { prefs.auth = StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20/", "made-up-new-token") }.exceptionOrNull()
        assertTrue("a new sign-in fails where the person sees it: $signIn", signIn is SignInNotStored)
        assertTrue(runCatching { prefs.auth = null }.exceptionOrNull() is SignInNotStored)
        assertEquals("the sealed token stays", sealed, onDisk().getValue("auth"))
        assertFalse(onDisk().getValue("auth").contains("made-up-new-token"))
        FakeAndroidKeyStore.down = false
        assertEquals(token, KioskPrefs(app).auth?.refreshToken)
    }

    @Test fun aNoiseKeyDuringAnOutageIsRefusedAndNeverFallsBackToPlaintext() {
        KioskPrefs(app)
        val sealed = onDisk().getValue("kiosk")
        FakeAndroidKeyStore.down = true
        val prefs = KioskPrefs(app)
        assertTrue((prefs.noiseKeyStore as dev.glassworks.esphome.ReadableNoiseKeyStore).read() is dev.glassworks.esphome.StoredKey.Unreadable)
        assertFalse("Home Assistant hears the key wasn't set", prefs.noiseKeyStore.save(ByteArray(32) { 9 }))
        assertFalse(prefs.noiseKeyStore.save(null))
        assertEquals("api_encryption and the key as they were", sealed, onDisk().getValue("kiosk"))
        assertTrue(prefs.apiEncryption)
        val keys = dev.glassworks.esphome.NoiseKeys(prefs.noiseKeyStore)
        assertTrue("nobody gets in, not plaintext", keys.locked)
        assertFalse(keys.provisionable)
        FakeAndroidKeyStore.down = false
        val after = dev.glassworks.esphome.NoiseKeys(KioskPrefs(app).noiseKeyStore)
        assertTrue("the same key once the key store is back", after.hasKey && !after.locked)
        assertArrayEquals(ByteArray(32) { (0x20 + it).toByte() }, KioskPrefs(app).noiseKeyStore.load())
    }

    /** Every opener of a file with secrets goes through SecretFiles: opened any other way, it reads sealed text. */
    @Test fun onlySecretFilesOpensAFileWithSecrets() {
        val sources = RepoFiles.code(atLeast = 100, inModules = RepoFiles.appModules)
        val opens = sources.filter { "getSharedPreferences(" in it.readText() }.map { RepoFiles.rel(it).substringAfterLast('/') }.toSet()
        val secretOpeners = sources.filter { "SecretFiles.open(" in it.readText() }.map { it.name }.toSet()
        assertEquals("the openers of kiosk, auth and sendspin (PaneFiles: every app's pane files, #23)",
            setOf("KioskPrefs.kt", "PaneFiles.kt", "PrefsSsStore.kt"), secretOpeners)
        assertTrue("the scan sees SecretFiles", "SecretFiles.kt" in opens)
        // The others open only files without secrets: PaneFiles opens SecretFiles' files through it, the
        // wall base its energy counter (s1base), the timers their anchor (timers), setup its answers to the device's
        // own steps (setup, #403), the copy of Home Assistant's page storage without its credentials
        // (ha_page_storage) and Vitro's island port (vitro_island, #881), which the factory reset clears (HaPageData).
        assertEquals(
            setOf("SecretFiles.kt", "PaneFiles.kt", "WallBase.kt", "TimerOverlay.kt", "SetupPrefs.kt", "HaPageStorage.kt", "VitroEngine.kt", "HaPageData.kt"),
            opens,
        )
        for (literal in SecretFiles.SECRETS.keys) {
            val raw = sources.filter { it.name != "SecretFiles.kt" && Regex("""getSharedPreferences\(\s*"$literal"""").containsMatchIn(it.readText()) }
            assertTrue("$literal opened raw in ${raw.map(RepoFiles::rel)}", raw.isEmpty())
        }
        assertTrue("the camera's password is listed", dev.glassworks.camera.CameraPrefs.KEY_STREAM_PASSWORD in SecretFiles.SECRETS.getValue("kiosk"))
    }
}
