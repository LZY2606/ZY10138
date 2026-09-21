package app

import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * 确定性演示数据：
 *  - 实测点按 COMMON/RISING/FALLING 三组“真值多项式 + 固定伪噪声”生成；
 *  - 一个明显受回水顶托的点默认 excluded=1，供用户在页面上排除/恢复；
 *  - 已发布 v1 曲线（2026-01-01 生效，定义域 0.5..6.0）；
 *  - 草稿 v2（由 v1 复制，等待用户拟合/调整）；
 *  - 2026-08-01 起的日水位序列，含缺失、超 v1 上限的洪峰、平水死区；
 *  - 初始派生流量按 v1 重算，页面上可以预览 v2 差异并显式重算。
 */
object Seed {

    private val T0 = Instant.parse("2026-01-01T00:00:00Z")

    // 真值系数（升幂），v1 即按此发布
    val COMMON_COEFF = listOf(2.0, 8.0, 0.4)          // 0.5..2.5
    val RISING_COEFF = listOf(2.75, 7.4, 0.52)        // 2.5..6.0，x=2.5 处连续
    val FALLING_COEFF = listOf(3.05, 7.16, 0.472)     // 2.5..6.0，x=2.5 处连续

    private fun noise(i: Int): Double {
        // 固定、平滑、零均值附近的小扰动
        val a = sin(i * 12.9898) * 43758.5453
        val frac = a - kotlin.math.floor(a)
        return (frac - 0.5) * 0.10
    }

    private fun q(coeff: List<Double>, x: Double): Double =
        RatingMath.evalPolynomial(coeff, x)!!

    fun seedIfEmpty(c: Connection) {
        val stationCount = (0L).let {
            var n = 0
            c.query("SELECT COUNT(*) AS n FROM station") { n = getInt("n") }
            n
        }
        if (stationCount > 0) return
        c.tx {
            val stationId = c.insert(
                "INSERT INTO station(code,name,deadband_m,max_age_seconds) VALUES(?,?,?,?)",
                "ST-01", "示范水文站", 0.03, 3L * 24 * 3600
            )

            var idx = 0
            fun addPoint(day: Long, stage: Double, coeff: List<Double>, branch: Branch,
                         excluded: Boolean = false, note: String? = null) {
                val n = if (excluded) 0.0 else noise(idx++)
                c.insert(
                    "INSERT INTO measurement(station_id,observed_at,stage_m,discharge_m3s,branch_hint,excluded,note)" +
                        " VALUES(?,?,?,?,?,?,?)",
                    stationId, T0.plus(day, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS),
                    round3(stage), round2(q(coeff, stage) + n), branch,
                    if (excluded) 1 else 0, note
                )
            }

            // COMMON 低水点 0.6..2.5（上界恰为分支接缝）
            var day = 10L
            for (k in 0..7) {
                val h = 0.6 + 1.9 * k / 7.0
                addPoint(day++, h, COMMON_COEFF, Branch.COMMON)
            }
            // RISING 上涨点 2.5..5.9（下界恰为分支接缝）
            for (k in 0..7) {
                val h = 2.5 + 3.4 * k / 7.0
                addPoint(day++, h, RISING_COEFF, Branch.RISING)
            }
            // FALLING 下落点 2.5..5.9
            for (k in 0..7) {
                val h = 2.5 + 3.4 * k / 7.0
                addPoint(day++, h, FALLING_COEFF, Branch.FALLING)
            }
            // 回水顶托点：同水位下实测流量明显偏大，默认排除
            addPoint(
                day + 2, 4.2, listOf(0.0, 0.0, 0.0), Branch.FALLING,
                excluded = true, note = "下游闸坝蓄水，回水顶托，用户标记排除"
            ).also {
                // 直接写入一个异常大的实测流量
                c.update(
                    "UPDATE measurement SET discharge_m3s=? WHERE id=?",
                    round2(q(FALLING_COEFF, 4.2) + 55.0), it
                )
            }

            // v1 已发布曲线
            val v1 = c.insert(
                "INSERT INTO rating_curve(station_id,status,version,revised_reason,rev,created_at,updated_at)" +
                    " VALUES(?,?,?,?,?,?,?)",
                stationId, CurveStatus.PUBLISHED, 1, "初始率定：低水公共段 + 涨/落支二次拟合",
                "rev-v1", T0, T0
            )
            insertSegments(c, v1, publishedV1Segments())
            c.insert(
                "INSERT INTO curve_publication(station_id,curve_id,valid_from,published_at,reason) VALUES(?,?,?,?,?)",
                stationId, v1, T0, T0, "初始发布"
            )

            // v2 草稿（复制 v1，等待编辑/拟合）
            val v2 = c.insert(
                "INSERT INTO rating_curve(station_id,status,version,revised_reason,rev,created_at,updated_at)" +
                    " VALUES(?,?,?,?,?,?,?)",
                stationId, CurveStatus.DRAFT, 2,
                "修订：排除回水点后重新拟合，准备把上延至 7.0m",
                "rev-v2-0", Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:00:00Z")
            )
            insertSegments(c, v2, publishedV1Segments())

            // 日水位序列 2026-08-01 .. 2026-09-15（含缺失、平水、超 v1 上限）
            val start = Instant.parse("2026-08-01T00:00:00Z")
            val stages = doubleArrayOf(
                1.20, 1.22, 1.45, 1.80, 2.20, 2.60, 3.10, 3.70, 4.30, 4.90,
                5.40, 5.80, 6.20, 6.40, 5.90, 5.30, 4.70, 4.10,
                Double.NaN, // 08-19 原始水位缺失（记录仪故障）
                3.50, 3.10, 2.70, 3.40, 3.41, 3.40, 3.42,
                // 高水回落途中出现平水：只有涨/落支且死区内 -> BRANCH_UNDETERMINED
                2.30, 2.00, 1.70, 1.50, 1.51, 1.50,
                Double.NaN, // 09-02 缺失
                1.60, 2.00, 2.50, 3.20, 4.00, 4.80, 5.50, 6.10,
                5.60, 4.90, 4.20, 3.40, 2.80
            )
            for (i in stages.indices) {
                val h = stages[i]
                c.insert(
                    "INSERT INTO stage_series(station_id,ts,stage_m) VALUES(?,?,?)",
                    stationId, start.plus(i.toLong(), ChronoUnit.DAYS),
                    if (h.isNaN()) null else h
                )
            }
        }
    }

    fun publishedV1Segments(): List<SegmentSpec> = listOf(
        SegmentSpec(Branch.COMMON, 0.5, 2.5, true, true, COMMON_COEFF.map { round4(it) }),
        SegmentSpec(Branch.RISING, 2.5, 6.0, false, true, RISING_COEFF.map { round4(it) }),
        SegmentSpec(Branch.FALLING, 2.5, 6.0, false, true, FALLING_COEFF.map { round4(it) })
    )

    private fun insertSegments(c: Connection, curveId: Long, segs: List<SegmentSpec>) {
        segs.forEachIndexed { i, s ->
            c.insert(
                "INSERT INTO curve_segment(curve_id,branch,stage_low,stage_high,low_include,high_include,position,coefficients)" +
                    " VALUES(?,?,?,?,?,?,?,?)",
                curveId, s.branch, s.stageLow, s.stageHigh,
                if (s.lowInclude) 1 else 0, if (s.highInclude) 1 else 0,
                i, Json.encodeCoeff(s.coefficients)
            )
        }
    }

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
    private fun round4(x: Double) = Math.round(x * 10000.0) / 10000.0
}
