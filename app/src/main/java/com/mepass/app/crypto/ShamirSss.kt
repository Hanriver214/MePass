package com.mepass.app.crypto

import java.security.SecureRandom

/**
 * Shamir (k, N) 门限秘密共享 - GF(2^8) 标准实现
 *
 * 在有限域 GF(2^8) 上对每个字节构造 k-1 次多项式：
 *   f(x) = a_0 + a_1*x + a_2*x^2 + ... + a_{k-1}*x^{k-1}   (mod 2^8)
 * 其中 a_0 为秘密字节，a_1..a_{k-1} 为随机系数。
 *
 * 对秘密的每个字节独立生成 N 个分片，combine 时对 k 个分片做拉格朗日插值恢复 a_0。
 *
 * 优点：
 * - 标准实现，广泛使用（如 secrets.rs、ssss、Shamir's Secret Sharing Library）
 * - 字节级处理，无填充需求
 * - n 最大 255，k 最大 255
 */
object ShamirSss {
    private val secureRandom = SecureRandom()

    // GF(2^8) 乘法/逆元用的对数/反对数表（生成多项式 0x11B，AES 同款）
    // 生成元用 3 而非 2：2 在 GF(2^8)/0x11B 中的阶是 51（2^51=1），不是本原元，
    // 用 2 只能覆盖 51/255 个域元素，会导致 gfInverse/gfDivide 对其余元素返回
    // 错误结果，进而使 combine 还原出错误（且随分片子集变化）的秘密。
    // 3 的阶是 255，是本原元，可覆盖全部非零元素。
    private val EXP = IntArray(256)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0..254) {
            EXP[i] = x
            LOG[x] = i
            x = gfMultiply(x, 3)
        }
        EXP[255] = EXP[0]  // 循环
    }

    private fun gfMultiply(a: Int, b: Int): Int {
        var result = 0
        var aa = a
        var bb = b
        for (i in 0..7) {
            if (bb and 1 != 0) result = result xor aa
            val hiBit = aa and 0x80
            aa = aa shl 1
            if (hiBit != 0) aa = aa xor 0x11B
            bb = bb shr 1
        }
        return result and 0xFF
    }

    /** GF(2^8) 逆元：用于除法 */
    private fun gfInverse(a: Int): Int {
        require(a != 0) { "0 没有逆元" }
        return EXP[255 - LOG[a]]
    }

    /** GF(2^8) 除法：a / b */
    private fun gfDivide(a: Int, b: Int): Int {
        require(b != 0) { "除数不能为 0" }
        if (a == 0) return 0
        return EXP[(LOG[a] + LOG[gfInverse(b)]) % 255]
    }

    /**
     * 分割秘密为 N 个分片
     *
     * @param secret 原始秘密
     * @param n 分片总数（2..255）
     * @param k 门限值（2..n）
     * @return 分片列表，每个分片包含 (index, data) 其中 data 长度 = secret.length
     */
    fun split(secret: ByteArray, n: Int, k: Int): List<Share> {
        require(n in 2..255) { "n 必须在 2..255 范围内" }
        require(k in 2..n) { "k 必须在 2..n 范围内" }

        val shares = Array(n) { Share(it + 1, ByteArray(secret.size)) }

        for (byteIndex in secret.indices) {
            // 构造多项式系数：a_0 = 秘密字节，a_1..a_{k-1} 随机
            val coefficients = IntArray(k)
            coefficients[0] = secret[byteIndex].toInt() and 0xFF
            for (i in 1 until k) {
                coefficients[i] = secureRandom.nextInt(256)
            }
            // 对每个分片 index 计算 f(index)
            for (shareIdx in 0 until n) {
                val x = shareIdx + 1
                shares[shareIdx].data[byteIndex] = evaluatePolynomial(coefficients, x).toByte()
            }
        }
        return shares.toList()
    }

    /** 计算 f(x) = a_0 + a_1*x + ... + a_{k-1}*x^{k-1} (mod 2^8) */
    private fun evaluatePolynomial(coefficients: IntArray, x: Int): Int {
        var result = 0
        var xi = 1  // x^0
        for (coef in coefficients) {
            result = result xor gfMultiply(coef, xi)
            xi = gfMultiply(xi, x)
        }
        return result
    }

    /**
     * 合并 k 个分片恢复秘密
     *
     * @param shares k 个分片（index 互不相同）
     * @return 原始秘密
     */
    fun combine(shares: List<Share>): ByteArray {
        require(shares.size >= 2) { "至少需要 2 个分片" }

        val secretLength = shares[0].data.size
        val result = ByteArray(secretLength)

        for (byteIndex in 0 until secretLength) {
            // 拉格朗日插值求 f(0)
            // f(0) = Σ y_i * Π (x_j / (x_j - x_i)) for j != i
            var secret = 0
            for (i in shares.indices) {
                val xi = shares[i].index
                val yi = shares[i].data[byteIndex].toInt() and 0xFF
                var lagrange = 1
                for (j in shares.indices) {
                    if (i == j) continue
                    val xj = shares[j].index
                    val num = xj
                    val den = xj xor xi
                    lagrange = gfMultiply(lagrange, gfDivide(num, den))
                }
                secret = secret xor gfMultiply(yi, lagrange)
            }
            result[byteIndex] = secret.toByte()
        }
        return result
    }

    /** 分片数据结构 */
    data class Share(val index: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Share) return false
            return index == other.index && data.contentEquals(other.data)
        }
        override fun hashCode(): Int = 31 * index + data.contentHashCode()
    }
}
