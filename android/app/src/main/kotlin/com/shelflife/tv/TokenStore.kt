package com.shelflife.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Plex token, encrypted with an AES key that never leaves the Android Keystore.
 * Also holds this install's Plex client identifier (not secret).
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)

    val clientId: String = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("clientId", it).apply() }

    var token: String?
        get() = prefs.getString("token", null)?.let { stored ->
            runCatching {
                val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(data), Charsets.UTF_8)
            }.getOrNull()
        }
        set(value) {
            if (value == null) { prefs.edit().remove("token").apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
            prefs.edit().putString("token", stored).apply()
        }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("plex-token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("plex-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
