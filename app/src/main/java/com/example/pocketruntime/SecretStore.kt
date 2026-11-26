package com.example.pocketruntime

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(filesDir: File) {
    private val secretFile = File(filesDir, "protected/token-harbor-key.bin")

    @Synchronized
    fun store(apiKey: String) {
        val normalizedKey = apiKey.trim()
        require(normalizedKey.isNotEmpty()) { "API key cannot be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalizedKey.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()

        require(secretFile.parentFile?.mkdirs() == true || secretFile.parentFile?.isDirectory == true) {
            "Could not create protected credential storage."
        }
        val temp = File(secretFile.parentFile, "${secretFile.name}.tmp")
        try {
            temp.writeBytes(payload)
            Files.move(
                temp.toPath(),
                secretFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @Synchronized
    fun retrieve(): String? {
        if (!secretFile.isFile) return null
        val payload = secretFile.readBytes()
        require(payload.size > 1) { "Stored API key is invalid. Please replace it." }
        val buffer = ByteBuffer.wrap(payload)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..16 && buffer.remaining() > ivLength) {
            "Stored API key is invalid. Please replace it."
        }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return try {
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException(
                "The saved API key cannot be decrypted. Replace or clear it in Settings.",
                error
            )
        }
    }

    @Synchronized
    fun clear() {
        if (secretFile.exists() && !secretFile.delete()) {
            throw IllegalStateException("Could not remove the stored API key.")
        }
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    fun isConfigured(): Boolean = secretFile.isFile

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "pocket-runtime-token-harbor-key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
