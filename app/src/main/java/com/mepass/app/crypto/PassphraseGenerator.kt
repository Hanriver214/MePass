package com.mepass.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 确定性 Passphrase 生成器
 *
 * 从主密钥（masterSecret）派生出 14~16 位的强密码，包含大小写字母、数字、符号。
 * 相同的主密钥输入必定产生相同的密码输出（确定性）。
 *
 * 算法：
 * 1. 用 SHA-256(masterSecret) 作为确定性随机源种子
 * 2. 长度在 14/15/16 中按种子选择
 * 3. 字符池分 4 类（小写、大写、数字、符号），每类至少 3 个
 * 4. Fisher-Yates 洗牌
 * 5. 输出字符串
 */
object PassphraseGenerator {

    // 去掉易混淆字符 I/O/l/0/1
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{}<>?"
    private const val MIN_PER_CLASS = 3

    /**
     * 从主密钥派生密码
     * @param masterSecret 32 字节主密钥
     * @return 14~16 位密码
     */
    fun generate(masterSecret: ByteArray): String {
        // 1. 用 masterSecret 的哈希作为确定性种子
        val seed = MessageDigest.getInstance("SHA-256").digest(masterSecret)
        val random = SeededRandom(seed)

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
     * 基于种子的确定性伪随机数生成器
     * 使用 SHA-256 作为 PRF，逐字节派生
     */
    private class SeededRandom(seed: ByteArray) {
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
