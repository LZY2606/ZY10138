package app.service

import app.db.Database
import app.db.Schema
import app.db.insertAndGetId
import app.db.query
import app.db.queryOne
import app.db.update
import app.domain.*
import java.sql.ResultSet
import java.time.Instant

class ConflictException(
    val baseRevision: Int,
    val currentRevision: Int,
    val serverSegments: List<SegmentDef>,
    val onlyInClient: List<SegmentDef>,
    val onlyOnServer: List<SegmentDef>,
    val changedInPlace: List<Pair<SegmentDef, SegmentDef>>,
) : RuntimeException("曲线已被其他人修改（基准版本 $baseRevision，当前版本 $currentRevision）")

class ValidationException(val issues: List<CurveIssue>) :
    RuntimeException("曲线校验失败：\n" + issues.joinToString("\n") { " - ${it.message}" })

data class StoredCurve(
    val id: Long,
    val stationId: String,
    val revision: Int,
    val status: CurveStatus,
    val reason: String?,
    val createdAt: String,
    val publishedAt: String?,
    val segments: List<SegmentDef>,
)

class CurveService(private val db: Database) {

    // ---------- 读取 ----------

    fun listCurves(stationId: String): List<CurveDto> = db.tx { conn ->
        conn.query(
            "SELECT * FROM rating_curve WHERE station_id = ? ORDER BY revision",
            { rs -> loadCurveDto(rs, conn) },
            stationId,
        )
    }

    fun getCurve(curveId: Long): StoredCurve = db.tx { conn -> loadCurve(conn, curveId) }

    fun listPeriods(stationId: String): List<PeriodDto> = db.tx { conn ->
        conn.query(
            """SELECT p.id, p.station_id, p.curve_id, c.revision, p.start_at, p.end_at
               FROM effective_period p JOIN rating_curve c ON c.id = p.curve_id
               WHERE p.station_id = ? ORDER BY p.start_at""",
            { rs ->
                val endRaw = rs.getString("end_at")
                val openEnded = endRaw == Schema.SENTINEL_MAX
                PeriodDto(
                    id = rs.getLong("id"),
                    stationId = rs.getString("station_id"),
                    curveId = rs.getLong("curve_id"),
                    revision = rs.getInt("revision"),
                    startAt = rs.getString("start_at"),
                    endAt = if (openEnded) null else endRaw,
                    openEnded = openEnded,
                )
            },
            stationId,
        )
    }

    /** 找出时刻 t 生效的已发布曲线；半开语义 start <= t < end，期望恰好一条。 */
    fun activeCurveAt(stationId: String, t: Instant): StoredCurve? =
        db.tx { conn -> activeCurveAt(conn, stationId, t) }

    fun activeCurveAt(conn: java.sql.Connection, stationId: String, t: Instant): StoredCurve? {
        val row = conn.queryOne(
            """SELECT c.id FROM effective_period p JOIN rating_curve c ON c.id = p.curve_id
               WHERE p.station_id = ? AND p.start_at <= ? AND ? < p.end_at
               AND c.status = 'published'""",
            { rs -> rs.getLong("id") },
            stationId, t.toString(), t.toString(),
        )
        return row?.let { loadCurve(conn, it) }
    }

    // ---------- 保存（草稿，乐观并发） ----------

