package app.service

import app.db.Database
import app.db.insertAndGetId
import app.db.query
import app.db.update
import java.sql.Connection
import java.time.Instant

class PointService(private val db: Database) {

    fun listPoints(stationId: String): List<PointDto> =
        db.tx { conn -> listPoints(conn, stationId) }

    fun listPoints(conn: Connection, stationId: String): List<PointDto> = conn.query(
        """SELECT id, observed_at, level, discharge, trend, excluded, exclude_reason
           FROM measured_point WHERE station_id = ? ORDER BY observed_at""",
        { rs ->
            PointDto(
                id = rs.getLong("id"),
                observedAt = rs.getString("observed_at"),
                level = rs.getDouble("level"),
                discharge = rs.getDouble("discharge"),
                trend = rs.getString("trend"),
                excluded = rs.getInt("excluded") == 1,
                excludeReason = rs.getString("exclude_reason"),
            )
        },
        stationId,
    )

    /** 新增原始实测点；观测值一经写入不再被拟合过程修改。 */
    fun addPoint(req: CreatePointRequest): Long = db.tx { conn ->
        val stationId = req.stationId ?: "ST01"
        conn.insertAndGetId(
            """INSERT INTO measured_point
               (station_id, observed_at, level, discharge, trend, excluded, created_at)
               VALUES (?,?,?,?,?,0,?)""",
            stationId,
            Instant.parse(req.observedAt).toString(),
            req.level,
            req.discharge,
            req.trend,
            Instant.now().toString(),
        )
    }

    /** 只允许修改“是否排除（如回水）”标记与理由；level/discharge 字段不可更新。 */
    fun setExcluded(id: Long, req: SetExcludedRequest) {
        db.tx { conn ->
            val rows = conn.update(
                "UPDATE measured_point SET excluded = ?, exclude_reason = ? WHERE id = ?",
                if (req.excluded) 1 else 0,
                if (req.excluded) req.reason else null,
                id,
            )
            require(rows == 1) { "实测点不存在: $id" }
        }
    }

    fun listLevelSeries(stationId: String, seriesKey: String): List<LevelPointDto> =
        db.tx { conn -> listLevelSeries(conn, stationId, seriesKey) }

    fun listLevelSeries(conn: Connection, stationId: String, seriesKey: String): List<LevelPointDto> =
        conn.query(
            """SELECT observed_at, level FROM level_series
               WHERE station_id = ? AND series_key = ? ORDER BY observed_at""",
            { rs ->
                val level = rs.getObject("level")?.let { (it as Number).toDouble() }
                LevelPointDto(rs.getString("observed_at"), level)
            },
            stationId, seriesKey,
        )

    fun listSeriesKeys(stationId: String): List<String> =
        db.tx { conn -> listSeriesKeys(conn, stationId) }

    fun listSeriesKeys(conn: Connection, stationId: String): List<String> = conn.query(
        "SELECT DISTINCT series_key AS k FROM level_series WHERE station_id = ? ORDER BY k",
        { rs -> rs.getString("k") },
        stationId,
    )
}
