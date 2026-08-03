package com.mepass.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * 确定性 Passphrase 生成器
 *
 * 从主密钥（masterSecret）派生出 14~16 位的强密码，包含大小写字母、数字、符号。
 * 相同的主密钥输入必定产生相同的密码输出（确定性）。
 *
 * v2：使用 HKDF-Expand（RFC 5869 风格）标准密钥派生替代自定义 DRBG，
 * 从主密钥派生密码生成所需的密钥材料，消除自研密码学的安全风险。
 *
 * 算法：
 * 1. 用 HKDF-Expand(HMAC-SHA256) 从 masterSecret 派生伪随机字节
 * 2. 长度在 14/15/16 中按派生字节确定性选择
 * 3. 字符池分 4 类（小写、大写、数字、符号），每类至少 3 个
 * 4. Fisher-Yates 洗牌
 * 5. 输出字符串
 */
object PassphraseGenerator {

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{}<>?"
    private const val MIN_PER_CLASS = 3

    // HKDF-Expand 所需最大字节数：16 字符 + 长度选择 + 洗牌
    // 每字符需要 4 字节（用于安全随机选择），再加 1 字节长度选择
    private const val MAX_BYTES_NEEDED = 16 * 4 + 1 + 64

    /**
     * 从主密钥派生密码
     * @param masterSecret 32 字节主密钥
     * @return 14~16 位密码
     */
    fun generate(masterSecret: ByteArray): String {
        // 使用 HKDF-Expand 派生所需的伪随机字节
        val randomBytes = hkdfExpand(
            masterSecret,
            "mepass_passphrase_generation",
            MAX_BYTES_NEEDED
        )

        // 1. 选择长度（14/15/16）
        val lengthByte = randomBytes[0].toInt() and 0xFF
        val length = when (lengthByte % 3) {
            0 -> 14
            1 -> 15
            else -> 16
        }

        // 2. 分配各类字符配额
        val classes = listOf(LOWER, UPPER, DIGITS, SYMBOLS)
        val charCount = IntArray(4) { MIN_PER_CLASS }
        var remaining = length - MIN_PER_CLASS * 4
        var byteIndex = 1

        while (remaining > 0) {
            val clsIdx = randomBytes[byteIndex].toInt() and 0xFF
            byteIndex++
            charCount[clsIdx % 4]++
            remaining--
        }

        // 3. 从各类中随机挑选字符
        val chars = CharArray(length)
        var pos = 0
        for (i in 0 until 4) {
            val cls = classes[i]
            repeat(charCount[i]) {
                val rByte = randomBytes[byteIndex].toInt() and 0xFF
                byteIndex++
                chars[pos++] = cls[rByte % cls.length]
            }
        }

        // 4. Fisher-Yates 洗牌
        for (i in chars.indices.reversed()) {
            val r1 = randomBytes[byteIndex].toInt() and 0xFF
            byteIndex++
            val r2 = randomBytes[byteIndex].toInt() and 0xFF
            byteIndex++
            val combined = (r1 shl 8) or r2
            val j = combined % (i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }

        return String(chars)
    }

    /**
     * HKDF-Expand (RFC 5869) using HMAC-SHA256
     *
     * @param ikm 输入密钥材料
     * @param info 上下文信息标签
     * @param length 需要输出的字节数
     */
    private fun hkdfExpand(ikm: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ikm, "HmacSHA256"))

        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val hashLen = 32  // SHA-256 output length

        val n = (length + hashLen - 1) / hashLen
        require(n <= 255) { "HKDF-Expand 请求长度超过最大值" }

        val okm = ByteArray(n * hashLen)
        var t = ByteArray(0)

        for (i in 1..n) {
            mac.update(t)
            mac.update(infoBytes)
            mac.update(i.toByte())
            t = mac.doFinal()
            System.arraycopy(t, 0, okm, (i - 1) * hashLen, hashLen)
        }

        return okm.copyOf(length)
    }
}
