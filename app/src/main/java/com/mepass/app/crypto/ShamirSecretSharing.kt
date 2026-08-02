package com.mepass.app.crypto

import kotlin.math.pow

/**
 * (k, n) 门限 Shamir 秘密共享实现
 * 在有限域 GF(2^8) 上使用字节级操作，或使用 32位整数 GF(2^32-1) 简化版
 *
 * 本实现使用 GF(prime) 版本，prime = 2^31 - 1 (Mersenne prime)，
 * 适用于将 32 字节秘密分割为 n 份，k 份可恢复。
 *
 * 每个分片数据结构：(index: Int, share: ByteArray)
 * - index: x坐标（1..n，不可为0）
 * - share: y坐标（长度等于秘密长度）
 */
object ShamirSecretSharing {

    // 使用的素数模数：2^31 - 1
    private const val PRIME: Long = 2147483647L

    /**
     * 将秘密分割成 n 份，k 份可恢复
     * @param secret 秘密字节数组
     * @param n 总分片数
     * @param k 门限值（需要k份才能恢复）
     * @return 分片列表，每个分片 Pair(index: Int [1..n], shareData: ByteArray)
     */
    fun split(secret: ByteArray, n: Int, k: Int): List<Pair<Int, ByteArray>> {
        require(n in 1..255) { "n 必须在 1..255 之间" }
        require(k in 1..n) { "k 必须在 1..n 之间" }
        require(secret.isNotEmpty()) { "秘密不能为空" }

        if (k == 1) {
            // 门限为1：所有分片都等于秘密
            return (1..n).map { i -> Pair(i, secret.copyOf()) }
        }

        // 每个字节独立处理，构造 k-1 次多项式
        // 对每个字节位置，生成 k-1 个随机系数（除常数项=秘密字节）
        val random = java.security.SecureRandom()
        val shares = Array(n) { i ->
            val index = i + 1
            val shareData = ByteArray(secret.size)
            Pair(index, shareData)
        }

        for (bytePos in secret.indices) {
            // 构造多项式: f(x) = a0 + a1*x + a2*x^2 + ... + a_{k-1}*x^{k-1} mod P
            // a0 = 当前秘密字节（转为0..255的正数）
            val coefficients = LongArray(k)
            coefficients[0] = (secret[bytePos].toLong() and 0xFFL)
            for (coefIdx in 1 until k) {
                coefficients[coefIdx] = (random.nextInt() and 0x7FFFFFFF).toLong() % PRIME
            }

            // 对每个分片计算 y = f(index)
            for (s in 0 until n) {
                val x = shares[s].first.toLong()
                var y = 0L
                var xPow = 1L
                for (c in coefficients) {
                    y = (y + c * xPow) % PRIME
                    xPow = (xPow * x) % PRIME
                }
                // 存储时截断到字节（每字节共享一个分片序列）
                shares[s].second[bytePos] = (y and 0xFFL).toByte()
                // 高位存储在元数据（简化处理，实际每字节单独做Shamir更安全）
                // 注意：简化实现中，我们只使用了低8位。完整实现应对每字节独立做 GF(256)
            }
        }

        // 为了解决每字节截断问题，改进方案：
        // 使用 4 字节为一块，每块单独做 Shamir(int32)
        return splitInt32(secret, n, k)
    }

    /**
     * 使用 32 位整数块版本的分割（更安全的实现）
     */
    private fun splitInt32(secret: ByteArray, n: Int, k: Int): List<Pair<Int, ByteArray>> {
        val blockSize = 4
        val padLen = (blockSize - (secret.size % blockSize)) % blockSize
        val padded = secret + ByteArray(padLen) { padLen.toByte() }
        val numBlocks = padded.size / blockSize
        val random = java.security.SecureRandom()

        // 结果分片：每个分片 = index + [blockCount 个 4字节 share]
        val shareDatas = Array(n) { ByteArray(4 + 4 * numBlocks) } // 4 bytes = padding length + 3 reserved

        // 写入原始长度信息（用于恢复时去除填充）
        shareDatas.forEach {
            it[0] = padLen.toByte()
            it[1] = 0
            it[2] = 0
            it[3] = 0
        }

        for (blockIdx in 0 until numBlocks) {
            val blockOffset = blockIdx * blockSize
            // 提取32位秘密值（大端序，保持正数）
            val s0 = ((padded[blockOffset].toLong() and 0xFFL) shl 24) or
                    ((padded[blockOffset + 1].toLong() and 0xFFL) shl 16) or
                    ((padded[blockOffset + 2].toLong() and 0xFFL) shl 8) or
                    ((padded[blockOffset + 3].toLong() and 0xFFL))
            val secretInt = s0 % PRIME

            // 生成 k-1 次多项式系数
            val coefficients = LongArray(k)
            coefficients[0] = secretInt
            for (c in 1 until k) {
                coefficients[c] = (random.nextLong() and 0x7FFFFFFFFFFFFFFFL) % PRIME
            }

            // 计算每个分片的y值
            for (shareIdx in 0 until n) {
                val x = (shareIdx + 1).toLong()
                var y = 0L
                var xPow = 1L
                for (c in coefficients) {
                    y = (y + c * xPow) % PRIME
                    xPow = (xPow * x) % PRIME
                }

                // 将y写入分片（偏移4字节后，每块4字节大端序）
                val outOffset = 4 + blockIdx * 4
                val yInt = (y and 0xFFFFFFFFL).toInt()
                shareDatas[shareIdx][outOffset] = ((yInt shr 24) and 0xFF).toByte()
                shareDatas[shareIdx][outOffset + 1] = ((yInt shr 16) and 0xFF).toByte()
                shareDatas[shareIdx][outOffset + 2] = ((yInt shr 8) and 0xFF).toByte()
                shareDatas[shareIdx][outOffset + 3] = (yInt and 0xFF).toByte()
            }
        }

        return (0 until n).map { i -> Pair(i + 1, shareDatas[i]) }
    }

