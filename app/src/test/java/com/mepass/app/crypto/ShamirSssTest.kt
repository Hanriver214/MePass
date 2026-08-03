package com.mepass.app.crypto

import com.mepass.app.model.Question
import com.mepass.app.model.RecoveryResult
import com.mepass.app.template.TemplateManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ShamirSss 回归测试。
 *
 * 历史 bug：EXP/LOG 表曾用 2 作为生成元，但 2 在 GF(2^8)/0x11B 中的阶是 51
 * （非本原元），导致 combine 还原出错误且随分片子集变化的秘密——表现为
 * 「同一模板用不同正确答案子集恢复出不同密码」。改用 3（本原元）后修复。
 */
class ShamirSssTest {

    @Test
    fun `split then combine 还原原始 secret`() {
        val secret = ByteArray(32) { i -> (i * 7 + 3).toByte() }
        val shares = ShamirSss.split(secret, n = 12, k = 7)
        val recovered = ShamirSss.combine(shares.take(7))
        assertArrayEquals(secret, recovered)
    }

    @Test
    fun `任意 k 个分片 combine 结果一致`() {
        val secret = ByteArray(32) { i -> (i * 13 + 5).toByte() }
        val shares = ShamirSss.split(secret, n = 12, k = 7)
        val r1 = ShamirSss.combine(shares.subList(0, 7))
        val r2 = ShamirSss.combine(shares.subList(5, 12))
        val r3 = ShamirSss.combine(listOf(shares[0], shares[2], shares[4], shares[6], shares[8], shares[10], shares[11]))
        assertArrayEquals(secret, r1)
        assertArrayEquals(secret, r2)
        assertArrayEquals(secret, r3)
    }

    @Test
    fun `2-of-2 单字节`() {
        val secret = byteArrayOf(0x05)
        val shares = ShamirSss.split(secret, n = 2, k = 2)
        assertArrayEquals(secret, ShamirSss.combine(shares))
    }

    @Test
    fun `3-of-5 多字节`() {
        val secret = byteArrayOf(0x7F, 0x80.toByte(), 0xFF.toByte())
        val shares = ShamirSss.split(secret, n = 5, k = 3)
        assertArrayEquals(secret, ShamirSss.combine(listOf(shares[0], shares[1], shares[2])))
        assertArrayEquals(secret, ShamirSss.combine(listOf(shares[2], shares[3], shares[4])))
        assertArrayEquals(secret, ShamirSss.combine(listOf(shares[0], shares[2], shares[4])))
    }

    // ---- 端到端：同一模板不同答案子集应恢复同一密码 ----

    private val questions = (1..12).map { Question("q$it", "问题$it") }
    private val answers = questions.associate { it.id to "答案${it.id}" }

    private fun buildTemplate(threshold: Int) = TemplateManager.createTemplate(
        name = "回归测试模板",
        questionAnswers = questions.map { it to (answers[it.id] ?: "") },
        threshold = threshold
    )

    private fun passOf(r: RecoveryResult) = (r as RecoveryResult.Success).passphrase

    @Test
    fun `threshold=10 不同 10 答案子集恢复同一密码`() {
        val template = buildTemplate(threshold = 10)
        val a = TemplateManager.recoverPassphrase(template,
            answers.filterKeys { it in setOf("q1","q2","q3","q4","q5","q6","q7","q8","q9","q10") })
        val b = TemplateManager.recoverPassphrase(template,
            answers.filterKeys { it in setOf("q3","q4","q5","q6","q7","q8","q9","q10","q11","q12") })
        assertTrue(a is RecoveryResult.Success)
        assertTrue(b is RecoveryResult.Success)
        assertEquals(passOf(a), passOf(b))
    }

    @Test
    fun `答案顺序不同恢复同一密码`() {
        val template = buildTemplate(threshold = 7)
        val ordered = questions.take(10).associate { it.id to answers[it.id]!! }
        val reversed = questions.reversed().take(10).associate { it.id to answers[it.id]!! }
        assertEquals(
            passOf(TemplateManager.recoverPassphrase(template, ordered)),
            passOf(TemplateManager.recoverPassphrase(template, reversed))
        )
    }

    @Test
    fun `加密导出往返后恢复同一密码`() {
        val template = buildTemplate(threshold = 7)
        val originalPass = passOf(TemplateManager.recoverPassphrase(template,
            answers.filterKeys { it in setOf("q1","q2","q3","q4","q5","q6","q7") }))
        val envelope = TemplateManager.exportTemplateEncrypted(template, "pwd123")
        val imported = TemplateManager.importTemplate(envelope, "pwd123").getOrThrow()
        val roundTripPass = passOf(TemplateManager.recoverPassphrase(imported,
            answers.filterKeys { it in setOf("q2","q4","q6","q8","q10","q11","q12") }))
        assertEquals(originalPass, roundTripPass)
    }
}
