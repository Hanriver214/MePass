package com.mepass.app

import com.mepass.app.model.PresetQuestions
import com.mepass.app.model.Question
import com.mepass.app.model.RecoveryResult
import com.mepass.app.model.Template
import com.mepass.app.template.IntegrityManager
import com.mepass.app.template.TemplateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateIntegrationTest {

    private fun makeTestQA(threshold: Int = 3): Pair<List<Pair<Question, String>>, Int> {
        val presets = PresetQuestions.presetQuestions.take(5)
        val answers = listOf("Alice", "三体", "5000", "200", "MyP@ss123")
        val pairs = presets.mapIndexed { i, q -> q to answers[i] }
        return Pair(pairs, threshold)
    }

    @Test
    fun `template creation and integrity verification`() {
        val (qa, thr) = makeTestQA()
        val template = TemplateManager.createTemplate(
            templateName = "UnitTest Template",
            qaPairs = qa,
            threshold = thr,
            enableAes = false
        )
        assertTrue(IntegrityManager.verifyIntegrity(template))
        assertEquals(qa.size, template.questions.size)
        assertEquals(thr, template.thresholdConfig.threshold)
    }

    @Test
    fun `template export import round trip preserves integrity`() {
        val (qa, thr) = makeTestQA()
        val original = TemplateManager.createTemplate("RoundTrip", qa, thr)
        val json = TemplateManager.exportTemplate(original)
        val imported = TemplateManager.importTemplate(json).getOrThrow()
        assertTrue(IntegrityManager.verifyIntegrity(imported))
        assertEquals(original.name, imported.name)
        assertEquals(original.integrityHash, imported.integrityHash)
        assertEquals(original.verificationHashes.size, imported.verificationHashes.size)
    }

    @Test
    fun `tampered template fails integrity`() {
        val (qa, thr) = makeTestQA()
        val original = TemplateManager.createTemplate("Tamper", qa, thr)
        val json = TemplateManager.exportTemplate(original)
        // 篡改问题文本
        val tamperedJson = json.replaceFirst(
            PresetQuestions.presetQuestions[0].text,
            "篡改后的问题"
        )
        val result = TemplateManager.importTemplate(tamperedJson)
        assertTrue(result.isFailure)
    }

    @Test
    fun `recovery succeeds with exactly threshold correct answers`() {
        val (qa, thr) = makeTestQA(threshold = 3)
        val template = TemplateManager.createTemplate("Recovery 3/5", qa, thr)

        // 给出 3 个正确答案
        val userAnswers = mapOf(
            qa[0].first.id to qa[0].second,
            qa[1].first.id to qa[1].second,
            qa[4].first.id to qa[4].second
        )

        val result = TemplateManager.recoverPassphrase(template, userAnswers)
        assertTrue(result is RecoveryResult.Success)
        val success = result as RecoveryResult.Success
        assertEquals(3, success.correctCount)
        assertTrue(success.passphrase.isNotBlank())
        assertTrue(success.passphrase.count { it == '-' } >= 10) // 12个词有11个分隔符
    }

    @Test
    fun `recovery fails with fewer than threshold correct`() {
        val (qa, thr) = makeTestQA(threshold = 4)
        val template = TemplateManager.createTemplate("Recovery 4/5", qa, thr)

        // 给出 2 个正确 3 个错误
        val userAnswers = mapOf(
            qa[0].first.id to qa[0].second,      // 正确
            qa[1].first.id to "错误答案",         // 错误
            qa[2].first.id to qa[2].second,      // 正确
            qa[3].first.id to "WRONG"             // 错误
        )

        val result = TemplateManager.recoverPassphrase(template, userAnswers)
        assertTrue(result is RecoveryResult.Failure)
    }

    @Test
    fun `same template + same answers always produce same passphrase (deterministic)`() {
        val (qa, thr) = makeTestQA(3)
        val template = TemplateManager.createTemplate("Det", qa, thr)
        val userAnswers = qa.take(4).associate { it.first.id to it.second }

        val r1 = TemplateManager.recoverPassphrase(template, userAnswers)
        val r2 = TemplateManager.recoverPassphrase(template, userAnswers)
        assertTrue(r1 is RecoveryResult.Success && r2 is RecoveryResult.Success)
        assertEquals((r1 as RecoveryResult.Success).passphrase, (r2 as RecoveryResult.Success).passphrase)
    }

    @Test
    fun `answer normalization applied during recovery`() {
        val (qa, thr) = makeTestQA(3)
        val template = TemplateManager.createTemplate("Norm", qa, thr)

        // 用户输入的答案格式不同，但规范化后相同
        val originalAnswer = qa[0].second
        val messyAnswer = "  $originalAnswer  " // 加空格
        val capitalized = originalAnswer // 中文不受大小写影响

        val userAnswers1 = mapOf(
            qa[0].first.id to messyAnswer,
            qa[1].first.id to qa[1].second,
            qa[2].first.id to qa[2].second
        )
        val r1 = TemplateManager.recoverPassphrase(template, userAnswers1)
        assertTrue(r1 is RecoveryResult.Success)
    }

    @Test
    fun `different templates produce different passphrases`() {
        val (qa1, thr) = makeTestQA(3)
        val qa2 = qa1.map { (q, a) -> q to (a + "_different") }
        val t1 = TemplateManager.createTemplate("T1", qa1, thr)
        val t2 = TemplateManager.createTemplate("T2", qa2, thr)

        val answers1 = qa1.take(3).associate { it.first.id to it.second }
        val answers2 = qa2.take(3).associate { it.first.id to it.second }

        val r1 = TemplateManager.recoverPassphrase(t1, answers1)
        val r2 = TemplateManager.recoverPassphrase(t2, answers2)

        assertTrue(r1 is RecoveryResult.Success && r2 is RecoveryResult.Success)
        assertNotEquals(
            (r1 as RecoveryResult.Success).passphrase,
            (r2 as RecoveryResult.Success).passphrase
        )
    }
}
