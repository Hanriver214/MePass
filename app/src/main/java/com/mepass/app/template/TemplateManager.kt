package com.mepass.app.template

import com.mepass.app.crypto.AesGcm
import com.mepass.app.crypto.AnswerNormalizer
import com.mepass.app.crypto.Argon2Kdf
import com.mepass.app.crypto.EncryptedExport
import com.mepass.app.crypto.PassphraseGenerator
import com.mepass.app.crypto.ShamirSss
import com.mepass.app.model.Question
import com.mepass.app.model.RecoveryResult
import com.mepass.app.model.ShamirEncryptedShare
import com.mepass.app.model.Template
import com.mepass.app.model.ThresholdConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 模板管理器：MePass 核心业务逻辑
 *
 * 职责：
 * 1. [createTemplate]：从问答对生成模板（含加密分片、完整性哈希）
 * 2. [exportTemplate] / [importTemplate]：序列化/反序列化 JSON
 * 3. [recoverPassphrase]：从 k 个正确答案恢复密码
 *
 * v2.2 更新：
 * - 双密钥派生（HKDF 风格）：验证哈希与加密密钥完全分离
 * - 完整性哈希覆盖所有模板字段
 * - 移除逐题即时验证功能
 * - 添加暴力破解速率限制
 */
object TemplateManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val secureRandom = SecureRandom()

    // 暴力破解防护配置
    private const val MAX_ATTEMPTS_PER_QUESTION = 5
    private const val LOCKOUT_DELAY_MS = 30_000L  // 30 秒锁定

    // 每题失败计数和锁定时间（内存中）
    private val failureCounts = mutableMapOf<String, Int>()
    private val lockoutTimes = mutableMapOf<String, Long>()

    /**
     * 创建模板
     *
     * @param name 模板名
     * @param questionAnswers 问答对列表
     * @param threshold 门限值（k）
     * @return 创建的模板
     */
    fun createTemplate(
        name: String,
        questionAnswers: List<Pair<Question, String>>,
        threshold: Int
    ): Template {
        require(questionAnswers.isNotEmpty()) { "至少需要 1 个问题" }
        require(threshold in 1..questionAnswers.size) { "门限值必须在 1..N 范围内" }

        val questions = questionAnswers.map { it.first }
        val thresholdConfig = ThresholdConfig(questionAnswers.size, threshold)

        // 1. 生成 32 字节主密钥
        val masterSecret = ByteArray(32)
        secureRandom.nextBytes(masterSecret)

        // 2. 分片主密钥
        val shares = if (questionAnswers.size == 1) {
            listOf(ShamirSss.Share(1, masterSecret))
        } else {
            ShamirSss.split(masterSecret, questionAnswers.size, threshold)
        }

        // 3. 对每题：使用双密钥派生分离验证哈希与加密密钥
        val verificationHashes = mutableMapOf<String, String>()
        val shamirShares = mutableMapOf<String, ShamirEncryptedShare>()

        for ((index, pair) in questionAnswers.withIndex()) {
            val (question, answer) = pair
            val normalized = AnswerNormalizer.normalize(answer)
            val salt = Argon2Kdf.generateSalt()

            // 双密钥派生：一次 Argon2id + 两次 HMAC
            val keys = Argon2Kdf.deriveKeys(normalized, salt)

            // 验证哈希 = SHA-256(verification_key)，与加密密钥完全分离
            verificationHashes[question.id] = Argon2Kdf.hashVerificationKey(keys.verificationKey)

            // 用加密密钥加密分片
            val key = keys.encryptionKey
            val share = shares[index]
            val encrypted = AesGcm.encrypt(share.data, key)

            shamirShares[question.id] = ShamirEncryptedShare(
                shareIndex = share.index,
                encryptedData = Base64.getEncoder().encodeToString(encrypted),
                nonce = Base64.getEncoder().encodeToString(encrypted.copyOfRange(0, 12)),
                salt = Base64.getEncoder().encodeToString(salt)
            )

            // 清零所有派生密钥
            keys.clear()
        }

        // 4. 计算完整性哈希（覆盖全部字段）
        // 重要：createdAt 必须只取一次，保证哈希计算与 Template 字段使用同一值
        val createdAt = System.currentTimeMillis()
        val integrityHash = IntegrityManager.computeIntegrityHash(
            version = Template.CURRENT_VERSION,
            name = name,
            questionIds = questions.map { it.id },
            thresholdConfig = thresholdConfig,
            verificationHashes = verificationHashes,
            shamirShares = shamirShares,
            createdAt = createdAt
        )

        // 5. 清零主密钥
        masterSecret.fill(0)

        return Template(
            name = name,
            createdAt = createdAt,
            questions = questions,
            thresholdConfig = thresholdConfig,
            integrityHash = integrityHash,
            verificationHashes = verificationHashes,
            shamirShares = shamirShares
        )
    }

    /** 导出模板为 JSON 字符串（明文） */
    fun exportTemplate(template: Template): String = json.encodeToString(template)

    /**
     * 导出模板为加密封装 JSON 字符串（口令保护）
     */
    fun exportTemplateEncrypted(template: Template, password: String): String {
        val plain = json.encodeToString(template)
        return EncryptedExport.encrypt(plain, password)
    }

    /** 检测 JSON 字符串是否为加密封装格式 */
    fun isEncryptedTemplate(jsonString: String): Boolean = EncryptedExport.isEncrypted(jsonString)

    /**
     * 从 JSON 字符串导入模板（自动识别明文 / 加密格式，含完整性校验）
     */
    fun importTemplate(jsonString: String, password: String? = null): Result<Template> = runCatching {
        val plainJson = if (EncryptedExport.isEncrypted(jsonString)) {
            require(!password.isNullOrBlank()) { "该模板已加密，请输入解密口令" }
            EncryptedExport.decrypt(jsonString, password).getOrThrow()
        } else {
            jsonString
        }
        importTemplatePlain(plainJson)
    }

    /** 从明文 JSON 字符串导入模板（包含完整性校验） */
    private fun importTemplatePlain(jsonString: String): Template {
        val template = json.decodeFromString<Template>(jsonString)
        require(template.version >= Template.MIN_SUPPORTED_VERSION) {
            "模板版本过低（最低支持: v${Template.MIN_SUPPORTED_VERSION}, 当前: v${template.version}）"
        }
        require(template.version <= Template.CURRENT_VERSION) {
            "模板版本过高（当前最高支持: v${Template.CURRENT_VERSION}, 模板版本: v${template.version}），请更新应用"
        }
        require(IntegrityManager.verifyIntegrity(template)) {
            "模板完整性校验失败：可能已被篡改"
        }
        return template
    }

    /**
     * 重置所有失败计数和锁定状态
     */
    fun resetRateLimitState() {
        failureCounts.clear()
        lockoutTimes.clear()
    }

    /**
     * 检查指定问题是否被锁定
     * @return true=已锁定 false=可尝试
     */
    fun isQuestionLocked(questionId: String): Boolean {
        val lockoutUntil = lockoutTimes[questionId] ?: 0L
        if (System.currentTimeMillis() < lockoutUntil) return true
        // 锁定时间已过，重置计数
        if (lockoutUntil > 0L) {
            failureCounts[questionId] = 0
            lockoutTimes.remove(questionId)
        }
        return false
    }

    /**
     * 恢复密码
     *
     * @param template 模板
     * @param answers 问题 ID 到答案的映射
     * @return [RecoveryResult]
     */
    fun recoverPassphrase(template: Template, answers: Map<String, String>): RecoveryResult {
        // 完整性校验
        if (!IntegrityManager.verifyIntegrity(template)) {
            return RecoveryResult.Failure("模板完整性校验失败", 0, template.thresholdConfig.threshold)
        }

        // 检查是否有问题被锁定
        for (questionId in answers.keys) {
            if (isQuestionLocked(questionId)) {
                val remainingMs = lockoutTimes[questionId]!! - System.currentTimeMillis()
                val remainingSec = (remainingMs / 1000) + 1
                return RecoveryResult.Failure(
                    "安全锁定：问题「${getQuestionText(template, questionId)}」尝试次数过多，请等待 ${remainingSec} 秒后再试",
                    0,
                    template.thresholdConfig.threshold
                )
            }
        }

        // 收集正确答案对应的分片
        val correctShares = mutableListOf<ShamirSss.Share>()
        var correctCount = 0
        var totalAnswered = 0
        var hasFailure = false

        for ((questionId, answer) in answers) {
            if (answer.isBlank()) continue
            totalAnswered++

            val normalized = AnswerNormalizer.normalize(answer)
            val share = template.shamirShares[questionId] ?: continue
            val salt = Base64.getDecoder().decode(share.salt)
            val storedHash = template.verificationHashes[questionId] ?: continue

            // 使用双密钥派生：一次 Argon2id 计算同时完成验证和密钥派生
            val keys = Argon2Kdf.deriveKeys(normalized, salt)

            // 验证答案
            val computedHash = Argon2Kdf.hashVerificationKey(keys.verificationKey)
            val isCorrect = constantTimeEquals(
                storedHash.toByteArray(Charsets.UTF_8),
                computedHash.toByteArray(Charsets.UTF_8)
            )

            if (!isCorrect) {
                keys.clear()
                // 记录失败
                val failures = (failureCounts[questionId] ?: 0) + 1
                failureCounts[questionId] = failures
                if (failures >= MAX_ATTEMPTS_PER_QUESTION) {
                    lockoutTimes[questionId] = System.currentTimeMillis() + LOCKOUT_DELAY_MS
                }
                hasFailure = true
                continue
            }

            // 答案正确：用加密密钥解密分片
            val key = keys.encryptionKey
            val encryptedData = Base64.getDecoder().decode(share.encryptedData)
            val decryptedShare = try {
                AesGcm.decrypt(encryptedData, key)
            } catch (_: Exception) {
                keys.clear()
                hasFailure = true
                continue
            }

            correctShares.add(ShamirSss.Share(share.shareIndex, decryptedShare))
            correctCount++
            keys.clear()

            // 答案正确：重置该题的失败计数
            failureCounts[questionId] = 0
        }

        // 检查是否达到门限
        val threshold = template.thresholdConfig.threshold
        if (correctCount < threshold) {
            return RecoveryResult.Failure(
                if (hasFailure) "正确答案数 $correctCount 不足，需要至少 $threshold 个。部分答案不正确或已被锁定"
                else "正确答案数 $correctCount 不足，需要至少 $threshold 个",
                correctCount,
                threshold
            )
        }

        // 合并分片恢复主密钥
        val masterSecret = if (correctShares.size == 1) {
            correctShares[0].data
        } else {
            ShamirSss.combine(correctShares.take(threshold))
        }

        // 派生密码
        val passphrase = PassphraseGenerator.generate(masterSecret)

        // 清零主密钥
        masterSecret.fill(0)

        // 成功：重置所有锁定状态
        resetRateLimitState()

        return RecoveryResult.Success(passphrase, correctCount, totalAnswered)
    }

    /** 获取问题文本（用于错误信息） */
    private fun getQuestionText(template: Template, questionId: String): String {
        return template.questions.find { it.id == questionId }?.text ?: questionId
    }

    /** 常量时间比较 */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}

