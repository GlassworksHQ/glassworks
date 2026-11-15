# Secrets on the panel

The panel keeps a few secrets so it can work with nobody at the screen. Since the secret store
(2026-11-15), each one is sealed with AES-256-GCM before it is written. The key lives in the
Android Keystore and never leaves it. Status: **unit-tested; not yet checked on a panel.**

## What is sealed

| Secret | File (`shared_prefs/`) | Key | If it is lost |
|---|---|---|---|
| Home Assistant refresh token (the panel's sign-in, which also signs in the dashboard) | `auth.xml` | `refresh_token` | Signed out: sign in again with the QR code |
| ESPHome API encryption key (Noise PSK) | `kiosk.xml` | `api_key` | Home Assistant can't connect until you give the panel a key again (see [esphome.md](esphome.md)) |
| Camera stream password | `kiosk.xml` | `camera_stream_password` | Set a new one on the Camera page |
| Sendspin identity (private key) | `sendspin.xml` | `identity` | Pair with Music Assistant again |
| Sendspin pairing key | `sendspin.xml` | `pairing_psk` | Pair again |
| Sendspin pairings (each server's key) | `sendspin.xml` | `records` | Pair again |

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

If the key is gone for good (the Keystore lost it, says its entry is corrupted or permanently
invalidated, a backup was restored onto another device), the panel makes a new key and treats the
secret as not set: signed out, no API key, not paired. It removes the unreadable value, logs it once
under `S1Kiosk.Secrets` without the value, and asks for it again through its usual setup. It never
crashes over it.

Only the key store's own word counts as "gone for good": error codes 7 (key not found), 8 (blob
corrupted), 17 (permanently invalidated) and Keymaster's -33 (invalid key blob), read from AOSP
Android 10's `KeyStore.java` on 2026-11-15, or a ciphertext whose tag doesn't match. Any other key
store error is treated as an outage (below), because Android 10 reports passing failures with the
same exception types. A fresh sign-in or pairing still replaces a value that stays unreadable once the
key store can seal again. Opening a secret never makes a key: Android 10 answers "no key" when a lookup
fails for a moment, and making a key under the same name deletes the one there. Only sealing makes
one, and only after a second lookup on a freshly loaded key store also finds none. A key store that
fails both lookups and then works at once can still cost the key (not seen; untested on a panel).

If the Keystore can't be reached at all, sealed secrets read as not set for now, and nothing replaces
or removes them. The app tries them again on each use, so they come back as soon as the Keystore does.
Until then:

| What | During the outage |
|---|---|
| Home Assistant sign-in | The dashboard can't sign in. A new sign-in fails on the phone with "The panel can't store the sign-in right now", and signing out keeps the stored token and says it failed |
| ESPHome API | A key is stored but can't be read: the API lets nobody in (no plaintext, no zero-PSK provisioning) and isn't advertised as provisionable. It reads the key again on each connection and every minute. A new key from Home Assistant is refused |
| Sendspin | It plays under a stand-in identity it never stores, hands out no pairing token, and keeps the stored pairings. The same running app reads the real identity once the Keystore is back |
| Camera password | A change isn't saved, and the Camera page says "The panel can't store the sign-in right now" |

A change that would replace or drop an unreadable secret is refused whole, with the other settings in
the same change, so nothing is half written. A secret that had no sealed value yet is written unsealed
so the panel keeps working, and is sealed the next time the app starts with a working Keystore. Each
kind of problem is logged once under `S1Kiosk.Secrets`, without the value.

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
