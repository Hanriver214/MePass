package com.mepass.app.crypto

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 答案规范化器
 *
 * 将用户输入的答案规范化为统一的内部表示，避免因大小写、空格、标点、
 * 全角/半角、日期格式等差异导致验证失败。
 *
 * 规范化流程：
 * 1. Unicode NFKC 规范化（全角→半角）
 * 2. 尝试日期识别（多种格式 → yyyyMMdd）
 * 3. 转小写
 * 4. 移除所有标点符号和符号字符
 * 5. 合并并移除所有空白
 */
object AnswerNormalizer {

    private val DATE_FORMATS = listOf(
        "yyyy-M-d", "yyyy/M/d", "yyyy.M.d",
        "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd",
        "d-M-yyyy", "d/M/yyyy", "d.M.yyyy",
        "dd-MM-yyyy", "dd/MM/yyyy", "dd.MM.yyyy",
        "M-d-yyyy", "M/d/yyyy", "M.d.yyyy",
        "MM-dd-yyyy", "MM/dd/yyyy", "MM.dd.yyyy"
    )

    fun normalize(raw: String): String {
        // 1. NFKC 规范化
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        // 2. 尝试日期规范化
        s = tryNormalizeDate(s)
        // 3. 转小写
        s = s.lowercase()
        // 4. 移除标点和符号
        s = s.replace(Regex("[\\p{Punct}\\p{S}]"), "")
        // 5. 移除所有空白
        s = s.replace(Regex("\\s+"), "")
        return s
    }

    /** 尝试将日期字符串统一为 yyyyMMdd 格式 */
    private fun tryNormalizeDate(s: String): String {
        val trimmed = s.trim()
        for (pattern in DATE_FORMATS) {
            try {
                val date = LocalDate.parse(
                    trimmed,
                    DateTimeFormatter.ofPattern(pattern)
                )
                return date.format(DateTimeFormatter.BASIC_ISO_DATE)
            } catch (_: Exception) {
                // 继续尝试下一个格式
            }
        }
        return s
    }
}
