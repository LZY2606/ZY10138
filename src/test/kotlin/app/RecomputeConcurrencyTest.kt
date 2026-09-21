package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 重算原子性/幂等边界、乐观并发、拟合不改原始数据、预览不入库。 */
class RecomputeConcurrencyTest {

    private val conn = TestSupport.newDb()
    private val repo = Repo(conn)
    private val service = RatingService(repo, conn)
    private val sid = TestSupport.station(conn)

    private val segsV1 = listOf(
        SegmentSpec(Branch.COMMON, 0.0, 2.0, true, false, listOf(0.0, 1.0, 0.0)),
        SegmentSpec(Branch.COMMON, 2.0, 6.0, true, true, listOf(-2.0, 2.0, 0.0))
    )
    private val segsV2 = listOf(
        SegmentSpec(Branch.COMMON, 0.0, 2.0, true, false, listOf(0.0, 1.1, 0.0)),
        SegmentSpec(Branch.COMMON, 2.0, 7.0, true, true, listOf(-2.2, 2.1, 0.0))
    )

    private fun makePublished(segments: List<SegmentSpec>, validFromDay: Long, reason: String): Curve {
        val d = repo.createDraft(sid, reason)
        val saved = repo.saveDraft(d.id, segments, null, d.rev)
        return repo.publish(saved.id, TestSupport.instant(validFromDay), reason, saved.rev)
    }

    private fun loadStage() {
        val points = listOf(
            StagePoint(TestSupport.instant(10), 1.0),
            StagePoint(TestSupport.instant(11), 1.5),
            StagePoint(TestSupport.instant(12), null),
            StagePoint(TestSupport.instant(13), 6.5)
        )
        repo.upsertStage(sid, points)
    }

    @Test
    fun `相同作业 ID 重试不生成第二套序列`() {
        val v1 = makePublished(segsV1, 0, "v1")
        loadStage()
        val from = TestSupport.instant(10)
        val to = TestSupport.instant(15)

        val r1 = service.recompute("job-fixed", sid, from, to, RecomputeMode.CURVE, v1.id)
        assertFalse(r1.idempotentRetry)
        val rowsAfterFirst = countDischarge()
        val curvesAfterFirst = distinctCurveIds()

        // 同 ID 立即重试
        val r2 = service.recompute("job-fixed", sid, from, to, RecomputeMode.CURVE, v1.id)
        assertTrue(r2.idempotentRetry)
        assertEquals(r1.changedCount, r2.changedCount)
        assertEquals(rowsAfterFirst, countDischarge())
        assertEquals(curvesAfterFirst, distinctCurveIds())

        // 同 ID 即便参数不同也必须原样返回，绝不重算
        val r3 = service.recompute("job-fixed", sid, from, to, RecomputeMode.PUBLISHED, null)
        assertTrue(r3.idempotentRetry)
        assertEquals(rowsAfterFirst, countDischarge())
    }

    @Test
    fun `不同作业对同一窗口的重算按新曲线原子替换且窗口外不动`() {
        val v1 = makePublished(segsV1, 0, "v1")
        // 窗口外放一条“旧时代”派生行
        repo.upsertStage(sid, listOf(StagePoint(TestSupport.instant(2), 1.0)))
        service.recompute("job-out", sid, TestSupport.instant(2), TestSupport.instant(3),
            RecomputeMode.CURVE, v1.id)
        loadStage()
        val from = TestSupport.instant(10)
        val to = TestSupport.instant(15)
        service.recompute("job-a", sid, from, to, RecomputeMode.CURVE, v1.id)

        val v1Rows = repo.listDischarge(sid, from, to)
        // 6.5 超出 v1 上界 6.0 -> OUT_OF_RANGE 且 null
        val peak = v1Rows.single { it.stageM == 6.5 }
        assertEquals(Quality.OUT_OF_RANGE, peak.quality)

        val v2 = makePublished(segsV2, 20, "v2 上延至 7m")
        val result = service.recompute("job-b", sid, from, to, RecomputeMode.CURVE, v2.id)
        assertTrue(result.changedCount >= 1)

        val v2Rows = repo.listDischarge(sid, from, to)
        val peak2 = v2Rows.single { it.stageM == 6.5 }
        assertEquals(Quality.OK, peak2.quality)
        assertEquals(v2.id, peak2.curveId)
        // 缺失行质量不变、仍为 null
        assertEquals(Quality.MISSING_STAGE, v2Rows.single { it.stageM == null }.quality)
        // 窗口外行仍引用 v1，不被新作业触碰
        val outside = repo.listDischarge(sid, TestSupport.instant(2), TestSupport.instant(3))
        assertEquals(v1.id, outside.single().curveId)
        // 每个时刻仍只有一行（窗口原子替换，无重复）
        assertEquals(4, v2Rows.size)
    }

