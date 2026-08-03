package com.mepass.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * 确定性 Passphrase 生成器（版本化）
 *
 * 从主密钥（masterSecret）派生出 14~16 位的强密码，包含大小写字母、数字、符号。
 * 相同的主密钥 + 相同版本号必定产生相同的密码输出（确定性）。
 *
 * 版本说明：
 * - version=1（v2.2.1 及以前）：使用自定义 SeededRandom（SHA-256 计数器模式）
 *   仅在恢复旧版本模板时调用，保证密码可重现
 * - version=2（v2.2.2 及以后）：使用 HKDF-Expand（RFC 5869 风格）标准密钥派生
 *   消除自研密码学的安全风险，用于新创建的 v2 模板
 */
object PassphraseGenerator {

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{}<>?"
    private const val MIN_PER_CLASS = 3

    // 版本 2（HKDF）所需最大字节数：16 字符 + 长度选择 + 洗牌
    private const val MAX_BYTES_NEEDED_V2 = 16 * 4 + 1 + 64

    /**
     * 从主密钥派生密码（版本化）
     * @param masterSecret 32 字节主密钥
     * @param version 模板版本号，1 = 旧算法，2+ = 新算法（HKDF）
     * @return 14~16 位密码
     */
    fun generate(masterSecret: ByteArray, version: Int): String {
        return if (version <= 1) {
            generateV1Legacy(masterSecret)
        } else {
            generateV2Hkdf(masterSecret)
        }
    }

    /**
     * 从主密钥派生密码（默认使用最新算法 v2）
     * 仅用于无需考虑模板版本的场景（例如单元测试）。
     */
    fun generate(masterSecret: ByteArray): String = generate(masterSecret, 2)

    // ============== V2：HKDF-Expand 标准派生（当前默认） ==============

    private fun generateV2Hkdf(masterSecret: ByteArray): String {
        // 使用 HKDF-Expand 派生所需的伪随机字节
        val randomBytes = hkdfExpand(
            masterSecret,
            "mepass_passphrase_generation",
            MAX_BYTES_NEEDED_V2
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
     */
    private fun hkdfExpand(ikm: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ikm, "HmacSHA256"))

        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val hashLen = 32

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

    // ============== V1：自定义 SeededRandom（旧版兼容，请勿在新模板使用） ==============

    private fun generateV1Legacy(masterSecret: ByteArray): String {
        // 1. 用 masterSecret 的哈希作为确定性种子
        val seed = MessageDigest.getInstance("SHA-256").digest(masterSecret)
        val random = SeededRandomLegacy(seed)

        // 2. 选择长度（14/15/16，14 占 50%）
        val length = when (random.nextInt(100)) {
            in 0..49 -> 14
            in 50..79 -> 15
            else -> 16
        }

        // 3. 分配各类字符配额
        val classes = listOf(LOWER, UPPER, DIGITS, SYMBOLS)
        val charCount = IntArray(4) { MIN_PER_CLASS }
        var remaining = length - MIN_PER_CLASS * 4
        while (remaining > 0) {
            charCount[random.nextInt(4)]++
            remaining--
        }

        // 4. 从各类中随机挑选字符
        val chars = CharArray(length)
        var pos = 0
        for (i in 0 until 4) {
            val cls = classes[i]
            repeat(charCount[i]) {
                chars[pos++] = cls[random.nextInt(cls.length)]
            }
        }

        // 5. Fisher-Yates 洗牌
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }

        return String(chars)
    }

    /**
     * 旧版（v1）自定义确定性伪随机数生成器
     * 使用 SHA-256 作为 PRF，逐字节派生。
     * 仅用于兼容 v2.2.1 及更早模板，保证密码可重现。
     * 请勿在新版模板中使用。
     */
    private class SeededRandomLegacy(seed: ByteArray) {
        private val baseSeed = seed
        private var counter = 0
        private var buffer = ByteArray(0)
        private var bufferPos = 0

        fun nextInt(bound: Int): Int {
            require(bound > 0) { "bound 必须为正数" }
            // 简单的拒绝采样
            while (true) {
                val byte = nextByte().toInt() and 0xFF
                if (byte < 256 - (256 % bound)) {
                    return byte % bound
                }
            }
        }

        fun nextByte(): Byte {
            if (bufferPos >= buffer.size) {
                counter++
                val input = baseSeed + intToBytes(counter)
                buffer = MessageDigest.getInstance("SHA-256").digest(input)
                bufferPos = 0
            }
            return buffer[bufferPos++]
        }

        private fun intToBytes(value: Int): ByteArray =
            byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
    }
}
