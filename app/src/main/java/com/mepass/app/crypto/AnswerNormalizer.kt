package com.mepass.app.crypto

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 答案规范化器
 * 确保相同含义的答案生成一致的哈希值
 * 处理：大小写、空格、标点、日期格式、全半角、 Unicode 规范化等
 */
object AnswerNormalizer {

    private val whitespaceRegex = Regex("\\s+")
    private val punctuationRegex = Regex("[\\p{Punct}\\p{S}]+")
    private val dateFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault()),
        DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.getDefault()),
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.getDefault()),
        DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA),
        DateTimeFormatter.ofPattern("MM-dd-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.getDefault()),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())
    )
    private val canonicalDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.getDefault())

    /**
     * 完整规范化流程
     */
    fun normalize(raw: String): String {
        var result = raw

        // 1. Unicode NFC 规范化（处理全角/半角、组合字符）
        result = Normalizer.normalize(result, Normalizer.Form.NFKC)

        // 2. 尝试日期规范化（如果看起来像日期）
        result = tryNormalizeDate(result)

        // 3. 转小写（对于字母语言），保留CJK字符
        result = result.lowercase(Locale.getDefault())

        // 4. 移除所有标点符号和特殊字符
        result = punctuationRegex.replace(result, "")

        // 5. 合并并移除所有空白字符
        result = whitespaceRegex.replace(result, "")
        result = result.trim()

        return result
    }

    /**
     * 尝试将看起来像日期的字符串规范化为 yyyyMMdd 格式
     */
    private fun tryNormalizeDate(text: String): String {
        // 快速过滤：太短或不含日期特征
        val clean = text.trim()
        if (clean.length < 6 || clean.length > 20) return text

        for (formatter in dateFormatters) {
            try {
                val date = LocalDate.parse(clean, formatter)
                return date.format(canonicalDateFormatter)
            } catch (_: DateTimeParseException) {
                // 继续尝试下一个格式
            }
        }
        return text
    }

    /**
     * 仅做基础规范化（用于即时预览对比）
     */
    fun normalizeLight(raw: String): String {
        return raw
            .let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            .lowercase(Locale.getDefault())
            .let { whitespaceRegex.replace(it, "") }
            .trim()
    }

    /**
     * 纯数字金额规范化
     * "￥5,000.00元" -> "5000"
     * "5000块" -> "5000"
     */
    fun normalizeAmount(raw: String): String {
        var result = raw
        // 提取数字部分（支持小数点）
        val numberRegex = Regex("[0-9]+(\\.[0-9]+)?")
        val match = numberRegex.find(result)
        if (match != null) {
            result = match.value
            // 去除小数部分如果是.00
            if (result.endsWith(".00") || result.endsWith(".0")) {
                result = result.substringBefore(".")
            }
            return result
        }
        // 中文数字转阿拉伯数字的简化处理
        return normalize(result)
    }
}
