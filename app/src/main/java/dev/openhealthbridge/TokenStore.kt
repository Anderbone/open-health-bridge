package dev.openhealthbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import java.util.UUID

class TokenStore(context: Context, preferencesName: String = "destination") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    val scope: String = prefs.getString("scope", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("scope", it).commit() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bridge-token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bridge-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): DestinationConfig {
        val encrypted = prefs.getString("token", "")!!
        val token = if (encrypted.isEmpty()) "" else {
            val parts = encrypted.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
        }
        return DestinationConfig(prefs.getString("url", "")!!, token, prefs.getBoolean("enabled", false),
            prefs.getStringSet("types", HealthType.entries.map { it.name }.toSet())!!.mapNotNull { name -> HealthType.entries.find { it.name == name } }.toSet(),
            prefs.getString("origin", "")!!, prefs.getBoolean("automatic", false))
    }
    fun save(config: DestinationConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = if (config.token.isEmpty()) "" else Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(config.token.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("url", config.url).putString("token", encrypted).putBoolean("enabled", config.enabled)
            .putStringSet("types", config.types.map { it.name }.toSet()).putString("origin", config.origin)
            .putBoolean("automatic", config.automatic).commit()) { "Settings could not be saved" }
    }
    @Synchronized fun nextRevision(): Long {
        val next = maxOf(prefs.getLong("revision", 0) + 1, System.currentTimeMillis())
        check(prefs.edit().putLong("revision", next).commit())
        return next
    }
    fun disable() { prefs.edit().putBoolean("enabled", false).putBoolean("automatic", false).commit() }
    fun clear() { val revision = prefs.getLong("revision", 0); prefs.edit().clear().putString("scope", scope).putLong("revision", revision).commit() }
}
