import AudioToolbox
import Foundation
import PikoKit

/// iOS 原生 AI 字幕音频解码器：
/// - 常规容器（MP4/MOV/M4A/MP3/AAC/WAV/FLAC 等）：通过 `AudioFileOpenWithCallbacks` +
///   `ExtAudioFileWrapAudioFileID` 直接随机读取并重采样为 16 kHz 单声道 `pcm_s16le`；
/// - 服务端转码 MPEG-TS 流（188 字节包）：按需拆包提取音频 PES 内的 ADTS AAC 负载，
///   交由 `AudioFileOpenWithCallbacks(kAudioFileAAC_ADTSType)` + `ExtAudioFile` 解码重采样。
final class IosNativeAiSubtitleDecoder: NSObject, IosNativePcmDecoderDelegate {
    func open(source: IosRandomAccessByteSource, audioStreamIndex: Int32) -> IosNativePcmReader? {
        if let direct = ExtAudioPcmReader(source: source, fileTypeHint: 0) {
            return direct
        }
        // 探测是否为 MPEG-TS（188 字节对齐同步字节 0x47）
        if isMpegTsSource(source), let tsReader = MpegTsAdtsPcmReader(source: source) {
            return tsReader
        }
        return nil
    }

    private func isMpegTsSource(_ source: IosRandomAccessByteSource) -> Bool {
        guard source.size >= 188 * 2, let head = source.readAt(position: 0, maxBytes: 188 * 3) else {
            return false
        }
        let bytes = [UInt8](head)
        guard bytes.count >= 188 * 2 else { return false }
        return bytes[0] == 0x47 && bytes[188] == 0x47
    }
}

private let targetSampleRate: Double = 16_000.0
private let targetFrameSamples: UInt32 = 1_600 // 100 ms @ 16 kHz
private let bytesPerSample: UInt32 = 2

private func makeTargetPcmAsbd() -> AudioStreamBasicDescription {
    AudioStreamBasicDescription(
        mSampleRate: targetSampleRate,
        mFormatID: kAudioFormatLinearPCM,
        mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
        mBytesPerPacket: bytesPerSample,
        mFramesPerPacket: 1,
        mBytesPerFrame: bytesPerSample,
        mChannelsPerFrame: 1,
        mBitsPerChannel: 16,
        mReserved: 0
    )
}

private final class CallbackContext {
    let readAt: (Int64, Int) -> Data?
    let totalSize: () -> Int64

    init(readAt: @escaping (Int64, Int) -> Data?, totalSize: @escaping () -> Int64) {
        self.readAt = readAt
        self.totalSize = totalSize
    }
}

private let audioFileReadProc: AudioFile_ReadProc = { inClientData, inPosition, requestCount, buffer, actualCount in
    let ctx = Unmanaged<CallbackContext>.fromOpaque(inClientData).takeUnretainedValue()
    guard requestCount > 0 else {
        actualCount.pointee = 0
        return noErr
    }
    guard let data = ctx.readAt(inPosition, Int(requestCount)), !data.isEmpty else {
        actualCount.pointee = 0
        return kAudioFileEndOfFileError
    }
    let count = min(Int(requestCount), data.count)
    data.copyBytes(to: buffer.assumingMemoryBound(to: UInt8.self), count: count)
    actualCount.pointee = UInt32(count)
    return noErr
}

private let audioFileGetSizeProc: AudioFile_GetSizeProc = { inClientData in
    let ctx = Unmanaged<CallbackContext>.fromOpaque(inClientData).takeUnretainedValue()
    return ctx.totalSize()
}

/// 基于 `AudioFileOpenWithCallbacks` + `ExtAudioFileWrapAudioFileID` 的直解读取器。
private final class ExtAudioPcmReader: NSObject, IosNativePcmReader {
    private var contextRef: Unmanaged<CallbackContext>?
    private var audioFileId: AudioFileID?
    private var extAudioFile: ExtAudioFileRef?
    private var fileSampleRate: Double = targetSampleRate
    private var currentPositionMs: Int64 = 0
    private var closed = false

    convenience init?(source: IosRandomAccessByteSource, fileTypeHint: AudioFileTypeID) {
        self.init(
            readAt: { pos, len in source.readAt(position: pos, maxBytes: Int32(len)) },
            totalSize: { source.size },
            fileTypeHint: fileTypeHint
        )
    }

