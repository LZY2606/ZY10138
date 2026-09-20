package app.web

import app.service.*
import app.domain.CurveIssue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ApiServer(
    private val pointService: PointService,
    private val curveService: CurveService,
    private val recomputeService: RecomputeService,
    private val previewService: PreviewService,
    private val stationService: StationService,
) {
    private lateinit var server: HttpServer

    fun start(port: Int) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api") { exchange -> handle(exchange) }
        server.createContext("/") { exchange -> Static.serve(exchange) }
        server.executor = null
        server.start()
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        try {
            route(ex)
        } catch (e: ConflictException) {
            val body = ConflictResponse(
                error = e.message ?: "版本冲突",
                baseRevision = e.baseRevision,
                currentRevision = e.currentRevision,
                serverSegments = e.serverSegments.map { it.toDto() },
                onlyInClient = e.onlyInClient.map { it.toDto() },
                onlyOnServer = e.onlyOnServer.map { it.toDto() },
                changedInPlace = e.changedInPlace.map { SegmentPair(it.first.toDto(), it.second.toDto()) },
            )
            writeJson(ex, 409, body)
        } catch (e: ValidationException) {
            writeJson(ex, 400, ErrorResponse(e.message ?: "校验失败", e.issues.map { it.message }))
        } catch (e: IllegalArgumentException) {
            writeJson(ex, 400, ErrorResponse(e.message ?: "请求参数错误"))
        } catch (e: NoSuchElementException) {
            writeJson(ex, 404, ErrorResponse(e.message ?: "资源不存在"))
        } catch (e: IllegalStateException) {
            writeJson(ex, 409, ErrorResponse(e.message ?: "状态冲突"))
        } catch (t: Throwable) {
            t.printStackTrace()
            writeJson(ex, 500, ErrorResponse(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val query = parseQuery(ex.requestURI.rawQuery)
        val method = ex.requestMethod

        when {
            method == "GET" && path == "/api/stations" ->
                writeJson(ex, 200, stationService.list())

            method == "GET" && path == "/api/points" ->
                writeJson(ex, 200, pointService.listPoints(query.require("stationId")))

            method == "POST" && path == "/api/points" -> {
                val req = readBody<CreatePointRequest>(ex)
                writeJson(ex, 201, mapOf("id" to pointService.addPoint(req)))
            }

            Regex("^/api/points/(\\d+)/excluded$").matches(path) && method == "POST" -> {
                val id = Regex("\\d+").find(path)!!.value.toLong()
                pointService.setExcluded(id, readBody(ex))
                writeJson(ex, 200, mapOf("ok" to true))
            }

            method == "GET" && path == "/api/curves" ->
                writeJson(ex, 200, curveService.listCurves(query.require("stationId")))

            method == "POST" && path == "/api/curves/save" ->
                writeJson(ex, 201, curveService.saveCurve(readBody(ex)).let { toDto(it) })

            method == "POST" && path == "/api/curves/publish" ->
                writeJson(ex, 201, curveService.publish(readBody(ex)))

            method == "GET" && path == "/api/periods" ->
                writeJson(ex, 200, curveService.listPeriods(query.require("stationId")))

            method == "POST" && path == "/api/curves/fit" ->
                writeJson(ex, 200, curveService.fit(query.require("stationId"), readBody(ex)))

            method == "POST" && path == "/api/curves/residuals" -> {
                val req = readBody<ResidualsRequest>(ex)
                val result = if (req.curveId != null)
                    curveService.residualsOfCurve(req.stationId, req.curveId)
                else curveService.residuals(req.stationId, req.segments!!.map { it.toDef() })
                writeJson(ex, 200, result)
            }

            method == "GET" && path == "/api/series" ->
                writeJson(ex, 200, pointService.listSeriesKeys(query.require("stationId")))

            method == "GET" && path == "/api/levels" ->
                writeJson(ex, 200, pointService.listLevelSeries(
                    query.require("stationId"), query.require("seriesKey")))

            method == "GET" && path == "/api/derived" ->
                writeJson(ex, 200, recomputeService.derived(
                    query.require("stationId"), query.require("seriesKey")))

            method == "POST" && path == "/api/preview" ->
                writeJson(ex, 200, previewService.preview(readBody(ex)))

            method == "POST" && path == "/api/recompute" ->
                writeJson(ex, 200, recomputeService.recompute(readBody(ex)))

            Regex("^/api/jobs/[^/]+$").matches(path) && method == "GET" -> {
                val uuid = path.substringAfterLast("/")
                val job = recomputeService.getJob(URLDecoder.decode(uuid, "UTF-8"))
                    ?: throw NoSuchElementException("作业不存在")
                writeJson(ex, 200, job)
            }

            else -> writeJson(ex, 404, ErrorResponse("未知接口: $method $path"))
        }
    }

    private fun toDto(c: StoredCurve) = CurveDto(
        id = c.id,
        stationId = c.stationId,
        revision = c.revision,
        status = c.status.code,
        reason = c.reason,
        createdAt = c.createdAt,
        publishedAt = c.publishedAt,
        segments = c.segments.map { it.toDto() },
    )

    private inline fun <reified T> readBody(ex: HttpExchange): T {
        val text = ex.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
        if (text.isBlank()) throw IllegalArgumentException("请求体为空")
        return Json.read(text)
    }

    private fun writeJson(ex: HttpExchange, status: Int, body: Any) {
        val bytes = Json.write(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): QueryMap {
        val map = mutableMapOf<String, String>()
        raw?.split("&")?.filter { it.isNotEmpty() }?.forEach { pair ->
            val (k, v) = pair.split("=", limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
            map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return QueryMap(map)
    }

    class QueryMap(private val map: Map<String, String>) {
        fun require(key: String): String =
            map[key] ?: throw IllegalArgumentException("缺少查询参数: $key")
    }

    // 让未使用的 CurveIssue import 保持语义（错误详情统一在 ValidationException 内）
    @Suppress("unused")
    private val issueTypeRef: Class<CurveIssue> = CurveIssue::class.java
}
