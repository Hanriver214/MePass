package com.mepass.app.model

import kotlinx.serialization.Serializable

/**
 * MePass 数据模型层（极简版）
 *
 * 核心概念：
 * - [Question]：用户预设的隐私问题
 * - [Template]：包含 N 个问题及其加密分片，可通过 k 个正确答案恢复出主密钥
 * - [RecoveryResult]：恢复操作的结果
 *
 * 设计原则：
 * - 所有敏感数据（答案）经 Argon2id 哈希后存储，明文从不落盘
 * - Shamir 分片经 AES-GCM 加密后存储，密钥由对应答案派生
 * - 完整性哈希防止模板被篡改
 */

/** 隐私问题 */
@Serializable
data class Question(
    val id: String,
    val text: String,
    val isCustom: Boolean = false,
    val hint: String? = null
)

/** 门限配置：(k, N) 表示 N 个问题中需要 k 个正确答案才能恢复 */
@Serializable
data class ThresholdConfig(
    val totalQuestions: Int,
    val threshold: Int
) {
    init {
        require(threshold in 1..totalQuestions) {
            "threshold 必须在 1..totalQuestions 范围内"
        }
    }
}

/**
 * 加密的 Shamir 分片
 *
 * @param shareIndex 分片索引（1-based，用于 Shamir combine）
 * @param encryptedData 加密后的分片数据（Base64）
 * @param nonce AES-GCM nonce（Base64）
 * @param salt Argon2id 盐值（Base64，用于从答案派生 AES 密钥）
 */
@Serializable
data class ShamirEncryptedShare(
    val shareIndex: Int,
    val encryptedData: String,
    val nonce: String,
    val salt: String
)

/**
 * 模板：MePass 的核心数据结构
 *
 * 包含 N 个问题和对应的加密分片，可通过 k 个正确答案恢复出主密钥。
 * 主密钥经确定性的 Passphrase 派生算法生成 14~16 位强密码。
 *
 * @param version 模板版本号
 * @param name 模板名称
 * @param createdAt 创建时间戳
 * @param questions 问题列表
 * @param thresholdConfig 门限配置
 * @param integrityHash 完整性哈希（防止篡改）
 * @param verificationHashes 每题答案的 Argon2id 哈希（用于本地验证）
 * @param shamirShares 每题对应的加密 Shamir 分片
 */
@Serializable
data class Template(
    val version: Int = CURRENT_VERSION,
    val name: String,
    val createdAt: Long,
    val questions: List<Question>,
    val thresholdConfig: ThresholdConfig,
    val integrityHash: String,
    val verificationHashes: Map<String, String>,
    val shamirShares: Map<String, ShamirEncryptedShare>
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** 恢复结果 */
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
