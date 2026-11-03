package com.afeicn.aiplayer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 手机侧局域网代理：电视从本机拉流，本机补上源站要求的 Referer / UA / Cookie。
 *
 * - 端口由系统随机分配，只在投屏会话期间监听；
 * - 每个请求都带会话令牌，局域网里的其他设备即使扫到端口也不能借用；
 * - m3u8 会先读出来重写所有 URI，再吐给电视；分片按流透传（支持 Range）；
 * - 上游用 HttpURLConnection，不引第三方库。
 */
internal class CastProxyServer(
    private val host: String,
    private val sessionHeaders: Map<String, String>,
    private val token: String
) {

    private val serverSocket = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "aiplayer-cast-proxy").apply { isDaemon = true }
    }
    @Volatile
    private var running = true

    val port: Int get() = serverSocket.localPort

    fun urlFor(originalUrl: String): String =
        CastProxyProtocol.proxyUrl(host, port, token, originalUrl)

    fun start() {
        thread(isDaemon = true, name = "aiplayer-cast-accept") {
            while (running) {
                val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching { pool.execute { handle(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return writeText(output, 400, "Bad Request", "bad request line")
            val method = parts[0].uppercase()
            if (method != "GET" && method != "HEAD") {
                return writeText(output, 405, "Method Not Allowed", "only GET/HEAD")
            }
            val requestHeaders = readHeaders(input)
            val originalUrl = CastProxyProtocol.parseRequestTarget(parts[1], token)
                ?: return writeText(output, 403, "Forbidden", "bad token or url")

            val connection = try {
                (URL(originalUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    requestMethod = method
                    // 上游可能是压缩过的；统一要 identity，转发时不用处理 Content-Encoding。
                    setRequestProperty("Accept-Encoding", "identity")
                    sessionHeaders.forEach { (key, value) ->
                        if (key.isNotBlank() && value.isNotBlank() &&
                            key.lowercase() !in HOP_BY_HOP_HEADERS
                        ) {
                            setRequestProperty(key, value)
                        }
                    }
                    requestHeaders["range"]?.let { setRequestProperty("Range", it) }
                }
            } catch (_: Exception) {
                return writeText(output, 502, "Bad Gateway", "cannot open upstream")
            }

            try {
                val status = connection.responseCode
                val contentType = connection.contentType
                    ?: CastProxyProtocol.contentTypeFor(originalUrl)
                val extraHeaders = buildMap {
                    connection.getHeaderField("Content-Range")?.let { put("Content-Range", it) }
                    connection.getHeaderField("Accept-Ranges")?.let { put("Accept-Ranges", it) }
                    connection.getHeaderField("Last-Modified")?.let { put("Last-Modified", it) }
                    connection.getHeaderField("ETag")?.let { put("ETag", it) }
                }

                if (CastProxyProtocol.isPlaylist(originalUrl, contentType) &&
                    status in 200..299 && method == "GET"
                ) {
                    forwardPlaylist(connection, output, originalUrl)
                } else {
                    writeHead(
                        output = output,
                        status = status,
                        contentType = contentType,
                        contentLength = connection.contentLengthLong.takeIf { it >= 0 },
                        extraHeaders = extraHeaders
                    )
                    if (method == "GET") {
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        stream?.use { copy(it, output) }
                    }
                    output.flush()
                }
            } catch (_: Exception) {
                // 头可能已经发给电视了，不能追加第二个响应；直接断开，让电视重试/报错。
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun forwardPlaylist(connection: HttpURLConnection, output: OutputStream, originalUrl: String) {
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val finalBaseUrl = connection.url.toString()
        val rewritten = runCatching {
            CastProxyProtocol.rewritePlaylist(text, finalBaseUrl, host, port, token)
        }.getOrDefault(text)
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        writeHead(
            output = output,
            status = 200,
            contentType = "application/vnd.apple.mpegurl; charset=utf-8",
            contentLength = bytes.size.toLong(),
            extraHeaders = mapOf("Cache-Control" to "no-store")
        )
        output.write(bytes)
        output.flush()
    }

    private fun writeText(output: OutputStream, status: Int, reason: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeHead(output, status, "text/plain; charset=utf-8", bytes.size.toLong(), emptyMap())
        runCatching {
            output.write(bytes)
            output.flush()
        }
    }

    private fun writeHead(
        output: OutputStream,
        status: Int,
        contentType: String,
        contentLength: Long?,
        extraHeaders: Map<String, String>
    ) {
        val head = buildString {
            append("HTTP/1.1 $status ${reasonOf(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            if (contentLength != null) append("Content-Length: $contentLength\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) return null
            if (current == '\n'.code) {
                return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            }
            if (previous == '\r'.code && current == '\n'.code) {
                return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            }
            buffer.write(current)
            previous = current
            if (buffer.size() > 16 * 1024) return null
        }
    }

    /** 只把请求头读掉，避免影响 keep-alive；实际只用到 Range。 */
    private fun readHeaders(input: InputStream): Map<String, String> = buildMap {
        var count = 0
        while (count++ < 100) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty()) put(key, value)
        }
    }

    private fun reasonOf(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    private companion object {
        val HOP_BY_HOP_HEADERS = setOf(
            "host", "connection", "accept-encoding", "transfer-encoding", "content-length", "range"
        )
    }
}
