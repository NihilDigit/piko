package dev.piko.desktop.media

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * mpv 运行库里随带的 FFmpeg（libavutil、libavcodec、libavformat），经 FFM 直调，只用于流复制的转封装。
 *
 * 结构体字段按偏移读写（[FfmpegLayout]），偏移取自 FFmpeg 8.0.1 的头文件。FFmpeg 只在主版本号变化时改动公开结构体
 * 已有字段的位置，所以载入时核对三个库的主版本，对不上就拒绝，而不是读错内存。mpv 运行库升级时按 desktopApp/CLAUDE.md 核对。
 *
 * 不用 JavaCV 一类的现成绑定：它们自带一份 FFmpeg，安装包要再多几十 MB，mpv 运行库里已有一份完整的。
 */
internal class Ffmpeg private constructor(avutil: SymbolLookup, avcodec: SymbolLookup, avformat: SymbolLookup) {
    private val linker = Linker.nativeLinker()

    private fun SymbolLookup.function(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(find(name).orElseThrow { FfmpegException("FFmpeg 缺少函数 $name") }, descriptor)

    val avutilVersion = avutil.function("avutil_version", FunctionDescriptor.of(JAVA_INT))
    val avcodecVersion = avcodec.function("avcodec_version", FunctionDescriptor.of(JAVA_INT))
    val avformatVersion = avformat.function("avformat_version", FunctionDescriptor.of(JAVA_INT))

    val malloc = avutil.function("av_malloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG))
    val freep = avutil.function("av_freep", FunctionDescriptor.ofVoid(ADDRESS))
    val strerror = avutil.function("av_strerror", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG))
    val dictSet = avutil.function("av_dict_set", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    val dictCopy = avutil.function("av_dict_copy", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    val dictFree = avutil.function("av_dict_free", FunctionDescriptor.ofVoid(ADDRESS))
    val rescaleRnd = avutil.function("av_rescale_rnd", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT))

    val packetAlloc = avcodec.function("av_packet_alloc", FunctionDescriptor.of(ADDRESS))
    val packetFree = avcodec.function("av_packet_free", FunctionDescriptor.ofVoid(ADDRESS))
    val packetUnref = avcodec.function("av_packet_unref", FunctionDescriptor.ofVoid(ADDRESS))
    val parametersCopy = avcodec.function("avcodec_parameters_copy", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

    val allocContext = avformat.function("avformat_alloc_context", FunctionDescriptor.of(ADDRESS))
    val openInput = avformat.function("avformat_open_input", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    val findStreamInfo = avformat.function("avformat_find_stream_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    val closeInput = avformat.function("avformat_close_input", FunctionDescriptor.ofVoid(ADDRESS))
    val findBestStream = avformat.function(
        "av_find_best_stream",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
    )
    val readFrame = avformat.function("av_read_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    val seekFrame = avformat.function("av_seek_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT))
    val allocOutputContext = avformat.function(
        "avformat_alloc_output_context2",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    val queryCodec = avformat.function("avformat_query_codec", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
    val newStream = avformat.function("avformat_new_stream", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    val avioOpen = avformat.function("avio_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    val avioClosep = avformat.function("avio_closep", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val avioAllocContext = avformat.function(
        "avio_alloc_context",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    val avioContextFree = avformat.function("avio_context_free", FunctionDescriptor.ofVoid(ADDRESS))
    val writeHeader = avformat.function("avformat_write_header", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    val interleavedWriteFrame = avformat.function("av_interleaved_write_frame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    val writeTrailer = avformat.function("av_write_trailer", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val freeContext = avformat.function("avformat_free_context", FunctionDescriptor.ofVoid(ADDRESS))

    init {
        val versions = listOf(
            Triple("avutil", avutilVersion, AVUTIL_MAJOR),
            Triple("avcodec", avcodecVersion, AVCODEC_MAJOR),
            Triple("avformat", avformatVersion, AVFORMAT_MAJOR),
        )
        for ((name, version, expected) in versions) {
            val major = (version.invokeWithArguments() as Int) ushr 16
            if (major != expected) {
                throw FfmpegException("$name 的主版本是 $major，转封装按 $expected 的结构体布局编写")
            }
        }
    }

    /** FFmpeg 的错误码换成文字，例如 Invalid data found when processing input。 */
    fun describe(code: Int): String = Arena.ofConfined().use { arena ->
        val buffer = arena.allocate(256)
        val found = strerror.invokeWithArguments(code, buffer, 256L) as Int
        if (found < 0) "错误码 $code" else buffer.getString(0)
    }

    fun check(code: Int, what: String): Int {
        if (code < 0) throw FfmpegException("$what 失败：${describe(code)}")
        return code
    }

    companion object {
        const val AVUTIL_MAJOR = 60
        const val AVCODEC_MAJOR = 62
        const val AVFORMAT_MAJOR = 62

        /**
         * 载入 [directory] 里的三个库。各系统的文件名与 mpv 运行库的打包方式一致：Windows 是带主版本号的 DLL，
         * Linux 取 SONAME（bundledAppResources 只留这一份），macOS 取带主版本号的 dylib。依赖都在同一目录：
         * Linux 的库带 `$ORIGIN` 的 RUNPATH，macOS 的依赖写的是 `@loader_path`，Windows 经 [WindowsDllLoader] 指定。
         */
        fun load(directory: File): Ffmpeg {
            val os = System.getProperty("os.name").orEmpty()
            val names = when {
                os.startsWith("Windows") -> listOf("avutil-$AVUTIL_MAJOR.dll", "avcodec-$AVCODEC_MAJOR.dll", "avformat-$AVFORMAT_MAJOR.dll")
                os.startsWith("Mac") -> listOf("libavutil.$AVUTIL_MAJOR.dylib", "libavcodec.$AVCODEC_MAJOR.dylib", "libavformat.$AVFORMAT_MAJOR.dylib")
                else -> listOf("libavutil.so.$AVUTIL_MAJOR", "libavcodec.so.$AVCODEC_MAJOR", "libavformat.so.$AVFORMAT_MAJOR")
            }
            val files = names.map { name ->
                directory.resolve(name).takeIf { it.isFile } ?: throw FfmpegException("找不到 FFmpeg 库 $name")
            }
            if (os.startsWith("Windows")) files.forEach(WindowsDllLoader::load)
            val (avutil, avcodec, avformat) = files.map { SymbolLookup.libraryLookup(it.toPath(), Arena.global()) }
            return Ffmpeg(avutil, avcodec, avformat)
        }

        private val bundledResult: Result<Ffmpeg> by lazy {
            runCatching {
                val resources = System.getProperty("compose.application.resources.dir")
                    ?: throw FfmpegException("没有应用资源目录，找不到 FFmpeg")
                load(File(resources, "mpv"))
            }
        }

        /** 应用资源目录 mpv 子目录里的那一份，与播放器共用。载不了时抛出原因，结果只求一次。 */
        fun bundled(): Ffmpeg = bundledResult.getOrThrow()
    }
}

internal class FfmpegException(message: String) : Exception(message)

/**
 * Windows 上按完整路径载入 DLL 时，系统只在程序目录、系统目录与 PATH 里找它的依赖，不找它所在的目录；
 * avformat 依赖同目录的 avcodec、zlib 等，SymbolLookup.libraryLookup 直接载会报找不到模块。
 * 先以 LOAD_WITH_ALTERED_SEARCH_PATH 载一次，依赖就从 DLL 所在目录找；之后 libraryLookup 拿到的是已载入的同一个模块。
 * 不靠 mediamp 设的 SetDllDirectoryW：转封装可能先于播放器用到这些库，测试进程里也没有它。
 */
private object WindowsDllLoader {
    private const val LOAD_WITH_ALTERED_SEARCH_PATH = 0x00000008

    private val loadLibraryEx: MethodHandle by lazy {
        val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
        Linker.nativeLinker().downcallHandle(
            kernel32.find("LoadLibraryExW").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
        )
    }

    fun load(file: File) {
        Arena.ofConfined().use { arena ->
            val path = file.absolutePath + "\u0000"
            val wide = arena.allocate(path.length * 2L)
            path.forEachIndexed { index, char -> wide.setAtIndex(JAVA_CHAR, index.toLong(), char) }
            val module = loadLibraryEx.invokeWithArguments(wide, MemorySegment.NULL, LOAD_WITH_ALTERED_SEARCH_PATH) as MemorySegment
            if (module == MemorySegment.NULL) throw FfmpegException("无法载入 ${file.name}")
        }
    }
}

/** FFmpeg 8.0.1 公开结构体里用到的字段偏移，64 位平台通用（字段只有指针、int 与 int64）。 */
internal object FfmpegLayout {
    // AVFormatContext
    const val FORMAT_OFORMAT = 16L
    const val FORMAT_PB = 32L
    const val FORMAT_NB_STREAMS = 44L
    const val FORMAT_STREAMS = 48L
    const val FORMAT_START_TIME = 96L
    const val FORMAT_SIZE = 112L

    // AVStream
    const val STREAM_CODECPAR = 16L
    const val STREAM_TIME_BASE = 32L
    const val STREAM_DISPOSITION = 64L
    const val STREAM_METADATA = 80L
    const val STREAM_SIZE = 88L

    // AVCodecParameters
    const val PAR_CODEC_TYPE = 0L
    const val PAR_CODEC_ID = 4L
    const val PAR_CODEC_TAG = 8L
    const val PAR_SIZE = 12L

    // AVPacket
    const val PACKET_PTS = 8L
    const val PACKET_DTS = 16L
    const val PACKET_STREAM_INDEX = 36L
    const val PACKET_FLAGS = 40L
    const val PACKET_DURATION = 64L
    const val PACKET_POS = 72L
    const val PACKET_SIZE = 80L

    // AVIOContext
    const val AVIO_BUFFER = 8L
    const val AVIO_SIZE = 16L

    const val AVMEDIA_TYPE_VIDEO = 0
    const val AVMEDIA_TYPE_AUDIO = 1
    const val AV_CODEC_ID_HEVC = 173
    const val AV_DISPOSITION_ATTACHED_PIC = 0x400
    const val AV_PKT_FLAG_KEY = 1
    const val AV_NOPTS_VALUE = Long.MIN_VALUE
    const val AVSEEK_FLAG_BACKWARD = 1
    const val AVSEEK_SIZE = 0x10000
    const val AVSEEK_FORCE = 0x20000
    const val AVIO_FLAG_WRITE = 2
    const val AVERROR_EOF = -541478725
    const val AVERROR_EXIT = -1414092869
    const val AVERROR_EIO = -5
    const val AV_ROUND_NEAR_INF_PASS_MINMAX = 5 or 8192
    const val FF_COMPLIANCE_NORMAL = 0

    /** MKTAG('h','v','c','1')：QuickTime 与系统播放器只认 hvc1 的 HEVC，FFmpeg 默认写 hev1。 */
    const val TAG_HVC1 = 0x31637668
}
