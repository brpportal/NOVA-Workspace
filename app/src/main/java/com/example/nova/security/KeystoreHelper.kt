package com.example.nova.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val ciphertext: String,
    val iv: String
)

class KeystoreHelper(
    private val keyAlias: String = "NovaVaultMasterKey"
) {
    private val androidKeyStore = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val gcmTagLength = 128
    private val ivLength = 12 // Standard 96-bit IV for GCM

    // In unit testing environments (JVM without AndroidKeyStore), fallback key is maintained in-memory
    private var testFallbackKey: SecretKey? = null

    private fun getSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(androidKeyStore).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    androidKeyStore
                )
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(false) // We supply our own cryptographically secure random IV
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                (keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
            }
        } catch (e: Exception) {
            // JVM / Unit-test fallback when AndroidKeyStore provider is absent
            if (testFallbackKey == null) {
                val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                testFallbackKey = SecretKeySpec(rawKey, "AES")
            }
            testFallbackKey!!
        }
    }

    fun encrypt(plaintext: String): EncryptedPayload {
        val secretKey = getSecretKey()
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(transformation)
        val spec = GCMParameterSpec(gcmTagLength, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return EncryptedPayload(
            ciphertext = encodeBase64(cipherBytes),
            iv = encodeBase64(iv)
        )
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String): String {
        val secretKey = getSecretKey()
        val cipherBytes = decodeBase64(ciphertextBase64)
        val ivBytes = decodeBase64(ivBase64)
        val cipher = Cipher.getInstance(transformation)
        val spec = GCMParameterSpec(gcmTagLength, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun decodeBase64(base64Str: String): ByteArray {
        return try {
            Base64.decode(base64Str, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(base64Str)
        }
    }
}
