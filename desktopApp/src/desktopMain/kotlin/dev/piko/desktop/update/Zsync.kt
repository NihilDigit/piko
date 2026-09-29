package dev.piko.desktop.update

import dev.piko.shared.update.ChecksumMismatchException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * zsync 的控制文件（Release 附件 piko-linux-<架构>-<版本>.AppImage.zsync，CI 用 zsyncmake 生成）。
 *
 * 新版 AppImage 按 [blockSize] 切块，每块记一个弱校验（rsum，可滚动计算）与一个强校验（MD4 的前几个字节）。
 * 客户端拿本机的旧 AppImage 在每个字节偏移上滚动算弱校验，撞上了再算 MD4 确认，确认过的块从本机拷，
 * 其余的按 HTTP Range 下载。squashfs 里没变的文件压出来的字节不变，只是位置挪了，逐字节滚动正好找得回来。
 *
 * 格式照 zsync 0.6.2（librcksum）：头部是「键: 值」行，空行之后是逐块的校验，每块先是 rsum 的末 rsumBytes 个字节
 * （4 字节里依次是大端的 a、b 各 16 位，取末尾是因为 b 更能区分块），再是 MD4 的前 checksumBytes 个字节。
 * 最后一块不足 blockSize 时按补零后的整块算。
 */
internal class ZsyncControl(
    val blockSize: Int,
    val length: Long,
    /** 连续几块一起匹配才算数。rsum 只存两个字节时误撞太多，zsyncmake 会要求 2。 */
    val seqMatches: Int,
    val rsumBytes: Int,
    val checksumBytes: Int,
    /** 整个新文件的 SHA-1，zsyncmake 写进头部。 */
    val sha1: String?,
    /** 每块的 rsum，已按 [rsumBytes] 截掉高位：a 在高 16 位，b 在低 16 位。 */
    val rsums: IntArray,
    /** 每块 MD4 的前 [checksumBytes] 个字节，首尾相接。 */
    val checksums: ByteArray,
) {
    val blockCount: Int get() = rsums.size

    /** 与本机滚动算出的 rsum 比较前要截掉的位，同 [rsums]。 */
    val rsumMask: Int = if (rsumBytes >= 4) -1 else (1 shl (8 * rsumBytes)) - 1

    fun checksumMatches(block: Int, md4: ByteArray): Boolean {
        val offset = block * checksumBytes
        for (i in 0 until checksumBytes) if (checksums[offset + i] != md4[i]) return false
        return true
    }

    companion object {
        fun parse(bytes: ByteArray): ZsyncControl {
            var headerEnd = -1
            for (i in 0 until bytes.size - 1) {
                if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                    headerEnd = i
                    break
                }
            }
            require(headerEnd > 0) { "zsync 控制文件没有头部" }
            val headers = bytes.copyOfRange(0, headerEnd).decodeToString().lines()
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
                .toMap()
            val blockSize = checkNotNull(headers["Blocksize"]?.toIntOrNull()) { "zsync 控制文件缺少 Blocksize" }
            val length = checkNotNull(headers["Length"]?.toLongOrNull()) { "zsync 控制文件缺少 Length" }
            val (seqMatches, rsumBytes, checksumBytes) = (headers["Hash-Lengths"] ?: "1,4,16").split(',').map { it.trim().toInt() }
            require(blockSize > 0 && length >= 0) { "zsync 控制文件的块大小或长度无效" }
            require(seqMatches in 1..2 && rsumBytes in 1..4 && checksumBytes in 3..16) { "zsync 控制文件的 Hash-Lengths 无效" }
            val blockCount = Math.toIntExact((length + blockSize - 1) / blockSize)
            val perBlock = rsumBytes + checksumBytes
            val body = headerEnd + 2
            require(bytes.size - body >= blockCount.toLong() * perBlock) { "zsync 控制文件的块校验不完整" }
            val rsums = IntArray(blockCount)
            val checksums = ByteArray(blockCount * checksumBytes)
            for (block in 0 until blockCount) {
                val offset = body + block * perBlock
                var rsum = 0
                for (i in 0 until rsumBytes) rsum = (rsum shl 8) or (bytes[offset + i].toInt() and 0xFF)
                rsums[block] = rsum
                System.arraycopy(bytes, offset + rsumBytes, checksums, block * checksumBytes, checksumBytes)
            }
            return ZsyncControl(blockSize, length, seqMatches, rsumBytes, checksumBytes, headers["SHA-1"]?.lowercase(), rsums, checksums)
        }
    }
}

