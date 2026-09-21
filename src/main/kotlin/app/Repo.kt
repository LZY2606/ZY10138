package app

import java.sql.Connection
import java.time.Instant

class ConflictException(
    message: String,
    val serverRev: String? = null,
    val serverSegments: List<SegmentSpec>? = null
) : RuntimeException(message)
class ValidationException(val issues: List<ValidationIssue>) :
    RuntimeException(issues.joinToString("; ") { it.message })
data class Publication(val validFrom: Instant, val curveId: Long, val reason: String)

class NotFoundException(message: String) : RuntimeException(message)

/** 数据访问：曲线/段/发布/序列的读写。 */
class Repo(private val c: Connection) {

    fun getStation(stationId: Long): Station {
        var found: Station? = null
        c.query("SELECT * FROM station WHERE id = ?", stationId) {
            found = Station(
                getLong("id"), getString("code"), getString("name"),
                getDouble("deadband_m"), getLong("max_age_seconds")
            )
        }
        return found ?: throw NotFoundException("station $stationId not found")
    }

    fun listStations(): List<Station> {
        val out = mutableListOf<Station>()
        c.query("SELECT * FROM station ORDER BY id") {
            out += Station(getLong("id"), getString("code"), getString("name"),
                getDouble("deadband_m"), getLong("max_age_seconds"))
        }
        return out
    }

    fun listMeasurements(stationId: Long): List<Measurement> {
        val out = mutableListOf<Measurement>()
        c.query("SELECT * FROM measurement WHERE station_id=? ORDER BY observed_at", stationId) {
            out += Measurement(
                getLong("id"), getLong("station_id"), getInstant("observed_at"),
                getDouble("stage_m"), getDouble("discharge_m3s"),
                getString("branch_hint")?.let { Branch.valueOf(it) },
                getInt("excluded") == 1, getString("note")
            )
        }
        return out
    }

    fun setMeasurementExcluded(id: Long, excluded: Boolean, note: String?): Measurement {
        val n = c.update(
            "UPDATE measurement SET excluded=?, note=COALESCE(?, note) WHERE id=?",
            if (excluded) 1 else 0, note, id
        )
        if (n == 0) throw NotFoundException("measurement $id not found")
        var m: Measurement? = null
        c.query("SELECT * FROM measurement WHERE id=?", id) {
            m = Measurement(getLong("id"), getLong("station_id"), getInstant("observed_at"),
                getDouble("stage_m"), getDouble("discharge_m3s"),
                getString("branch_hint")?.let { Branch.valueOf(it) },
                getInt("excluded") == 1, getString("note"))
        }
        return m!!
    }

    fun listCurves(stationId: Long): List<Curve> {
        val out = mutableListOf<Curve>()
        c.query("SELECT * FROM rating_curve WHERE station_id=? ORDER BY version", stationId) {
            val id = getLong("id")
            out += Curve(
                id, getLong("station_id"), CurveStatus.valueOf(getString("status")),
                getInt("version"), getString("revised_reason"), getString("rev"),
                getInstant("created_at"), getInstant("updated_at"),
                listSegments(id), validFromOf(id)
            )
        }
        return out
    }

    fun getCurve(id: Long): Curve {
        var curve: Curve? = null
        c.query("SELECT * FROM rating_curve WHERE id=?", id) {
            curve = Curve(
                getLong("id"), getLong("station_id"), CurveStatus.valueOf(getString("status")),
                getInt("version"), getString("revised_reason"), getString("rev"),
                getInstant("created_at"), getInstant("updated_at"),
                listSegments(id), validFromOf(id)
            )
        }
        return curve ?: throw NotFoundException("curve $id not found")
    }

    fun listSegments(curveId: Long): List<SegmentSpec> {
        val out = mutableListOf<SegmentSpec>()
        c.query(
            "SELECT * FROM curve_segment WHERE curve_id=? ORDER BY branch, position", curveId
        ) {
            out += SegmentSpec(
                Branch.valueOf(getString("branch")),
                getDouble("stage_low"), getDouble("stage_high"),
                getInt("low_include") == 1, getInt("high_include") == 1,
                Json.decodeCoeff(getString("coefficients"))
            )
        }
        return out
    }

    private fun validFromOf(curveId: Long): Instant? {
        var t: Instant? = null
        c.query("SELECT MIN(valid_from) AS v FROM curve_publication WHERE curve_id=?", curveId) {
            t = getInstantOrNull("v")
        }
        return t
    }