    /**
     * 保存曲线草稿：
     *  - 无 curveId：在该站新建一个 revision = 当前最大 + 1 的草稿；
     *  - 有 curveId：必须提供与服务端一致的 baseRevision，否则抛 ConflictException，
     *    并带回客户端/服务端的差异。
     * 保存前必须通过分段数学校验；校验失败不写库。
     */
    fun saveCurve(req: SaveCurveRequest): StoredCurve {
        val stationId = req.stationId ?: "ST01"
        val defs = req.segments.map { it.toDef() }
        val issues = CurveMath.validate(defs)
        if (issues.isNotEmpty()) throw ValidationException(issues)

        return db.tx { conn ->
            if (req.curveId == null) {
                val maxRev = conn.queryOne(
                    "SELECT COALESCE(MAX(revision),0) AS m FROM rating_curve WHERE station_id = ?",
                    { rs -> rs.getInt("m") },
                    stationId,
                )!!
                val revision = maxRev + 1
                val id = conn.insertAndGetId(
                    """INSERT INTO rating_curve (station_id, revision, status, reason, created_at)
                       VALUES (?,?, 'draft', ?, ?)""",
                    stationId, revision, req.reason, Instant.now().toString(),
                )
                replaceSegments(conn, id, defs)
                insertRevisionSnapshot(conn, id, revision, defs, req.reason)
                loadCurve(conn, id)
            } else {
                val existing = loadCurve(conn, req.curveId)
                require(existing.stationId == stationId) { "曲线不属于站点 $stationId" }
                if (existing.revision != req.baseRevision) {
                    val diff = diffSegments(defs, existing.segments)
                    throw ConflictException(
                        baseRevision = req.baseRevision,
                        currentRevision = existing.revision,
                        serverSegments = existing.segments,
                        onlyInClient = diff.first,
                        onlyOnServer = diff.second,
                        changedInPlace = diff.third,
                    )
                }
                // 已发布曲线永不就地改写：编辑一律派生 revision+1 的新草稿，
                // 旧版本及其生效期因此仍可按当时曲线复现。
                // 未发布草稿若被再次保存，同样整行留痕（单调递增）。
                val maxRev = conn.queryOne(
                    "SELECT COALESCE(MAX(revision),0) AS m FROM rating_curve WHERE station_id = ?",
                    { rs -> rs.getInt("m") },
                    stationId,
                )!!
                val newRevision = maxOf(maxRev, existing.revision) + 1
                val newId = conn.insertAndGetId(
                    """INSERT INTO rating_curve (station_id, revision, status, reason, created_at)
                       VALUES (?,?, 'draft', ?, ?)""",
                    stationId, newRevision, req.reason, Instant.now().toString(),
                )
                replaceSegments(conn, newId, defs)
                insertRevisionSnapshot(conn, newId, req.baseRevision, defs, req.reason)
                loadCurve(conn, newId)
            }
        }
    }

    // ---------- 发布（原子） ----------

    /**
     * 原子发布：
     *  1. 曲线必须存在且通过校验；
     *  2. 把该站当前开放生效期 [x, +∞) 收束为 [x, start)（若 start 落在区间内）；
     *  3. 插入新的半开生效期 [start, end)（end 缺省 = +∞）；
     *  4. 曲线状态置为 published。
     * 重叠由数据库触发器兜底，任何一步失败整笔回滚。
     */
    fun publish(req: PublishRequest): PeriodDto = db.tx { conn ->
        val curve = loadCurve(conn, req.curveId)
        require(curve.stationId == req.stationId) { "曲线不属于该站点" }
        if (curve.status != CurveStatus.DRAFT) {
            throw IllegalStateException("仅草稿曲线可以发布（revision=${curve.revision} 已发布）")
        }
        val issues = CurveMath.validate(curve.segments)
        if (issues.isNotEmpty()) throw ValidationException(issues)

        val start = Instant.parse(req.startAt)
        val end = req.endAt?.let { Instant.parse(it) }
        require(end == null || start.isBefore(end)) { "生效期必须满足 start < end（半开）" }

        // 收束当前覆盖 start 的区间，确保“一个时刻只用一份曲线”。
        conn.query(
            """SELECT id, start_at, end_at FROM effective_period
               WHERE station_id = ? AND start_at <= ? AND ? < end_at""",
            { rs -> Triple(rs.getLong("id"), rs.getString("start_at"), rs.getString("end_at")) },
            req.stationId, start.toString(), start.toString(),
        ).forEach { (id, pStart, pEnd) ->
            conn.update(
                "UPDATE effective_period SET end_at = ? WHERE id = ? AND end_at = ?",
                start.toString(), id, pEnd,
            )
        }

        val endStore = end?.toString() ?: Schema.SENTINEL_MAX
        val periodId = conn.insertAndGetId(
            "INSERT INTO effective_period (station_id, curve_id, start_at, end_at) VALUES (?,?,?,?)",
            req.stationId, req.curveId, start.toString(), endStore,
        )
        conn.update(
            "UPDATE rating_curve SET status = 'published', published_at = ? WHERE id = ?",
            Instant.now().toString(), req.curveId,
        )
        PeriodDto(
            id = periodId,
            stationId = req.stationId,
            curveId = req.curveId,
            revision = curve.revision,
            startAt = start.toString(),
            endAt = if (end == null) null else end.toString(),
            openEnded = end == null,
        )
    }

