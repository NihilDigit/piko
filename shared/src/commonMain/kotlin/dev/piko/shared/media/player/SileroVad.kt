package dev.piko.shared.media.player

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 不可变的 Silero VAD v5（16 kHz）权重集。
 *
 * 从打包的 IEEE 754 FP16 扁平权重文件（`silero_vad_v5_16k.fp16`，约 605 KiB，MIT 协议）一次性展开为 FP32，
 * 供多个 [SileroVad] 实例零拷贝共享。
 */
class SileroVadWeights private constructor(
    internal val stftBasis: FloatArray,     // [258, 256]
    internal val conv0W: FloatArray,        // [128, 129, 3]
    internal val conv0B: FloatArray,        // [128]
    internal val conv1W: FloatArray,        // [64, 128, 3]
    internal val conv1B: FloatArray,        // [64]
    internal val conv2W: FloatArray,        // [64, 64, 3]
    internal val conv2B: FloatArray,        // [64]
    internal val conv3W: FloatArray,        // [128, 64, 3]
    internal val conv3B: FloatArray,        // [128]
    internal val lstmWih: FloatArray,       // [512, 128]
    internal val lstmWhh: FloatArray,       // [512, 128]
    internal val lstmBias: FloatArray,      // [512] = bias_ih + bias_hh
    internal val headW: FloatArray,         // [128]
    internal val headB: Float,
) {
    fun newDetector(): SileroVad = SileroVad(this)

    companion object {
        private const val HEADER_SIZE = 8
        private const val TOTAL_PARAMS =
            258 * 256 +
                128 * 129 * 3 + 128 +
                64 * 128 * 3 + 64 +
                64 * 64 * 3 + 64 +
                128 * 64 * 3 + 128 +
                512 * 128 + 512 * 128 + 512 + 512 +
                128 + 1
        private const val EXPECTED_BYTES = HEADER_SIZE + TOTAL_PARAMS * 2

        fun fromFp16(bytes: ByteArray): SileroVadWeights {
            require(bytes.size == EXPECTED_BYTES) {
                "Invalid Silero VAD weight size: ${bytes.size} (expected $EXPECTED_BYTES)"
            }
            require(
                bytes[0] == 'S'.code.toByte() &&
                    bytes[1] == 'V'.code.toByte() &&
                    bytes[2] == '5'.code.toByte() &&
                    bytes[3] == 0.toByte(),
            ) { "Invalid Silero VAD weight magic" }
            val version = (bytes[4].toInt() and 0xFF) or
                ((bytes[5].toInt() and 0xFF) shl 8) or
                ((bytes[6].toInt() and 0xFF) shl 16) or
                ((bytes[7].toInt() and 0xFF) shl 24)
            require(version == 1) { "Unsupported Silero VAD weight version: $version" }

            var pos = HEADER_SIZE
            fun readTensor(count: Int): FloatArray {
                val out = FloatArray(count)
                for (i in 0 until count) {
                    val lo = bytes[pos++].toInt() and 0xFF
                    val hi = bytes[pos++].toInt() and 0xFF
                    out[i] = fp16ToFloat(lo or (hi shl 8))
                }
                return out
            }

            val stftBasis = readTensor(258 * 256)
            val conv0W = readTensor(128 * 129 * 3)
            val conv0B = readTensor(128)
            val conv1W = readTensor(64 * 128 * 3)
            val conv1B = readTensor(64)
            val conv2W = readTensor(64 * 64 * 3)
            val conv2B = readTensor(64)
            val conv3W = readTensor(128 * 64 * 3)
            val conv3B = readTensor(128)
            val lstmWih = readTensor(512 * 128)
            val lstmWhh = readTensor(512 * 128)
            val biasIh = readTensor(512)
            val biasHh = readTensor(512)
            val lstmBias = FloatArray(512) { idx -> biasIh[idx] + biasHh[idx] }
            val headW = readTensor(128)
            val headB = readTensor(1)[0]

            return SileroVadWeights(
                stftBasis = stftBasis,
                conv0W = conv0W,
                conv0B = conv0B,
                conv1W = conv1W,
                conv1B = conv1B,
                conv2W = conv2W,
                conv2B = conv2B,
                conv3W = conv3W,
                conv3B = conv3B,
                lstmWih = lstmWih,
                lstmWhh = lstmWhh,
                lstmBias = lstmBias,
                headW = headW,
                headB = headB,
            )
        }

        private fun fp16ToFloat(bits: Int): Float {
            val sign = (bits ushr 15) and 0x1
            val exp = (bits ushr 10) and 0x1F
            val frac = bits and 0x3FF
            val fBits = when (exp) {
                0 -> {
                    if (frac == 0) {
                        sign shl 31
                    } else {
                        var m = frac
                        var e = 127 - 14
                        while ((m and 0x400) == 0) {
                            m = m shl 1
                            e--
                        }
                        m = m and 0x3FF
                        (sign shl 31) or (e shl 23) or (m shl 13)
                    }
                }
                31 -> (sign shl 31) or (0xFF shl 23) or (frac shl 13)
                else -> (sign shl 31) or ((exp + 112) shl 23) or (frac shl 13)
            }
            return Float.fromBits(fBits)
        }
    }
}

/**
 * 纯 Kotlin Silero VAD v5（16 kHz）有状态流式人声活动检测器。
 *
 * 每次处理 [WINDOW_SAMPLES]（512 采样点 = 32 ms）单声道浮点音频（范围 `-1f..1f`），
 * 内部维护 64 采样点上下文与 128 维 LSTM 状态，全程复用预分配临时缓冲，零逐帧堆分配。
 */
