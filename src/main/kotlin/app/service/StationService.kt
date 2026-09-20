package app.service

import app.db.Database
import app.db.insertAndGetId
import app.db.query

class StationService(private val db: Database) {
    fun list(): List<StationDto> = db.tx { conn ->
        conn.query(
            "SELECT station_id, name, branch_deadband FROM station ORDER BY station_id",
            { rs ->
                StationDto(
                    stationId = rs.getString("station_id"),
                    name = rs.getString("name"),
                    branchDeadband = rs.getDouble("branch_deadband"),
                )
            },
        )
    }
}