    // ---------- 残差 ----------

    /**
     * 针对给定草稿（或指定曲线）计算实测点残差。
     * 实测点的 trend 决定涨/落分支；缺失或不在范围时给出相应质量标志。
     */
    fun residuals(stationId: String, segments: List<SegmentDef>): List<ResidualDto> {
        val issues = CurveMath.validate(segments)
        if (issues.isNotEmpty()) throw ValidationException(issues)
        val points = PointService(db).listPoints(stationId)
        val directional = segments.any { it.branch != Branch.COMMON }
        return points.map { p ->
            val branch = if (directional) p.trend?.let { runCatching { Branch.of(it) }.getOrNull() } else Branch.COMMON
            if (directional && branch == null) {
                ResidualDto(p.id, p.observedAt, null, p.level, p.discharge, null, null,
                    Quality.BRANCH_UNKNOWN.code, p.excluded)
            } else {
                val seg = CurveMath.findSegment(segments, branch!!, p.level)
                if (seg == null) {
                    ResidualDto(p.id, p.observedAt, branch.code, p.level, p.discharge, null, null,
                        Quality.OUT_OF_RANGE.code, p.excluded)
                } else {
                    val pred = seg.evaluate(p.level)
                    if (!pred.isFinite()) {
                        ResidualDto(p.id, p.observedAt, branch.code, p.level, p.discharge, null, null,
                            Quality.OVERFLOW.code, p.excluded)
                    } else {
                        ResidualDto(p.id, p.observedAt, branch.code, p.level, p.discharge,
                            pred, p.discharge - pred, Quality.OK.code, p.excluded)
                    }
                }
            }
        }
    }

    fun residualsOfCurve(stationId: String, curveId: Long): List<ResidualDto> {
        val curve = getCurve(curveId)
        return residuals(stationId, curve.segments)
    }

    // ---------- 拟合（不落库） ----------

    fun fit(stationId: String, req: FitRequest): FitResponse {
        val branch = Branch.of(req.branch)
        val points = PointService(db).listPoints(stationId)
            .filter { !it.excluded }
            .filter { req.branch == "common" || it.trend == req.branch }
            .mapNotNull { p ->
                val inLow = req.levelLow == null || p.level >= req.levelLow
                val inHigh = req.levelHigh == null || p.level < req.levelHigh
                if (inLow && inHigh) CurveFitter.FitPoint(p.id, p.level, p.discharge) else null
            }
        val fit = CurveFitter.fit(points, req.degree)
        val seg = SegmentDef(
            branch = branch,
            levelLow = req.levelLow ?: points.minOf { it.h },
            levelHigh = req.levelHigh ?: points.maxOf { it.h },
            lowOpen = Endpoint.of(req.lowOpen),
            highOpen = Endpoint.of(req.highOpen),
            coeffs = fit.coeffs,
            note = req.note,
        )
        return FitResponse(seg.toDto(), fit.usedPointIds)
    }

    // ---------- 内部 ----------

