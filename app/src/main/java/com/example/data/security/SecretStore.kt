package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Small Android-Keystore-backed store for integration credentials.
 *
 * Ciphertext lives in an excluded SharedPreferences file; the non-exportable
 * AES key lives in Android Keystore and is not transferred to another device.
 *
 * REDESIGN (docs/saas/09-server-invariants.md §3): on the server this shape becomes a KMS
 * envelope — data key per write, AAD bound to workspace + setting key, ciphertext never
 * destroyed on a failed read. The device Keystore stays for device-local secrets.
 */
class SecretStore(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val lock = Any()

  fun read(key: String): String? =
    synchronized(lock) {
      val encoded = preferences.getString(key, null) ?: return@synchronized null
      try {
        val separator = encoded.indexOf(':')
        require(separator > 0 && separator < encoded.lastIndex)
        val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(key.toByteArray(StandardCharsets.UTF_8))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
      } catch (e: Exception) {
        // Keystore/provider failures can be transient. Never destroy the only
        // ciphertext copy merely because one read failed; a later read may
        // recover, and an explicit save/delete can still replace it safely.
        Log.e(TAG, "Could not decrypt $key; retaining the encrypted value", e)
        null
      }
    }

  fun write(key: String, value: String) {
    if (value.isBlank()) {
      delete(key)
      return
    }
    synchronized(lock) {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
      cipher.updateAAD(key.toByteArray(StandardCharsets.UTF_8))
      val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
      val ciphertext =
        Base64.encodeToString(
          cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
          Base64.NO_WRAP,
        )
      preferences.edit { putString(key, "$iv:$ciphertext") }
    }
  }

  fun delete(key: String) {
    preferences.edit { remove(key) }
  }

  /**
   * Encrypts an arbitrary document (the backup) with the same Keystore key as the credential
   * store, bound to a different purpose so one ciphertext can never decrypt as the other.
   */
  fun encryptBackup(plaintext: String): String =
    synchronized(lock) {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
      cipher.updateAAD(BACKUP_AAD)
      val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
      val ciphertext =
        Base64.encodeToString(
          cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)),
          Base64.NO_WRAP,
        )
      "$iv:$ciphertext"
    }

  fun decryptBackup(encoded: String): String? =
    synchronized(lock) {
      try {
        val separator = encoded.indexOf(':')
        require(separator > 0 && separator < encoded.lastIndex)
        val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(BACKUP_AAD)
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
      } catch (e: Exception) {
        Log.e(TAG, "Could not decrypt backup", e)
        null
      }
    }

  fun flow(key: String): Flow<String?> =
    callbackFlow {
        val listener =
          SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(read(key))
          }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Register first so a concurrent save cannot slip between the initial
        // read and listener registration.
        trySend(read(key))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
      }
      .distinctUntilChanged()
      .flowOn(Dispatchers.IO)

  private fun encryptionKey(): SecretKey =
    synchronized(KEY_LOCK) {
      val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
      (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
          .apply {
            init(
              KeyGenParameterSpec.Builder(
                  KEY_ALIAS,
                  KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            )
          }
          .generateKey()
    }

  private companion object {
    const val TAG = "SecretStore"
    const val PREFERENCES_NAME = "encrypted_integration_credentials"
    const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val KEY_ALIAS = "daily_brief.integration_credentials.v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
    val KEY_LOCK = Any()
    val BACKUP_AAD = "daily-brief.backup.v1".toByteArray(StandardCharsets.UTF_8)
  }
}
