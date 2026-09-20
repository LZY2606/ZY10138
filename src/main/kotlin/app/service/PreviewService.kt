package app.service

import app.db.Database
import app.db.queryOne
import app.domain.CurveEvaluator
import app.domain.Quality
import app.domain.SegmentDef
import java.time.Instant

/**
 * 影响预览：用“候选曲线”对历史水位序列做一次只读求值，
 * 与当前生产派生逐点比较，告诉用户哪些历史日流量会改变。
 *
 * 重要边界：预览结果不落 derived_discharge、不登记作业；
 * 只有显式调用发布 + 重算才会改变生产序列。
 */
class PreviewService(
    private val db: Database,
    private val curveService: CurveService,
    private val pointService: PointService,
    private val recomputeService: RecomputeService,
) {

    fun preview(req: PreviewRequest): PreviewResponse = db.tx { conn ->
        val deadband = conn.queryOne(
            "SELECT branch_deadband AS d FROM station WHERE station_id = ?",
            { rs -> rs.getDouble("d") },
            req.stationId,
        ) ?: 0.0

        // 候选分段：请求体内直接给出（未保存草稿）；为空时退化为该站最新草稿。
        val candidate: List<SegmentDef> = if (!req.segments.isNullOrEmpty()) {
            req.segments.map { it.toDef() }
        } else {
            val draftId = conn.queryOne(
                "SELECT id FROM rating_curve WHERE station_id = ? ORDER BY revision DESC LIMIT 1",
                { rs -> rs.getLong("id") },
                req.stationId,
            ) ?: throw IllegalStateException("该站还没有曲线草稿可供预览")
            curveService.getCurve(draftId).segments
        }
        app.domain.CurveMath.validate(candidate).let { issues ->
            if (issues.isNotEmpty()) throw ValidationException(issues)
        }

        val levels = pointService.listLevelSeries(conn, req.stationId, req.seriesKey)
        val oldByTime = recomputeService
            .derived(conn, req.stationId, req.seriesKey)
            .associateBy { it.observedAt }

        var prev: Double? = null
        var changedCount = 0
        val byQuality = mutableMapOf<String, Int>()
        val points = levels.map { lp ->
            val current = lp.level?.takeIf { it.isFinite() }
            val t = Instant.parse(lp.observedAt)
            val ev = CurveEvaluator.evaluate(candidate, current, prev, deadband)
            if (ev.quality == Quality.OK) prev = current

            byQuality[ev.quality.code] = (byQuality[ev.quality.code] ?: 0) + 1
            val old = oldByTime[lp.observedAt]
            // 候选曲线只是“若现在发布将使用它”，因此预览用候选段；
            // 但对“该时刻根本无生效曲线”的历史点仍保持独立标志——
            // 预览假定候选曲线覆盖整个被预览时间窗。
            val changed = old == null ||
                old.discharge != ev.discharge ||
                old.quality != ev.quality.code
            if (changed) changedCount++
            PreviewPoint(
                observedAt = lp.observedAt,
                level = ev.level,
                branchUsed = ev.branchUsed?.code,
                newDischarge = ev.discharge,
                newQuality = ev.quality.code,
                oldDischarge = old?.discharge,
                oldQuality = old?.quality,
                changed = changed,
            )
        }
        PreviewResponse(
            total = points.size,
            changed = changedCount,
            byQuality = byQuality,
            points = points,
        )
    }
}
