package app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 分段率定曲线的纯函数数学：
 *  - 多项式求值（Horner，溢出/非有限值 -> Quality.OVERFLOW）
 *  - 分段定义域校验（开闭端点、同分支恰好接续、不得重叠/留缝）
 *  - 加权最小二乘多项式拟合（正规方程 + 部分主元高斯消元，奇异阵返回 null）
 *  - 由相邻水位判定上涨/下落趋势（死区 -> Trend.UNDETERMINED）
 */
object RatingMath {

    const val EPS = 1e-9

    /** Horner 求值；任何非有限输入/输出都视为溢出。 */
    fun evalPolynomial(coeff: List<Double>, x: Double): Double? {
        if (!x.isFinite() || coeff.isEmpty()) return null
        var y = 0.0
        for (i in coeff.indices.reversed()) {
            val c = coeff[i]
            if (!c.isFinite()) return null
            y = y * x + c
            if (!y.isFinite()) return null
        }
        return y
    }

    /**
     * 用一份曲线的段集合把单个水位率定为流量。
     * 选择规则：
     *  1. stage 非有限 -> OVERFLOW
     *  2. 趋势已知时，优先命中对应分支（RISING/FALLING）的段；
     *     再回退到 COMMON 段。
     *  3. 趋势未知（死区/首点）时只允许 COMMON，否则 BRANCH_UNDETERMINED。
     *  4. 没有任何段包含该水位 -> OUT_OF_RANGE。
     *  5. 命中段但求值溢出 -> OVERFLOW。
     */
    fun evaluate(segments: List<SegmentSpec>, stage: Double?, trend: Trend, curveId: Long?): EvalResult {
        if (stage == null) return EvalResult(null, Quality.MISSING_STAGE, null, curveId)
        if (!stage.isFinite()) return EvalResult(null, Quality.OVERFLOW, null, curveId)

        val wantedBranches: List<Branch> = when (trend) {
            Trend.RISING -> listOf(Branch.RISING, Branch.COMMON)
            Trend.FALLING -> listOf(Branch.FALLING, Branch.COMMON)
            Trend.UNDETERMINED -> listOf(Branch.COMMON)
        }
        for (b in wantedBranches) {
            val hit = segments.firstOrNull { it.branch == b && it.contains(stage) }
            if (hit != null) {
                val q = evalPolynomial(hit.coefficients, stage)
                return if (q == null) EvalResult(null, Quality.OVERFLOW, b, curveId)
                else EvalResult(q, Quality.OK, b, curveId)
            }
        }
        val quality =
            if (trend == Trend.UNDETERMINED &&
                segments.any { (it.branch == Branch.RISING || it.branch == Branch.FALLING) && it.contains(stage) })
                Quality.BRANCH_UNDETERMINED
            else Quality.OUT_OF_RANGE
        return EvalResult(null, quality, null, curveId)
    }

    /**
     * 校验分段集合。每个分支（COMMON/RISING/FALLING）独立处理：
     *  - 区间必须 low <= high；端点开闭自洽
     *  - 排序后相邻两段必须“恰好接续”（上界=下界，且开闭互补：一个含端点另一个不含）
     *    -> 既不重复命中也不留缝隙
     * 缺失某个分支是允许的（该分支回退 COMMON）。
     * 发布前调用本方法，任何 ERROR 都阻止发布；草稿保存时只做 WARNING 提示。
     */
    fun validateSegments(segments: List<SegmentSpec>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (b in Branch.entries) {
            val segs = segments.filter { it.branch == b }.sortedBy { it.stageLow }
            for (s in segs) {
                if (!s.stageLow.isFinite() || !s.stageHigh.isFinite()) {
                    issues += ValidationIssue("ERROR", "$b 段端点必须为有限值")
                    continue
                }
                if (s.stageLow > s.stageHigh + EPS) {
                    issues += ValidationIssue("ERROR", "$b 段下界 ${s.stageLow} 大于上界 ${s.stageHigh}")
                }
                if (s.stageLow == s.stageHigh && !(s.lowInclude && s.highInclude)) {
                    issues += ValidationIssue("ERROR", "$b 段退化为单点时两端必须均闭")
                }
                if (s.coefficients.isEmpty()) {
                    issues += ValidationIssue("ERROR", "$b 段 [${s.stageLow}, ${s.stageHigh}] 缺少系数")
                }
                if (s.coefficients.any { !it.isFinite() }) {
                    issues += ValidationIssue("ERROR", "$b 段系数存在非有限值")
                }
            }
            for (i in 0 until segs.size - 1) {
                val a = segs[i]
                val c = segs[i + 1]
                if (abs(a.stageHigh - c.stageLow) > 1e-7) {
                    if (a.stageHigh < c.stageLow)
                        issues += ValidationIssue(
                            "ERROR",
                            "$b 段之间存在缝隙：上界 ${a.stageHigh} 与下界 ${c.stageLow} 不接续"
                        )
                    else
                        issues += ValidationIssue(
                            "ERROR",
                            "$b 段定义域重叠：上界 ${a.stageHigh} 大于下界 ${c.stageLow}"
                        )
                    continue
                }
                if (a.highInclude == c.lowInclude) {
                    issues += ValidationIssue(
                        "ERROR",
                        "$b 段在连接水位 ${a.stageHigh} 处开闭端点不互补：" +
                            "上段 highInclude=${a.highInclude}，下段 lowInclude=${c.lowInclude}，" +
                            "会重复命中或留下缝隙"
                    )
                }
            }
        }
        return issues
    }

