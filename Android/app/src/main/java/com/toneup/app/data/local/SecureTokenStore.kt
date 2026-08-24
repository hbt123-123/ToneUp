package com.toneup.app.data.local

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 加解密适配器：便于单元测试注入 */
interface CipherAdapter {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(payload: ByteArray): ByteArray
}

class KeystoreCipherAdapter(private val keyAlias: String = KEY_ALIAS) : CipherAdapter {

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance("AES").apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    keyAlias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return ByteArray(1 + iv.size + encrypted.size).apply {
            this[0] = iv.size.toByte()
            iv.copyInto(this, 1)
            encrypted.copyInto(this, 1 + iv.size)
        }
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > 1) { "ciphertext too short" }
        val ivSize = payload[0].toInt()
        require(ivSize in 12..16 && payload.size > 1 + ivSize) { "corrupted payload" }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val data = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "toneup_master_key"
    }
}

/**
 * 访问令牌加密存储（Android Keystore AES-GCM，密文落 SharedPreferences）。
 * 解密失败视为无令牌（密钥轮换/系统重置场景），静默降级不崩溃。
 */
class SecureTokenStore(context: Context, private val cipher: CipherAdapter = KeystoreCipherAdapter()) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(token: String) {
        val payload = cipher.encrypt(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun token(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "decrypt token failed, treating as no session", it) }
            .getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val PREFS_NAME = "toneup_secure_prefs"
        const val KEY_TOKEN = "access_token_encrypted"
        const val TAG = "SecureTokenStore"
    }
}
