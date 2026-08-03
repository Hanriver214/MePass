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
 * 3. [verifySingleAnswer]：验证单题答案是否正确
 * 4. [recoverPassphrase]：从 k 个正确答案恢复密码
 */
object TemplateManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val secureRandom = SecureRandom()

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
            // 单题情况：分片 = 主密钥本身
            listOf(ShamirSss.Share(1, masterSecret))
        } else {
            ShamirSss.split(masterSecret, questionAnswers.size, threshold)
        }

        // 3. 对每题：哈希答案 + 加密对应分片
        val verificationHashes = mutableMapOf<String, String>()
        val shamirShares = mutableMapOf<String, ShamirEncryptedShare>()

        for ((index, pair) in questionAnswers.withIndex()) {
            val (question, answer) = pair
            val normalized = AnswerNormalizer.normalize(answer)
            val salt = Argon2Kdf.generateSalt()

            // 答案哈希（用于本地验证）
            val hash = Argon2Kdf.hashAnswer(normalized, salt)
            verificationHashes[question.id] = hash

            // 用答案派生密钥加密分片
            val key = Argon2Kdf.deriveKey(normalized, salt)
            val share = shares[index]
            val encrypted = AesGcm.encrypt(share.data, key)

            shamirShares[question.id] = ShamirEncryptedShare(
                shareIndex = share.index,
                encryptedData = Base64.getEncoder().encodeToString(encrypted),
                nonce = Base64.getEncoder().encodeToString(encrypted.copyOfRange(0, 12)),
                salt = Base64.getEncoder().encodeToString(salt)
            )

            // 清零敏感数据
            key.fill(0)
        }

        // 4. 计算完整性哈希
        val integrityHash = IntegrityManager.computeIntegrityHash(
            version = Template.CURRENT_VERSION,
            name = name,
            questionIds = questions.map { it.id },
            thresholdConfig = thresholdConfig
        )

        // 5. 清零主密钥
        masterSecret.fill(0)

        return Template(
            name = name,
            createdAt = System.currentTimeMillis(),
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
     *
     * 在明文模板 JSON 之外再套一层 Argon2id + AES-256-GCM 加密信封，
     * 适合模板需要离开本机备份 / 传输的场景。
     *
     * @param password 用户口令
     */
    fun exportTemplateEncrypted(template: Template, password: String): String {
        val plain = json.encodeToString(template)
        return EncryptedExport.encrypt(plain, password)
    }

    /** 检测 JSON 字符串是否为加密封装格式（用于导入界面自适应） */
    fun isEncryptedTemplate(jsonString: String): Boolean = EncryptedExport.isEncrypted(jsonString)

    /**
     * 从 JSON 字符串导入模板（自动识别明文 / 加密格式，含完整性校验）
     *
     * - 明文 JSON：[password] 参数被忽略，直接解析校验
     * - 加密信封：必须提供正确口令，先解密再校验
     *
     * @param jsonString 模板 JSON（明文或加密封装）
     * @param password 加密模板的解密口令；明文模板可传 null
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
        require(template.version == Template.CURRENT_VERSION) {
            "模板版本不兼容（当前版本: ${Template.CURRENT_VERSION}, 模板版本: ${template.version}）"
        }
        require(IntegrityManager.verifyIntegrity(template)) {
            "模板完整性校验失败：可能已被篡改"
        }
        return template
    }

    /**
     * 验证单题答案
     * @return true=正确 false=错误
     */
    fun verifySingleAnswer(template: Template, questionId: String, answer: String): Boolean {
        val storedHash = template.verificationHashes[questionId] ?: return false
        val share = template.shamirShares[questionId] ?: return false
        val salt = Base64.getDecoder().decode(share.salt)
        val normalized = AnswerNormalizer.normalize(answer)
        return Argon2Kdf.verifyAnswer(storedHash, normalized, salt)
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

        // 收集正确答案对应的分片
        val correctShares = mutableListOf<ShamirSss.Share>()
        var correctCount = 0
        var totalAnswered = 0

        for ((questionId, answer) in answers) {
            if (answer.isBlank()) continue
            totalAnswered++

            val normalized = AnswerNormalizer.normalize(answer)
            val share = template.shamirShares[questionId] ?: continue
            val salt = Base64.getDecoder().decode(share.salt)

            // 验证答案
            val storedHash = template.verificationHashes[questionId] ?: continue
            if (!Argon2Kdf.verifyAnswer(storedHash, normalized, salt)) continue

            // 解密分片
            val key = Argon2Kdf.deriveKey(normalized, salt)
            val encryptedData = Base64.getDecoder().decode(share.encryptedData)
            val decryptedShare = try {
                AesGcm.decrypt(encryptedData, key)
            } catch (_: Exception) {
                key.fill(0)
                continue
            }

            correctShares.add(ShamirSss.Share(share.shareIndex, decryptedShare))
            correctCount++
            key.fill(0)
        }

        // 检查是否达到门限
        val threshold = template.thresholdConfig.threshold
        if (correctCount < threshold) {
            return RecoveryResult.Failure(
                "正确答案数 $correctCount 不足，需要至少 $threshold 个",
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

        return RecoveryResult.Success(passphrase, correctCount, totalAnswered)
    }
}

/**
 * 模板完整性校验
 * 基于 SHA-256 计算 (version|name|sortedQuestionIds|total|threshold) 的哈希
 */
object IntegrityManager {
    fun computeIntegrityHash(
        version: Int,
        name: String,
        questionIds: List<String>,
        thresholdConfig: ThresholdConfig
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
            thresholdConfig = template.thresholdConfig
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