class SileroVad internal constructor(
    private val w: SileroVadWeights,
) {
    private val context = FloatArray(CONTEXT_SAMPLES)
    private val h = FloatArray(LSTM_HIDDEN)
    private val c = FloatArray(LSTM_HIDDEN)

    // 预分配复用缓冲区，避免高频推理产生 GC 压力
    private val padded = FloatArray(PADDED_LEN)       // 64 + 512 + 64 = 640
    private val stftMag = FloatArray(129 * 4)         // [129, 4]
    private val enc0 = FloatArray(128 * 4)            // [128, 4]
    private val enc1 = FloatArray(64 * 2)             // [64, 2]
    private val enc2 = FloatArray(64 * 1)             // [64, 1]
    private val enc3 = FloatArray(128)                // [128]
    private val gates = FloatArray(512)

    /** 仅在真实解码器 seek 或切换音轨时重置；服务端 PAUSE 而本地仍连续扫描时不得重置。 */
    fun reset() {
        context.fill(0f)
        h.fill(0f)
        c.fill(0f)
    }

    /**
     * 对一段 16 kHz 单声道 512 采样点窗口（`-1f..1f`）执行前向推理，返回人声概率 `0f..1f`。
     */
    fun scoreWindow(samples: FloatArray, offset: Int = 0): Float {
        // 1. 拼接 [64 context | 512 samples] 并做右端 64 点反射填充（ONNX Pad mode=reflect）
        context.copyInto(padded, destinationOffset = 0)
        samples.copyInto(padded, destinationOffset = CONTEXT_SAMPLES, startIndex = offset, endIndex = offset + WINDOW_SAMPLES)
        // 更新下一帧的 64 采样点上下文
        samples.copyInto(context, destinationOffset = 0, startIndex = offset + WINDOW_SAMPLES - CONTEXT_SAMPLES, endIndex = offset + WINDOW_SAMPLES)
        for (i in 0 until CONTEXT_SAMPLES) {
            padded[INPUT_LEN + i] = padded[INPUT_LEN - 2 - i]
        }

        // 2. STFT: Conv1d(in=1, out=258, k=256, stride=128) -> split real/imag -> magnitude [129, 4]
        val basis = w.stftBasis
        for (t in 0 until 4) {
            val baseIn = t * 128
            for (bin in 0 until 129) {
                val reOff = bin * 256
                val imOff = (bin + 129) * 256
                var re = 0f
                var im = 0f
                for (k in 0 until 256) {
                    val v = padded[baseIn + k]
                    re += v * basis[reOff + k]
                    im += v * basis[imOff + k]
                }
                stftMag[bin * 4 + t] = sqrt(re * re + im * im)
            }
        }

        // 3. Encoder Conv1d x4 + ReLU
        conv1dRelu(stftMag, 129, 4, w.conv0W, w.conv0B, enc0, 128, 4, stride = 1)
        conv1dRelu(enc0, 128, 4, w.conv1W, w.conv1B, enc1, 64, 2, stride = 2)
        conv1dRelu(enc1, 64, 2, w.conv2W, w.conv2B, enc2, 64, 1, stride = 2)
        conv1dRelu(enc2, 64, 1, w.conv3W, w.conv3B, enc3, 128, 1, stride = 1)

        // 4. Single-step LSTMCell (hidden=128)
        val wih = w.lstmWih
        val whh = w.lstmWhh
        val bias = w.lstmBias
        for (r in 0 until 512) {
            val rowOff = r * 128
            var sum = bias[r]
            for (k in 0 until 128) {
                sum += enc3[k] * wih[rowOff + k] + h[k] * whh[rowOff + k]
            }
            gates[r] = sum
        }
        for (j in 0 until 128) {
            val ig = sigmoid(gates[j])
            val fg = sigmoid(gates[128 + j])
            val gg = tanh(gates[256 + j].toDouble()).toFloat()
            val og = sigmoid(gates[384 + j])
            val cNext = fg * c[j] + ig * gg
            c[j] = cNext
            h[j] = og * tanh(cNext.toDouble()).toFloat()
        }

        // 5. Output head: sigmoid(dot(relu(h), headW) + headB)
        var logit = w.headB
        val headW = w.headW
        for (j in 0 until 128) {
            val hv = h[j]
            if (hv > 0f) logit += hv * headW[j]
        }
        return sigmoid(logit)
    }

    private fun conv1dRelu(
        input: FloatArray,
        inCh: Int,
        inLen: Int,
        weight: FloatArray,
        bias: FloatArray,
        output: FloatArray,
        outCh: Int,
        outLen: Int,
        stride: Int,
    ) {
        val inChK = inCh * 3
        for (oc in 0 until outCh) {
            val wBase = oc * inChK
            val bVal = bias[oc]
            val outBase = oc * outLen
            for (t in 0 until outLen) {
                val center = t * stride
                val kStart = if (center == 0) 1 else 0
                val kEnd = if (center == inLen - 1) 2 else 3
                var sum = bVal
                for (ic in 0 until inCh) {
                    val inBase = ic * inLen + center - 1
                    val wOff = wBase + ic * 3
                    for (k in kStart until kEnd) {
                        sum += input[inBase + k] * weight[wOff + k]
                    }
                }
                output[outBase + t] = if (sum > 0f) sum else 0f
            }
        }
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SAMPLES = 512
        const val WINDOW_MS = 32L
        private const val CONTEXT_SAMPLES = 64
        private const val INPUT_LEN = CONTEXT_SAMPLES + WINDOW_SAMPLES
        private const val PADDED_LEN = INPUT_LEN + CONTEXT_SAMPLES
        private const val LSTM_HIDDEN = 128
    }
}
