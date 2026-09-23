package dev.piko.shared.media.testing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI

/**
 * 最小的 HTTP/1.1 客户端，直接走 socket：测试要控制「读一半不读了」「中途断开」这类
 * 播放器才会有的行为，常规 HTTP 客户端会替调用方把连接读完或复用掉。
 */
internal class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

internal class OpenResponse(val socket: Socket, val status: Int, val headers: Map<String, String>) {
    val body: InputStream get() = socket.getInputStream()
}

internal fun openRequest(url: String, method: String = "GET", range: String? = null): OpenResponse {
    val uri = URI(url)
    val socket = Socket(uri.host, uri.port)
    val request = buildString {
        append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n")
        if (range != null) append("Range: $range\r\n")
        append("\r\n")
    }
    socket.getOutputStream().apply {
        write(request.toByteArray())
        flush()
    }
    val input = socket.getInputStream()
    val status = input.readHttpLine().split(' ')[1].toInt()
    val headers = HashMap<String, String>()
    while (true) {
        val line = input.readHttpLine()
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return OpenResponse(socket, status, headers)
}

internal fun request(url: String, method: String = "GET", range: String? = null): HttpResponse {
    val open = openRequest(url, method, range)
    open.socket.use {
        val length = if (method == "HEAD") 0 else open.headers["content-length"]?.toInt() ?: 0
        return HttpResponse(open.status, open.headers, open.body.readNBytes(length))
    }
}

private fun InputStream.readHttpLine(): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val b = read()
        check(b >= 0) { "连接提前关闭" }
        if (b == '\n'.code) break
        if (b != '\r'.code) bytes.write(b)
    }
    return bytes.toString(Charsets.UTF_8)
}
