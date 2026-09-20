package app.web

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets

/** 从 classpath:/web 提供前端静态资源；未知路径回落到 index.html（单页）。 */
object Static {
    private val media = mapOf(
        ".html" to "text/html; charset=utf-8",
        ".js" to "text/javascript; charset=utf-8",
        ".css" to "text/css; charset=utf-8",
        ".svg" to "image/svg+xml",
        ".ico" to "image/x-icon",
        ".json" to "application/json; charset=utf-8",
    )

    fun serve(ex: HttpExchange) {
        var path = ex.requestURI.path.trimStart('/')
        if (path.isEmpty()) path = "index.html"
        if (path.contains("..")) {
            send(ex, 400, "bad request", "text/plain")
            return
        }
        val resource = "/web/$path"
        val bytes = object {}.javaClass.getResourceAsStream(resource)?.use { it.readAllBytes() }
        if (bytes == null) {
            // 单页应用回落
            val index = object {}.javaClass.getResourceAsStream("/web/index.html")?.use { it.readAllBytes() }
            if (index == null) {
                send(ex, 404, "not found", "text/plain")
            } else {
                send(ex, 200, index, "text/html; charset=utf-8")
            }
            return
        }
        val type = media.entries.firstOrNull { path.endsWith(it.key) }?.value
            ?: "application/octet-stream"
        send(ex, 200, bytes, type)
    }

    private fun send(ex: HttpExchange, status: Int, text: String, type: String) =
        send(ex, status, text.toByteArray(StandardCharsets.UTF_8), type)

    private fun send(ex: HttpExchange, status: Int, bytes: ByteArray, type: String) {
        ex.responseHeaders.set("Content-Type", type)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
