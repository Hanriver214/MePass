package com.mepass.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EncryptedExport 加密导出/解密导入测试。
 */
class EncryptedExportTest {

    private val sampleJson = """{"version":1,"name":"测试","questions":[],"thresholdConfig":{"totalQuestions":1,"threshold":1},"integrityHash":"abc","verificationHashes":{},"shamirShares":{}}"""

    @Test
    fun `加密往返还原明文`() {
        val envelope = EncryptedExport.encrypt(sampleJson, "S3cret!")
        assertTrue(EncryptedExport.isEncrypted(envelope))
        assertEquals(sampleJson, EncryptedExport.decrypt(envelope, "S3cret!").getOrThrow())
    }

    @Test
    fun `明文 JSON 不是加密格式`() {
        assertFalse(EncryptedExport.isEncrypted(sampleJson))
        assertFalse(EncryptedExport.isEncrypted(""))
    }

    @Test
    fun `错误口令解密失败`() {
        val envelope = EncryptedExport.encrypt(sampleJson, "correct")
        val result = EncryptedExport.decrypt(envelope, "wrong")
        assertTrue(result.isFailure)
    }

    @Test
    fun `篡改 KDF 参数被拒绝`() {
        val envelope = EncryptedExport.encrypt(sampleJson, "pwd")
        val tampered = envelope.replace("\"kdfIterations\": 3", "\"kdfIterations\": 1")
        assertTrue(EncryptedExport.decrypt(tampered, "pwd").isFailure)
    }

    @Test
    fun `不同口令产生不同密文`() {
        val e1 = EncryptedExport.encrypt(sampleJson, "A")
        val e2 = EncryptedExport.encrypt(sampleJson, "B")
        assertFalse(e1 == e2)
        assertEquals(sampleJson, EncryptedExport.decrypt(e1, "A").getOrThrow())
        assertEquals(sampleJson, EncryptedExport.decrypt(e2, "B").getOrThrow())
    }
}
