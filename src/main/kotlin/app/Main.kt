package app

import app.db.Database
import app.service.*
import app.web.ApiServer
import java.nio.file.Paths

fun main(args: Array<String>) {
    var port = 5338
    var dbPath = "data/rating.db"
    var seed = true
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { dbPath = args[++i] }
            "--no-seed" -> { seed = false }
            else -> throw IllegalArgumentException("未知参数: ${args[i]}")
        }
        i++
    }

    val db = Database.open(Paths.get(dbPath))
    if (seed) Seed.seedIfEmpty(db)

    val points = PointService(db)
    val curves = CurveService(db)
    val recompute = RecomputeService(db, curves, points)
    val preview = PreviewService(db, curves, points, recompute)
    val stations = StationService(db)

    val api = ApiServer(points, curves, recompute, preview, stations)
    api.start(port)
    println("率定曲线离线系统已启动: http://127.0.0.1:$port （数据库 $dbPath）")
}
