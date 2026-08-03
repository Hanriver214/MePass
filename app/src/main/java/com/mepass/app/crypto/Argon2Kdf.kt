package com.mepass.app.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id 密钥派生与答案哈希（极简版）
 *
 * 参数遵循 OWASP 推荐量级：
 * - iterations = 3
 * - memory = 64 MB
 * - parallelism = 2
 * - hashLength = 32 bytes
 *
 * 提供两个核心能力：
 * 1. [hashAnswer] / [verifyAnswer]：答案的哈希存储与验证
 * 2. [deriveKey]：从答案派生 AES-256 密钥（用于加密 Shamir 分片）
 */
object Argon2Kdf {
    private const val ITERATIONS = 3
    private const val MEMORY_KB = 65536  // 64 MB
    private const val PARALLELISM = 2
    private const val HASH_LENGTH = 32
    private const val SALT_LENGTH = 16

    private val secureRandom = SecureRandom()

    /** 生成随机盐值 */
    fun generateSalt(): ByteArray = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }

    /**
     * 计算答案的 Argon2id 哈希
     *
     * @param normalizedAnswer 已规范化的答案
     * @param salt 盐值（16 字节）
     * @return Base64 编码的哈希值
     */
    fun hashAnswer(normalizedAnswer: String, salt: ByteArray): String {
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
        return Base64.getEncoder().encodeToString(output)
    }

    /**
     * 验证答案是否匹配
     *
     * @param storedHash 存储的哈希（Base64）
     * @param normalizedAnswer 待验证的已规范化答案
     * @param salt 盐值
     */
    fun verifyAnswer(storedHash: String, normalizedAnswer: String, salt: ByteArray): Boolean {
        val computed = hashAnswer(normalizedAnswer, salt)
        return constantTimeEquals(
            storedHash.toByteArray(Charsets.UTF_8),
            computed.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * 从答案派生 AES-256 密钥（32 字节）
     * 与 [hashAnswer] 使用相同参数，但直接返回 raw bytes
     */
    fun deriveKey(normalizedAnswer: String, salt: ByteArray): ByteArray {
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
