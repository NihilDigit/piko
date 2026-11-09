package dev.piko.shared.sync

/**
 * SHA-256、HMAC-SHA256 与 PBKDF2-HMAC-SHA256 的可移植实现。写成 commonMain 而不是借平台的加密库，
 * 是因为 iOS 侧没有 JDK 的 `SecretKeyFactory`，而同步用的密钥派生与 [PortableAes] 的 GCM 必须两端
 * 对得上——同一份代码在桌面、Android、iOS 上算出的字节完全一样，才谈得上互相解密。
 *
 * 只做同步用得到的那几件事，不做流式接口、不复用中间状态；一次调用算完就结束。
 */
internal object PortableDigest {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    const val BLOCK_BYTES = 64
    const val DIGEST_BYTES = 32

    fun sha256(message: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )
        val padded = pad(message)
        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (t in 0 until 16) w[t] = readInt(padded, offset + t * 4)
            for (t in 16 until 64) {
                val s0 = rotateRight(w[t - 15], 7) xor rotateRight(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotateRight(w[t - 2], 17) xor rotateRight(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[t] + w[t]
                val s0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            offset += BLOCK_BYTES
        }
        val out = ByteArray(DIGEST_BYTES)
        for (i in 0 until 8) writeInt(out, i * 4, h[i])
        return out
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val block = ByteArray(BLOCK_BYTES)
        if (key.size > BLOCK_BYTES) sha256(key).copyInto(block) else key.copyInto(block)
        val inner = ByteArray(BLOCK_BYTES) { (block[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK_BYTES) { (block[it].toInt() xor 0x5c).toByte() }
        return sha256(outer + sha256(inner + message))
    }

    /** PBKDF2-HMAC-SHA256，[keyLength] 字节。迭代次数由调用方决定，这里只管算。 */
    fun pbkdf2HmacSha256(secret: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(iterations > 0) { "Iterations must be positive" }
        require(keyLength > 0) { "Key length must be positive" }
        val out = ByteArray(keyLength)
        val blocks = (keyLength + DIGEST_BYTES - 1) / DIGEST_BYTES
        for (blockIndex in 1..blocks) {
            // U1 的输入是 salt || INT_BE(块序号)：长度只能是 salt.size + 4，多一个字节都会算错
            val first = ByteArray(salt.size + 4)
            salt.copyInto(first)
            writeInt(first, salt.size, blockIndex)
            var u = hmacSha256(secret, first)
            val accumulated = u.copyOf()
            for (round in 2..iterations) {
                u = hmacSha256(secret, u)
                for (i in accumulated.indices) accumulated[i] = (accumulated[i].toInt() xor u[i].toInt()).toByte()
            }
            val offset = (blockIndex - 1) * DIGEST_BYTES
            val count = minOf(DIGEST_BYTES, keyLength - offset)
            accumulated.copyInto(out, offset, 0, count)
        }
        return out
    }

    private fun pad(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        var padded = message.size + 1 + 8
        val remainder = padded % BLOCK_BYTES
        if (remainder != 0) padded += BLOCK_BYTES - remainder
        val result = ByteArray(padded)
        message.copyInto(result)
        result[message.size] = 0x80.toByte()
        var bits = bitLength
        for (i in 0 until 8) {
            result[padded - 8 + i] = (bits ushr (56 - i * 8)).toByte()
        }
        return result
    }

    private fun rotateRight(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
