package app.domain

/**
 * 一条“某版本”的率定曲线。分支段集合必须通过 [CurveMath.validate]。
 */
data class RatingCurve(
    val id: Long,
    val stationId: String,
    val revision: Int,
    val status: CurveStatus,
    val segments: List<SegmentDef>,
    val reason: String?,
    val publishedAt: String?,
)

enum class CurveStatus(val code: String) {
    DRAFT("draft"),
    PUBLISHED("published");

    companion object {
        fun of(code: String) = entries.first { it.code == code }
    }
}

/** 单点求值结果：要么带流量，要么带一个明确的质量标志（绝不补零）。 */
data class EvalResult(
    val level: Double?,
    val branchUsed: Branch?,
    val discharge: Double?,
    val quality: Quality,
) {
    companion object {
        fun fail(level: Double?, branch: Branch?, q: Quality) =
            EvalResult(level, branch, null, q)
    }
}

object CurveEvaluator {

    /**
     * 依据水位变化方向求分支，再在该分支段上求值。
     *
     * @param prev 上一时刻的“存在且有效”的水位；为空表示序列开头或前值缺失。
     * @param branchDeadband 判定涨落的死区（>0）。|Δh| ≤ 死区时无法判定。
     */
    fun branchOf(prev: Double?, current: Double, branchDeadband: Double): Branch? {
        if (prev == null) return null
        val delta = current - prev
        if (kotlin.math.abs(delta) <= branchDeadband) return null
        return if (delta > 0) Branch.RISING else Branch.FALLING
    }

    /**
     * 对单个水位点求值：
     *  - COMMON 曲线：无需方向；
     *  - RISING/FALLING 曲线：必须先能判定方向，否则 BRANCH_UNKNOWN；
     *  - 水位不在标定范围：OUT_OF_RANGE；
     *  - 计算结果非有限：OVERFLOW。
     */
    fun evaluate(
        segments: List<SegmentDef>,
        level: Double?,
        prevLevel: Double?,
        branchDeadband: Double = 0.0,
    ): EvalResult {
        if (level == null || !level.isFinite()) {
            return EvalResult.fail(level, null, Quality.MISSING_LEVEL)
        }

        val hasDirectionalBranches = segments.any { it.branch != Branch.COMMON }
        val branch: Branch
        if (hasDirectionalBranches) {
            branch = branchOf(prevLevel, level, branchDeadband)
                ?: return EvalResult.fail(level, null, Quality.BRANCH_UNKNOWN)
        } else {
            branch = Branch.COMMON
        }

        val segment = CurveMath.findSegment(segments, branch, level)
            ?: return EvalResult.fail(level, branch, Quality.OUT_OF_RANGE)

        val q = segment.evaluate(level)
        if (!q.isFinite()) {
            return EvalResult.fail(level, branch, Quality.OVERFLOW)
        }
        return EvalResult(level, branch, q, Quality.OK)
    }
}
