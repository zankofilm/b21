package ir.bazr.inspection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private AES-GCM storage; backups are disabled in AndroidManifest. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("bazr_secure_v2", Context.MODE_PRIVATE)
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val plainAlias = "bazr_local_aes_v2"
    private val bioAlias = "bazr_bio_aes_v2"
    private fun getKey(alias: String, biometric: Boolean): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (biometric) {
            builder.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }
    fun save(name: String, text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(plainAlias, false))
        val raw = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("$name.iv", enc(cipher.iv)).putString("$name.ct", enc(raw)).apply()
    }
    fun load(name: String): String? {
        val iv = prefs.getString("$name.iv", null) ?: return null
        val ct = prefs.getString("$name.ct", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(plainAlias, false), GCMParameterSpec(128, dec(iv)))
            String(cipher.doFinal(dec(ct)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
    fun clear(name: String) { prefs.edit().remove("$name.iv").remove("$name.ct").apply() }
    fun getString(name: String): String? = prefs.getString(name, null)
    fun putString(name: String, value: String) { prefs.edit().putString(name, value).apply() }
    fun biometricEnabled(): Boolean = prefs.getBoolean("bio.enabled", false)
    fun bioEncryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, getKey(bioAlias, true))
    }
    fun bioDecryptCipher(): Cipher? {
        val iv = prefs.getString("bio.iv", null) ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getKey(bioAlias, true), GCMParameterSpec(128, dec(iv)))
        }
    }
    fun finalizeBioEncryption(cipher: Cipher, token: String) {
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("bio.iv", enc(cipher.iv)).putString("bio.ct", enc(encrypted))
            .putBoolean("bio.enabled", true).apply()
        clear("device_token")
    }
    fun decryptBio(cipher: Cipher): String {
        val raw = dec(prefs.getString("bio.ct", null) ?: error("bio token unavailable"))
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }
    fun clearBio() {
        prefs.edit().remove("bio.iv").remove("bio.ct").putBoolean("bio.enabled", false).apply()
    }
    private fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
