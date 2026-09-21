package app

import java.time.Instant

/** 分支类型：公共段 / 上涨支 / 下落支 */
enum class Branch { COMMON, RISING, FALLING }

/** 由水位序列判定的瞬时趋势 */
enum class Trend { RISING, FALLING, UNDETERMINED }

/** 派生质量标志，四者互不等价，非 OK 时流量一律为 null（禁止填零） */
enum class Quality {
    OK,                  // 正常率定
    OUT_OF_RANGE,        // 水位超出已发布曲线的标定范围
    BRANCH_UNDETERMINED, // 趋势在死区内，无法判定上涨/下落分支
    MISSING_STAGE,       // 原始水位缺失
    OVERFLOW             // 系数/计算溢出或非有限值
}

enum class CurveStatus { DRAFT, PUBLISHED }
enum class JobState { DONE, FAILED }
enum class RecomputeMode { PUBLISHED, CURVE }

/**
 * 一条曲线段。水位定义域为带开闭端点的区间：
 * lowInclude/highInclude 决定端点是否命中本段。
 * coefficients 为按升幂排列的多项式系数：Q = Σ c_k * (h - stageLow?)^k（此处直接对 h 升幂）。
 */
data class SegmentSpec(
    val branch: Branch,
    val stageLow: Double,
    val stageHigh: Double,
    val lowInclude: Boolean,
    val highInclude: Boolean,
    val coefficients: List<Double>
) {
    fun contains(stage: Double): Boolean {
        val okLow = if (lowInclude) stage >= stageLow else stage > stageLow
        val okHigh = if (highInclude) stage <= stageHigh else stage < stageHigh
        return okLow && okHigh
    }
}

data class Station(
    val id: Long,
    val code: String,
    val name: String,
    val deadbandM: Double,
    val maxAgeSeconds: Long
)

data class Measurement(
    val id: Long,
    val stationId: Long,
    val observedAt: Instant,
    val stageM: Double,
    val dischargeM3s: Double,
    val branchHint: Branch?,
    val excluded: Boolean,
    val note: String?
)

data class Curve(
    val id: Long,
    val stationId: Long,
    val status: CurveStatus,
    val version: Int,
    val revisedReason: String?,
    val rev: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val segments: List<SegmentSpec>,
    val validFrom: Instant?
)

data class StagePoint(val ts: Instant, val stageM: Double?)

data class DischargePoint(
    val ts: Instant,
    val stageM: Double?,
    val dischargeM3s: Double?,
    val quality: Quality,
    val branch: Branch?,
    val curveId: Long?,
    val jobId: String?
)

data class RecomputeJob(
    val id: String,
    val stationId: Long,
    val state: JobState,
    val seriesFrom: Instant,
    val seriesTo: Instant,
    val mode: RecomputeMode,
    val curveId: Long?,
    val changedCount: Int,
    val writtenCount: Int,
    val createdAt: Instant,
    val finishedAt: Instant?
)

/** 单点率定结果 */
data class EvalResult(
    val discharge: Double?,
    val quality: Quality,
    val branch: Branch?,
    val curveId: Long?
)

data class ValidationIssue(val level: String, val message: String)
