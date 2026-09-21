package app

import java.time.Instant
import java.util.UUID

data class ResidualPoint(
    val measurementId: Long,
    val stageM: Double,
    val observedDischarge: Double,
    val predictedDischarge: Double?,
    val residual: Double?,
    val branch: Branch?,
    val covered: Boolean,
    val excluded: Boolean
)

data class PreviewDelta(
    val ts: Instant,
    val stageM: Double?,
    val branch: Branch?,
    val oldDischarge: Double?,
    val oldQuality: Quality,
    val newDischarge: Double?,
    val newQuality: Quality,
    val changed: Boolean
)

data class PreviewResult(
    val pointCount: Int,
    val changedCount: Int,
    val deltas: List<PreviewDelta>
)

data class RecomputeResult(
    val jobId: String,
    val idempotentRetry: Boolean,
    val changedCount: Int,
    val writtenCount: Int
)

/**
 * 业务编排：拟合 / 残差 / 预览 / 发布 / 批量重算。
 * 预览只读，绝不写库，绝不进入生产派生。
 */
class RatingService(private val repo: Repo, private val c: java.sql.Connection) {

    private val fitDegree = 2

    /**
     * 最小二乘拟合：对每条出现了“纳入点”的分支各拟一条二次段，
     * 水位定义域取该分支纳入点的 min/max，端点统一取为下闭上开，
     * 最后一段的上界闭。段之间若不接续，交由 validateSegments 在发布时拦截。
     * 回水点（excluded=1）不参与拟合；原始测量数据不被改写。
     */
    fun fitDraft(curveId: Long): Curve {
        val curve = repo.getCurve(curveId)
        require(curve.status == CurveStatus.DRAFT) { "只有草稿可以拟合" }
        val points = repo.listMeasurements(curve.stationId).filter { !it.excluded }
        val newSegments = mutableListOf<SegmentSpec>()
        for (b in listOf(Branch.RISING, Branch.FALLING, Branch.COMMON)) {
            val bp = points.filter { it.branchHint == b }
            if (bp.size < fitDegree + 1) continue
            val xs = bp.map { it.stageM }
            val ys = bp.map { it.dischargeM3s }
            val coeff = RatingMath.fitPolynomial(xs, ys, fitDegree)
                ?: throw ValidationException(
                    listOf(ValidationIssue("ERROR", "$b 分支拟合失败：数据点过少或矩阵奇异"))
                )
            val lo = xs.min()
            val hi = xs.max()
            newSegments += SegmentSpec(b, lo, hi, lowInclude = true, highInclude = true, coeff)
        }
        if (newSegments.isEmpty()) {
            throw ValidationException(
                listOf(ValidationIssue("ERROR", "没有任何分支拥有足够的已纳入实测点（每支至少 ${fitDegree + 1} 个）"))
            )
        }
        // 保存拟合结果只改草稿段，不碰 measurement / stage_series。
        return repo.saveDraft(curveId, normalizeEndpoints(newSegments), curve.revisedReason, curve.rev)
    }

    /**
     * 端点规范化：同一分支按水位排序后设为 [lo,hi) 下闭上开，
     * 最后一个段上界取闭。相邻恰接时开闭互补，满足“不重不漏”。
     */
    fun normalizeEndpoints(segments: List<SegmentSpec>): List<SegmentSpec> {
        val out = mutableListOf<SegmentSpec>()
        for (b in Branch.entries) {
            val segs = segments.filter { it.branch == b }.sortedBy { it.stageLow }
            segs.forEachIndexed { i, s ->
                out += s.copy(
                    lowInclude = true,
                    highInclude = i == segs.lastIndex
                )
            }
        }
        return out
    }

    /**
     * 残差：用草稿曲线对每个实测点求值（实测点按 branchHint 直接定位，不判趋势）。
     * covered=false 表示该点水位落在草稿定义域之外。
     */
    fun residuals(curveId: Long): List<ResidualPoint> {
        val curve = repo.getCurve(curveId)
        return repo.listMeasurements(curve.stationId).map { m ->
            val seg = m.branchHint?.let { b ->
                curve.segments.firstOrNull { it.branch == b && it.contains(m.stageM) }
            }
            val pred = seg?.let { RatingMath.evalPolynomial(it.coefficients, m.stageM) }
            ResidualPoint(
                m.id, m.stageM, m.dischargeM3s, pred,
                pred?.let { m.dischargeM3s - it },
                m.branchHint, pred != null, m.excluded
            )
        }
    }

    /**
     * 影响预览（只读）：在给定时间窗内，用 draftCurve 逐点率定，
     * 与“当前生产派生序列”逐点比较，列出将发生变化的日/时流量。
     * 旧值取自 discharge_series；若尚无派生，则与当时已发布曲线的结果比较。
     * 本方法不写任何数据，预览曲线不可能进入生产。
     */
    fun preview(
        draftCurve: Curve,
        from: Instant,
        to: Instant
    ): PreviewResult {
        val station = repo.getStation(draftCurve.stationId)
        val stage = repo.listStage(draftCurve.stationId, from, to)
        val existing = repo.listDischarge(draftCurve.stationId, from, to)
            .associateBy { it.ts }
        val deltas = rateSequence(draftCurve, stage, station, null).mapIndexed { i, np ->
            val old = existing[np.ts]
            val oldQ = old?.dischargeM3s
            val oldQual = old?.quality ?: Quality.MISSING_STAGE
            val changed = old == null ||
                oldQual != np.quality ||
                (oldQ == null) != (np.dischargeM3s == null) ||
                (oldQ != null && np.dischargeM3s != null &&
                    !sameFloat(oldQ, np.dischargeM3s))
            PreviewDelta(np.ts, np.stageM, np.branch, oldQ, oldQual,
                np.dischargeM3s, np.quality, changed)
        }
        return PreviewResult(deltas.size, deltas.count { it.changed }, deltas)
    }

