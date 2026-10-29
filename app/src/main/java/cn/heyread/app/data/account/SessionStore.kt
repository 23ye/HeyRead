package cn.heyread.app.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Persists only AES-GCM ciphertext; the key is generated and retained by AndroidKeyStore. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)
    private val keyAlias = "cn.heyread.account.session.v1"

    @Synchronized
    fun load(): AccountSession? =
        runCatching {
                val encoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                val ivLength = bytes.first().toInt() and 0xff
                require(ivLength in 12..16 && bytes.size > ivLength)
                val iv = bytes.copyOfRange(1, 1 + ivLength)
                val cipherText = bytes.copyOfRange(1 + ivLength, bytes.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val json = JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8))
                AccountSession(
                        json.getString("cookie"),
                        json.getString("userId"),
                        json.optString("nickname"),
                        json.optString("avatarUrl"),
                    )
                    .takeIf { it.isValid }
            }
            .getOrNull()

    @Synchronized
    fun save(session: AccountSession) {
        require(session.isValid) { "账号响应缺少有效凭据或用户 ID" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val plain =
            JSONObject()
                .put("cookie", session.cookieHeader)
                .put("userId", session.userId)
                .put("nickname", session.nickname)
                .put("avatarUrl", session.avatarUrl)
                .toString()
                .toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(plain)
        val packed = byteArrayOf(iv.size.toByte()) + iv + encrypted
        check(
            prefs
                .edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit()
        )
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_CIPHERTEXT = "session_ciphertext"
    }
}
