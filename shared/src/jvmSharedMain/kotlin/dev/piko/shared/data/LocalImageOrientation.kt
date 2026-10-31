package dev.piko.shared.data

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JPEG EXIF 的方向只需读取 APP1，不解码像素，也不读取整个原图。 */
fun localImageSwapsAxes(source: InputStream): Boolean = runCatching {
    val input = DataInputStream(source)
    if (input.readUnsignedShort() != 0xffd8) return@runCatching false
    repeat(256) {
        if (input.readUnsignedByte() != 0xff) return@runCatching false
        var marker = input.readUnsignedByte()
        while (marker == 0xff) marker = input.readUnsignedByte()
        if (marker == 0xda || marker == 0xd9) return@runCatching false
        if (marker in 0xd0..0xd8 || marker == 1) return@repeat
        val length = input.readUnsignedShort() - 2
        if (length < 0) return@runCatching false
        if (marker != 0xe1) {
            var remaining = length
            while (remaining > 0) {
                val skipped = input.skipBytes(remaining)
                if (skipped == 0) return@runCatching false
                remaining -= skipped
            }
            return@repeat
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        if (bytes.size < 14 || !bytes.copyOfRange(0, 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) return@repeat
        val buffer = ByteBuffer.wrap(bytes, 6, bytes.size - 6).slice()
        val little = buffer.get(0) == 0x49.toByte() && buffer.get(1) == 0x49.toByte()
        val big = buffer.get(0) == 0x4d.toByte() && buffer.get(1) == 0x4d.toByte()
        if (!little && !big) return@runCatching false
        buffer.order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        if (buffer.getShort(2).toInt() != 42) return@runCatching false
        val offset = buffer.getInt(4)
        if (offset < 8 || offset > buffer.limit() - 2) return@runCatching false
        val count = buffer.getShort(offset).toInt() and 0xffff
        repeat(count) { index ->
            val entry = offset.toLong() + 2 + index * 12L
            if (entry > buffer.limit() - 12) return@runCatching false
            val at = entry.toInt()
            if ((buffer.getShort(at).toInt() and 0xffff) == 0x112 && buffer.getShort(at + 2).toInt() == 3 && buffer.getInt(at + 4) == 1) {
                return@runCatching (buffer.getShort(at + 8).toInt() and 0xffff) in 5..8
            }
        }
    }
    false
}.getOrDefault(false)
