package com.mepass.app

import com.mepass.app.crypto.AnswerNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerNormalizerTest {

    @Test
    fun `case insensitive normalization`() {
        val a1 = AnswerNormalizer.normalize("Hello World")
        val a2 = AnswerNormalizer.normalize("HELLO world")
        assertEquals(a1, a2)
        assertEquals("helloworld", a1)
    }

    @Test
    fun `whitespace and punctuation removal`() {
        val a1 = AnswerNormalizer.normalize("  It's a beautiful-day!  ")
        val a2 = AnswerNormalizer.normalize("Its a beautiful day")
        assertEquals(a1, a2)
    }

    @Test
    fun `fullwidth to halfwidth normalization`() {
        val a1 = AnswerNormalizer.normalize("Ｈｅｌｌｏ　　Ｗｏｒｌｄ")
        val a2 = AnswerNormalizer.normalize("hello world")
        assertEquals(a1, a2)
    }

    @Test
    fun `date format normalization YYYY variants`() {
        val dates = listOf(
            "2024-01-15",
            "2024/01/15",
            "2024.01.15",
            "20240115",
            "2024年01月15日"
        )
        val normalized = dates.map { AnswerNormalizer.normalize(it) }
        normalized.forEach { assertEquals("20240115", it) }
    }

    @Test
    fun `amount normalization extracts digits`() {
        val result = AnswerNormalizer.normalizeAmount("￥5,000.00元")
        assertEquals("5000", result)
    }

    @Test
    fun `CJK character preservation`() {
        val a1 = AnswerNormalizer.normalize("暗恋的人名字叫张三")
        assertTrue(a1.contains("张三"))
    }

    @Test
    fun `different answers produce different results`() {
        val a1 = AnswerNormalizer.normalize("AnswerOne")
        val a2 = AnswerNormalizer.normalize("AnswerTwo")
        assertNotEquals(a1, a2)
    }
}
