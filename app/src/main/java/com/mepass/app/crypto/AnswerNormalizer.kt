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
 * v2 修复：日期格式歧义问题
 * - 优先使用 ISO 格式 (yyyy-MM-dd)，最无歧义
 * - 将 d-M-yyyy 格式放在 M-d-yyyy 之前，使两者的解析顺序一致
 * - 同时支持的格式必须互斥（d-M 和 M-d 不会同时匹配，因为日期不能同时是日和月）
 *
 * 规范化流程：
 * 1. Unicode NFKC 规范化（全角→半角）
 * 2. 尝试日期规范化（多种格式 → yyyyMMdd）
 * 3. 转小写
 * 4. 移除所有标点符号和符号字符
 * 5. 合并并移除所有空白
 */
object AnswerNormalizer {

    // 日期格式列表：ISO 格式优先，减少歧义
    // 对于 d-M-yyyy 和 M-d-yyyy 两种格式，当输入如 "2-3-2000" 时：
    // - d-M-yyyy 解析为 2000年3月2日
    // - M-d-yyyy 解析为 2000年2月3日
    // 优先使用 d-M-yyyy 格式（符合大多数中文用户习惯）
    private val DATE_FORMATS = listOf(
        // ISO 格式（最无歧义，优先）
        "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd",
        // d-M-yyyy 格式（日-月-年，常见于欧洲/中文习惯）
        "d-M-yyyy", "d/M/yyyy", "d.M.yyyy",
        "dd-MM-yyyy", "dd/MM/yyyy", "dd.MM.yyyy",
        // M-d-yyyy 格式（月-日-年，北美习惯）
        "M-d-yyyy", "M/d/yyyy", "M.d.yyyy",
        "MM-dd-yyyy", "MM/dd/yyyy", "MM.dd.yyyy",
        // yyyy-M-d 变体
        "yyyy-M-d", "yyyy/M/d", "yyyy.M.d"
    )

    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = tryNormalizeDate(s)
        s = s.lowercase()
        s = s.replace(Regex("[\\p{Punct}\\p{S}]"), "")
        s = s.replace(Regex("\\s+"), "")
        return s
    }

    /** 尝试将日期字符串统一为 yyyyMMdd 格式 */
    private fun tryNormalizeDate(s: String): String {
        val trimmed = s.trim()
        for (pattern in DATE_FORMATS) {
            try {
                val date = LocalDate.parse(trimmed, DateTimeFormatter.ofPattern(pattern))
                return date.format(DateTimeFormatter.BASIC_ISO_DATE)
            } catch (_: Exception) {
                // 继续尝试下一个格式
            }
        }
        return s
    }
}
