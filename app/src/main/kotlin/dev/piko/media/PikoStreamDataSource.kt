package dev.piko.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 包装 PikPakStreamReader 为 Media3 / ExoPlayer 的 DataSource。
 *
 * 遵循 Animeko 与 Media3 架构规范：
 * 1. 配合 PikPakStreamReader 实现 8 连接并发分块预取与内存 LRU 缓存；
 * 2. 精确处理 ExoPlayer Loader 的线程中断：捕获 InterruptedException 并重新置位线程中断标志，
 *    包装为 InterruptedIOException，避免媒体解析线程因非检查异常闪退；
 * 3. 跨 seek 生命周期保持 underlying reader 存活，防止拖动时反复重构连接池与丢弃缓存。
 */
@OptIn(UnstableApi::class)
class PikoStreamDataSource(
    private val reader: PikPakStreamReader,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var opened = false
    private var transferHasStarted = false
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)
        return try {
            val totalSize = reader.size
            if (dataSpec.position < 0L || dataSpec.position > totalSize) {
                throw IOException("Invalid stream position: ${dataSpec.position}")
            }
            if (dataSpec.length < 0L && dataSpec.length != C.LENGTH_UNSET.toLong()) {
                throw IOException("Invalid stream length: ${dataSpec.length}")
            }

            runBlockingInterruptible {
                reader.seekTo(dataSpec.position)
            }

            uri = dataSpec.uri
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                totalSize - dataSpec.position
            } else {
                minOf(dataSpec.length, totalSize - dataSpec.position)
            }
            opened = true
            transferHasStarted = true
            transferStarted(dataSpec)
            bytesRemaining
        } catch (e: CancellationException) {
            close()
            throw InterruptedIOException("Piko stream open was cancelled").apply { initCause(e) }
        } catch (e: IOException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw IOException("Unable to open Piko stream", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("DataSource not open")
        if (length == 0 || bytesRemaining == 0L) {
            return if (length == 0) 0 else C.RESULT_END_OF_INPUT
        }
        val requestedLength = minOf(length.toLong(), bytesRemaining).toInt()
        val bytesRead = try {
            runBlockingInterruptible {
                reader.read(buffer, offset, requestedLength)
            }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw InterruptedIOException("Piko stream read was cancelled").apply { initCause(e) }
        } catch (e: Exception) {
            throw IOException(e)
        }

        if (bytesRead == -1) {
            bytesRemaining = 0L
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRead == 0) {
            throw IOException("Piko stream returned no data")
        }
        bytesRemaining = (bytesRemaining - bytesRead).coerceAtLeast(0L)
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            if (transferHasStarted) {
                transferEnded()
            }
        }
        transferHasStarted = false
        bytesRemaining = 0L
        uri = null
    }
}

/**
 * 路由 DataSourceFactory：将当前媒体 URI 路由至 Piko 8连接取流管道，
 * 外挂字幕或第三方音轨等回落至系统默认 DataSource。
 */
@OptIn(UnstableApi::class)
class PikoRoutingDataSourceFactory(
    private val mediaUri: String,
    private val pikoDataSourceFactory: DataSource.Factory,
    private val fallbackDataSourceFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        PikoRoutingDataSource(mediaUri, pikoDataSourceFactory, fallbackDataSourceFactory)
}

@OptIn(UnstableApi::class)
private class PikoRoutingDataSource(
    private val mediaUri: String,
    private val pikoDataSourceFactory: DataSource.Factory,
    private val fallbackDataSourceFactory: DataSource.Factory,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        activeDataSource?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val uriStr = dataSpec.uri.toString()
        val dataSource = if (uriStr == mediaUri || uriStr.startsWith("piko://")) {
            pikoDataSourceFactory.createDataSource()
        } else {
            fallbackDataSourceFactory.createDataSource()
        }
        transferListeners.forEach(dataSource::addTransferListener)
        activeDataSource = dataSource
        return try {
            dataSource.open(dataSpec)
        } catch (e: Exception) {
            dataSource.close()
            activeDataSource = null
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (activeDataSource ?: throw IOException("DataSource not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = activeDataSource?.uri

    override fun close() {
        activeDataSource?.close()
        activeDataSource = null
    }
}

/**
 * 播放会话：管理当前视频的 8 并发连接 FileHandle、StreamReader 及生命周期
 */
class PikoStreamSession(
    val fileId: String,
    val mediaUri: String,
    val handle: PikPakFileHandle,
    val reader: PikPakStreamReader,
    val dataSourceFactory: DataSource.Factory,
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
        }
        try {
            reader.close()
        } finally {
            handle.close()
        }
    }
}

/**
 * 拦截并处理中断，遵循 Media3 / ExoPlayer 异常协议规范
 */
internal fun <T> runBlockingInterruptible(block: suspend () -> T): T =
    try {
        runBlocking { block() }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Piko stream read was interrupted").apply { initCause(e) }
    }