    @Test
    fun `PUBLISHED 模式按各时刻生效曲线复现历史版本`() {
        val v1 = makePublished(segsV1, 0, "v1")
        val v2 = makePublished(segsV2, 12, "v2")
        loadStage() // day10,11 用 v1；day12 缺失；day13 用 v2
        service.recompute("job-pub", sid, TestSupport.instant(10), TestSupport.instant(15),
            RecomputeMode.PUBLISHED, null)
        val rows = repo.listDischarge(sid, TestSupport.instant(10), TestSupport.instant(15))
        assertEquals(v1.id, rows.single { it.ts == TestSupport.instant(10) }.curveId)
        assertEquals(v1.id, rows.single { it.ts == TestSupport.instant(11) }.curveId)
        assertEquals(v2.id, rows.single { it.ts == TestSupport.instant(13) }.curveId)
        // 缺失行无曲线引用
        assertEquals(null, rows.single { it.ts == TestSupport.instant(12) }.curveId)
    }

    @Test
    fun `过期 rev 保存返回冲突且服务端段随响应给出用于差异比对`() {
        val d0 = repo.createDraft(sid, null)
        val staleRev = d0.rev
        // 甲先保存
        repo.saveDraft(d0.id, segsV1, "甲的修改", staleRev)
        // 乙基于过期 rev 保存 -> 冲突
        val ex = assertThrows(ConflictException::class.java) {
            repo.saveDraft(d0.id, segsV2, "乙的修改", staleRev)
        }
        assertTrue(ex.message!!.contains("rev"))
        val current = repo.getCurve(d0.id)
        assertNotEquals(staleRev, current.rev)
        assertEquals(segsV1.size, current.segments.size) // 保留甲的版本，乙的覆盖失败
    }

    @Test
    fun `已发布曲线不可再修改`() {
        val pub = makePublished(segsV1, 0, "v1")
        assertThrows(ConflictException::class.java) {
            repo.saveDraft(pub.id, segsV2, null, pub.rev)
        }
    }

    @Test
    fun `草稿曲线禁止用于生产重算`() {
        makePublished(segsV1, 0, "v1")
        // 基于 v1 新建一份草稿，但禁止用它做生产重算
        repo.createDraft(sid, "未发布草稿")
        val d = repo.listCurves(sid).last { it.status == CurveStatus.DRAFT }
        loadStage()
        assertThrows(IllegalArgumentException::class.java) {
            service.recompute("job-draft", sid, TestSupport.instant(10),
                TestSupport.instant(15), RecomputeMode.CURVE, d.id)
        }
        assertEquals(0L, countDischarge().toLong())
    }

    @Test
    fun `拟合与预览不改写实测点和原始水位且预览不写派生`() {
        // 准备各分支实测点
        val t0 = TestSupport.instant(0)
        var k = 0
        fun addMeasurement(h: Double, q: Double, b: Branch, excluded: Boolean = false) {
            conn.insert(
                "INSERT INTO measurement(station_id,observed_at,stage_m,discharge_m3s,branch_hint,excluded,note)" +
                    " VALUES(?,?,?,?,?,?,?)",
                sid, t0.plusSeconds((k++ * 3600).toLong()), h, q, b,
                if (excluded) 1 else 0, null
            )
        }
        for (i in 0..5) addMeasurement(0.5 + i * 0.3, 1.0 + i, Branch.COMMON)
        for (i in 0..5) addMeasurement(2.6 + i * 0.6, 5.0 + i * 4.0, Branch.RISING)
        for (i in 0..5) addMeasurement(2.6 + i * 0.6, 4.0 + i * 3.0, Branch.FALLING)
        addMeasurement(4.0, 999.0, Branch.FALLING, excluded = true) // 回水点

        val snapshot = repo.listMeasurements(sid).map { it.id to it.dischargeM3s }
        val draft = repo.createDraft(sid, null)
        val fitted = service.fitDraft(draft.id)
        assertTrue(fitted.segments.isNotEmpty())
        // 原始实测流量不变
        assertEquals(snapshot, repo.listMeasurements(sid).map { it.id to it.dischargeM3s })

        loadStage()
        val before = countDischarge()
        val preview = service.preview(fitted, TestSupport.instant(10), TestSupport.instant(15))
        assertEquals(4, preview.pointCount)
        assertEquals(before, countDischarge()) // 预览没有产生任何派生行
    }

    private fun countDischarge(): Int {
        var n = 0
        conn.query("SELECT COUNT(*) AS c FROM discharge_series") { n = getInt("c") }
        return n
    }

    private fun distinctCurveIds(): Set<Long?> {
        val s = mutableSetOf<Long?>()
        conn.query("SELECT DISTINCT curve_id AS c FROM discharge_series") {
            s += getObject("c")?.let { (it as Number).toLong() }
        }
        return s
    }
}
