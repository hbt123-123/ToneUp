package com.toneup.app.data.local

import android.content.Context
import android.os.Looper
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

    // M-12：check-then-generate 必须互斥——两线程并发首次调用会各自 generateKey，
    // 同 alias 后写覆盖先写，先写者持有的密钥随即失效、旧密文不可解
    private fun obtainKey(): SecretKey = synchronized(KEY_LOCK) {
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
        // M-12：obtainKey 的互斥锁（类级，防多实例并发生成）
        val KEY_LOCK = Any()
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
        val editor = prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(payload, Base64.NO_WRAP))
        // M-13：安全令牌不能完全 fire-and-forget——主线程保持 apply()（同步 commit
        // 会阻塞磁盘 IO，登录流程运行在主线程协程有 ANR 风险）；后台线程改用 commit()
        // 同步落盘并校验返回值，失败留痕日志
        if (Looper.myLooper() == Looper.getMainLooper()) {
            editor.apply()
        } else if (!editor.commit()) {
            Log.w(TAG, "token commit returned false; token may not be persisted")
        }
    }

    fun token(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            // M-14：只捕获解密失败（Exception），不再吞 Error（runCatching 捕 Throwable）；
            // 失败时清除残留密文（密钥轮换/密文损坏后永不可解）并视为无令牌
            String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt token failed, treating as no session", e)
            prefs.edit().remove(KEY_TOKEN).apply()
            null
        }
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
