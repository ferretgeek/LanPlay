package com.lanplay.player.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 需求 C-06：SMB 凭据用 Android Keystore 的 AES-256-GCM 加密后落盘，
 * 明文密码不进 DataStore、不进日志、不进任何文件。
 *
 * 密钥常驻 Keystore，应用私有；即使 DataStore 文件被拉出来也解不开。
 */
@Singleton
class CredentialCipher @Inject constructor() {

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "lanplay_credential_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }

    /** @return 密文 + IV。访客共享或允许空密码的账户同样能正常加解密。 */
    fun encrypt(plain: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return out to cipher.iv
    }

    fun decrypt(cipherText: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }
}
