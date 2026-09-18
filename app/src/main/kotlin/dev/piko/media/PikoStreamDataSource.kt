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

    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        transferInitializing(dataSpec)
        opened = true

        val totalSize = reader.size
        if (dataSpec.position in 1..<totalSize) {
            runBlockingInterruptible {
                reader.seekTo(dataSpec.position)
            }
        }

        val bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (totalSize - dataSpec.position).coerceAtLeast(0L)
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val bytesRead = try {
            runBlockingInterruptible {
                reader.read(buffer, offset, length)
            }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException(e)
        }

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
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
        val uriStr = dataSpec.uri.toString()
        val dataSource = if (uriStr == mediaUri || uriStr.startsWith("piko://")) {
            pikoDataSourceFactory.createDataSource()
        } else {
            fallbackDataSourceFactory.createDataSource()
        }
        transferListeners.forEach(dataSource::addTransferListener)
        activeDataSource = dataSource
        return dataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(activeDataSource) { "DataSource not open" }.read(buffer, offset, length)

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
    override fun close() {
        reader.close()
        handle.close()
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
