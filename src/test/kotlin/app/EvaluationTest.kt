package app

import app.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EvaluationTest {

    private val common = listOf(
        SegmentDef(Branch.COMMON, 1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, listOf(0.0, 1.0)),
        SegmentDef(Branch.COMMON, 3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED, listOf(0.0, 1.0)),
    )

    private fun branches(coeff: List<Double>) = listOf(
        SegmentDef(Branch.RISING, 1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, coeff),
        SegmentDef(Branch.RISING, 3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED, coeff),
        SegmentDef(Branch.FALLING, 1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, coeff),
        SegmentDef(Branch.FALLING, 3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED, coeff),
    )

    @Test
    fun `missing level is distinct flag and null discharge`() {
        val r = CurveEvaluator.evaluate(common, null, null)
        assertEquals(Quality.MISSING_LEVEL, r.quality)
        assertNull(r.discharge)
    }

    @Test
    fun `out of range is distinct flag`() {
        val r = CurveEvaluator.evaluate(common, 6.0, null)
        assertEquals(Quality.OUT_OF_RANGE, r.quality)
        assertNull(r.discharge)
    }

    @Test
    fun `first point on rising-falling curve is branch unknown`() {
        val r = CurveEvaluator.evaluate(branches(listOf(0.0, 1.0)), 2.0, null)
        assertEquals(Quality.BRANCH_UNKNOWN, r.quality)
        assertNull(r.branchUsed)
    }

    @Test
    fun `flat within deadband is branch unknown`() {
        val prev = 2.0
        val cur = 2.01 // delta 0.01 <= deadband 0.02
        val r = CurveEvaluator.evaluate(branches(listOf(0.0, 1.0)), cur, prev, branchDeadband = 0.02)
        assertEquals(Quality.BRANCH_UNKNOWN, r.quality)
    }

    @Test
    fun `rising and falling select correct branch`() {
        val segs = branches(listOf(0.0, 1.0))
        val rising = CurveEvaluator.evaluate(segs, 3.0, 2.0, 0.0)
        assertEquals(Branch.RISING, rising.branchUsed)
        assertEquals(Quality.OK, rising.quality)

        val falling = CurveEvaluator.evaluate(segs, 2.0, 3.0, 0.0)
        assertEquals(Branch.FALLING, falling.branchUsed)
    }

    @Test
    fun `overflow is detected for non-finite polynomial result`() {
        // 构造会产生溢出的系数（极高阶次 × 大 h）。
        val huge = 1e308
        val seg = SegmentDef(Branch.COMMON, 0.0, 100.0, Endpoint.CLOSED, Endpoint.OPEN, listOf(0.0, huge, huge))
        val r = CurveEvaluator.evaluate(listOf(seg), 50.0, null)
        assertEquals(Quality.OVERFLOW, r.quality)
        assertNull(r.discharge)
    }

    @Test
    fun `non-finite level counts as missing`() {
        val r = CurveEvaluator.evaluate(common, Double.NaN, null)
        assertEquals(Quality.MISSING_LEVEL, r.quality)
    }
}
