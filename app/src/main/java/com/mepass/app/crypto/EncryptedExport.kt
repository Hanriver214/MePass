package com.mepass.app.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * 模板加密导出 / 解密导入（口令保护）
 *
 * 在普通模板 JSON 之上再套一层「加密信封」：
 * - 用 Argon2id 从用户口令派生 AES-256 密钥
 * - 用 AES-256-GCM 加密模板 JSON 明文（含完整性 tag）
 * - 信封自描述 KDF 参数 / 盐值 / 密文，便于审计与未来扩展
 *
 * 信封 JSON 结构：
 * ```
 * {
 *   "format": "mepass-encrypted",
 *   "version": 1,
 *   "kdfAlgorithm": "argon2id",
 *   "kdfIterations": 3,
 *   "kdfMemoryKB": 65536,
 *   "kdfParallelism": 2,
 *   "salt": "<Base64>",
 *   "data": "<Base64: nonce(12B) + ciphertext + tag(16B)>"
 * }
 * ```
 *
 * [isEncrypted] 用于导入时自动识别明文 / 加密格式。
 */
object EncryptedExport {

    const val FORMAT_ID = "mepass-encrypted"
    const val CURRENT_VERSION = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 加密信封 */
    @Serializable
    data class Envelope(
        val format: String = FORMAT_ID,
        val version: Int = CURRENT_VERSION,
        val kdfAlgorithm: String = "argon2id",
        val kdfIterations: Int,
        val kdfMemoryKB: Int,
        val kdfParallelism: Int,
        val salt: String,
        val data: String
    )

    /**
     * 加密模板 JSON 明文，返回信封 JSON 字符串。
     *
     * @param plaintextJson 已序列化的模板 JSON
     * @param password 用户口令（不能为空）
     */
    fun encrypt(plaintextJson: String, password: String): String {
        require(password.isNotEmpty()) { "口令不能为空" }

        val salt = Argon2Kdf.generateSalt()
        val key = Argon2Kdf.deriveKeyFromPassword(password, salt)
        val cipherBytes = AesGcm.encrypt(plaintextJson.toByteArray(Charsets.UTF_8), key)
        key.fill(0)

        val envelope = Envelope(
            kdfIterations = Argon2Kdf.ITERATIONS,
            kdfMemoryKB = Argon2Kdf.MEMORY_KB,
            kdfParallelism = Argon2Kdf.PARALLELISM,
            salt = Base64.getEncoder().encodeToString(salt),
            data = Base64.getEncoder().encodeToString(cipherBytes)
        )
        return json.encodeToString(envelope)
    }

    /**
     * 解密信封 JSON，返回模板 JSON 明文。
     *
     * 失败原因：格式不符、版本不支持、KDF 参数被篡改、口令错误、数据损坏。
     * 所有失败统一抛 [IllegalArgumentException]，调用方可用 [Result] 捕获。
     */
    fun decrypt(envelopeJson: String, password: String): Result<String> = runCatching {
        require(password.isNotEmpty()) { "口令不能为空" }

        val envelope = json.decodeFromString<Envelope>(envelopeJson)
        require(envelope.format == FORMAT_ID) { "不是 MePass 加密模板格式" }
        require(envelope.version == CURRENT_VERSION) {
            "不支持的加密模板版本: ${envelope.version}"
        }
        require(envelope.kdfAlgorithm == "argon2id") {
            "不支持的 KDF 算法: ${envelope.kdfAlgorithm}"
        }
        // KDF 参数必须与本地一致，防止参数被篡改为弱配置后离线爆破
        require(envelope.kdfIterations == Argon2Kdf.ITERATIONS &&
            envelope.kdfMemoryKB == Argon2Kdf.MEMORY_KB &&
            envelope.kdfParallelism == Argon2Kdf.PARALLELISM) {
            "KDF 参数与当前应用不一致，拒绝解密"
        }

        val salt = Base64.getDecoder().decode(envelope.salt)
        val data = Base64.getDecoder().decode(envelope.data)
        require(data.size > 12) { "密文数据过短" }

        val key = Argon2Kdf.deriveKeyFromPassword(password, salt)
        val plain = try {
            AesGcm.decrypt(data, key)
        } catch (_: Exception) {
            key.fill(0)
            throw IllegalArgumentException("解密失败：口令错误或数据已损坏")
        }
        key.fill(0)
        String(plain, Charsets.UTF_8)
    }

    /** 检测 JSON 字符串是否为加密封装格式 */
    fun isEncrypted(jsonString: String): Boolean = runCatching {
        val element = json.parseToJsonElement(jsonString)
        if (element !is JsonObject) return false
        element["format"]?.jsonPrimitive?.contentOrNull == FORMAT_ID
    }.getOrDefault(false)
}
