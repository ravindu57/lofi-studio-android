package com.lofiautopilot.studio.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys and tokens, encrypted with a key that lives in the Android Keystore (never leaves the phone's secure hardware). */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    fun get(name: String): String {
        val raw = prefs.getString(name, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun put(name: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply()
    }

    fun has(name: String) = prefs.contains(name) && get(name).isNotEmpty()

    fun masked(name: String): String {
        val v = get(name)
        return if (v.isEmpty()) "" else "···" + v.takeLast(4)
    }

    companion object {
        private const val ALIAS = "lofi_secrets_v1"
        const val ANTHROPIC = "ANTHROPIC_API_KEY"
        const val GEMINI = "GEMINI_API_KEY"
        const val GROQ = "GROQ_API_KEY"
        const val OPENROUTER = "OPENROUTER_API_KEY"
        const val OPENAI = "OPENAI_API_KEY"
        const val YOUTUBE_API = "YOUTUBE_API_KEY"
        const val FB_TOKEN = "FB_PAGE_TOKEN"
        const val FB_APP_ID = "FB_APP_ID"
        const val FB_APP_SECRET = "FB_APP_SECRET"
        const val TELEGRAM = "TELEGRAM_BOT_TOKEN"
        const val YT_CLIENT_ID = "YT_CLIENT_ID"
        const val YT_CLIENT_SECRET = "YT_CLIENT_SECRET"
        const val YT_REFRESH = "YT_REFRESH_TOKEN"
        const val HF_TOKEN = "HF_TOKEN"
    }
}
