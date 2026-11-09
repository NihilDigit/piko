package dev.piko.shared.sync

/**
 * AES（只做加密方向的块运算，GCM 用不到解密）与 GCM 模式的可移植实现。
 *
 * 为什么自己写而不借平台库：iOS 的 CommonCrypto 里 AES-GCM 的 C 接口在 Kotlin/Native 侧没有稳定绑定，
 * 而桌面与 Android 用的是 JDK 的 `AES/GCM/NoPadding`。同一份 GCM 代码跑在三端，密文才互相解不开的
 * 概率为零。常数表由 GF(2^8) 计算得出而不是手抄 256 个字节，减少手误。
 */
internal class PortableAes(private val key: ByteArray) {
    private val rounds: Int
    private val roundKeys: IntArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes" }
        val words = key.size / 4
        rounds = words + 6
        val total = 4 * (rounds + 1)
        roundKeys = IntArray(total)
        for (i in 0 until words) {
            roundKeys[i] = ((key[i * 4].toInt() and 0xFF) shl 24) or
                ((key[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((key[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (key[i * 4 + 3].toInt() and 0xFF)
        }
        var rcon = 1
        for (i in words until total) {
            var temp = roundKeys[i - 1]
            if (i % words == 0) {
                temp = subWord((temp shl 8) or (temp ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (words > 6 && i % words == 4) {
                temp = subWord(temp)
            }
            roundKeys[i] = roundKeys[i - words] xor temp
        }
    }

    fun encryptBlock(input: ByteArray, inputOffset: Int, output: ByteArray, outputOffset: Int) {
        val state = IntArray(16) { input[inputOffset + it].toInt() and 0xFF }
        addRoundKey(state, 0)
        for (round in 1 until rounds) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, rounds)
        for (i in 0 until 16) output[outputOffset + i] = state[i].toByte()
    }

    private fun addRoundKey(state: IntArray, round: Int) {
        for (i in 0 until 16) {
            val word = roundKeys[round * 4 + i / 4]
            state[i] = state[i] xor ((word ushr (24 - 8 * (i % 4))) and 0xFF)
        }
    }

    private fun subBytes(state: IntArray) {
        for (i in 0 until 16) state[i] = SBOX[state[i]]
    }

    /** 状态按列存放：下标 = 4 * 列 + 行。第 r 行循环左移 r 列。 */
    private fun shiftRows(state: IntArray) {
        val copy = state.copyOf()
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                state[4 * column + row] = copy[4 * ((column + row) % 4) + row]
            }
        }
    }

    private fun mixColumns(state: IntArray) {
        for (column in 0 until 4) {
            val base = 4 * column
            val a0 = state[base]
            val a1 = state[base + 1]
            val a2 = state[base + 2]
            val a3 = state[base + 3]
            val all = a0 xor a1 xor a2 xor a3
            state[base] = a0 xor all xor xtime(a0 xor a1)
            state[base + 1] = a1 xor all xor xtime(a1 xor a2)
            state[base + 2] = a2 xor all xor xtime(a2 xor a3)
            state[base + 3] = a3 xor all xor xtime(a3 xor a0)
        }
    }

    private fun subWord(word: Int): Int =
        (SBOX[(word ushr 24) and 0xFF] shl 24) or
            (SBOX[(word ushr 16) and 0xFF] shl 16) or
            (SBOX[(word ushr 8) and 0xFF] shl 8) or
            SBOX[word and 0xFF]

    private companion object {
        fun xtime(value: Int): Int {
            val shifted = value shl 1
            return if (shifted and 0x100 != 0) (shifted xor 0x11B) and 0xFF else shifted and 0xFF
        }

        fun multiply(left: Int, right: Int): Int {
            var a = left
            var b = right
            var product = 0
            while (b != 0) {
                if (b and 1 != 0) product = product xor a
                a = xtime(a)
                b = b ushr 1
            }
            return product
        }

        fun power(base: Int, exponent: Int): Int {
            var result = 1
            var current = base
            var remaining = exponent
            while (remaining > 0) {
                if (remaining and 1 != 0) result = multiply(result, current)
                current = multiply(current, current)
                remaining = remaining ushr 1
            }
            return result
        }

        fun rotateLeftByte(value: Int, bits: Int): Int =
            ((value shl bits) or (value ushr (8 - bits))) and 0xFF

        val SBOX = IntArray(256) { value ->
            val inverse = if (value == 0) 0 else power(value, 254)
            val rotated = rotateLeftByte(inverse, 1) xor rotateLeftByte(inverse, 2) xor
                rotateLeftByte(inverse, 3) xor rotateLeftByte(inverse, 4)
            (inverse xor rotated xor 0x63) and 0xFF
        }
    }
}

/**
 * GCM：96 位 IV、128 位认证标签、无附加认证数据（同步载荷不需要 AAD）。
 * 结果布局与 JVM 的 `Cipher.doFinal` 一致：密文在前、16 字节标签在后，IV 由调用方拼在更前面。
 */
internal object PortableGcm {
    const val TAG_BYTES = 16
    private const val GHASH_REDUCTION = 0xE100000000000000UL

    fun seal(aes: PortableAes, iv: ByteArray, plain: ByteArray): ByteArray {
        require(iv.size == 12) { "GCM IV must be 12 bytes" }
        val hashKey = hashKey(aes)
        val counter = ByteArray(16)
        iv.copyInto(counter)
        counter[15] = 1

        val cipher = ByteArray(plain.size)
        var higher = 0UL
        var lower = 0UL
        var offset = 0
        val keystream = ByteArray(16)
        while (offset < plain.size) {
            increment(counter)
            aes.encryptBlock(counter, 0, keystream, 0)
            val take = minOf(16, plain.size - offset)
            val block = ByteArray(16)
            for (i in 0 until take) {
                val value = plain[offset + i].toInt() xor keystream[i].toInt()
                cipher[offset + i] = value.toByte()
                block[i] = value.toByte()
            }
            val hashed = multiply(higher, lower, block, 0, hashKey)
            higher = hashed.first
            lower = hashed.second
            offset += take
        }
        val lengths = ByteArray(16)
        val bitLength = plain.size.toLong() * 8
        for (i in 0 until 8) lengths[8 + i] = (bitLength ushr (56 - 8 * i)).toByte()
        val finalHash = multiply(higher, lower, lengths, 0, hashKey)

        val mask = ByteArray(16)
        aes.encryptBlock(counter0(iv), 0, mask, 0)
        val tag = ByteArray(TAG_BYTES)
        for (i in 0 until TAG_BYTES) {
            val highByte = ((if (i < 8) finalHash.first else finalHash.second) shr (56 - 8 * (i % 8))).toByte()
            tag[i] = (highByte.toInt() xor mask[i].toInt()).toByte()
        }
        return cipher + tag
    }

    fun open(aes: PortableAes, iv: ByteArray, sealed: ByteArray): ByteArray? {
        if (iv.size != 12 || sealed.size < TAG_BYTES) return null
        val cipherSize = sealed.size - TAG_BYTES
        val cipher = sealed.copyOfRange(0, cipherSize)
        val givenTag = sealed.copyOfRange(cipherSize, sealed.size)

        val hashKey = hashKey(aes)
        var higher = 0UL
        var lower = 0UL
        var offset = 0
        while (offset < cipherSize) {
            val block = ByteArray(16)
            val take = minOf(16, cipherSize - offset)
            cipher.copyInto(block, 0, offset, offset + take)
            val hashed = multiply(higher, lower, block, 0, hashKey)
            higher = hashed.first
            lower = hashed.second
            offset += take
        }
        val lengths = ByteArray(16)
        val bitLength = cipherSize.toLong() * 8
        for (i in 0 until 8) lengths[8 + i] = (bitLength ushr (56 - 8 * i)).toByte()
        val finalHash = multiply(higher, lower, lengths, 0, hashKey)

        val mask = ByteArray(16)
        aes.encryptBlock(counter0(iv), 0, mask, 0)
        var mismatch = 0
        for (i in 0 until TAG_BYTES) {
            val source = if (i < 8) finalHash.first else finalHash.second
            val expected = (((source shr (56 - 8 * (i % 8))).toByte()).toInt() xor mask[i].toInt()).toByte()
            mismatch = mismatch or (expected.toInt() xor givenTag[i].toInt())
        }
        // 标签不对就当作被改过或密钥不对，别去解密，免得把明文交出去
        if (mismatch != 0) return null

        val counter = counter0(iv)
        val keystream = ByteArray(16)
        val plain = ByteArray(cipherSize)
        offset = 0
        while (offset < cipherSize) {
            increment(counter)
            aes.encryptBlock(counter, 0, keystream, 0)
            val take = minOf(16, cipherSize - offset)
            for (i in 0 until take) {
                plain[offset + i] = (cipher[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += take
        }
        return plain
    }

    private fun hashKey(aes: PortableAes): Pair<ULong, ULong> {
        val zeros = ByteArray(16)
        val h = ByteArray(16)
        aes.encryptBlock(zeros, 0, h, 0)
        return readULong(h, 0) to readULong(h, 8)
    }

    private fun counter0(iv: ByteArray): ByteArray {
        val block = ByteArray(16)
        iv.copyInto(block)
        block[15] = 1
        return block
    }

    private fun increment(counter: ByteArray) {
        for (i in 15 downTo 12) {
            counter[i] = (counter[i] + 1).toByte()
            if (counter[i].toInt() != 0) return
        }
    }

    private fun readULong(source: ByteArray, offset: Int): ULong {
        var value = 0UL
        for (i in 0 until 8) value = (value shl 8) or (source[offset + i].toULong() and 0xFFUL)
        return value
    }

    private fun multiply(
        higher: ULong,
        lower: ULong,
        block: ByteArray,
        offset: Int,
        hashKey: Pair<ULong, ULong>,
    ): Pair<ULong, ULong> {
        val xHigh = higher xor readULong(block, offset)
        val xLow = lower xor readULong(block, offset + 8)
        var zHigh = 0UL
        var zLow = 0UL
        var vHigh = hashKey.first
        var vLow = hashKey.second
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHigh shr (63 - i)) and 1UL else (xLow shr (63 - (i - 64))) and 1UL
            if (bit == 1UL) {
                zHigh = zHigh xor vHigh
                zLow = zLow xor vLow
            }
            val lowest = vLow and 1UL
            vLow = (vLow shr 1) or (vHigh shl 63)
            vHigh = vHigh shr 1
            if (lowest == 1UL) vHigh = vHigh xor GHASH_REDUCTION
        }
        return zHigh to zLow
    }
}
