package app

import java.sql.DriverManager
import java.time.Instant

/**
 * 入口：
 *   mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5338'
 * 可选：--db data/rating.db（默认）
 */
fun main(args: Array<String>) {
    var port = 5338
    var dbPath = "data/rating.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--db" -> dbPath = args[++i]
            else -> error("未知参数: ${args[i]}")
        }
        i++
    }

    Class.forName("org.sqlite.JDBC")
    val conn = Db.open(dbPath)
    conn.initSchema()
    Seed.seedIfEmpty(conn)

    val repo = Repo(conn)
    val service = RatingService(repo, conn)

    // 首次启动：用“当时已发布曲线”（v1）生成初始派生序列（固定作业 ID，幂等）。
    val stationId = repo.listStations().first().id
    var existing = 0L
    conn.query("SELECT COUNT(*) AS n FROM discharge_series WHERE station_id=?", stationId) {
        existing = getLong("n")
    }
    if (existing == 0L) {
        service.recompute(
            jobId = "bootstrap-v1",
            stationId = stationId,
            from = Instant.parse("2026-08-01T00:00:00Z"),
            to = Instant.parse("2026-09-16T00:00:00Z"),
            mode = RecomputeMode.PUBLISHED,
            curveId = null
        )
        println("已按 v1 初始发布曲线生成历史派生流量（作业 bootstrap-v1）")
    }

    val server = Server(port, repo, service, conn)
    server.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        conn.close()
    })
    Thread.currentThread().join()
}
