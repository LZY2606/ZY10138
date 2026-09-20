package app

import app.db.Database
import app.db.query
import app.db.update
import app.domain.*
import app.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class ServiceIntegrationTest {

    private lateinit var db: Database
    private lateinit var points: PointService
    private lateinit var curves: CurveService
    private lateinit var recompute: RecomputeService
    private lateinit var preview: PreviewService

    private val station = "T1"
    private val seriesA = "a"
    private val seriesB = "b"

    private fun commonSegs(slope: Double = 1.0) = listOf(
        SegmentDef(Branch.COMMON, 1.0, 3.0, Endpoint.CLOSED, Endpoint.OPEN, listOf(0.0, slope)),
        SegmentDef(Branch.COMMON, 3.0, 10.0, Endpoint.CLOSED, Endpoint.CLOSED, listOf(0.0, slope)),
    )

    @BeforeEach
    fun setup() {
        db = Database.memory()
        points = PointService(db)
        curves = CurveService(db)
        recompute = RecomputeService(db, curves, points)
        preview = PreviewService(db, curves, points, recompute)

        db.tx { conn ->
            conn.update(
                "INSERT INTO station (station_id, name, branch_deadband, created_at) VALUES (?,?,?,?)",
                station, "测试站", 0.02, Instant.now().toString(),
            )
        }
    }

    private fun addLevels(seriesKey: String, values: List<Double?>, start: String = "2026-01-01T00:00:00Z") {
        db.tx { conn ->
            values.forEachIndexed { i, v ->
                conn.update(
                    "INSERT INTO level_series (station_id, series_key, observed_at, level) VALUES (?,?,?,?)",
                    station, seriesKey,
                    Instant.parse(start).plusSeconds(i.toLong() * 3600).toString(), v,
                )
            }
        }
    }

    private fun saveAndPublish(segs: List<SegmentDef>, start: String, reason: String = "r"): StoredCurve {
        val saved = curves.saveCurve(SaveCurveRequest(station, null, 0, segs.map { it.toDto() }, reason))
        curves.publish(PublishRequest(station, saved.id, start, null))
        return saved
    }

    @Test
    fun `raw observations are never rewritten by recompute`() {
        addLevels(seriesA, listOf(2.0, null, 4.0))
        saveAndPublish(commonSegs(), "2026-01-01T00:00:00Z")
        recompute.recompute(RecomputeRequest("j1", station, seriesA))
        val raw = points.listLevelSeries(station, seriesA)
        assertEquals(listOf(2.0, null, 4.0), raw.map { it.level })
    }

    @Test
    fun `missing level yields missing flag not zero`() {
        addLevels(seriesA, listOf(2.0, null, 4.0))
        saveAndPublish(commonSegs(), "2026-01-01T00:00:00Z")
        recompute.recompute(RecomputeRequest("j1", station, seriesA))
        val d = recompute.derived(station, seriesA)
        assertEquals(Quality.MISSING_LEVEL.code, d[1].quality)
        assertNull(d[1].discharge)
        // 缺测不影响后一点的涨落参考（此处为 common 曲线，重点是不抛错且有结果）。
        assertEquals(Quality.OK.code, d[2].quality)
        assertEquals(4.0, d[2].discharge)
    }

    @Test
    fun `no active curve at a time is a distinct flag`() {
        // 曲线在第二个时刻才生效：第一点无曲线。
        addLevels(seriesA, listOf(2.0, 4.0))
        saveAndPublish(commonSegs(), "2026-01-01T01:00:00Z")
        recompute.recompute(RecomputeRequest("j1", station, seriesA))
        val d = recompute.derived(station, seriesA)
        assertEquals(Quality.NO_ACTIVE_CURVE.code, d[0].quality)
        assertNull(d[0].discharge)
        assertEquals(Quality.OK.code, d[1].quality)
    }

    @Test
    fun `same job id retry does not create second set`() {
        addLevels(seriesA, listOf(2.0, 4.0))
        saveAndPublish(commonSegs(), "2026-01-01T00:00:00Z")
        val first = recompute.recompute(RecomputeRequest("dup", station, seriesA))
        val second = recompute.recompute(RecomputeRequest("dup", station, seriesA))
        assertEquals(first.id, second.id)
        assertTrue(second.retried)
        assertEquals(1, db.tx { c -> c.query("SELECT COUNT(*) AS n FROM recompute_job", { it.getInt("n") })[0] })
        // 派生序列仍只有每个时刻一行。
        assertEquals(2, recompute.derived(station, seriesA).size)
    }

    @Test
    fun `recompute only touches explicitly selected series`() {
        addLevels(seriesA, listOf(2.0))
        addLevels(seriesB, listOf(2.0))
        saveAndPublish(commonSegs(), "2026-01-01T00:00:00Z")
        recompute.recompute(RecomputeRequest("ja", station, seriesA))
        assertTrue(recompute.derived(station, seriesB).isEmpty())
        assertEquals(1, recompute.derived(station, seriesA).size)
    }

    @Test
    fun `publishing new curve does not alter old derived until explicit recompute`() {
        addLevels(seriesA, listOf(2.0, 4.0))
        val v1 = saveAndPublish(commonSegs(1.0), "2026-01-01T00:00:00Z", "v1")
        recompute.recompute(RecomputeRequest("j1", station, seriesA))
        val before = recompute.derived(station, seriesA).map { it.discharge }
        assertEquals(listOf(2.0, 4.0), before)

        // 发布 v2（斜率 2 倍），自 2026-02-01 生效；不重算则生产派生保持不变。
        val v2 = curves.saveCurve(SaveCurveRequest(station, null, 0, commonSegs(2.0).map { it.toDto() }, "v2"))
        curves.publish(PublishRequest(station, v2.id, "2026-02-01T00:00:00Z", null))
        assertEquals(before, recompute.derived(station, seriesA).map { it.discharge })

        // 显式重算后值改变，且历史点仍由 v1 复现（因为时刻在 v1 生效窗内）。
        recompute.recompute(RecomputeRequest("j2", station, seriesA))
        val after = recompute.derived(station, seriesA)
        // 时刻都在 1 月，仍命中 v1 ⇒ 值不变；v2 的生效期从 2 月才开始。
        assertEquals(listOf(2.0, 4.0), after.map { it.discharge })
        assertEquals(listOf(v1.id, v1.id), after.map { it.curveId })
    }

    @Test
    fun `a point at the boundary between periods uses the later curve`() {
        addLevels(seriesA, listOf(2.0, 2.0), start = "2026-01-01T00:00:00Z")
        val v1 = saveAndPublish(commonSegs(1.0), "2026-01-01T00:00:00Z", "v1")
        val v2 = curves.saveCurve(SaveCurveRequest(station, null, 0, commonSegs(3.0).map { it.toDto() }, "v2"))
        curves.publish(PublishRequest(station, v2.id, "2026-01-01T01:00:00Z", null))
        recompute.recompute(RecomputeRequest("j1", station, seriesA))
        val d = recompute.derived(station, seriesA)
        assertEquals(v1.id, d[0].curveId)
        assertEquals(v2.id, d[1].curveId) // t=01:00:00 半开：v2 生效
        assertEquals(6.0, d[1].discharge)
        val periods = curves.listPeriods(station)
        assertEquals(2, periods.size)
        assertEquals("2026-01-01T01:00:00Z", periods[0].endAt)
    }

    @Test
    fun `overlapping periods are rejected atomically`() {
        val v1 = saveAndPublish(commonSegs(1.0), "2026-01-01T00:00:00Z", "v1")
        val v2 = curves.saveCurve(SaveCurveRequest(station, null, 0, commonSegs(2.0).map { it.toDto() }, "v2"))
        // v1 是开放区间；试图在其中插入起点更早的区间必然重叠。
        assertThrows(Throwable::class.java) {
            curves.publish(PublishRequest(station, v2.id, "2025-12-01T00:00:00Z", null))
        }
        // 失败后不产生任何新生效期，且 v2 仍为草稿。
        assertEquals(1, curves.listPeriods(station).size)
        assertEquals(CurveStatus.DRAFT, curves.getCurve(v2.id).status)
    }

    @Test
    fun `invalid segments cannot be saved or published`() {
        val bad = listOf(
            SegmentDef(Branch.COMMON, 1.0, 3.0, Endpoint.CLOSED, Endpoint.CLOSED, listOf(0.0, 1.0)),
            SegmentDef(Branch.COMMON, 3.0, 10.0, Endpoint.CLOSED, Endpoint.CLOSED, listOf(0.0, 1.0)),
        )
        assertThrows(ValidationException::class.java) {
            curves.saveCurve(SaveCurveRequest(station, null, 0, bad.map { it.toDto() }, "bad"))
        }
        assertEquals(0, curves.listCurves(station).size)
    }

    @Test
    fun `concurrent edit on stale revision returns a conflict with diff`() {
        val v1 = curves.saveCurve(SaveCurveRequest(station, null, 0, commonSegs(1.0).map { it.toDto() }, "v1"))
        // 客户端甲基于 rev1 修改；与此同时客户端乙先保存为 rev2。
        val other = commonSegs(2.0)
        val v2 = curves.saveCurve(SaveCurveRequest(station, v1.id, v1.revision, other.map { it.toDto() }, "乙"))
        // 甲仍以 baseRevision=1 提交（基于它打开编辑时的版本）⇒ 冲突，
        // 且拿到服务端当前分段与差异。
        val ex = assertThrows(ConflictException::class.java) {
            curves.saveCurve(SaveCurveRequest(station, v2.id, 1, commonSegs(5.0).map { it.toDto() }, "甲"))
        }
        assertEquals(1, ex.baseRevision)
        assertEquals(v2.revision, ex.currentRevision)
        assertTrue(ex.serverSegments.isNotEmpty())
        // 斜率变化属“同位置但内容改变”。
        assertTrue(ex.changedInPlace.isNotEmpty() || ex.onlyInClient.isNotEmpty())
    }

    @Test
    fun `preview never writes production and lists changed days`() {
        addLevels(seriesA, listOf(2.0, 4.0))
        saveAndPublish(commonSegs(1.0), "2026-01-01T00:00:00Z")
        recompute.recompute(RecomputeRequest("j1", station, seriesA))

        val resp = preview.preview(
            PreviewRequest(station, seriesA, commonSegs(2.0).map { it.toDto() })
        )
        assertEquals(2, resp.changed)
        assertEquals(2, resp.points.count { it.changed })
        assertEquals(listOf(4.0, 8.0), resp.points.map { it.newDischarge })
        // 预览后生产数据不变。
        assertEquals(listOf(2.0, 4.0), recompute.derived(station, seriesA).map { it.discharge })
        // 没有产生作业。
        assertEquals(1, db.tx { c -> c.query("SELECT COUNT(*) AS n FROM recompute_job", { it.getInt("n") })[0] })
    }

    @Test
    fun `excluded backwater points do not participate in fit`() {
        points.addPoint(CreatePointRequest(station, "2026-01-01T00:00:00Z", 1.0, 10.0))
        points.addPoint(CreatePointRequest(station, "2026-01-02T00:00:00Z", 2.0, 20.0))
        points.addPoint(CreatePointRequest(station, "2026-01-03T00:00:00Z", 2.5, 99.0)) // 回水
        val all = points.listPoints(station)
        points.setExcluded(all.first { it.discharge == 99.0 }.id, SetExcludedRequest(true, "回水顶托"))
        val fit = curves.fit(station, FitRequest(station, "common", 1))
        // 只用前两点拟合 Q = h*10 ⇒ 斜率 10；若混入 99 会明显不同。
        assertEquals(2, fit.usedPointIds.size)
        assertEquals(10.0, fit.segment.coeffs[1], 1e-6)
    }
}
