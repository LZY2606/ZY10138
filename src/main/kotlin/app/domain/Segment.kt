package app.domain

import kotlin.math.abs

/**
 * 一段率定曲线：Q = Σ coeffs[k] * h^k，k 从 0 起。
 *
 * 水位区间为半开/闭合端点的组合，[levelLow, levelHigh]：
 * lowOpen/highOpen = false 表示闭端（包含），true 表示开端（不包含）。
 * 相邻两段必须“恰好接续”——公共端点被且仅被其中一段命中（一端闭、一端开），
 * 因此既不会重复命中，也不会留下缝隙。
 */
data class SegmentDef(
    val branch: Branch,
    val levelLow: Double,
    val levelHigh: Double,
    val lowOpen: Endpoint,
    val highOpen: Endpoint,
    val coeffs: List<Double>,
    val note: String? = null,
) {
    fun contains(h: Double): Boolean {
        val aboveLow = if (lowOpen == Endpoint.CLOSED) h >= levelLow else h > levelLow
        val belowHigh = if (highOpen == Endpoint.CLOSED) h <= levelHigh else h < levelHigh
        return aboveLow && belowHigh
    }

    /** Horner 法求值；非有限输入/输出交由上层映射为质量标志。 */
    fun evaluate(h: Double): Double {
        if (!h.isFinite()) return Double.NaN
        var acc = 0.0
        for (k in coeffs.indices.reversed()) {
            acc = acc * h + coeffs[k]
        }
        return acc
    }
}

/** 整曲线校验发现的问题。index 为该分支内分段序号（从 0 起）。 */
sealed interface CurveIssue {
    val message: String

    data class General(override val message: String) : CurveIssue
    data class SegmentError(val branch: Branch, val index: Int, override val message: String) : CurveIssue
    data class Junction(
        val branch: Branch, val level: Double, val kind: String,
        override val message: String,
    ) : CurveIssue
}

object CurveMath {
    private const val EPS = 1e-9

    /**
     * 校验一组分段：
     *  1. 分支集合只能是 {common} 或 {rising,falling}；
     *  2. 每段 levelLow < levelHigh，系数非空且有限；
     *  3. 每个分支内按水位排序后，相邻段恰好接续（端点 XOR），无缝隙/重叠。
     */
    fun validate(segments: List<SegmentDef>): List<CurveIssue> {
        val issues = mutableListOf<CurveIssue>()
        if (segments.isEmpty()) {
            issues += CurveIssue.General("曲线没有任何分段")
            return issues
        }

        val branches = segments.map { it.branch }.toSet()
        val validBranchSet = branches == setOf(Branch.COMMON) ||
            (Branch.COMMON !in branches && Branch.RISING in branches && Branch.FALLING in branches)
        if (!validBranchSet) {
            issues += CurveIssue.General(
                "分支集合非法：要么只有 common，要么同时含 rising 与 falling（当前=${branches.map { it.code }}）"
            )
        }

        for (b in branches) {
            val segs = segments.withIndex().filter { it.value.branch == b }
            segs.forEach { (globalIdx, s) ->
                if (!s.levelLow.isFinite() || !s.levelHigh.isFinite()) {
                    issues += CurveIssue.SegmentError(b, globalIdx, "水位端点必须为有限值")
                } else if (s.levelLow >= s.levelHigh) {
                    issues += CurveIssue.SegmentError(b, globalIdx, "必须满足 levelLow < levelHigh")
                }
                if (s.coeffs.isEmpty()) {
                    issues += CurveIssue.SegmentError(b, globalIdx, "多项式系数不能为空")
                } else if (s.coeffs.any { !it.isFinite() }) {
                    issues += CurveIssue.SegmentError(b, globalIdx, "多项式系数必须全部有限")
                }
            }

            data class Row(val globalIndex: Int, val seg: SegmentDef)
            val rows = segs.map { Row(it.index, it.value) }
                .sortedWith(compareBy({ it.seg.levelLow }, { it.seg.levelHigh }))

            // 先查同序区间的交叉（含另一段完全落在本段内的情形）。
            for (i in 0 until rows.size - 1) {
                val a = rows[i].seg
                val c = rows[i + 1].seg
                if (c.levelLow < a.levelHigh - EPS) {
                    issues += CurveIssue.Junction(
                        b, c.levelLow, "overlap",
                        "分段重叠：水位 ${c.levelLow} 同时落在两段内"
                    )
                }
            }

            // 再查“恰好接续”：相邻上界与下界必须重合，且端点开闭互斥。
            for (i in 0 until rows.size - 1) {
                val left = rows[i].seg
                val right = rows[i + 1].seg
                val junction = left.levelHigh
                if (abs(junction - right.levelLow) > EPS) {
                    if (right.levelLow > junction) {
                        issues += CurveIssue.Junction(
                            b, right.levelLow, "gap",
                            "水位 ${junction} 与 ${right.levelLow} 之间存在缝隙（无端点命中）"
                        )
                    }
                    continue
                }
                val leftIncludes = left.highOpen == Endpoint.CLOSED
                val rightIncludes = right.lowOpen == Endpoint.CLOSED
                if (leftIncludes && rightIncludes) {
                    issues += CurveIssue.Junction(
                        b, junction, "double_hit",
                        "水位 $junction 被左右两段同时命中（两端皆闭）"
                    )
                } else if (!leftIncludes && !rightIncludes) {
                    issues += CurveIssue.Junction(
                        b, junction, "gap_point",
                        "水位 $junction 不被任何一段命中（两端皆开）"
                    )
                }
            }
        }
        return issues
    }

    /**
     * 在一组分段中查找命中水位 h 的那一段。
     * 合法曲线保证每个分支至多一段命中。
     */
    fun findSegment(segments: List<SegmentDef>, branch: Branch, h: Double): SegmentDef? =
        segments.firstOrNull { it.branch == branch && it.contains(h) }
}