    init?(readAt: @escaping (Int64, Int) -> Data?, totalSize: @escaping () -> Int64, fileTypeHint: AudioFileTypeID) {
        super.init()
        let ctx = CallbackContext(readAt: readAt, totalSize: totalSize)
        let unmanaged = Unmanaged.passRetained(ctx)
        var afid: AudioFileID?
        let openStatus = AudioFileOpenWithCallbacks(
            unmanaged.toOpaque(),
            audioFileReadProc,
            nil,
            audioFileGetSizeProc,
            nil,
            fileTypeHint,
            &afid
        )
        guard openStatus == noErr, let validAfid = afid else {
            unmanaged.release()
            return nil
        }
        var extRef: ExtAudioFileRef?
        let wrapStatus = ExtAudioFileWrapAudioFileID(validAfid, false, &extRef)
        guard wrapStatus == noErr, let validExt = extRef else {
            AudioFileClose(validAfid)
            unmanaged.release()
            return nil
        }

        var fileFormat = AudioStreamBasicDescription()
        var fileFormatSize = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        if ExtAudioFileGetProperty(
            validExt,
            kExtAudioFileProperty_FileDataFormat,
            &fileFormatSize,
            &fileFormat
        ) == noErr, fileFormat.mSampleRate > 0 {
            self.fileSampleRate = fileFormat.mSampleRate
        }

        var clientFormat = makeTargetPcmAsbd()
        let clientSize = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        let setStatus = ExtAudioFileSetProperty(
            validExt,
            kExtAudioFileProperty_ClientDataFormat,
            clientSize,
            &clientFormat
        )
        guard setStatus == noErr else {
            ExtAudioFileDispose(validExt)
            AudioFileClose(validAfid)
            unmanaged.release()
            return nil
        }

        self.contextRef = unmanaged
        self.audioFileId = validAfid
        self.extAudioFile = validExt
    }

    func seekTo(positionMs: Int64) {
        guard !closed, let ext = extAudioFile else { return }
        let clampedMs = max(0, positionMs)
        let targetFrame = Int64((Double(clampedMs) / 1_000.0) * fileSampleRate)
        if ExtAudioFileSeek(ext, targetFrame) == noErr {
            currentPositionMs = clampedMs
        }
    }

    func readFrame() -> IosNativePcmFrame? {
        guard !closed, let ext = extAudioFile else { return nil }
        let byteCapacity = Int(targetFrameSamples * bytesPerSample)
        var data = Data(count: byteCapacity)
        var framesToRead = targetFrameSamples
        let status: OSStatus = data.withUnsafeMutableBytes { rawBuf in
            var abl = AudioBufferList(
                mNumberBuffers: 1,
                mBuffers: AudioBuffer(
                    mNumberChannels: 1,
                    mDataByteSize: UInt32(byteCapacity),
                    mData: rawBuf.baseAddress
                )
            )
            return ExtAudioFileRead(ext, &framesToRead, &abl)
        }
        guard status == noErr, framesToRead > 0 else { return nil }
        let actualBytes = Int(framesToRead * bytesPerSample)
        if actualBytes < data.count {
            data.removeSubrange(actualBytes..<data.count)
        }
        let frameStartMs = currentPositionMs
        currentPositionMs += Int64((Double(framesToRead) / targetSampleRate) * 1_000.0)
        return IosNativePcmFrame(positionMs: frameStartMs, data: data)
    }

    func close() {
        guard !closed else { return }
        closed = true
        if let ext = extAudioFile {
            ExtAudioFileDispose(ext)
            extAudioFile = nil
        }
        if let afid = audioFileId {
            AudioFileClose(afid)
            audioFileId = nil
        }
        if let ref = contextRef {
            ref.release()
            contextRef = nil
        }
    }

    deinit {
        close()
    }
}

/// 面向 PikPak 服务端 MPEG-TS 转码流的轻量 ADTS AAC 拆包 + `ExtAudioFile` 解码器。
/// 按需扫描 188 字节 TS 包并过滤出音频 PID 的 ADTS 负载，再交由 CoreAudio 解码重采样。
private final class MpegTsAdtsPcmReader: NSObject, IosNativePcmReader {
    private let source: IosRandomAccessByteSource
    private var audioPid: UInt16?
    private var tsOffset: Int64 = 0
    private var adtsBuffer = Data()
    private var adtsBasePosMs: Int64 = 0
    private var currentPosMs: Int64 = 0
    private var firstPtsMs: Int64?
    private var eof = false
    private var closed = false
    private var activeDecoder: ExtAudioPcmReader?

    init?(source: IosRandomAccessByteSource) {
        self.source = source
        super.init()
        guard refillAdtsChunk(minBytes: 16_384) else {
            return nil
        }
        guard rebuildDecoderFromBuffer() else {
            return nil
        }
    }

    func seekTo(positionMs: Int64) {
        guard !closed else { return }
        let clampedMs = max(0, positionMs)
        activeDecoder?.close()
        activeDecoder = nil
        adtsBuffer.removeAll(keepingCapacity: true)
        eof = false
        // 按总字节数粗略估算 TS 偏移，再对齐 188 字节边界（随后由 PES PTS 精确校准）
        let approxByte = Int64(Double(source.size) * min(0.98, max(0.0, Double(clampedMs) / 3_600_000.0)))
        tsOffset = (approxByte / 188) * 188
        currentPosMs = clampedMs
        adtsBasePosMs = clampedMs
        _ = refillAdtsChunk(minBytes: 16_384)
        _ = rebuildDecoderFromBuffer()
    }

