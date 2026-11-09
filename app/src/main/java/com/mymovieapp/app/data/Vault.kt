package com.mymovieapp.app.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN-protected sources. A locked source is hidden everywhere until the PIN is entered.
 * Keys: a library folder's uri, or "web:<id>" for a saved web folder.
 * The PIN is stored only as a salted hash.
 */
class Vault(ctx: Context) {
    private val sp = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)

    val locked = MutableStateFlow(sp.getStringSet("locked", emptySet())?.toSet() ?: emptySet())
    /** Always starts locked: it is never saved, so every fresh start of the app asks for the PIN again. */
    val unlocked = MutableStateFlow(false)
    val hasPin = MutableStateFlow(sp.contains("hash"))
    /** Seconds away from the app before it locks again. 0 = the moment you leave. */
    val lockAfterSec = MutableStateFlow(sp.getInt("lockAfter", 0))
    private var leftAt = 0L

    /** Keys that must be hidden right now. */
    val hidden = combine(locked, unlocked) { l, u -> if (u) emptySet() else l }

    private fun hash(pin: String, salt: ByteArray): String {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)).encoded
        return Base64.encodeToString(k, Base64.NO_WRAP)
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("hash", hash(pin, salt)).apply()
        hasPin.value = true
    }

    fun check(pin: String): Boolean {
        val salt = Base64.decode(sp.getString("salt", null) ?: return false, Base64.NO_WRAP)
        val want = (sp.getString("hash", null) ?: return false).toByteArray()
        return MessageDigest.isEqual(want, hash(pin, salt).toByteArray())
    }

    fun unlock(pin: String): Boolean = check(pin).also { if (it) unlocked.value = true }
    fun lockNow() { unlocked.value = false }

    /** Removing the PIN also removes every lock, so nothing stays hidden with no way back. */
    fun clearPin() {
        sp.edit().remove("hash").remove("salt").remove("locked").apply()
        hasPin.value = false
        locked.value = emptySet()
        unlocked.value = false
    }

    fun setLocked(key: String, on: Boolean) {
        val n = if (on) locked.value + key else locked.value - key
        locked.value = n
        sp.edit().putStringSet("locked", n).apply()
    }

    /** For "Sync to TV": the PIN as salt + hash (never the PIN itself), the auto-lock time and the locked keys. Null when there is no PIN. */
    fun export(): org.json.JSONObject? {
        val salt = sp.getString("salt", null) ?: return null
        val hash = sp.getString("hash", null) ?: return null
        return org.json.JSONObject().put("salt", salt).put("hash", hash).put("lockAfter", lockAfterSec.value)
    }

    /** The other device's PIN becomes this device's PIN. */
    fun importPin(salt: String, hash: String, lockAfter: Int) {
        sp.edit().putString("salt", salt).putString("hash", hash).putInt("lockAfter", lockAfter).apply()
        hasPin.value = true
        lockAfterSec.value = lockAfter
    }

    fun setAllLocked(keys: Set<String>) {
        locked.value = keys
        sp.edit().putStringSet("locked", keys).apply()
    }

    fun setLockAfter(sec: Int) { lockAfterSec.value = sec; sp.edit().putInt("lockAfter", sec).apply() }

    fun leftApp() {
        leftAt = System.currentTimeMillis()
        if (lockAfterSec.value == 0) unlocked.value = false
    }

    /** Back in the app: lock again if we were away longer than the chosen time. */
    fun backInApp() {
        if (unlocked.value && leftAt > 0 && System.currentTimeMillis() - leftAt > lockAfterSec.value * 1000L) unlocked.value = false
    }
}
