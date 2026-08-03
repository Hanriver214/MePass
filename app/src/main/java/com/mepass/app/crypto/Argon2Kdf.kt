package com.mepass.app.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id 密钥派生与答案哈希
 *
 * v2 设计：使用 HKDF 风格双密钥派生，彻底分离验证哈希与加密密钥：
 *   1. master_key = Argon2id(normalizedAnswer, salt)
 *   2. verification_key = HMAC-SHA256(master_key, "mepass_verification")
 *   3. encryption_key   = HMAC-SHA256(master_key, "mepass_encryption")
 *   4. verification_hash = SHA-256(verification_key) — 存入模板
 *
 * 即使攻击者获取模板中的 verification_hash，也无法推导出 encryption_key，
 * 从而阻止了「Base64 解码哈希即得 AES 密钥」的严重攻击路径。
 *
 * 参数遵循 OWASP 推荐量级：
 * - iterations = 3
 * - memory = 64 MB
 * - parallelism = 2
 */
object Argon2Kdf {
    const val ITERATIONS = 3
    const val MEMORY_KB = 65536  // 64 MB
    const val PARALLELISM = 2
    private const val HASH_LENGTH = 32
    private const val SALT_LENGTH = 16

    private const val CONTEXT_VERIFICATION = "mepass_verification"
    private const val CONTEXT_ENCRYPTION = "mepass_encryption"

    private val secureRandom = SecureRandom()

    /** 生成随机盐值 */
    fun generateSalt(): ByteArray = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }

    /**
     * 从答案派生主密钥（Argon2id）
     *
     * @param normalizedAnswer 已规范化的答案
     * @param salt 盐值
     * @return 32 字节主密钥
     */
    private fun computeMasterKey(normalizedAnswer: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withIterations(ITERATIONS)
            .withMemoryAsKB(MEMORY_KB)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val output = ByteArray(HASH_LENGTH)
        generator.generateBytes(
            normalizedAnswer.toByteArray(Charsets.UTF_8),
            output
        )
        return output
    }

    /** HMAC-SHA256 密钥派生 */
    private fun hmacSha256(key: ByteArray, context: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(context.toByteArray(Charsets.UTF_8))
    }

    /** SHA-256 哈希 */
    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * 从答案同时派生验证密钥和加密密钥
     * 一次 Argon2id 计算 + 两次 HMAC，比分别调用节省一半计算量
     *
     * @param normalizedAnswer 已规范化的答案
     * @param salt 盐值
     * @return DerivedKeys 包含验证密钥和加密密钥
     */
    fun deriveKeys(normalizedAnswer: String, salt: ByteArray): DerivedKeys {
        val masterKey = computeMasterKey(normalizedAnswer, salt)
        val verificationKey = hmacSha256(masterKey, CONTEXT_VERIFICATION)
        val encryptionKey = hmacSha256(masterKey, CONTEXT_ENCRYPTION)
        masterKey.fill(0)
        return DerivedKeys(verificationKey, encryptionKey)
    }

    /** 双密钥派生结果 */
    data class DerivedKeys(
        val verificationKey: ByteArray,
        val encryptionKey: ByteArray
    ) {
        fun clear() {
            verificationKey.fill(0)
            encryptionKey.fill(0)
        }
    }

    /**
     * 将验证密钥哈希为可存储的 Base64 字符串
     * 使用 SHA-256 对验证密钥再哈希，确保即使泄露验证哈希也无法反推验证密钥
     */
    fun hashVerificationKey(verificationKey: ByteArray): String {
        return Base64.getEncoder().encodeToString(sha256(verificationKey))
    }

    /**
     * 验证答案是否正确
     *
     * @param storedHash 存储的验证哈希（Base64）
     * @param normalizedAnswer 待验证的已规范化答案
     * @param salt 盐值
     */
    fun verifyAnswer(storedHash: String, normalizedAnswer: String, salt: ByteArray): Boolean {
        val keys = deriveKeys(normalizedAnswer, salt)
        val computed = hashVerificationKey(keys.verificationKey)
        keys.clear()
        return constantTimeEquals(
            storedHash.toByteArray(Charsets.UTF_8),
            computed.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * 从答案派生 AES-256 加密密钥
     * 用于加密/解密 Shamir 分片
     *
     * @param normalizedAnswer 已规范化的答案
     * @param salt 盐值
     * @return 32 字节加密密钥
     */
    fun deriveEncryptionKey(normalizedAnswer: String, salt: ByteArray): ByteArray {
        val keys = deriveKeys(normalizedAnswer, salt)
        val key = keys.encryptionKey.copyOf()
        keys.clear()
        return key
    }

    /**
     * 从用户口令派生 AES-256 密钥（用于加密导出）
     *
     * 与答案派生使用相同的 Argon2id 参数，但使用口令原始输入（不做规范化）
     * 使用独立上下文标签 "mepass_export" 与答案派生隔离
     */
    fun deriveKeyFromPassword(password: String, salt: ByteArray): ByteArray {
        val masterKey = computeMasterKey(password, salt)
        val exportKey = hmacSha256(masterKey, "mepass_export")
        masterKey.fill(0)
        return exportKey
    }

    /** 常量时间比较，防止时序攻击 */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
