package com.mepass.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * AES-256-GCM 加解密
 *
 * - 密钥长度：32 字节（AES-256）
 * - Nonce：12 字节
 * - Tag 长度：128 bit
 *
 * [encrypt] 输出 = nonce(12B) + ciphertext + tag(16B)
 * [decrypt] 输入 = nonce(12B) + ciphertext + tag(16B)
 *
 * v2：支持 AAD（Additional Authentication Data）绑定元数据，
 * 防止密文被重排到其他上下文。
 */
object AesGcm {
    private const val KEY_LENGTH = 32
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    /** 生成随机 AES-256 密钥 */
    fun generateKey(): ByteArray = ByteArray(KEY_LENGTH).also { secureRandom.nextBytes(it) }

    /** 生成随机 nonce */
    fun generateNonce(): ByteArray = ByteArray(NONCE_LENGTH).also { secureRandom.nextBytes(it) }

    /**
     * 加密（可选 AAD 认证数据）
     *
     * @param plaintext 明文
     * @param key 32 字节密钥
     * @param nonce 12 字节 nonce（可选，默认随机生成）
     * @param aad 额外认证数据（可选，绑定到密文防止重排攻击）
     * @return nonce + ciphertext + tag 拼接的字节数组
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray = generateNonce(), aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_LENGTH) { "密钥长度必须为 $KEY_LENGTH 字节" }
        require(nonce.size == NONCE_LENGTH) { "nonce 长度必须为 $NONCE_LENGTH 字节" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val cipherText = cipher.doFinal(plaintext)
        return nonce + cipherText
    }

    /**
     * 解密（可选 AAD 认证数据）
     *
     * @param input encrypt() 输出的字节数组
     * @param key 32 字节密钥
     * @param aad 额外认证数据（必须与加密时使用的一致）
     * @return 明文
     */
    fun decrypt(input: ByteArray, key: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_LENGTH) { "密钥长度必须为 $KEY_LENGTH 字节" }
        require(input.size > NONCE_LENGTH) { "输入数据过短" }

        val nonce = input.copyOfRange(0, NONCE_LENGTH)
        val cipherText = input.copyOfRange(NONCE_LENGTH, input.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(cipherText)
    }

    /** Base64 包装版本 */
    fun encryptToBase64(plaintext: ByteArray, key: ByteArray, nonce: ByteArray = generateNonce(), aad: ByteArray? = null): String =
        Base64.getEncoder().encodeToString(encrypt(plaintext, key, nonce, aad))

    fun decryptFromBase64(input: String, key: ByteArray, aad: ByteArray? = null): ByteArray =
        decrypt(Base64.getDecoder().decode(input), key, aad)
}
