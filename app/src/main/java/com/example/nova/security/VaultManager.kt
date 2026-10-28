package com.example.nova.security

import com.example.nova.data.SecretDao
import com.example.nova.data.SecretMetadataEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class VaultManager(
    private val secretDao: SecretDao,
    private val keystoreHelper: KeystoreHelper = KeystoreHelper()
) {
    val allSecrets: Flow<List<SecretMetadataEntity>> = secretDao.getAllSecrets()

    suspend fun createSecret(
        name: String,
        type: String,
        plaintextValue: String,
        username: String? = null,
        domain: String? = null,
        permissionMode: PermissionMode = PermissionMode.ASK_EVERY_TIME
    ): SecretMetadataEntity {
        val payload = keystoreHelper.encrypt(plaintextValue)
        val entity = SecretMetadataEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            type = type,
            username = username?.trim(),
            domain = domain?.let { PermissionManager.normalizeDomain(it) },
            permissionMode = permissionMode.name,
            encryptedCiphertext = payload.ciphertext,
            iv = payload.iv,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        secretDao.insertSecret(entity)
        return entity
    }

    suspend fun updateSecretValue(
        id: String,
        plaintextValue: String
    ): Boolean {
        val existing = secretDao.getSecretById(id) ?: return false
        val payload = keystoreHelper.encrypt(plaintextValue)
        val updated = existing.copy(
            encryptedCiphertext = payload.ciphertext,
            iv = payload.iv,
            updatedAt = System.currentTimeMillis()
        )
        secretDao.updateSecret(updated)
        return true
    }

    suspend fun updateSecretMetadata(
        id: String,
        name: String,
        username: String?,
        domain: String?,
        permissionMode: PermissionMode
    ): Boolean {
        val existing = secretDao.getSecretById(id) ?: return false
        val updated = existing.copy(
            name = name.trim(),
            username = username?.trim(),
            domain = domain?.let { PermissionManager.normalizeDomain(it) },
            permissionMode = permissionMode.name,
            updatedAt = System.currentTimeMillis()
        )
        secretDao.updateSecret(updated)
        return true
    }

    suspend fun deleteSecret(id: String) {
        secretDao.deleteSecretById(id)
    }

    suspend fun revealSecretValue(id: String): String? {
        val existing = secretDao.getSecretById(id) ?: return null
        return try {
            keystoreHelper.decrypt(existing.encryptedCiphertext, existing.iv)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves matching secret for autofill only when verified against the current website domain.
     * Updates lastUsedAt.
     */
    suspend fun getSecretForAutofill(
        secretId: String,
        currentWebDomain: String
    ): Pair<SecretMetadataEntity, String>? {
        val secret = secretDao.getSecretById(secretId) ?: return null
        val normalizedWeb = PermissionManager.normalizeDomain(currentWebDomain)

        // Verify domain binding if secret is domain-scoped
        if (!secret.domain.isNullOrBlank() && secret.domain != "*") {
            val normalizedSecretDomain = PermissionManager.normalizeDomain(secret.domain)
            if (normalizedWeb != normalizedSecretDomain && !normalizedWeb.endsWith(".$normalizedSecretDomain")) {
                return null // Domain mismatch: block autofill
            }
        }

        val plainValue = try {
            keystoreHelper.decrypt(secret.encryptedCiphertext, secret.iv)
        } catch (e: Exception) {
            return null
        }

        // Update lastUsed timestamp
        secretDao.updateSecret(secret.copy(lastUsedAt = System.currentTimeMillis()))

        return Pair(secret, plainValue)
    }

    suspend fun getSecretsForDomain(webDomain: String): List<SecretMetadataEntity> {
        val norm = PermissionManager.normalizeDomain(webDomain)
        return secretDao.getSecretsForDomain(norm)
    }
}
