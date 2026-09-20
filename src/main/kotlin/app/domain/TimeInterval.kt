package app.domain

import java.time.Instant

/**
 * 生效期采用半开区间 [start, end)：
 *  - start 闭合：曲线在 start 时刻起生效；
 *  - end 开：曲线在 end 时刻起不再生效；
 *  - end == null 表示 +∞。
 *
 * 一个时刻 t 恰好命中一个生效期 ⇔ start <= t < end。
 */
data class HalfOpenInterval(val start: Instant, val end: Instant?) {

    fun contains(t: Instant): Boolean =
        !t.isBefore(start) && (end == null || t.isBefore(end))

    /** 与另一区间是否在任意正长度（或公共端点时刻）上重叠。 */
    fun overlaps(other: HalfOpenInterval): Boolean {
        val a = start
        val b = end
        val c = other.start
        val d = other.end
        if (d != null && !a.isBefore(d)) return false
        if (b != null && !c.isBefore(b)) return false
        return true
    }
}

/**
 * 判断相邻两条生效期是否“恰好接续”：前段 end 必须等于后段 start。
 * 半开语义下 t == end 时前段失效、后段生效，时刻无重叠无空档。
 */
fun abuts(leftEnd: Instant?, rightStart: Instant): Boolean =
    leftEnd != null && leftEnd == rightStart
