package app

import java.time.Instant

object TestSupport {
    fun newDb(): java.sql.Connection {
        Class.forName("org.sqlite.JDBC")
        val c = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        c.autoCommit = true
        c.initSchema()
        return c
    }

    fun station(c: java.sql.Connection, deadband: Double = 0.03, maxAgeSec: Long = 3 * 86400): Long {
        return c.insert(
            "INSERT INTO station(code,name,deadband_m,max_age_seconds) VALUES(?,?,?,?)",
            "T", "test", deadband, maxAgeSec
        )
    }

    fun instant(day: Long): Instant = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(day * 86400)

    fun quad(c0: Double, c1: Double, c2: Double) = listOf(c0, c1, c2)
}
