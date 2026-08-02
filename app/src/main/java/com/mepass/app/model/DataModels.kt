package com.mepass.app.model

import kotlinx.serialization.Serializable

/**
 * 预设或自定义的隐私问题
 * 模板中只包含此字段，不含答案
 */
@Serializable
data class Question(
    val id: String,
    val text: String,
    val isCustom: Boolean = false,
    val hint: String? = null
) {
    companion object {
        fun generateId(): String = "q_${System.currentTimeMillis()}_${(0..9999).random()}"
    }
}

/**
 * 问题答案对 - 仅在本地内存或加密存储时使用
 * 注意：此对象绝不会出现在导出的模板中
 */
@Serializable
data class QuestionAnswer(
    val question: Question,
    /** 规范化后的答案哈希（Argon2id）
    val answerHash: String,
    /** 规范化后的答案明文（仅在生成时临时保存，导出时清除） */
    val plainAnswer: String? = null
)

/**
 * 门限配置：导出时指定需要多少个正确答案才能恢复
 */
@Serializable
data class ThresholdConfig(
    /** 总问题数 */
    val totalQuestions: Int,
    /** 门限：需要正确回答的问题数量（<= totalQuestions） */
    val threshold: Int
) {
    init {
        require(threshold > 0) { "门限必须大于0" }
        require(threshold <= totalQuestions) { "门限不能超过总问题数" }
    }
}

/**
 * 导出的模板文件结构
 * 重要：模板中绝对不包含任何答案或答案哈希
 * 答案验证使用：每个问题ID对应的哈希存在加密存储区或通过本地验证流程
 */
@Serializable
data class Template(
    val version: Int = 1,
    val name: String,
    val createdAt: Long,
    val questions: List<Question>,
    val thresholdConfig: ThresholdConfig,
    /**
     * 完整性校验哈希：
     * SHA-256(version + name + sorted(questionIds).join() + threshold + totalQuestions)
     */
    val integrityHash: String,
    /**
     * 验证引用数据：
     * 对于每个问题，存储 H(answer_normalized) 的 Argon2id 哈希
     * 此数据用于验证用户输入的答案是否正确，但无法逆向还原答案
     */
    val verificationHashes: Map<String, String>,
    /**
     * 用于门限 Shamir 秘密共享的数据
     * 主秘密被分割为 totalQuestions 份，threshold 份可恢复
     * 每份使用对应问题的答案哈希加密存储
     */
    val shamirShares: Map<String, ShamirEncryptedShare>,
    /** 是否启用AES加密模板内容 */
    val aesEncrypted: Boolean = false,
    /** AES-GCM 加密参数（如果启用） */
    val aesParams: AesEncryptionParams? = null
)

/**
 * Shamir 秘密共享的加密分片
 * 每个分片使用对应问题的答案哈希作为密钥加密
 */
@Serializable
data class ShamirEncryptedShare(
    val shareIndex: Int,
    val encryptedData: String, // Base64编码的加密分片
    val nonce: String // Base64编码的nonce
)

/**
 * AES 加密参数
 */
@Serializable
data class AesEncryptionParams(
    val salt: String,   // Base64编码的盐
    val nonce: String,  // Base64编码的nonce
    val tagLength: Int = 128
)

/**
 * 恢复结果
 */
sealed class RecoveryResult {
    data class Success(
        val passphrase: String,
        val correctCount: Int,
        val totalAnswered: Int
    ) : RecoveryResult()

    data class Failure(
        val reason: String,
        val correctCount: Int,
        val threshold: Int
    ) : RecoveryResult()
}

/**
 * 模板导出格式版本
 */
object TemplateVersion {
    const val CURRENT = 1
}
