package sh.edm.s1kiosk

/**
 * The adb extras `--es api_key` and `--ez api_encryption` ([MainActivity]), in that order. A key that
 * isn't stored (the key store is down) leaves `api_encryption` as it was, so encryption never turns on
 * without its key. An invalid key is ignored, as before. Returns what to log, or null. Public for tests.
 */
fun applyApiKeyExtras(prefs: KioskPrefs, key: String?, encryption: Boolean?): String? {
    var why: String? = null
    if (key != null) {
        if (KioskPrefs.decodeApiKey(key) == null) why = "api_key must be base64 of exactly 32 bytes; ignored"
        else if (runCatching { prefs.apiKey = key }.isFailure) return "api_key not stored: the key store isn't available; api_encryption left as it was"
    }
    encryption?.let { prefs.apiEncryption = it }
    return why
}