/**
 * 拿本机文件对照控制文件的结果：[sources] 为每块在本机文件里的偏移，-1 为要下载。
 * [ranges] 是要下载的字节区间（闭区间），相邻的缺块已合并，隔得很近的几段也并成一段：
 * 每段是一次 HTTP 请求，GitHub 的附件地址还要先跳转一次，多下几个已有的块比多一个来回便宜。
 */
internal class ZsyncPlan(val control: ZsyncControl, val sources: LongArray) {
    val ranges: List<LongRange> = buildList {
        val last = control.length - 1
        var start = -1L
        var end = -1L
        for (block in sources.indices) {
            if (sources[block] >= 0) continue
            val from = block.toLong() * control.blockSize
            val to = minOf(from + control.blockSize - 1, last)
            if (start >= 0 && from - end - 1 <= MERGE_GAP_BYTES) {
                end = to
            } else {
                if (start >= 0) add(start..end)
                start = from
                end = to
            }
        }
        if (start >= 0) add(start..end)
    }

    val downloadBytes: Long = ranges.sumOf { it.last - it.first + 1 }

    val reusedBytes: Long get() = control.length - downloadBytes

    companion object {
        /** 两段缺块之间隔着不到这么多已有的字节就并成一次请求。 */
        const val MERGE_GAP_BYTES = 64 * 1024L
    }
}

/** 在本机文件 [local] 里找控制文件列出的块。本机文件读不了时抛异常，调用方退回整包下载。 */
internal fun planZsync(control: ZsyncControl, local: File): ZsyncPlan {
    val sources = LongArray(control.blockCount) { -1L }
    FileChannel.open(local.toPath(), StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        // 超过 2 GiB 映射不了；AppImage 远没有这么大，真碰上就整包下载
        require(size <= Int.MAX_VALUE) { "本机文件过大：$size" }
        val data = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        ZsyncScanner(control, data, size.toInt(), sources).scan()
    }
    return ZsyncPlan(control, sources)
}

