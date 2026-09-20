package app.service

import app.db.Database
import app.db.insertAndGetId
import app.db.query
import app.db.queryOne
import app.db.update
import app.domain.CurveEvaluator
import app.domain.Quality
import java.time.Instant

class RecomputeService(
    private val db: Database,
    private val curveService: CurveService,
    private val pointService: PointService,
) {
    /**
     * 原子、幂等的批量重算：
     *  - 相同 jobUuid 重试直接返回首次作业（及其写入的序列），不生成第二套序列；
     *  - 新作业在单个事务内：逐点选择时刻 t 生效的已发布曲线并求值、
     *    UPSERT 派生表、登记作业；任一步失败整体回滚（连作业行也不留）。
     *  - 只影响显式指定的 (station, series)；其它派生序列保持不变。
     */
    fun recompute(req: RecomputeRequest): JobDto {
        val existing = db.tx { conn ->
            conn.queryOne("SELECT * FROM recompute_job WHERE job_uuid = ?", ::mapJob, req.jobUuid)
        }
        if (existing != null) return existing.copy(retried = true)

        return db.tx { conn ->
            val createdAt = Instant.now().toString()
            val jobId = conn.insertAndGetId(
                """INSERT INTO recompute_job (job_uuid, station_id, series_key, status, created_at)
                   VALUES (?,?,?, 'running', ?)""",
                req.jobUuid, req.stationId, req.seriesKey, createdAt,
            )

            val levels = pointService.listLevelSeries(conn, req.stationId, req.seriesKey)
            var changed = 0
            var prevLevel: Double? = null

            val deadband = conn.queryOne(
                "SELECT branch_deadband AS d FROM station WHERE station_id = ?",
                { rs -> rs.getDouble("d") },
                req.stationId,
            ) ?: 0.0

            levels.forEach { lp ->
                val t = Instant.parse(lp.observedAt)
                val current = lp.level?.takeIf { it.isFinite() }

                val (evalResult, curveId) = if (current == null) {
                    app.domain.EvalResult.fail(null, null, Quality.MISSING_LEVEL) to null
                } else {
                    val curve = curveService.activeCurveAt(conn, req.stationId, t)
                    if (curve == null) {
                        app.domain.EvalResult.fail(current, null, Quality.NO_ACTIVE_CURVE) to null
                    } else {
                        CurveEvaluator.evaluate(curve.segments, current, prevLevel, deadband) to curve.id
                    }
                }

                if (evalResult.quality == Quality.OK && current != null) {
                    prevLevel = current
                }
                // 前一“存在”的水位：即使本次缺测，下一点的涨落参考也不更新。

                val old = conn.queryOne(
                    """SELECT discharge, quality FROM derived_discharge
                       WHERE station_id = ? AND series_key = ? AND observed_at = ?""",
                    { rs -> rs.getObject("discharge")?.let { (it as Number).toDouble() } to rs.getString("quality") },
                    req.stationId, req.seriesKey, lp.observedAt,
                )
                val isChanged = old == null ||
                    old.first != evalResult.discharge || old.second != evalResult.quality.code
                if (isChanged) changed++

                conn.update(
                    """INSERT INTO derived_discharge
                       (station_id, series_key, observed_at, level_ref, branch_used,
                        discharge, quality, curve_id, job_id)
                       VALUES (?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(station_id, series_key, observed_at) DO UPDATE SET
                         level_ref = excluded.level_ref,
                         branch_used = excluded.branch_used,
                         discharge = excluded.discharge,
                         quality = excluded.quality,
                         curve_id = excluded.curve_id,
                         job_id = excluded.job_id""",
                    req.stationId, req.seriesKey, lp.observedAt,
                    evalResult.level,
                    evalResult.branchUsed?.code,
                    evalResult.discharge,
                    evalResult.quality.code,
                    curveId,
                    jobId,
                )
            }

            val finishedAt = Instant.now().toString()
            conn.update(
                """UPDATE recompute_job
                   SET status = 'succeeded', point_count = ?, changed_points = ?, finished_at = ?
                   WHERE id = ?""",
                levels.size, changed, finishedAt, jobId,
            )
            conn.queryOne(
                "SELECT * FROM recompute_job WHERE id = ?", ::mapJob, jobId,
            )!!
        }
    }

    fun getJob(jobUuid: String): JobDto? = db.tx { conn ->
        conn.queryOne("SELECT * FROM recompute_job WHERE job_uuid = ?", ::mapJob, jobUuid)
    }

    fun derived(stationId: String, seriesKey: String): List<DerivedPointDto> =
        db.tx { conn -> derived(conn, stationId, seriesKey) }

    fun derived(conn: java.sql.Connection, stationId: String, seriesKey: String): List<DerivedPointDto> =
        conn.query(
            """SELECT observed_at, level_ref, branch_used, discharge, quality, curve_id
               FROM derived_discharge WHERE station_id = ? AND series_key = ?
               ORDER BY observed_at""",
            { rs ->
                DerivedPointDto(
                    observedAt = rs.getString("observed_at"),
                    level = rs.getObject("level_ref")?.let { (it as Number).toDouble() },
                    branchUsed = rs.getString("branch_used"),
                    discharge = rs.getObject("discharge")?.let { (it as Number).toDouble() },
                    quality = rs.getString("quality"),
                    curveId = rs.getObject("curve_id")?.let { (it as Number).toLong() },
                )
            },
            stationId, seriesKey,
        )

    private fun mapJob(rs: java.sql.ResultSet): JobDto = JobDto(
        id = rs.getLong("id"),
        jobUuid = rs.getString("job_uuid"),
        stationId = rs.getString("station_id"),
        seriesKey = rs.getString("series_key"),
        status = rs.getString("status"),
        pointCount = rs.getInt("point_count"),
        changedPoints = rs.getInt("changed_points"),
        createdAt = rs.getString("created_at"),
        finishedAt = rs.getString("finished_at"),
        detail = rs.getString("detail"),
    )

}