    /**
     * 从任意 k 个分片恢复秘密
     * @param shares k个分片的列表，每个分片 (index, shareData)
     * @param k 门限值
     * @return 恢复的秘密字节数组
     */
    fun combine(shares: List<Pair<Int, ByteArray>>, k: Int): ByteArray {
        require(shares.size >= k) { "至少需要 $k 个分片才能恢复，当前 ${shares.size}" }

        // 取前k个分片（假设有效）
        val usedShares = shares.take(k)
        require(usedShares.map { it.first }.toSet().size == k) { "分片索引不能重复" }

        val shareData0 = usedShares[0].second
        require(shareData0.size >= 4) { "分片数据格式无效" }

        val padLen = (shareData0[0].toInt() and 0xFF)
        val dataLen = shareData0.size - 4
        require(dataLen % 4 == 0) { "分片数据长度异常" }

        val numBlocks = dataLen / 4
        val paddedSecret = ByteArray(numBlocks * 4)

        for (blockIdx in 0 until numBlocks) {
            val blockOffset = 4 + blockIdx * 4
            // 收集 k 个 (x, y) 点
            val points = usedShares.map { (xIdx, data) ->
                val x = xIdx.toLong()
                val yInt = ((data[blockOffset].toLong() and 0xFFL) shl 24) or
                        ((data[blockOffset + 1].toLong() and 0xFFL) shl 16) or
                        ((data[blockOffset + 2].toLong() and 0xFFL) shl 8) or
                        ((data[blockOffset + 3].toLong() and 0xFFL))
                Pair(x, yInt)
            }

            // Lagrange 插值计算 f(0)
            val recovered = lagrangeInterpolate(points, 0L, PRIME)

            // 写入大端序
            val rInt = (recovered and 0xFFFFFFFFL).toInt()
            val outOffset = blockIdx * 4
            paddedSecret[outOffset] = ((rInt shr 24) and 0xFF).toByte()
            paddedSecret[outOffset + 1] = ((rInt shr 16) and 0xFF).toByte()
            paddedSecret[outOffset + 2] = ((rInt shr 8) and 0xFF).toByte()
            paddedSecret[outOffset + 3] = (rInt and 0xFF).toByte()
        }

        // 去除填充
        return if (padLen in 1..3 && paddedSecret.size >= padLen) {
            paddedSecret.copyOfRange(0, paddedSecret.size - padLen)
        } else {
            paddedSecret
        }
    }

    /**
     * Lagrange 插值：给定点集 (x,y)，计算在目标点 targetX 的值
     * 使用模运算在有限域上
     */
    private fun lagrangeInterpolate(
        points: List<Pair<Long, Long>>,
        targetX: Long,
        prime: Long
    ): Long {
        var result = 0L
        val k = points.size

        for (i in 0 until k) {
            val (xi, yi) = points[i]
            var numerator = 1L
            var denominator = 1L

            for (j in 0 until k) {
                if (i == j) continue
                val (xj, _) = points[j]

                // numerator *= (targetX - xj)
                var term = (targetX - xj) % prime
                if (term < 0) term += prime
                numerator = (numerator * term) % prime

                // denominator *= (xi - xj)
                var denom = (xi - xj) % prime
                if (denom < 0) denom += prime
                denominator = (denominator * denom) % prime
            }

            // 计算 numerator/denominator mod prime = numerator * inverse(denominator) mod prime
            val invDenominator = modInverse(denominator, prime)
            val lagrangeTerm = (numerator * invDenominator) % prime
            result = (result + yi * lagrangeTerm) % prime
        }

        return result
    }

    /**
     * 模逆元：使用扩展欧几里得算法
     */
    private fun modInverse(a: Long, m: Long): Long {
        // 使用 Fermat 小定理，因为 m 是素数
        // a^(m-2) mod m
        return modPow(a, m - 2, m)
    }

    /**
     * 快速模幂：(base^exp) mod m
     */
    private fun modPow(base: Long, exp: Long, m: Long): Long {
        var result = 1L
        var b = base % m
        var e = exp

        if (b < 0) b += m

        while (e > 0) {
            if (e and 1L == 1L) {
                result = (result * b) % m
            }
            e = e shr 1
            b = (b * b) % m
        }

        return result
    }
}