    private fun sameFloat(a: Double, b: Double): Boolean {
        val scale = maxOf(1.0, kotlin.math.abs(a), kotlin.math.abs(b))
        return kotlin.math.abs(a - b) <= 1e-9 * scale
    }

    /**
     * 用一条曲线把水位序列率定为带质量标志的流量序列。
     * 生产模式（PUBLISHED）下逐点取该时刻生效的已发布曲线；
     * 指定曲线模式（CURVE）下全程使用 curveOverride（用于重算/预览）。
     * 缺失水位 -> MISSING_STAGE，且不会用相邻值填补。
     */
    fun rateSequence(
        curveOverride: Curve?,
        stage: List<StagePoint>,
        station: Station,
        jobId: String?
    ): List<DischargePoint> {
        return stage.mapIndexed { i, p ->
            if (p.stageM == null) {
                DischargePoint(p.ts, null, null, Quality.MISSING_STAGE, null,
                    curveOverride?.id, jobId)
            } else {
                val trend = RatingMath.trendAt(stage, i, station.deadbandM, station.maxAgeSeconds)
                val curve = curveOverride ?: repo.publishedCurveAt(station.id, p.ts)
                if (curve == null) {
                    DischargePoint(p.ts, p.stageM, null, Quality.OUT_OF_RANGE, null, null, jobId)
                } else {
                    val r = RatingMath.evaluate(curve.segments, p.stageM, trend, curve.id)
                    DischargePoint(p.ts, p.stageM, r.discharge, r.quality, r.branch,
                        r.curveId, jobId)
                }
            }
        }
    }

    /**
     * 批量重算（原子 + 幂等）：
     *  - 相同 jobId 的重试直接返回既有作业统计，不删除/不重写任何行。
     *  - mode=PUBLISHED：逐点使用各时刻生效的已发布曲线；
     *    mode=CURVE：全程使用 curveId 指定曲线（须已发布，草稿禁止进入生产）。
     *  - 整个“删除目标半开时间窗 + 批量写入”在一个 SQLite 事务内完成，
     *    并发可见性为全有或全无；窗口外的旧派生行不动，旧发布版本仍可按当时曲线复现。
     * 时间窗为半开 [from, to)。
     */
    fun recompute(
        jobId: String,
        stationId: Long,
        from: Instant,
        to: Instant,
        mode: RecomputeMode,
        curveId: Long?
    ): RecomputeResult {
        require(!to.isBefore(from)) { "时间窗非法：from=$from to=$to" }
        repo.getJob(jobId)?.let {
            return RecomputeResult(it.id, true, it.changedCount, it.writtenCount)
        }
        val station = repo.getStation(stationId)
        val override: Curve? = when (mode) {
            RecomputeMode.CURVE -> {
                val cv = repo.getCurve(curveId ?: error("CURVE 模式必须提供 curveId"))
                require(cv.stationId == stationId) { "曲线不属于该站" }
                require(cv.status == CurveStatus.PUBLISHED) { "只有已发布曲线才能进入生产重算" }
                cv
            }
            RecomputeMode.PUBLISHED -> null
        }

        c.tx {
            val stage = repo.listStage(stationId, from, to)
            val old = repo.listDischarge(stationId, from, to).associateBy { it.ts }
            val fresh = rateSequence(override, stage, station, jobId)
            // 先登记作业行，使 discharge_series.job_id 的外键在同一事务内可解析；
            // 作业行与序列行同生共死，整个重算原子可见。
            c.insert(
                "INSERT INTO recompute_job(id,station_id,state,series_from,series_to,mode,curve_id," +
                    "changed_count,written_count,created_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                jobId, stationId, JobState.DONE, from, to, mode, override?.id,
                0, 0, Instant.now(), Instant.now()
            )
            c.update(
                "DELETE FROM discharge_series WHERE station_id=? AND ts>=? AND ts<?",
                stationId, from, to
            )
            var changed = 0
            for (p in fresh) {
                val before = old[p.ts]
                if (before == null || before.quality != p.quality ||
                    (before.dischargeM3s == null) != (p.dischargeM3s == null) ||
                    (before.dischargeM3s != null && p.dischargeM3s != null &&
                        !sameFloat(before.dischargeM3s, p.dischargeM3s))
                ) changed++
                c.insert(
                    "INSERT INTO discharge_series(station_id,ts,stage_m,discharge_m3s,quality,branch,curve_id,job_id)" +
                        " VALUES(?,?,?,?,?,?,?,?)",
                    stationId, p.ts, p.stageM, p.dischargeM3s, p.quality, p.branch, p.curveId, jobId
                )
            }
            c.update(
                "UPDATE recompute_job SET changed_count=?, written_count=?, finished_at=? WHERE id=?",
                changed, fresh.size, Instant.now(), jobId
            )
        }
        val job = repo.getJob(jobId)!!
        return RecomputeResult(jobId, false, job.changedCount, job.writtenCount)
    }

    fun newJobId(): String = UUID.randomUUID().toString()
}