    /**
     * 对给定点做 degree 次多项式最小二乘拟合（升幂系数）。
     * 构造正规方程 A^T A c = A^T y，列缩放后部分主元消元；
     * 矩阵奇异/近似奇异（回水点导致秩亏时可能发生）返回 null。
     */
    fun fitPolynomial(xs: List<Double>, ys: List<Double>, degree: Int): List<Double>? {
        require(xs.size == ys.size)
        val n = degree + 1
        if (xs.size < n) return null
        val ata = Array(n) { DoubleArray(n) }
        val aty = DoubleArray(n)
        for (k in xs.indices) {
            val x = xs[k]
            val y = ys[k]
            if (!x.isFinite() || !y.isFinite()) return null
            val xpow = DoubleArray(2 * degree + 1)
            xpow[0] = 1.0
            for (p in 1..2 * degree) xpow[p] = xpow[p - 1] * x
            for (i in 0..degree) {
                for (j in 0..degree) ata[i][j] += xpow[i + j]
                aty[i] += xpow[i] * y
            }
        }
        // 列缩放，降低量纲差异
        val scale = DoubleArray(n) { i -> max(sqrt(abs(ata[i][i])), 1e-12) }
        for (i in 0 until n) {
            for (j in 0 until n) ata[i][j] /= scale[i] * scale[j]
            aty[i] /= scale[i]
        }
        val sol = gaussianSolve(ata, aty) ?: return null
        return sol.mapIndexed { i, v -> v / scale[i] }
    }

    /** 部分主元高斯消元；奇异返回 null。 */
    private fun gaussianSolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-10) return null
            if (pivot != col) {
                val tmpRow = a[col]; a[col] = a[pivot]; a[pivot] = tmpRow
                val tmpB = b[col]; b[col] = b[pivot]; b[pivot] = tmpB
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (j in i + 1 until n) s -= a[i][j] * x[j]
            x[i] = s / a[i][i]
            if (!x[i].isFinite()) return null
        }
        return x
    }

    /**
     * 判定某时刻的涨落趋势：
     *  - 序列首点无法判定 -> UNDETERMINED
     *  - 用上一有效水位（缺失水位跳过）做差；
     *  - |差值| <= deadband（死区，米）视为平衡水 -> UNDETERMINED；
     *  - maxAgeSeconds>0 时，若上一有效水位距当前过久，也不判定。
     */
    fun trendAt(points: List<StagePoint>, index: Int, deadband: Double, maxAgeSeconds: Long): Trend {
        if (index <= 0) return Trend.UNDETERMINED
        val cur = points[index].stageM
        if (cur == null || !cur.isFinite()) return Trend.UNDETERMINED
        for (j in index - 1 downTo 0) {
            val prev = points[j].stageM ?: continue
            if (!prev.isFinite()) return Trend.UNDETERMINED
            if (maxAgeSeconds > 0) {
                val ageSec = points[index].ts.epochSecond - points[j].ts.epochSecond
                if (ageSec > maxAgeSeconds) return Trend.UNDETERMINED
            }
            val d = cur - prev
            if (abs(d) <= deadband) return Trend.UNDETERMINED
            return if (d > 0) Trend.RISING else Trend.FALLING
        }
        return Trend.UNDETERMINED
    }
}
