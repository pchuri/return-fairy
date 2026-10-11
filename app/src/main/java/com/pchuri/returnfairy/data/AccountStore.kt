package com.pchuri.returnfairy.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [label] is the name shown on the dashboard; blank means "use the name the library site shows". */
data class Account(val userId: String, val password: String, val label: String = "", val revision: String = "") {
    override fun toString(): String = "Account(userId=$userId, label=$label)"
}

/**
 * Stores library accounts in SharedPreferences with passwords encrypted
 * by an AES-GCM key held in the Android Keystore (never leaves the device).
 */
class AccountStore internal constructor(
    private val context: Context,
    private val encodePassword: ((String) -> String)? = null,
    private val decodePassword: ((String) -> String?)? = null,
) {

    private val prefs = context.getSharedPreferences("returnfairy_accounts", Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val ciphertext = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun load(): List<Account> = synchronized(LookupCoordination.lock) {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val userId = obj.optString("userId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val password = (decodePassword ?: ::decrypt)(obj.optString("encryptedPassword")) ?: return@mapNotNull null
                Account(userId, password, obj.optString("label"), obj.optString("revision"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** New/changed accounts get a new incarnation, even when removed then re-added unchanged. */
    fun save(accounts: List<Account>): List<Account> = synchronized(LookupCoordination.lock) {
        val previous = load().associateBy { it.userId }
        val revised = accounts.distinctBy { it.userId }.map { account ->
            val old = previous[account.userId]
            val revision = if (old != null && old.password == account.password && old.label == account.label)
                old.revision else UUID.randomUUID().toString()
            account.copy(revision = revision)
        }
        val array = JSONArray()
        revised.forEach { account ->
            array.put(JSONObject()
                .put("userId", account.userId)
                .put("encryptedPassword", (encodePassword ?: ::encrypt)(account.password))
                .put("label", account.label)
                .put("revision", account.revision))
        }
        check(prefs.edit().putString(KEY_ACCOUNTS, array.toString()).commit()) { "Could not save accounts" }
        // Use the same lock as commits; invalidate changed accounts as well as deleted accounts.
        SnapshotStore(context).retainAccounts(revised)
        revised
    }

    fun beginLookup(): LookupSession = synchronized(LookupCoordination.lock) {
        val sequence = Math.addExact(prefs.getLong("lookupSequence", 0), 1)
        check(prefs.edit().putLong("lookupSequence", sequence).commit()) { "Could not order lookup" }
        LookupSession(load(), sequence)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "returnfairy_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val KEY_ACCOUNTS = "accounts"
    }
}
