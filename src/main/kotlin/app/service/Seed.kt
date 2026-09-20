package app.service

import app.db.Database
import app.db.insertAndGetId
import app.db.query
import app.db.queryOne
import app.db.update
import app.domain.*
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random

/**
 * 生成确定性演示数据：
 *  - 一个站点、若干原始实测点（含明确受回水影响需排除的点）；
 *  - 6 小时一条的水位序列，含 1 个原始缺测和 1 个超出标定范围的水位；
 *  - v1 已发布通用曲线（生效期从起点到 +∞）并完成一次生产重算；
 *  - v2 上涨/下落分支草稿，供在页面上继续编辑、预览与发布。
 */
object Seed {
    const val STATION = "ST01"
    const val SERIES = "daily"
    private const val BASE = "2026-09-01T00:00:00Z"
    private const val HOURS = 6

    // 两段连续二次曲线，交界 h=3：左段 [1,3) 右端开，右段 [3,5] 左端闭。
    private fun commonSegments(scale: Double): List<SegmentDef> = listOf(
        SegmentDef(
            Branch.COMMON, 1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN,
            listOf(3.5, 1.0, 0.5).map { it * scale },
            note = "低水段",
        ),
        SegmentDef(
            Branch.COMMON, 3.0, 5.0, Endpoint.CLOSED, Endpoint.CLOSED,
            listOf(14.3, -3.8, 0.9).map { it * scale },
            note = "高水段",
        ),
    )

    private fun branchSegments(): List<SegmentDef> {
        val rising = commonSegments(1.04).map { it.copy(branch = Branch.RISING) }
        val falling = commonSegments(0.96).map { it.copy(branch = Branch.FALLING) }
        return rising + falling
    }

    fun seedIfEmpty(db: Database) {
        db.tx { conn ->
            val count = conn.queryOne("SELECT COUNT(*) AS c FROM station", { rs -> rs.getInt("c") })!!
            if (count > 0) return@tx

            conn.insertAndGetId(
                "INSERT INTO station (station_id, name, branch_deadband, created_at) VALUES (?,?,?,?)",
                STATION, "示范水文站", 0.02, Instant.now().toString(),
            )

            // ---- 原始实测点（确定性小噪声）----
            val rnd = Random(42)
            val levels = (10..48).map { 1.0 + (it % 40) / 10.0 }.distinct()
            levels.sorted().forEachIndexed { idx, h ->
                val trueQ = commonSegments(1.0).first { it.contains(h) }.evaluate(h)
                val noise = (rnd.nextDouble() - 0.5) * 0.6
                val at = Instant.parse(BASE).plusSeconds(idx.toLong() * 12 * 3600)
                val trend = when {
                    idx == 0 -> null
                    h > levels.sorted().let { it[(idx - 1).coerceAtLeast(0)] } -> "rising"
                    h < levels.sorted()[idx - 1] -> "falling"
                    else -> null
                }
                conn.insertAndGetId(
                    """INSERT INTO measured_point
                       (station_id, observed_at, level, discharge, trend, excluded, created_at)
                       VALUES (?,?,?,?,?,0,?)""",
                    STATION, at.toString(), round2(h), round2(trueQ + noise), trend,
                    Instant.now().toString(),
                )
            }
            // 明确受回水影响的两个点（水位不高但流量虚高），默认排除。
            listOf(
                2.2 to 42.0,
                2.6 to 47.5,
            ).forEachIndexed { i, (h, q) ->
                val at = Instant.parse(BASE).plusSeconds((200 + i).toLong() * 3600)
                val id = conn.insertAndGetId(
                    """INSERT INTO measured_point
                       (station_id, observed_at, level, discharge, trend, excluded, exclude_reason, created_at)
                       VALUES (?,?,?,?,?,1,'回水顶托，水位-流量关系失真',?)""",
                    STATION, at.toString(), h, q, "falling", Instant.now().toString(),
                )
                id
            }

            // ---- 水位序列（含缺测与超量程）----
            val n = 40
            for (i in 0 until n) {
                val at = Instant.parse(BASE).plusSeconds(i.toLong() * HOURS * 3600)
                val level: Double? = when (i) {
                    10 -> null                  // 原始水位缺失
                    25 -> 5.6                   // 超出标定上限 5.0
                    else -> {
                        val wave = 3.0 + 1.6 * Math.sin(i / 3.2) + 0.25 * Math.sin(i / 0.7)
                        round2(wave.coerceIn(0.9, 5.7))
                    }
                }
                conn.insertAndGetId(
                    "INSERT INTO level_series (station_id, series_key, observed_at, level) VALUES (?,?,?,?)",
                    STATION, SERIES, at.toString(), level,
                )
            }

            // ---- v1 已发布曲线 ----
            val now = Instant.now().toString()
            val v1Id = conn.insertAndGetId(
                """INSERT INTO rating_curve (station_id, revision, status, reason, created_at, published_at)
                   VALUES (?,1,'published','初始率定（基于汛前实测点）',?,?)""",
                STATION, now, now,
            )
            val v1 = commonSegments(1.0)
            v1.forEachIndexed { ord, s ->
                conn.update(
                    """INSERT INTO rating_segment
                       (curve_id, ord, branch, level_low, level_high, low_open, high_open, coeffs_json, note)
                       VALUES (?,?,?,?,?,?,?,?,?)""",
                    v1Id, ord, s.branch.code, s.levelLow, s.levelHigh,
                    s.lowOpen.code, s.highOpen.code, Json.write(s.coeffs), s.note,
                )
            }
            conn.insertAndGetId(
                "INSERT INTO effective_period (station_id, curve_id, start_at, end_at) VALUES (?,?,?,?)",
                STATION, v1Id, BASE, app.db.Schema.SENTINEL_MAX,
            )

            // ---- v2 涨/落分支草稿（未发布，不进入生产）----
            val v2Id = conn.insertAndGetId(
                "INSERT INTO rating_curve (station_id, revision, status, reason, created_at, published_at) VALUES (?,2,'draft','洪峰过程发现涨落绳套，建立分支',?,NULL)",
                STATION, Instant.now().toString(),
            )
            branchSegments().forEachIndexed { ord, s ->
                conn.update(
                    """INSERT INTO rating_segment
                       (curve_id, ord, branch, level_low, level_high, low_open, high_open, coeffs_json, note)
                       VALUES (?,?,?,?,?,?,?,?,?)""",
                    v2Id, ord, s.branch.code, s.levelLow, s.levelHigh,
                    s.lowOpen.code, s.highOpen.code, Json.write(s.coeffs), s.note,
                )
            }
        }

        // 初始生产派生：用显式作业 ID，保证可复现。
        val curves = CurveService(db)
        val points = PointService(db)
        val recompute = RecomputeService(db, curves, points)
        recompute.recompute(
            RecomputeRequest(
                jobUuid = "seed-recompute-v1",
                stationId = STATION,
                seriesKey = SERIES,
            )
        )
    }

    private fun round2(x: Double) = abs(x * 100).let { Math.round(it).toDouble() / 100.0 } * if (x < 0) -1 else 1
}
