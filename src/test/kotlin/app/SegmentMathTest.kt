package app

import app.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SegmentMathTest {

    private fun seg(low: Double, high: Double, lo: Endpoint, hi: Endpoint, b: Branch = Branch.COMMON) =
        SegmentDef(b, low, high, lo, hi, listOf(0.0, 1.0))

    @Test
    fun `junction exactly matched - open high and closed low`() {
        // [1,3) 与 [3,5]：3 只属于右段，无重复命中也无缝隙。
        val left = seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN)
        val right = seg(3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED)
        assertTrue(CurveMath.validate(listOf(left, right)).isEmpty())

        assertTrue(left.contains(1.0))
        assertFalse(left.contains(3.0))
        assertTrue(right.contains(3.0))
        // 每个水位恰好命中一段。
        listOf(1.0, 2.999, 3.0, 4.5, 5.0).forEach { h ->
            val hits = listOf(left, right).count { it.contains(h) }
            assertEquals(1, hits, "水位 $h 应恰好命中一段")
        }
    }

    @Test
    fun `closed-closed junction is double hit`() {
        val left = seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.CLOSED)
        val right = seg(3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED)
        val issues = CurveMath.validate(listOf(left, right))
        assertTrue(issues.any { it is CurveIssue.Junction && it.kind == "double_hit" })
        assertEquals(2, listOf(left, right).count { it.contains(3.0) })
    }

    @Test
    fun `open-open junction leaves a point gap`() {
        val left = seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN)
        val right = seg(3.0, 5.0, Endpoint.OPEN, Endpoint.CLOSED)
        val issues = CurveMath.validate(listOf(left, right))
        assertTrue(issues.any { it is CurveIssue.Junction && it.kind == "gap_point" })
        assertEquals(0, listOf(left, right).count { it.contains(3.0) })
    }

    @Test
    fun `gap between segments detected`() {
        val left = seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN)
        val right = seg(3.5, 5.0, Endpoint.CLOSED, Endpoint.CLOSED)
        val issues = CurveMath.validate(listOf(left, right))
        assertTrue(issues.any { it is CurveIssue.Junction && it.kind == "gap" })
        assertNull(CurveMath.findSegment(listOf(left, right), Branch.COMMON, 3.2))
    }

    @Test
    fun `overlapping segments detected`() {
        val left = seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.CLOSED)
        val right = seg(2.5, 5.0, Endpoint.CLOSED, Endpoint.CLOSED)
        val issues = CurveMath.validate(listOf(left, right))
        assertTrue(issues.any { it is CurveIssue.Junction && it.kind == "overlap" })
    }

    @Test
    fun `level below lowest range is out of range not missing`() {
        val s = seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.CLOSED)
        assertNull(CurveMath.findSegment(listOf(s), Branch.COMMON, 0.5))
    }

    @Test
    fun `invalid level bounds rejected`() {
        val bad = SegmentDef(Branch.COMMON, 3.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, listOf(1.0))
        assertTrue(CurveMath.validate(listOf(bad)).any { it is CurveIssue.SegmentError })
    }

    @Test
    fun `branches must be common only or rising plus falling`() {
        val rising = seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, Branch.RISING)
        val onlyRising = listOf(rising)
        assertTrue(CurveMath.validate(onlyRising).any { it.message.contains("分支集合非法") })

        val mixed = listOf(
            seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, Branch.RISING),
            seg(3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED, Branch.RISING),
            seg(1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, Branch.FALLING),
            seg(3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED, Branch.FALLING),
        )
        assertTrue(CurveMath.validate(mixed).isEmpty())
    }

    @Test
    fun `evaluate polynomial with horner`() {
        val s = SegmentDef(Branch.COMMON, 0.0, 10.0, Endpoint.CLOSED, Endpoint.OPEN, listOf(3.5, 1.0, 0.5))
        // Q(2) = 3.5 + 2 + 2 = 7.5
        assertEquals(7.5, s.evaluate(2.0), 1e-12)
    }
}
