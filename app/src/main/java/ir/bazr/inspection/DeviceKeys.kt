package ir.bazr.inspection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** P-256 private key never leaves Android Keystore; only the PEM public key is sent. */
class DeviceKeys {
    private val alias = "bazr_inspector_device_p256_v1"
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun keyPair(): KeyPair {
        val entry = keystore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) return KeyPair(entry.certificate.publicKey, entry.privateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair()
    }

    fun publicKeyPem(): String {
        val base = Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP)
        val wrapped = base.chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----"
    }
    fun signed(message: String): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair().private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signer.sign(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    fun nonce(): String = UUID.randomUUID().toString()
}
