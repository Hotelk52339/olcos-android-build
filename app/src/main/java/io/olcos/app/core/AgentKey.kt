package io.olcos.app.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This device's identity to every server agent: one P-256 key in the Android
 * keystore (hardware-backed where the device has it), registered on each
 * server the device pairs with. The agent hashes the public key (X.509 SPKI
 * DER) into the device id, the same way the iOS app's key is handled.
 */
class AgentKey private constructor(private val privateKey: PrivateKey, val publicKey: PublicKey) {
    /** X.509 SubjectPublicKeyInfo DER, what `POST pair` sends and the agent stores. */
    val spkiDER: ByteArray get() = publicKey.encoded

    val deviceID: String get() = deviceID(spkiDER)

    /** The same SPKI as standard base64: what `olcos-agent init -owner` and `add-device -key` take. */
    val spkiBase64: String get() = Base64.encodeToString(spkiDER, Base64.NO_WRAP)

    /** ECDSA over SHA-256, ASN.1/DER encoded: what the agent verifies. */
    fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    companion object {
        private const val ALIAS = "olcos.agent.device"
        private const val STORE = "AndroidKeyStore"

        fun base64url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun deviceID(spki: ByteArray): String =
            base64url(MessageDigest.getInstance("SHA-256").digest(spki)).take(16)

        /** Loads the key, creating it on first use. */
        @Synchronized
        fun loadOrCreate(): AgentKey {
            val store = KeyStore.getInstance(STORE).apply { load(null) }
            val entry = store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) return AgentKey(entry.privateKey, entry.certificate.publicKey)
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            val pair = generator.generateKeyPair()
            return AgentKey(pair.private, pair.public)
        }
    }
}