    /**
     * 创建草稿。版本号 = 同站现有最大版本 + 1（事务内取号）。
     * 初始段集合可从上一份草稿或最新发布版复制。
     */
    fun createDraft(stationId: Long, reason: String?): Curve {
        getStation(stationId)
        val now = Instant.now()
        val version = (listCurves(stationId).maxOfOrNull { it.version } ?: 0) + 1
        val id = c.insert(
            "INSERT INTO rating_curve(station_id,status,version,revised_reason,rev,created_at,updated_at)" +
                " VALUES(?,?,?,?,?,?,?)",
            stationId, CurveStatus.DRAFT, version, reason, newRev(), now, now
        )
        val source = listCurves(stationId).filter { it.status == CurveStatus.PUBLISHED }
            .maxByOrNull { it.version }
            ?: listCurves(stationId).filter { it.id != id }.maxByOrNull { it.version }
        if (source != null) replaceSegments(id, source.segments)
        return getCurve(id)
    }

    /**
     * 保存草稿段集合。expectedRev 做乐观并发控制：
     * 过期版本提交时抛 ConflictException，并带服务端当前段集合供前端做差异比对。
     */
    fun saveDraft(curveId: Long, segments: List<SegmentSpec>, reason: String?, expectedRev: String): Curve {
        val curve = getCurve(curveId)
        if (curve.status != CurveStatus.DRAFT)
            throw ConflictException("curve $curveId 已发布，不可修改；请基于它创建新草稿")
        if (curve.rev != expectedRev) {
            throw ConflictException(
                "曲线已被他人修改（当前 rev=${curve.rev}，提交基于 rev=$expectedRev）",
                serverRev = curve.rev,
                serverSegments = curve.segments
            )
        }
        c.update(
            "UPDATE rating_curve SET revised_reason=COALESCE(?, revised_reason), rev=?, updated_at=? WHERE id=?",
            reason, newRev(), Instant.now(), curveId
        )
        replaceSegments(curveId, segments)
        return getCurve(curveId)
    }

    fun replaceSegments(curveId: Long, segments: List<SegmentSpec>) {
        c.update("DELETE FROM curve_segment WHERE curve_id=?", curveId)
        segments.forEachIndexed { idx, s ->
            c.insert(
                "INSERT INTO curve_segment(curve_id,branch,stage_low,stage_high,low_include,high_include,position,coefficients)" +
                    " VALUES(?,?,?,?,?,?,?,?)",
                curveId, s.branch, s.stageLow, s.stageHigh,
                if (s.lowInclude) 1 else 0, if (s.highInclude) 1 else 0,
                idx, Json.encodeCoeff(s.coefficients)
            )
        }
    }

    /**
     * 发布：事务原子完成
     *  1) 分段数学校验（ERROR 即回滚，不产生发布记录）
     *  2) valid_from 必须严格晚于同站既有所有发布（半开区间不重叠）
     *  3) 曲线 DRAFT -> PUBLISHED（不可变）
     *  4) 写发布记录
     * 不会触碰任何派生序列；是否重算由用户显式选择。
     */
    fun publish(curveId: Long, validFrom: Instant, reason: String, expectedRev: String): Curve = c.tx {
        val curve = getCurve(curveId)
        if (curve.status != CurveStatus.DRAFT)
            throw ConflictException("curve $curveId 不是草稿或已发布")
        if (curve.rev != expectedRev)
            throw ConflictException(
                "曲线已被他人修改（当前 rev=${curve.rev}，提交基于 rev=$expectedRev）",
                serverRev = curve.rev, serverSegments = curve.segments
            )
        val issues = RatingMath.validateSegments(curve.segments)
        if (issues.any { it.level == "ERROR" }) throw ValidationException(issues)

        var latest: Instant? = null
        c.query("SELECT MAX(valid_from) AS v FROM curve_publication WHERE station_id=?", curve.stationId) {
            latest = getInstantOrNull("v")
        }
        if (latest != null && !validFrom.isAfter(latest)) {
            throw ConflictException(
                "valid_from=$validFrom 必须严格晚于上一份发布的生效时间 $latest（半开有效区间不可重叠）"
            )
        }
        val now = Instant.now()
        c.update(
            "UPDATE rating_curve SET status='PUBLISHED', revised_reason=COALESCE(?, revised_reason)," +
                " rev=?, updated_at=? WHERE id=?",
            reason, newRev(), now, curveId
        )
        c.insert(
            "INSERT INTO curve_publication(station_id,curve_id,valid_from,published_at,reason) VALUES(?,?,?,?,?)",
            curve.stationId, curveId, validFrom, now, reason
        )
    }.let { getCurve(curveId) }

