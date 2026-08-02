package com.mepass.app

import com.mepass.app.crypto.AesManager
import com.mepass.app.crypto.Argon2Manager
import com.mepass.app.crypto.ShamirSecretSharing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CryptoModulesTest {

    @Test
    fun `argon2 answer hash verification round trip`() {
        val answer = "my_secret_answer_123"
        val hash = Argon2Manager.hashAnswer(answer)
        assertTrue(Argon2Manager.verifyAnswer(hash, answer))
    }

    @Test
    fun `argon2 wrong answer verification fails`() {
        val answer = "correct_answer"
        val hash = Argon2Manager.hashAnswer(answer)
        assertTrue(!Argon2Manager.verifyAnswer(hash, "wrong_answer"))
    }

    @Test
    fun `aes encrypt decrypt round trip`() {
        val key = AesManager.generateKey()
        val plaintext = "Hello, MePass! This is a test message."
        val encrypted = AesManager.encryptToBase64(plaintext, key)
        val decrypted = AesManager.decryptFromBase64(encrypted, key)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `aes wrong key fails`() {
        val key1 = AesManager.generateKey()
        val key2 = AesManager.generateKey()
        val plaintext = "Secret data"
        val encrypted = AesManager.encryptToBase64(plaintext, key1)
        var thrown = false
        try {
            AesManager.decryptFromBase64(encrypted, key2)
        } catch (_: Exception) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `shamir secret sharing k=3 n=5 reconstruct with any 3`() {
        val secret = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C)
        val n = 5
        val k = 3
        val shares = ShamirSecretSharing.split(secret, n, k)
        assertEquals(n, shares.size)

        // 尝试用 1,2,4 号分片
        val subset1 = listOf(shares[0], shares[1], shares[3])
        val recovered1 = ShamirSecretSharing.combine(subset1, k)
        assertArrayEquals(secret, recovered1)

        // 尝试用 2,3,5 号分片
        val subset2 = listOf(shares[1], shares[2], shares[4])
        val recovered2 = ShamirSecretSharing.combine(subset2, k)
        assertArrayEquals(secret, recovered2)
    }

    @Test
    fun `shamir k=1 n=5 all shares equal secret`() {
        val secret = byteArrayOf(0xAA, 0xBB.toByte(), 0xCC.toByte())
        val shares = ShamirSecretSharing.split(secret, n = 5, k = 1)
        for (share in shares) {
            val recovered = ShamirSecretSharing.combine(listOf(share), 1)
            assertArrayEquals(secret, recovered)
        }
    }

    @Test
    fun `shamir fails with fewer than k shares`() {
        val secret = Random.Default.nextBytes(32)
        val shares = ShamirSecretSharing.split(secret, n = 5, k = 4)
        // 只用 3 个分片，应该不能正确恢复
        val bad = ShamirSecretSharing.combine(shares.take(3), 3) // 注意这里k传3，实际门限是4
        // 用错误的k恢复会得到错误的秘密（因为Shamir.combine只按给定k插值，这里通过combine参数模拟越权尝试失败场景）
        val correct = ShamirSecretSharing.combine(shares.take(4), 4)
        assertArrayEquals(secret, correct)
        // 错误数量下（如果k传小）结果应该不同
        val wrongK = ShamirSecretSharing.combine(shares.take(3), 3)
        assertTrue(!wrongK.contentEquals(secret))
    }

    // ==================== Passphrase 生成策略测试 ====================

    private val UPPERCASE_POOL = "ABCDEFGHJKLMNPQRSTUVWXYZ".toSet()
    private val LOWERCASE_POOL = "abcdefghijkmnopqrstuvwxyz".toSet()
    private val DIGITS_POOL = "23456789".toSet()
    private val SYMBOLS_POOL = "!@#\$%^&*()-_=+[]{};:,./<>".toSet()
    private val ALL_ALLOWED = UPPERCASE_POOL + LOWERCASE_POOL + DIGITS_POOL + SYMBOLS_POOL

    private fun Char.category(): Int = when {
        this in UPPERCASE_POOL -> 0
        this in LOWERCASE_POOL -> 1
        this in DIGITS_POOL    -> 2
        this in SYMBOLS_POOL   -> 3
        else                   -> -1
    }

    @Test
    fun `passphrase length between 14 and 16`() {
        val seenLengths = mutableSetOf<Int>()
        repeat(200) { i ->
            val seed = byteArrayOf(i.toByte(), (i shr 8).toByte(), 0x11, 0x22, 0x33)
            val pwd = Argon2Manager.derivePassphrase(seed)
            assertTrue("长度 ${pwd.length} 不在 [14,16] 范围",
                pwd.length in 14..16)
            seenLengths.add(pwd.length)
        }
        // 在 200 次随机种子中，14/15/16 三种长度应该都能出现（概率验证）
        assertTrue("应该至少见到两种长度，但只见到 $seenLengths",
            seenLengths.size >= 2)
    }

    @Test
    fun `passphrase contains all 4 categories`() {
        repeat(100) { i ->
            val seed = byteArrayOf(i.toByte(), (i * 3).toByte(), (i * 7).toByte())
            val pwd = Argon2Manager.derivePassphrase(seed)
            val cats = pwd.map { it.category() }.toSet()
            assertTrue("种子 $i 生成的密码 '$pwd' 缺少类别，实际类别: $cats",
                cats == setOf(0, 1, 2, 3))
        }
    }

    @Test
    fun `passphrase each category at least 3 times`() {
        repeat(100) { i ->
            val seed = byteArrayOf(i.toByte(), (i * 5).toByte(), (i * 11).toByte())
            val pwd = Argon2Manager.derivePassphrase(seed)
            val counts = IntArray(4)
            pwd.forEach { counts[it.category()]++ }
            assertTrue("种子 $i 类别计数不足: 大写=${counts[0]} 小写=${counts[1]} 数字=${counts[2]} 符号=${counts[3]}, 密码=$pwd",
                counts.all { it >= 3 })
        }
    }

    @Test
    fun `passphrase uses only readable characters (no ambiguous chars)`() {
        repeat(100) { i ->
            val seed = byteArrayOf(i.toByte(), (i * 2).toByte(), 0x55)
            val pwd = Argon2Manager.derivePassphrase(seed)
            val bad = pwd.filter { it !in ALL_ALLOWED }
            assertTrue("种子 $i 包含禁用字符 '$bad'，完整密码='$pwd'",
                bad.isEmpty())
            // 明确断言不包含易混淆字符
            assertFalse("不应包含大写 I 或 O", pwd.any { it == 'I' || it == 'O' })
            assertFalse("不应包含小写 l", pwd.any { it == 'l' })
            assertFalse("不应包含数字 0 或 1", pwd.any { it == '0' || it == '1' })
        }
    }

    @Test
    fun `passphrase no adjacent same category for more than 2`() {
        // 确保同类字符不连续出现超过2次（提高可读性）
        repeat(100) { i ->
            val seed = byteArrayOf(i.toByte(), 0x33, (i * 13).toByte())
            val pwd = Argon2Manager.derivePassphrase(seed)
            var runLen = 1
            var maxRun = 1
            for (j in 1 until pwd.length) {
                if (pwd[j].category() == pwd[j-1].category()) {
                    runLen++
                    if (runLen > maxRun) maxRun = runLen
                } else {
                    runLen = 1
                }
            }
            assertTrue("种子 $i 有连续 $maxRun 个同类字符: $pwd", maxRun <= 2)
        }
    }

    @Test
    fun `passphrase deterministic with same input`() {
        // 相同输入必须产生相同输出（保证恢复一致性）
        repeat(50) { i ->
            val seed = byteArrayOf(i.toByte(), (i * 17).toByte(), 0x77, (i * 23).toByte())
            val a = Argon2Manager.derivePassphrase(seed)
            val b = Argon2Manager.derivePassphrase(seed.copyOf())
            assertEquals("相同输入应产生相同输出（种子=$i）", a, b)
        }
    }

    @Test
    fun `passphrase diverse with different input`() {
        // 不同输入应该几乎总能产生不同输出（确保熵扩散）
        val outputs = mutableSetOf<String>()
        repeat(100) { i ->
            val seed = ByteArray(32) { (i * 31 + it * 7).toByte() }
            outputs.add(Argon2Manager.derivePassphrase(seed))
        }
        // 碰撞概率几乎为零，100个不同输入应得到100个不同输出
        assertEquals("100个不同种子应产生100个不同密码，实际=${outputs.size}", 100, outputs.size)
    }
}
