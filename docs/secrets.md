# Secrets on the panel

The panel keeps a few secrets so it can work with nobody at the screen. Each one is sealed with
AES-256-GCM before it is written. The key lives in the Android Keystore and never leaves it. Every
release since 0.9.0 seals them, and it has been checked on real panels.

## What is sealed

| Secret | File (`shared_prefs/`) | Key | If it is lost |
|---|---|---|---|
| Home Assistant refresh token (the panel's sign-in, which also signs in the dashboard) | `auth.xml` | `refresh_token` | Signed out: sign in again with the QR code |
| ESPHome API encryption key (Noise PSK) | `kiosk.xml` | `api_key` | Home Assistant can't connect until you give the panel a key again (see [esphome.md](esphome.md)) |
| Camera stream password | `kiosk.xml` | `camera_stream_password` | Set a new one on the Camera page |
| Sendspin identity (private key) | `sendspin.xml` | `identity` | Pair with Music Assistant again |
| Sendspin pairing key | `sendspin.xml` | `pairing_psk` | Pair again |
| Sendspin pairings (each server's key) | `sendspin.xml` | `records` | Pair again |

The ESPHome API key and the Home Assistant refresh token leave the app in one place: adb can read them on the app's
hand-over socket `@glassworks_api_key` (`SecretHandover`: adb only, uid 0 or 2000; never the LAN), and give them back
to a new app. A Mirror reflash carries the key that way ([esphome.md](esphome.md#identity-across-reinstalls)),
and the S1's signing-key switch carries both
([install.md](../frames/aqara-s1-plus/docs/install.md#switching-a-development-build-to-a-release)). The
app takes a value back only where none is stored, or over one sealed with a key it no longer has; never over one it
can read. Until the switch's hand-over is done (its marker `files/secrets-handover`, at most 15 minutes), a value
sealed with a key the app no longer has stays locked, not removed, so nothing reads as "no key" in the meantime. It takes a refresh token only into a sign-in its data already holds. It logs a 4-hex fingerprint, never the
value. Whoever has adb on the panel can read both; with adb they could already drive the signed-in dashboard.

The files and keys are the same as before; only the stored text changes. A sealed value starts with
`gw-secret-v1:`. The file and key it is stored under are part of what the seal checks, so a value
copied to another key doesn't open.

What isn't in that table, and why:

| Secret | Where it is |
|---|---|
| The Manage page's code | Only in memory, for 5 minutes |
| Manage sign-ins | `files/manage/sessions.json` keeps a SHA-256 of each session token, never the token |
| Wi-Fi passwords | Android's own Wi-Fi settings. The app passes them on and doesn't keep them |
| Thread dataset | Written to the app's files for the root helper, which deletes it within seconds. The helper needs it in plaintext |

## The update

The first time the updated app opens a settings file, it seals the plaintext secrets an earlier
version left there, in one write per file. Android writes a settings file whole and then swaps it
in, so a crash leaves either the old plaintext or the sealed values, and both read back. Running it
again finds nothing to do. Nothing is lost: the panel stays signed in, Home Assistant keeps its
encrypted connection, and Sendspin stays paired.

**A downgrade** to a build from before the secret store sees the sealed values as unreadable. The
panel then needs setting up again: sign in, give it the API key, pair Sendspin. See
[releasing.md](releasing.md#the-secret-store-and-downgrades).

## When the key is gone

If the key store says the key is gone for good (its entry is corrupted or permanently invalidated),
the panel makes a new key and treats the secrets as not set: signed out, no API key, not paired. It
removes the unreadable values, logs it once under `Glassworks.Secrets` without the values, and asks for
them again through its usual setup. It never crashes over it.

If the key's entry is simply absent (wiped by the system or a vendor bug), the panel can't tell that
from a lookup that failed for a moment, so at first it treats it as an outage (below): the values stay
and read as not set, the ESPHome API stays locked and Sendspin hands out no pairing token. The panel
reads as signed out, so the usual next step is the Home Assistant sign-in (the QR code). Storing that
sign-in makes a new key, once two lookups and the key store's own list all find none. From then on the
old values fail their check: each is removed as it is next read, the ESPHome API becomes unprovisioned
for Home Assistant to give it a new key, and Sendspin pairs again.

Only the key store's own word counts as "gone for good": error codes 7 (key not found), 8 (blob
corrupted), 17 (permanently invalidated) and Keymaster's -33 (invalid key blob), read from AOSP
Android 10's `KeyStore.java` on 2026-11-15, or a ciphertext whose tag doesn't match. Any other key
store error is treated as an outage (below), because Android 10 reports passing failures with the
same exception types. A fresh sign-in still replaces a value that stays unreadable once the key store
can seal again. Opening a secret never makes a key: Android 10 answers "no key" when a lookup
fails for a moment, and making a key under the same name deletes the one there. Only sealing makes
one, and only after a second lookup on a freshly loaded key store also finds none. A key store that
fails both lookups and then works at once can still cost the key (not seen; untested on a panel).

If the Keystore can't be reached, sealed secrets read as not set for now, and nothing replaces or
removes them. **No secret is written either**, not even one the panel never had (fail closed). The app
tries again on each use, so everything comes back as soon as the Keystore does. A secret the running
app already read stays in its memory and keeps working. Until then:

| What | During the outage |
|---|---|
| Home Assistant sign-in | The dashboard can't sign in. A new sign-in, a first one too, fails on the phone with "The panel can't store the sign-in right now". Signing out keeps the stored token and says it failed |
| ESPHome API, with a key | The key can't be read: the API lets nobody in (no plaintext, no zero-PSK provisioning), still advertises `api_encryption` (so Home Assistant tries Noise and fails, never plaintext) and isn't provisionable. It reads the key again on each connection and every minute, and advertises again once it can. A new key from Home Assistant is refused |
| ESPHome API, never given a key | It stays unprovisioned as before (plaintext and zero-PSK), but a key from Home Assistant is refused: Home Assistant hears it wasn't set, and nothing is stored in the clear |
| Sendspin | Started during the outage, it plays under a stand-in identity it never stores, hands out no pairing token, and keeps the stored pairings. The same running app reads the real identity once the Keystore is back |
| Camera password | A change or a clear isn't saved, and the Camera page says "The panel can't store the sign-in right now", an empty password field too |

A change that would write a secret, or replace or drop an unreadable one, is refused whole, with the
other settings in the same change, so nothing is half written. Each kind of problem is logged once
under `Glassworks.Secrets`, without the value. A device with no Keystore at all (never one with Android 6
or later) stores its secrets as they were before the secret store.

Nothing on the panel goes into a backup or a device-to-device transfer (`allowBackup="false"` and
the data extraction rules), because a sealed secret is useless without this panel's key.

## What it protects against, and what it doesn't

It protects the secrets in:

- **A copy of the settings files**: a backup, an `adb pull` or `run-as` on a debug build, a file that
  ends up in a bug report or a support thread.
- **Other apps** that find a way to read the app's files.
- **The flash chip read on its own**, when the Keystore key is in hardware or bound to the device.

It doesn't protect them from:

- **Root on the panel.** The S1's patched boot image gives root, and on a panel whose Keystore keeps
  its keys in software (no hardware-backed keymaster), root can read the key material too. Anyone with
  root on the panel can also run code as the app and ask the Keystore to open each secret.
- **The running app.** The app opens a secret when it needs it and keeps it in memory while it runs.
- **The network.** The ESPHome API is encrypted with the Noise key; the Home Assistant sign-in and the
  Manage page go over whatever Home Assistant uses (see [manage.md](manage.md#how-it-keeps-you-safe)).
- **Old copies on the flash.** Android replaces the file, but the blocks that held the old plaintext
  may stay on the flash until they are reused. Reading them needs root or the chip.

## For developers

`SecretStore` (`core`, `dev.glassworks.secrets`) seals and opens a value. `SecretPrefs` wraps a
SharedPreferences file and seals the keys it is given; code reads and writes through it as through any
SharedPreferences; `canRead` says when a sealed value can't be opened now, so a caller doesn't replace
it. `KeystoreKey` (`dev.glassworks.platform`) holds the Android Keystore calls. The S1 app lists its
secrets in `SecretFiles.SECRETS`, and `SecretFiles.open` is the only way to open those files
(`KeystoreSecretsTest` fails the build otherwise); a new secret goes in that list. Tests run the real
Keystore code over `FakeAndroidKeyStore` (`testing/`). Never log a secret, and keep the file and key names: panels update in place
(`PrefsPinTest`).