    fun listPublications(stationId: Long): List<Publication> {
        val out = mutableListOf<Publication>()
        c.query(
            "SELECT * FROM curve_publication WHERE station_id=? ORDER BY valid_from", stationId
        ) {
            out += Publication(getInstant("valid_from"), getLong("curve_id"), getString("reason"))
        }
        return out
    }

    /**
     * 返回某时刻生效的已发布曲线（半开区间 [valid_from, next_valid_from)）。
     * 草稿永不参与生产率定。
     */
    fun publishedCurveAt(stationId: Long, at: Instant): Curve? {
        var id: Long? = null
        c.query(
            "SELECT curve_id FROM curve_publication WHERE station_id=? AND valid_from<=?" +
                " ORDER BY valid_from DESC LIMIT 1",
            stationId, at
        ) { id = getLong("curve_id") }
        return id?.let { getCurve(it) }
    }

    fun latestPublished(stationId: Long): Curve? {
        var id: Long? = null
        c.query(
            "SELECT curve_id FROM curve_publication WHERE station_id=? ORDER BY valid_from DESC LIMIT 1",
            stationId
        ) { id = getLong("curve_id") }
        return id?.let { getCurve(it) }
    }

    // ---- 原始水位（只追加） ----
    fun upsertStage(stationId: Long, points: List<StagePoint>) {
        points.forEach { p ->
            c.update(
                "INSERT INTO stage_series(station_id,ts,stage_m) VALUES(?,?,?)" +
                    " ON CONFLICT(station_id,ts) DO UPDATE SET stage_m=excluded.stage_m",
                stationId, p.ts, p.stageM
            )
        }
    }

    fun listStage(stationId: Long, from: Instant?, to: Instant?): List<StagePoint> {
        val out = mutableListOf<StagePoint>()
        val sql = buildString {
            append("SELECT ts,stage_m FROM stage_series WHERE station_id=?")
            if (from != null) append(" AND ts>=?")
            if (to != null) append(" AND ts<?")
            append(" ORDER BY ts")
        }
        val args = mutableListOf<Any?>(stationId)
        if (from != null) args += from
        if (to != null) args += to
        c.query(sql, *args.toTypedArray()) {
            out += StagePoint(getInstant("ts"), getObject("stage_m")?.let { (it as Number).toDouble() })
        }
        return out
    }

    // ---- 派生流量 ----
    fun listDischarge(stationId: Long, from: Instant?, to: Instant?): List<DischargePoint> {
        val out = mutableListOf<DischargePoint>()
        val sql = buildString {
            append("SELECT * FROM discharge_series WHERE station_id=?")
            if (from != null) append(" AND ts>=?")
            if (to != null) append(" AND ts<?")
            append(" ORDER BY ts")
        }
        val args = mutableListOf<Any?>(stationId)
        if (from != null) args += from
        if (to != null) args += to
        c.query(sql, *args.toTypedArray()) {
            out += DischargePoint(
                getInstant("ts"),
                getObject("stage_m")?.let { (it as Number).toDouble() },
                getObject("discharge_m3s")?.let { (it as Number).toDouble() },
                Quality.valueOf(getString("quality")),
                getString("branch")?.let { Branch.valueOf(it) },
                getObject("curve_id")?.let { (it as Number).toLong() },
                getString("job_id")
            )
        }
        return out
    }

    fun getJob(id: String): RecomputeJob? {
        var job: RecomputeJob? = null
        c.query("SELECT * FROM recompute_job WHERE id=?", id) {
            job = RecomputeJob(
                getString("id"), getLong("station_id"), JobState.valueOf(getString("state")),
                getInstant("series_from"), getInstant("series_to"),
                RecomputeMode.valueOf(getString("mode")),
                getObject("curve_id")?.let { (it as Number).toLong() },
                getInt("changed_count"), getInt("written_count"),
                getInstant("created_at"), getInstantOrNull("finished_at")
            )
        }
        return job
    }
}

private val revCounter = java.util.concurrent.atomic.AtomicLong(0)
internal fun newRev(): String =
    "%d-%04x".format(System.currentTimeMillis(), revCounter.incrementAndGet() and 0xffff)
