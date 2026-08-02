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
}