/**
 * 模板完整性校验
 *
 * v2：完整性哈希覆盖所有模板字段，防止篡改
 * 基于规范化 JSON 序列化后计算 SHA-256
 */
object IntegrityManager {

    /**
     * 计算完整性哈希
     * 将所有关键字段按固定顺序拼接后计算 SHA-256
     */
    fun computeIntegrityHash(
        version: Int,
        name: String,
        questionIds: List<String>,
        thresholdConfig: ThresholdConfig,
        verificationHashes: Map<String, String> = emptyMap(),
        shamirShares: Map<String, com.mepass.app.model.ShamirEncryptedShare> = emptyMap(),
        createdAt: Long = 0L
    ): String {
        val input = buildString {
            append(version)
            append("|")
            append(name)
            append("|")
            append(questionIds.sorted().joinToString(","))
            append("|")
            append(thresholdConfig.totalQuestions)
            append("|")
            append(thresholdConfig.threshold)
            append("|")
            append(createdAt)
            append("|")
            // 覆盖 verificationHashes
            append(verificationHashes.toSortedMap().entries.joinToString("&") { (k, v) -> "$k=$v" })
            append("|")
            // 覆盖 shamirShares 的所有字段
            append(shamirShares.toSortedMap().entries.joinToString("&") { (k, v) ->
                "$k=${v.shareIndex}:${v.encryptedData}:${v.nonce}:${v.salt}"
            })
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun verifyIntegrity(template: Template): Boolean {
        val computed = computeIntegrityHash(
            version = template.version,
            name = template.name,
            questionIds = template.questions.map { it.id },
            thresholdConfig = template.thresholdConfig,
            verificationHashes = template.verificationHashes,
            shamirShares = template.shamirShares,
            createdAt = template.createdAt
        )
        return constantTimeEquals(
            computed.toByteArray(Charsets.UTF_8),
            template.integrityHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
