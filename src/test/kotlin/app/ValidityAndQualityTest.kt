package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** 有效期半开区间语义、四类质量标志、缺失水位不填零。 */
class ValidityAndQualityTest {

    private val conn = TestSupport.newDb()
    private val repo = Repo(conn)
    private val service = RatingService(repo, conn)
    private val sid = TestSupport.station(conn)

    private val goodSegments = listOf(
        SegmentSpec(Branch.COMMON, 0.0, 2.0, true, false, listOf(0.0, 1.0, 0.0)),
        SegmentSpec(Branch.COMMON, 2.0, 6.0, true, true, listOf(-2.0, 2.0, 0.0))
    )

    private fun draftWith(segments: List<SegmentSpec> = goodSegments): Curve {
        val d = repo.createDraft(sid, "test")
        return repo.saveDraft(d.id, segments, null, d.rev)
    }

    @Test
    fun `同一时刻只用一份已发布曲线且草稿不参与生产`() {
        val d1 = draftWith()
        repo.publish(d1.id, TestSupport.instant(10), "v1", d1.rev)
        // 草稿存在但不可见
        val d2 = draftWith()

        assertEquals(d1.id, repo.publishedCurveAt(sid, TestSupport.instant(10))!!.id)
        assertEquals(d1.id, repo.publishedCurveAt(sid, TestSupport.instant(20))!!.id)
        assertNull(repo.publishedCurveAt(sid, TestSupport.instant(9))) // 生效前无曲线
        assertNotEquals(d2.id, repo.publishedCurveAt(sid, TestSupport.instant(20))!!.id)

        // v2 在 day30 生效：半开 [10,30) 仍取 v1，day30 整点取 v2
        repo.publish(d2.id, TestSupport.instant(30), "v2", d2.rev)
        assertEquals(d1.id, repo.publishedCurveAt(sid, TestSupport.instant(29))!!.id)
        assertEquals(d2.id, repo.publishedCurveAt(sid, TestSupport.instant(30))!!.id)
        assertEquals(d2.id, repo.publishedCurveAt(sid, TestSupport.instant(100))!!.id)
    }

    @Test
    fun `valid_from 必须严格递增重叠生效区间被拒绝`() {
        val d1 = draftWith()
        repo.publish(d1.id, TestSupport.instant(10), "v1", d1.rev)
        val d2 = draftWith()
        assertThrows(ConflictException::class.java) {
            repo.publish(d2.id, TestSupport.instant(10), "same", d2.rev)
        }
        assertThrows(ConflictException::class.java) {
            repo.publish(d2.id, TestSupport.instant(5), "earlier", d2.rev)
        }
    }

    @Test
    fun `发布原子性：段校验失败时不产生发布记录曲线仍为草稿`() {
        // 两段重叠且开闭不互补
        val bad = listOf(
            SegmentSpec(Branch.COMMON, 0.0, 2.0, true, true, listOf(0.0, 1.0)),
            SegmentSpec(Branch.COMMON, 2.0, 6.0, true, true, listOf(-2.0, 2.0))
        )
        val d = draftWith(bad)
        assertThrows(ValidationException::class.java) {
            repo.publish(d.id, TestSupport.instant(1), "bad", d.rev)
        }
        // 事务回滚：仍为草稿、无发布记录
        assertEquals(CurveStatus.DRAFT, repo.getCurve(d.id).status)
        var pubs = 0
        conn.query("SELECT COUNT(*) AS n FROM curve_publication") { pubs = getInt("n") }
        assertEquals(0, pubs)
    }

    @Test
    fun `缺失 超界 分支不定 溢出四种质量标志互不相同且流量为 null`() {
        // 构造一条已发布曲线：低水 COMMON，高水只有 RISING 支
        val segs = listOf(
            SegmentSpec(Branch.COMMON, 0.0, 2.0, true, true, listOf(0.0, 1.0, 0.0)),
            // 高水涨、落支均存在：下落支为普通直线，上涨支含巨系数三次项用于制造溢出
            SegmentSpec(Branch.FALLING, 2.0, 6.0, false, true, listOf(-2.0, 2.0, 0.0)),
            SegmentSpec(Branch.RISING, 2.0, 6.0, false, true,
                listOf(0.0, 0.0, 0.0, Double.MAX_VALUE / 2))
        )
        val d = draftWith(segs)
        val pub = repo.publish(d.id, TestSupport.instant(0), "v", d.rev)

        val station = repo.getStation(sid)
        val stage = listOf(
            StagePoint(TestSupport.instant(0), null),       // 缺失
            StagePoint(TestSupport.instant(1), 1.0),        // OK（首点趋势未知，但落在 COMMON）
            StagePoint(TestSupport.instant(2), 8.0),        // 上涨超界
            StagePoint(TestSupport.instant(3), 3.0),        // 自 8.0 下落 -> FALLING 普通段 OK
            StagePoint(TestSupport.instant(4), 3.002),      // 死区内、高水只有涨落支 -> 分支不定
            StagePoint(TestSupport.instant(5), 3.5)         // 上涨 -> RISING 三次段溢出
        )
        val out = service.rateSequence(pub, stage, station, null)
        assertEquals(Quality.MISSING_STAGE, out[0].quality)
        assertNull(out[0].dischargeM3s)
        assertEquals(Quality.OK, out[1].quality)
        assertEquals(1.0, out[1].dischargeM3s!!, 1e-12)
        assertEquals(Quality.OUT_OF_RANGE, out[2].quality)
        assertNull(out[2].dischargeM3s)
        assertEquals(Quality.OK, out[3].quality)
        assertEquals(Quality.BRANCH_UNDETERMINED, out[4].quality)
        assertNull(out[4].dischargeM3s)
        assertEquals(Quality.OVERFLOW, out[5].quality)
        assertNull(out[5].dischargeM3s)
        // 四类非 OK 标志确实是不同枚举
        val badQualities = out.filter { it.quality != Quality.OK }.map { it.quality }.toSet()
        assertEquals(
            setOf(Quality.MISSING_STAGE, Quality.OUT_OF_RANGE, Quality.OVERFLOW,
                Quality.BRANCH_UNDETERMINED), badQualities
        )
        // 没有任何一个非 OK 点被偷偷写成 0
        assertTrue(out.none { it.quality != Quality.OK && it.dischargeM3s != null })
    }

    @Test
    fun `原始水位缺失不参与趋势且不被填补`() {
        val station = repo.getStation(sid)
        val p = listOf(
            StagePoint(TestSupport.instant(0), 1.0),
            StagePoint(TestSupport.instant(1), null),
            StagePoint(TestSupport.instant(2), 1.5)
        )
        // 跨缺失点仍以最近有效水位判定上涨
        assertEquals(Trend.RISING, RatingMath.trendAt(p, 2, 0.03, 9 * 86400))
        val d0 = draftWith(listOf(SegmentSpec(Branch.COMMON, 0.0, 6.0, true, true,
            listOf(0.0, 1.0))))
        val curve = repo.publish(d0.id, TestSupport.instant(0), "v", d0.rev)
        val out = service.rateSequence(curve, p, station, null)
        assertEquals(Quality.MISSING_STAGE, out[1].quality)
        assertNull(out[1].stageM)
        assertNull(out[1].dischargeM3s)
    }
}
