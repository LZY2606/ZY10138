package app

import com.fasterxml.jackson.core.type.TypeReference
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant

data class ApiError(val error: String, val details: List<ValidationIssue>? = null,
                    val serverRev: String? = null, val serverSegments: List<SegmentSpec>? = null)

/**
 * 单进程离线 HTTP 服务。SQLite 写入以库级互斥锁串行化，
 * 配合事务保证“发布”和“批量重算”各自原子。
 */
class Server(
    private val port: Int,
    private val repo: Repo,
    private val service: RatingService,
    private val connection: java.sql.Connection
) {
    private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val writeLock = Any()

    fun start() {
        http.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: Throwable) {
                handleError(exchange, e)
            } finally {
                exchange.close()
            }
        }
        http.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        http.start()
        println("服务已启动: http://127.0.0.1:$port")
    }

    fun stop() = http.stop(0)

    private fun route(ex: HttpExchange) {
        val method = ex.requestMethod
        val path = ex.requestURI.path
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            serveStatic(ex, "web/index.html", "text/html; charset=utf-8")
            return
        }
        val body = if (method in setOf("POST", "PUT", "PATCH"))
            Json.mapper.readTree(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        else Json.mapper.createObjectNode()

        when {
            method == "GET" && path == "/api/state" -> {
                val qs = parseQuery(ex.requestURI.rawQuery)
                val stationId = qs["stationId"]?.toLong() ?: repo.listStations().first().id
                writeJson(ex, 200, fullState(stationId))
            }
            method == "POST" && path == "/api/measurements/exclude" -> synchronized(writeLock) {
                val m = repo.setMeasurementExcluded(
                    body["measurementId"].asLong(),
                    body["excluded"].asBoolean(),
                    body.get("note")?.takeIf { !it.isNull }?.asText()
                )
                writeJson(ex, 200, m)
            }
            method == "POST" && path == "/api/curves/draft" -> synchronized(writeLock) {
                val stationId = body["stationId"].asLong()
                val reason = body.get("reason")?.takeIf { !it.isNull }?.asText()
                writeJson(ex, 201, repo.createDraft(stationId, reason))
            }
            method == "PUT" && path.startsWith("/api/curves/") && path.endsWith("/segments") -> {
                synchronized(writeLock) {
                    val id = path.removePrefix("/api/curves/").removeSuffix("/segments").toLong()
                    val segments = Json.mapper.convertValue(
                        body["segments"], object : TypeReference<List<SegmentSpec>>() {})
                    val reason = body.get("reason")?.takeIf { !it.isNull }?.asText()
                    val expectedRev = body["expectedRev"].asText()
                    writeJson(ex, 200, repo.saveDraft(id, segments, reason, expectedRev))
                }
            }
            method == "POST" && path.startsWith("/api/curves/") && path.endsWith("/fit") -> {
                synchronized(writeLock) {
                    val id = path.removePrefix("/api/curves/").removeSuffix("/fit").toLong()
                    writeJson(ex, 200, service.fitDraft(id))
                }
            }
            method == "GET" && path.startsWith("/api/curves/") && path.endsWith("/residuals") -> {
                val id = path.removePrefix("/api/curves/").removeSuffix("/residuals").toLong()
                writeJson(ex, 200, mapOf("residuals" to service.residuals(id)))
            }
            method == "POST" && path.startsWith("/api/curves/") && path.endsWith("/publish") -> {
                synchronized(writeLock) {
                    val id = path.removePrefix("/api/curves/").removeSuffix("/publish").toLong()
                    val curve = repo.publish(
                        id,
                        Instant.parse(body["validFrom"].asText()),
                        body["reason"].asText(),
                        body["expectedRev"].asText()
                    )
                    writeJson(ex, 200, curve)
                }
            }
            method == "POST" && path.startsWith("/api/curves/") && path.endsWith("/preview") -> {
                val id = path.removePrefix("/api/curves/").removeSuffix("/preview").toLong()
                val from = Instant.parse(body["from"].asText())
                val to = Instant.parse(body["to"].asText())
                val curve = repo.getCurve(id)
                if (curve.status == CurveStatus.PUBLISHED) {
                    // 预览已发布曲线本身没有意义；只允许草稿预览
                    throw ConflictException("已发布曲线无需预览；请对草稿执行影响预览")
                }
                writeJson(ex, 200, service.preview(curve, from, to))
            }
            method == "POST" && path == "/api/segments/validate" -> {
                val segments = Json.mapper.convertValue(
                    body["segments"], object : TypeReference<List<SegmentSpec>>() {})
                writeJson(ex, 200, mapOf("issues" to RatingMath.validateSegments(segments)))
            }
            method == "POST" && path == "/api/recompute" -> synchronized(writeLock) {
                val stationId = body["stationId"].asLong()
                val jobId = body.get("jobId")?.takeIf { !it.isNull }?.asText()
                    ?: service.newJobId()
                val mode = RecomputeMode.valueOf(body.get("mode")?.asText() ?: "PUBLISHED")
                val result = service.recompute(
                    jobId, stationId,
                    Instant.parse(body["from"].asText()),
                    Instant.parse(body["to"].asText()),
                    mode,
                    body.get("curveId")?.takeIf { !it.isNull }?.asLong()
                )
                writeJson(ex, 200, result)
            }
            method == "GET" && path.startsWith("/api/jobs/") -> {
                val id = path.removePrefix("/api/jobs/")
                writeJson(ex, 200, repo.getJob(id) ?: throw NotFoundException("job $id 不存在"))
            }
            else -> writeJson(ex, 404, ApiError("not found: $method $path"))
        }
    }

    private fun fullState(stationId: Long): Map<String, Any?> {
        val station = repo.getStation(stationId)
        val from = Instant.parse("2026-08-01T00:00:00Z")
        val to = Instant.parse("2026-09-16T00:00:00Z")
        return mapOf(
            "station" to station,
            "measurements" to repo.listMeasurements(stationId),
            "curves" to repo.listCurves(stationId),
            "publications" to repo.listPublications(stationId),
            "stage" to repo.listStage(stationId, from, to),
            "discharge" to repo.listDischarge(stationId, from, to),
            "window" to mapOf("from" to from, "to" to to)
        )
    }

    private fun parseQuery(q: String?): Map<String, String> {
        if (q.isNullOrBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun serveStatic(ex: HttpExchange, resource: String, contentType: String) {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.readBytes()
            ?: throw NotFoundException(resource)
        ex.responseHeaders.set("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun writeJson(ex: HttpExchange, status: Int, payload: Any?) {
        val bytes = Json.mapper.writeValueAsBytes(payload)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handleError(ex: HttpExchange, e: Throwable) {
        when (e) {
            is ConflictException ->
                writeJson(ex, 409, ApiError(e.message ?: "冲突", null, e.serverRev, e.serverSegments))
            is ValidationException -> writeJson(ex, 422, ApiError("分段校验未通过", e.issues))
            is NotFoundException -> writeJson(ex, 404, ApiError(e.message ?: "not found"))
            is IllegalArgumentException -> writeJson(ex, 400, ApiError(e.message ?: "bad request"))
            else -> {
                e.printStackTrace()
                writeJson(ex, 500, ApiError(e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
