package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 分段开闭端点：恰好接续、不重不漏；多项式与拟合数学。 */
class SegmentEndpointTest {

    private val cLo = listOf(0.0, 1.0, 0.0)       // Q = h
    private val cHi = listOf(-2.0, 2.0, 0.0)      // Q = 2h-2，在 h=2 处连续

    private fun connectedPair(lowInc: Boolean, highInc: Boolean) = listOf(
        SegmentSpec(Branch.COMMON, 0.0, 2.0, true, highInc, cLo),
        SegmentSpec(Branch.RISING, 2.0, 5.0, lowInc, true, cHi)
    )

    @Test
    fun `恰接半开端点在接缝处只命中一段且流量连续`() {
        // 上段 [0,2)、下段 [2,5] -> 接缝 2.0 只命中下段
        val segs = connectedPair(lowInc = true, highInc = false)
        assertTrue(RatingMath.validateSegments(segs).none { it.level == "ERROR" })

        val atSeam = RatingMath.evaluate(segs, 2.0, Trend.RISING, null)
        assertEquals(Quality.OK, atSeam.quality)
        assertEquals(Branch.RISING, atSeam.branch)
        assertEquals(2.0, atSeam.discharge!!, 1e-12)

        // 1.9999 只命中上段
        val below = RatingMath.evaluate(segs, 1.9999, Trend.RISING, null)
        assertEquals(Branch.COMMON, below.branch)
        assertEquals(1.9999, below.discharge!!, 1e-9)
    }

    @Test
    fun `两端都闭在接缝处重复命中（按分支优先级掩盖问题）必须被校验拦截`() {
        // 同一分支内 [0,2] 与 [2,5] 都含 2.0 -> 重复命中，校验失败
        val segs = listOf(
            SegmentSpec(Branch.COMMON, 0.0, 2.0, true, true, cLo),
            SegmentSpec(Branch.COMMON, 2.0, 5.0, true, true, cHi)
        )
        val issues = RatingMath.validateSegments(segs)
        assertTrue(issues.any { it.level == "ERROR" && it.message.contains("开闭端点不互补") })
    }

    @Test
    fun `两端都开在接缝处留下缝隙必须被校验拦截`() {
        val segs = listOf(
            SegmentSpec(Branch.COMMON, 0.0, 2.0, true, false, cLo),
            SegmentSpec(Branch.COMMON, 2.0, 5.0, false, true, cHi)
        )
        val issues = RatingMath.validateSegments(segs)
        assertTrue(issues.any { it.level == "ERROR" })
        // 缝隙点：2.0 不属于任何段
        assertNull(segs.first { it.stageHigh == 5.0 }.let { if (it.contains(2.0)) it else null })
        assertFalse(segs.any { it.contains(2.0) })
        val r = RatingMath.evaluate(segs, 2.0, Trend.UNDETERMINED, null)
        assertEquals(Quality.OUT_OF_RANGE, r.quality)
    }

    @Test
    fun `定义域外水位得到 OUT_OF_RANGE 而不是零值`() {
        val segs = listOf(SegmentSpec(Branch.COMMON, 0.0, 2.0, true, false, cLo))
        val r = RatingMath.evaluate(segs, 9.9, Trend.UNDETERMINED, null)
        assertEquals(Quality.OUT_OF_RANGE, r.quality)
        assertNull(r.discharge)
    }

    @Test
    fun `非有限系数与水位得到 OVERFLOW`() {
        val segs = listOf(
            SegmentSpec(Branch.COMMON, 0.0, 2.0, true, true, listOf(0.0, Double.NaN))
        )
        val r = RatingMath.evaluate(segs, 1.0, Trend.UNDETERMINED, null)
        assertEquals(Quality.OVERFLOW, r.quality)
        assertNull(r.discharge)
        val r2 = RatingMath.evaluate(segs, Double.POSITIVE_INFINITY, Trend.UNDETERMINED, null)
        assertEquals(Quality.OVERFLOW, r2.quality)
    }

    @Test
    fun `死区内趋势未知且只有涨落支时返回 BRANCH_UNDETERMINED`() {
        val segs = connectedPair(lowInc = true, highInc = false)
        val r = RatingMath.evaluate(segs, 3.0, Trend.UNDETERMINED, null)
        assertEquals(Quality.BRANCH_UNDETERMINED, r.quality)
        assertNull(r.discharge)
    }

    @Test
    fun `最小二乘二次拟合恢复已知系数`() {
        val coeff = listOf(1.5, -2.0, 0.3)
        val xs = (0..10).map { it / 2.0 }
        val ys = xs.map { RatingMath.evalPolynomial(coeff, it)!! }
        val fit = RatingMath.fitPolynomial(xs, ys, 2)!!
        coeff.zip(fit).forEach { (a, b) -> assertEquals(a, b, 1e-8) }
    }

    @Test
    fun `点少于阶数或常数序列时拟合返回 null（奇异阵）`() {
        assertNull(RatingMath.fitPolynomial(listOf(1.0, 1.0), listOf(2.0, 2.0), 2))
        assertNull(RatingMath.fitPolynomial(listOf(1.0, 1.0, 1.0), listOf(2.0, 2.0, 2.0), 2))
    }

    @Test
    fun `趋势判定尊重死区与最大回看时长`() {
        val p = listOf(
            TestSupport.instant(0) to 1.00,
            TestSupport.instant(1) to 1.02,  // 差 0.02 <= 死区 0.03
            TestSupport.instant(2) to 1.50,
            TestSupport.instant(20) to 2.00  // 距上一有效点 18 天 > 3 天
        ).map { StagePoint(it.first, it.second) }

        assertEquals(Trend.UNDETERMINED, RatingMath.trendAt(p, 0, 0.03, 3 * 86400))
        assertEquals(Trend.UNDETERMINED, RatingMath.trendAt(p, 1, 0.03, 3 * 86400))
        assertEquals(Trend.RISING, RatingMath.trendAt(p, 2, 0.03, 3 * 86400))
        assertEquals(Trend.UNDETERMINED, RatingMath.trendAt(p, 3, 0.03, 3 * 86400))
    }
}