    func readFrame() -> IosNativePcmFrame? {
        guard !closed else { return nil }
        while !closed {
            if let decoder = activeDecoder, let frame = decoder.readFrame() {
                let adjustedMs = adtsBasePosMs + frame.positionMs
                currentPosMs = adjustedMs + 100
                return IosNativePcmFrame(positionMs: adjustedMs, data: frame.data)
            }
            activeDecoder?.close()
            activeDecoder = nil
            adtsBuffer.removeAll(keepingCapacity: true)
            if eof { return nil }
            adtsBasePosMs = currentPosMs
            guard refillAdtsChunk(minBytes: 32_768), rebuildDecoderFromBuffer() else {
                return nil
            }
        }
        return nil
    }

    func close() {
        guard !closed else { return }
        closed = true
        activeDecoder?.close()
        activeDecoder = nil
        adtsBuffer.removeAll()
    }

    deinit {
        close()
    }

    private func rebuildDecoderFromBuffer() -> Bool {
        guard !adtsBuffer.isEmpty else { return false }
        let chunk = adtsBuffer
        let total = Int64(chunk.count)
        let reader = ExtAudioPcmReader(
            readAt: { pos, len in
                guard pos >= 0, pos < total, len > 0 else { return nil }
                let start = Int(pos)
                let end = min(chunk.count, start + len)
                return chunk.subdata(in: start..<end)
            },
            totalSize: { total },
            fileTypeHint: kAudioFileAAC_ADTSType
        )
        activeDecoder = reader
        return reader != nil
    }

    private func refillAdtsChunk(minBytes: Int) -> Bool {
        let readBlock = 188 * 256
        while adtsBuffer.count < minBytes && tsOffset < source.size {
            let remaining = Int(min(Int64(readBlock), source.size - tsOffset))
            guard remaining >= 188, let raw = source.readAt(position: tsOffset, maxBytes: Int32(remaining)) else {
                eof = true
                break
            }
            let bytes = [UInt8](raw)
            let packetCount = bytes.count / 188
            guard packetCount > 0 else {
                eof = true
                break
            }
            tsOffset += Int64(packetCount * 188)

            for i in 0..<packetCount {
                let base = i * 188
                guard bytes[base] == 0x47 else { continue }
                let pusi = (bytes[base + 1] & 0x40) != 0
                let pid = (UInt16(bytes[base + 1] & 0x1F) << 8) | UInt16(bytes[base + 2])
                let afc = (bytes[base + 3] >> 4) & 0x03
                guard afc == 1 || afc == 3 else { continue }
                var pos = base + 4
                if afc == 3 {
                    let adaptLen = Int(bytes[pos])
                    pos += 1 + adaptLen
                }
                let end = base + 188
                guard pos < end else { continue }

                if pusi && end - pos >= 9 && bytes[pos] == 0x00 && bytes[pos + 1] == 0x00 && bytes[pos + 2] == 0x01 {
                    let streamId = bytes[pos + 3]
                    // 0xC0..0xDF 为 MPEG/AAC 音频 PES 流
                    if streamId >= 0xC0 && streamId <= 0xDF {
                        if audioPid == nil {
                            audioPid = pid
                        }
                        if pid == audioPid {
                            let flags = bytes[pos + 7]
                            let headerDataLen = Int(bytes[pos + 8])
                            if (flags & 0x80) != 0 && end - pos >= 14 {
                                let ptsMs = parsePtsMs(bytes, offset: pos + 9)
                                if firstPtsMs == nil {
                                    firstPtsMs = ptsMs
                                }
                                if adtsBuffer.isEmpty, let basePts = firstPtsMs {
                                    adtsBasePosMs = max(0, ptsMs - basePts)
                                }
                            }
                            pos += 9 + headerDataLen
                            if pos < end {
                                adtsBuffer.append(contentsOf: bytes[pos..<end])
                            }
                        }
                    }
                } else if let targetPid = audioPid, pid == targetPid {
                    adtsBuffer.append(contentsOf: bytes[pos..<end])
                }
            }
        }
        if tsOffset >= source.size {
            eof = true
        }
        return !adtsBuffer.isEmpty
    }

    private func parsePtsMs(_ bytes: [UInt8], offset: Int) -> Int64 {
        let p0 = Int64((bytes[offset] >> 1) & 0x07) << 30
        let p1 = Int64(bytes[offset + 1]) << 22
        let p2 = Int64((bytes[offset + 2] >> 1) & 0x7F) << 15
        let p3 = Int64(bytes[offset + 3]) << 7
        let p4 = Int64((bytes[offset + 4] >> 1) & 0x7F)
        let pts90k = p0 | p1 | p2 | p3 | p4
        return pts90k / 90
    }
}