    private fun loadCurveDto(rs: ResultSet, conn: java.sql.Connection): CurveDto {
        val id = rs.getLong("id")
        return CurveDto(
            id = id,
            stationId = rs.getString("station_id"),
            revision = rs.getInt("revision"),
            status = rs.getString("status"),
            reason = rs.getString("reason"),
            createdAt = rs.getString("created_at"),
            publishedAt = rs.getString("published_at"),
            segments = loadSegments(conn, id).map { it.toDto() },
        )
    }

    private fun loadCurve(conn: java.sql.Connection, curveId: Long): StoredCurve {
        val rs = conn.prepareStatement("SELECT * FROM rating_curve WHERE id = ?").apply {
            setObject(1, curveId)
        }.executeQuery()
        require(rs.next()) { "曲线不存在: $curveId" }
        val c = StoredCurve(
            id = rs.getLong("id"),
            stationId = rs.getString("station_id"),
            revision = rs.getInt("revision"),
            status = CurveStatus.of(rs.getString("status")),
            reason = rs.getString("reason"),
            createdAt = rs.getString("created_at"),
            publishedAt = rs.getString("published_at"),
            segments = loadSegments(conn, curveId),
        )
        rs.close()
        return c
    }

    private fun loadSegments(conn: java.sql.Connection, curveId: Long): List<SegmentDef> =
        conn.query(
            "SELECT * FROM rating_segment WHERE curve_id = ? ORDER BY ord",
            { rs ->
                SegmentDef(
                    branch = Branch.of(rs.getString("branch")),
                    levelLow = rs.getDouble("level_low"),
                    levelHigh = rs.getDouble("level_high"),
                    lowOpen = Endpoint.of(rs.getString("low_open")),
                    highOpen = Endpoint.of(rs.getString("high_open")),
                    coeffs = Json.read(rs.getString("coeffs_json")),
                    note = rs.getString("note"),
                )
            },
            curveId,
        )

    private fun replaceSegments(conn: java.sql.Connection, curveId: Long, defs: List<SegmentDef>) {
        conn.update("DELETE FROM rating_segment WHERE curve_id = ?", curveId)
        defs.forEachIndexed { ord, s ->
            conn.update(
                """INSERT INTO rating_segment
                   (curve_id, ord, branch, level_low, level_high, low_open, high_open, coeffs_json, note)
                   VALUES (?,?,?,?,?,?,?,?,?)""",
                curveId, ord, s.branch.code, s.levelLow, s.levelHigh,
                s.lowOpen.code, s.highOpen.code, Json.write(s.coeffs), s.note,
            )
        }
    }

    private fun insertRevisionSnapshot(
        conn: java.sql.Connection, curveId: Long, revision: Int,
        defs: List<SegmentDef>, reason: String?,
    ) {
        conn.update(
            """INSERT INTO curve_revision (curve_id, base_revision, segments_json, reason, edited_at)
               VALUES (?,?,?,?,?)""",
            curveId, revision, Json.write(defs.map { it.toDto() }), reason, Instant.now().toString(),
        )
    }

    /** 以 (branch, levelLow, levelHigh) 为身份键，比较两套分段的差异。 */
    private fun diffSegments(
        client: List<SegmentDef>, server: List<SegmentDef>,
    ): Triple<List<SegmentDef>, List<SegmentDef>, List<Pair<SegmentDef, SegmentDef>>> {
        fun key(s: SegmentDef) = listOf(s.branch.code, s.levelLow, s.levelHigh)
        val serverByKey = server.associateBy { key(it) }
        val clientByKey = client.associateBy { key(it) }
        val onlyClient = client.filter { key(it) !in serverByKey }
        val onlyServer = server.filter { key(it) !in clientByKey }
        val changed = client.mapNotNull { c ->
            val s = serverByKey[key(c)]
            if (s != null && c != s) c to s else null
        }
        return Triple(onlyClient, onlyServer, changed)
    }
}
