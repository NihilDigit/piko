@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.upload

import dev.piko.shared.storage.IosAppDirectories
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import platform.posix.*

/** App-owned Documents/Piko files only; external providers need a separate picker/import lifecycle. */
fun createIosUploadSources(directories: IosAppDirectories): AppDirectoryUploadSources =
    AppDirectoryUploadSources(directories.downloads, IosUploadFileAccess)

private object IosUploadFileAccess : UploadFileAccess {
    override fun modifiedAtMs(path: String): Long {
        val fd = openRegular(path)
        return try {
            memScoped {
                val info = alloc<stat>()
                if (fstat(fd, info.ptr) != 0) throw IOException("Reading upload metadata failed: errno=$errno")
                info.st_mtimespec.tv_sec * 1_000L + info.st_mtimespec.tv_nsec / 1_000_000L
            }
        } finally {
            platform.posix.close(fd)
        }
    }

    override fun openAt(path: String, offset: Long): RawSource {
        require(offset >= 0) { "Upload offset must not be negative" }
        val fd = openRegular(path)
        try {
            memScoped {
                val info = alloc<stat>()
                if (fstat(fd, info.ptr) != 0) throw IOException("Reading upload length failed: errno=$errno")
                if (offset > info.st_size) throw IOException("Upload offset exceeds source length")
            }
            return PosixUploadSource(fd, offset)
        } catch (failure: Throwable) {
            platform.posix.close(fd)
            throw failure
        }
    }

    private fun openRegular(path: String): Int {
        // NONBLOCK avoids blocking on a FIFO if a source changes after the managed-path check.
        val fd = platform.posix.open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
        if (fd < 0) throw IOException("Opening upload source failed: errno=$errno")
        try {
            val regular = memScoped {
                val info = alloc<stat>()
                fstat(fd, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFREG
            }
            if (!regular) throw IOException("Upload source is not a regular file")
            return fd
        } catch (failure: Throwable) {
            platform.posix.close(fd)
            throw failure
        }
    }
}

/** Each source owns its descriptor and position; CID probes never scan preceding file bytes. */
private class PosixUploadSource(private val fd: Int, private var position: Long) : RawSource {
    private val scratch = ByteArray(64 * 1024)
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Upload source is closed" }
        require(byteCount >= 0) { "Byte count must not be negative" }
        if (byteCount == 0L) return 0L
        val count = minOf(byteCount, scratch.size.toLong(), Long.MAX_VALUE - position).toInt()
        if (count == 0) throw IOException("Upload source position overflow")
        val amount = scratch.usePinned { bytes ->
            var read: Long
            do {
                read = pread(fd, bytes.addressOf(0), count.toULong(), position)
            } while (read < 0 && errno == EINTR)
            if (read < 0) throw IOException("Reading upload source failed: errno=$errno")
            read
        }
        if (amount == 0L) return -1L
        sink.write(scratch, 0, amount.toInt())
        position += amount
        return amount
    }

    override fun close() {
        if (closed) return
        closed = true
        // Never retry close after EINTR: the descriptor may already have been released/reused.
        if (platform.posix.close(fd) != 0) throw IOException("Closing upload source failed: errno=$errno")
    }
}
