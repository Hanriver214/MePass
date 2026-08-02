package com.mepass.app.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 加密管理器
 * 用于：
 * 1. 加密 Shamir 秘密共享的分片（使用每个问题的答案密钥）
 * 2. 可选：加密整个模板文件内容
 */
object AesManager {

    private const val AES_GCM_NONCE_LENGTH = 12 // 字节（推荐值）
    private const val AES_GCM_TAG_LENGTH_BITS = 128
    private const val AES_KEY_LENGTH = 32 // 256位密钥

    /**
     * AES-256-GCM 加密
     * @param plaintext 明文
     * @param key 密钥（32字节 = 256位）
     * @param nonce 可选的nonce，不传则随机生成（推荐）
     * @return Pair(密文, nonce)
     */
    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray? = null
    ): Pair<ByteArray, ByteArray> {
        require(key.size == AES_KEY_LENGTH) {
            "AES密钥长度必须为${AES_KEY_LENGTH}字节，当前为${key.size}"
        }

        val actualNonce = nonce ?: Argon2Manager.generateRandomBytes(AES_GCM_NONCE_LENGTH)
        require(actualNonce.size == AES_GCM_NONCE_LENGTH) {
            "GCM nonce长度必须为${AES_GCM_NONCE_LENGTH}字节"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH_BITS, actualNonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)
        return Pair(ciphertext, actualNonce)
    }

    /**
     * AES-256-GCM 解密
     * @param ciphertext 密文
     * @param key 密钥（32字节）
     * @param nonce 加密时使用的nonce
     * @return 明文
     */
    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        require(key.size == AES_KEY_LENGTH) {
            "AES密钥长度必须为${AES_KEY_LENGTH}字节"
        }
        require(nonce.size == AES_GCM_NONCE_LENGTH) {
            "GCM nonce长度必须为${AES_GCM_NONCE_LENGTH}字节"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Base64 封装版本的加密 - 直接返回 Base64 字符串
     */
    fun encryptToBase64(
        plaintext: String,
        key: ByteArray,
        nonce: ByteArray? = null
    ): EncryptedData {
        val ptBytes = plaintext.toByteArray(Charsets.UTF_8)
        val (ct, n) = encrypt(ptBytes, key, nonce)
        return EncryptedData(
            ciphertextBase64 = android.util.Base64.encodeToString(
                ct, android.util.Base64.NO_WRAP
            ),
            nonceBase64 = android.util.Base64.encodeToString(
                n, android.util.Base64.NO_WRAP
            )
        )
    }

    /**
     * Base64 封装版本的解密
     */
    fun decryptFromBase64(
        encryptedData: EncryptedData,
        key: ByteArray
    ): String {
        val ct = android.util.Base64.decode(
            encryptedData.ciphertextBase64, android.util.Base64.NO_WRAP
        )
        val n = android.util.Base64.decode(
            encryptedData.nonceBase64, android.util.Base64.NO_WRAP
        )
        val pt = decrypt(ct, key, n)
        return String(pt, Charsets.UTF_8)
    }

    /**
     * 生成新的 AES 密钥
     */
    fun generateKey(): ByteArray = Argon2Manager.generateRandomBytes(AES_KEY_LENGTH)

    /**
     * 通过密码派生 AES 密钥（使用 Argon2id）
     */
    fun deriveKeyFromPassword(password: String, salt: ByteArray): ByteArray {
        return Argon2Manager.deriveKeyFromAnswer(password, salt)
    }

    /**
     * 获取推荐的 nonce 长度
     */
    fun getNonceLength(): Int = AES_GCM_NONCE_LENGTH
}

/**
 * 封装加密结果
 */
data class EncryptedData(
    val ciphertextBase64: String,
    val nonceBase64: String
)