private class ZsyncScanner(
    private val control: ZsyncControl,
    private val data: MappedByteBuffer,
    private val length: Int,
    private val sources: LongArray,
) {
    private val bs = control.blockSize
    private val mask = control.rsumMask

    // 按 rsum 的 b 分桶，同一桶里的块串成链。b 总在存下的字节里（rsumBytes 至少 2）
    private val head = IntArray(65536) { -1 }
    private val next = IntArray(control.blockCount)

    init {
        for (block in control.blockCount - 1 downTo 0) {
            val key = control.rsums[block] and 0xFFFF
            next[block] = head[key]
            head[key] = block
        }
    }

    private val window = ByteArray(bs)
    private val md4 = Md4()
    private var md4At = -1
    private var md4Value = ByteArray(16)
    private var nextMd4At = -1
    private var nextMd4Value = ByteArray(16)

    private fun digestAt(offset: Int): ByteArray {
        data.get(offset, window, 0, bs)
        return md4.digest(window)
    }

    private fun md4At(offset: Int): ByteArray {
        if (md4At != offset) {
            md4Value = digestAt(offset)
            md4At = offset
        }
        return md4Value
    }

    private fun nextMd4At(offset: Int): ByteArray {
        if (nextMd4At != offset) {
            nextMd4Value = digestAt(offset)
            nextMd4At = offset
        }
        return nextMd4Value
    }

    /** 窗口 [offset, offset + bs) 的 rsum，a 在高 16 位，b 在低 16 位。 */
    private fun rsumAt(offset: Int): Int {
        var a = 0
        var b = 0
        for (i in 0 until bs) {
            val c = data.get(offset + i).toInt() and 0xFF
            a += c
            b += (bs - i) * c
        }
        return ((a and 0xFFFF) shl 16) or (b and 0xFFFF)
    }

    private fun roll(rsum: Int, out: Int, `in`: Int): Int {
        val a = ((rsum ushr 16) - out + `in`) and 0xFFFF
        val b = ((rsum and 0xFFFF) - bs * out + a) and 0xFFFF
        return (a shl 16) or b
    }

    fun scan() {
        if (length < bs || control.blockCount == 0) return
        val paired = control.seqMatches > 1
        var x = 0
        var r0 = rsumAt(0)
        var r1 = if (length >= 2 * bs) rsumAt(bs) else 0
        while (true) {
            val r1Valid = x + 2 * bs <= length
            var matched = false
            var block = head[r0 and 0xFFFF]
            while (block != -1) {
                if ((r0 and mask) == control.rsums[block] && tryMatch(block, x, r1, r1Valid, paired)) matched = true
                block = next[block]
            }
            if (matched) {
                // 与 zsync 相同，匹配上之后跳过整块，从下一块的起点重新算
                x += bs
                if (x + bs > length) return
                r0 = rsumAt(x)
                if (x + 2 * bs <= length) r1 = rsumAt(x + bs)
            } else {
                if (x + bs >= length) return
                val out0 = data.get(x).toInt() and 0xFF
                val in0 = data.get(x + bs).toInt() and 0xFF
                r0 = roll(r0, out0, in0)
                if (x + 2 * bs < length) {
                    val in1 = data.get(x + 2 * bs).toInt() and 0xFF
                    r1 = roll(r1, in0, in1)
                }
                x++
            }
        }
    }

    /**
     * 块 [block] 是否就在本机偏移 [x] 处。成对匹配时（seqMatches 为 2）连下一块也要在 x + bs 处对上，
     * 两块一起记下；新文件的最后一块没有下一块，只对它自己。
     */
    private fun tryMatch(block: Int, x: Int, r1: Int, r1Valid: Boolean, paired: Boolean): Boolean {
        val following = block + 1
        val needFollowing = paired && following < control.blockCount
        if (sources[block] >= 0 && (!needFollowing || sources[following] >= 0)) return false
        if (needFollowing && (!r1Valid || (r1 and mask) != control.rsums[following])) return false
        if (!control.checksumMatches(block, md4At(x))) return false
        if (needFollowing && !control.checksumMatches(following, nextMd4At(x + bs))) return false
        if (sources[block] < 0) sources[block] = x.toLong()
        if (needFollowing && sources[following] < 0) sources[following] = (x + bs).toLong()
        return true
    }
}

/**
 * 按 [plan] 拼出新文件 [target]：已有的块从 [local] 拷，缺的由 [fetch] 按区间下载，最多 [parallelism] 段同时下。
 * 拼完核对控制文件里的 SHA-1；对不上抛 [ChecksumMismatchException]。调用方还要按 GitHub 公布的 SHA-256 再核一遍：
 * SHA-1 与控制文件同源，只能查出拼错，查不出控制文件本身被换过。
 */
