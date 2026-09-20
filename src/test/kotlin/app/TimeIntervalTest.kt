package app

import app.domain.HalfOpenInterval
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TimeIntervalTest {
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val t1 = Instant.parse("2026-02-01T00:00:00Z")
    private val t2 = Instant.parse("2026-03-01T00:00:00Z")

    @Test
    fun `half open contains start but not end`() {
        val iv = HalfOpenInterval(t0, t1)
        assertTrue(iv.contains(t0))
        assertTrue(iv.contains(t1.minusSeconds(1)))
        assertFalse(iv.contains(t1))
    }

    @Test
    fun `null end means forever`() {
        val iv = HalfOpenInterval(t0, null)
        assertTrue(iv.contains(Instant.parse("9999-01-01T00:00:00Z")))
        assertFalse(iv.contains(t0.minusSeconds(1)))
    }

    @Test
    fun `abutting half open intervals do not overlap`() {
        // [t0,t1) 与 [t1,t2)：t1 时刻仅后段生效。
        val a = HalfOpenInterval(t0, t1)
        val b = HalfOpenInterval(t1, t2)
        assertFalse(a.overlaps(b))
        assertTrue(a.contains(t1.minusSeconds(1)))
        assertFalse(a.contains(t1))
        assertTrue(b.contains(t1))
    }

    @Test
    fun `overlapping intervals detected`() {
        val a = HalfOpenInterval(t0, t2)
        val b = HalfOpenInterval(t1, null)
        assertTrue(a.overlaps(b))
    }

    @Test
    fun `gap between periods`() {
        val a = HalfOpenInterval(t0, t1)
        val b = HalfOpenInterval(t1.plusSeconds(60), null)
        assertFalse(a.overlaps(b)) // 不是重叠，但留下 60 秒空档（由发布流程负责收束）
    }
}