internal suspend fun assembleZsync(
    plan: ZsyncPlan,
    local: File,
    target: File,
    parallelism: Int = 4,
    fetch: suspend (range: LongRange, onChunk: (ByteArray, Int) -> Unit) -> Unit,
) {
    val control = plan.control
    RandomAccessFile(target, "rw").use { it.setLength(control.length) }
    FileChannel.open(target.toPath(), StandardOpenOption.WRITE).use { out ->
        FileChannel.open(local.toPath(), StandardOpenOption.READ).use { input ->
            for (block in plan.sources.indices) {
                val source = plan.sources[block]
                if (source < 0) continue
                val offset = block.toLong() * control.blockSize
                val size = minOf(control.blockSize.toLong(), control.length - offset)
                var copied = 0L
                while (copied < size) {
                    val n = input.transferTo(source + copied, size - copied, out.position(offset + copied))
                    if (n <= 0) throw ChecksumMismatchException("本机文件在拼接途中变短了")
                    copied += n
                }
            }
        }
        val gate = Semaphore(parallelism)
        coroutineScope {
            plan.ranges.map { range ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        var position = range.first
                        fetch(range) { buffer, length ->
                            val chunk = ByteBuffer.wrap(buffer, 0, length)
                            while (chunk.hasRemaining()) position += out.write(chunk, position)
                        }
                        if (position != range.last + 1) throw ChecksumMismatchException("区间 $range 只收到 ${position - range.first} 字节")
                    }
                }
            }.awaitAll()
        }
        out.force(false)
    }
    control.sha1?.let { expected ->
        val actual = target.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-1")
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().toHex()
        }
        if (actual != expected) throw ChecksumMismatchException("zsync 拼出的文件 SHA-1 不符：$actual != $expected")
    }
}

/**
 * MD4（RFC 1320），zsync 的强校验用它。JDK 自带的实现不经 MessageDigest.getInstance 公开，
 * 为这一个算法引入 BouncyCastle 不值当。
 */
internal class Md4 {
    private val x = IntArray(16)

    fun digest(input: ByteArray): ByteArray {
        var a = 0x67452301
        var b = -0x10325477
        var c = -0x67452302
        var d = 0x10325476
        val bitLength = input.size.toLong() * 8
        val padded = ((input.size + 8) / 64 + 1) * 64
        val message = input.copyOf(padded)
        message[input.size] = 0x80.toByte()
        for (i in 0 until 8) message[padded - 8 + i] = (bitLength ushr (8 * i)).toByte()
        var block = 0
        while (block < padded) {
            for (i in 0 until 16) {
                val o = block + i * 4
                x[i] = (message[o].toInt() and 0xFF) or ((message[o + 1].toInt() and 0xFF) shl 8) or
                    ((message[o + 2].toInt() and 0xFF) shl 16) or ((message[o + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a
            val bb = b
            val cc = c
            val dd = d
            for (i in intArrayOf(0, 4, 8, 12)) {
                a = (a + ((b and c) or (b.inv() and d)) + x[i]).rotateLeft(3)
                d = (d + ((a and b) or (a.inv() and c)) + x[i + 1]).rotateLeft(7)
                c = (c + ((d and a) or (d.inv() and b)) + x[i + 2]).rotateLeft(11)
                b = (b + ((c and d) or (c.inv() and a)) + x[i + 3]).rotateLeft(19)
            }
            for (i in intArrayOf(0, 1, 2, 3)) {
                a = (a + ((b and c) or (b and d) or (c and d)) + x[i] + 0x5A827999).rotateLeft(3)
                d = (d + ((a and b) or (a and c) or (b and c)) + x[i + 4] + 0x5A827999).rotateLeft(5)
                c = (c + ((d and a) or (d and b) or (a and b)) + x[i + 8] + 0x5A827999).rotateLeft(9)
                b = (b + ((c and d) or (c and a) or (d and a)) + x[i + 12] + 0x5A827999).rotateLeft(13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = (a + (b xor c xor d) + x[i] + 0x6ED9EBA1).rotateLeft(3)
                d = (d + (a xor b xor c) + x[i + 8] + 0x6ED9EBA1).rotateLeft(9)
                c = (c + (d xor a xor b) + x[i + 4] + 0x6ED9EBA1).rotateLeft(11)
                b = (b + (c xor d xor a) + x[i + 12] + 0x6ED9EBA1).rotateLeft(15)
            }
            a += aa
            b += bb
            c += cc
            d += dd
            block += 64
        }
        val out = ByteArray(16)
        for ((index, word) in intArrayOf(a, b, c, d).withIndex()) {
            for (i in 0 until 4) out[index * 4 + i] = (word ushr (8 * i)).toByte()
        }
        return out
    }
}
