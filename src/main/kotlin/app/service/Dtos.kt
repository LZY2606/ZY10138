package app.service

import app.domain.Branch
import app.domain.Endpoint
import app.domain.SegmentDef

// ---------- 实测点 ----------
data class PointDto(
    val id: Long,
    val observedAt: String,
    val level: Double,
    val discharge: Double,
    val trend: String?,
    val excluded: Boolean,
    val excludeReason: String?,
)

data class CreatePointRequest(
    val stationId: String?,
    val observedAt: String,
    val level: Double,
    val discharge: Double,
    val trend: String? = null,
)

data class SetExcludedRequest(val excluded: Boolean, val reason: String? = null)

// ---------- 分段 / 曲线 ----------
data class SegmentDto(
    val branch: String,
    val levelLow: Double,
    val levelHigh: Double,
    val lowOpen: String,
    val highOpen: String,
    val coeffs: List<Double>,
    val note: String? = null,
) {
    fun toDef(): SegmentDef = SegmentDef(
        branch = Branch.of(branch),
        levelLow = levelLow,
        levelHigh = levelHigh,
        lowOpen = Endpoint.of(lowOpen),
        highOpen = Endpoint.of(highOpen),
        coeffs = coeffs,
        note = note,
    )
}

fun SegmentDef.toDto() = SegmentDto(
    branch = branch.code,
    levelLow = levelLow,
    levelHigh = levelHigh,
    lowOpen = lowOpen.code,
    highOpen = highOpen.code,
    coeffs = coeffs,
    note = note,
)

data class CurveDto(
    val id: Long,
    val stationId: String,
    val revision: Int,
    val status: String,
    val reason: String?,
    val createdAt: String,
    val publishedAt: String?,
    val segments: List<SegmentDto>,
)

data class SaveCurveRequest(
    val stationId: String? = null,
    val curveId: Long? = null,
    val baseRevision: Int,
    val segments: List<SegmentDto>,
    val reason: String? = null,
)

data class PublishRequest(
    val stationId: String,
    val curveId: Long,
    val startAt: String,
    val endAt: String? = null,
)

data class PeriodDto(
    val id: Long,
    val stationId: String,
    val curveId: Long,
    val revision: Int,
    val startAt: String,
    val endAt: String?,
    val openEnded: Boolean,
)

data class FitRequest(
    val stationId: String? = null,
    val branch: String = "common",
    val degree: Int = 2,
    val levelLow: Double? = null,
    val levelHigh: Double? = null,
    val lowOpen: String = "closed",
    val highOpen: String = "open",
    val note: String? = null,
)

data class FitResponse(val segment: SegmentDto, val usedPointIds: List<Long>)

// ---------- 残差 ----------
data class ResidualDto(
    val pointId: Long,
    val observedAt: String,
    val branch: String?,
    val level: Double,
    val observedDischarge: Double,
    val predictedDischarge: Double?,
    val residual: Double?,
    val quality: String,
    val excluded: Boolean,
)

// ---------- 水位 / 派生 ----------
data class LevelPointDto(
    val observedAt: String,
    val level: Double?,
)

data class DerivedPointDto(
    val observedAt: String,
    val level: Double?,
    val branchUsed: String?,
    val discharge: Double?,
    val quality: String,
    val curveId: Long?,
)

data class PreviewRequest(
    val stationId: String,
    val seriesKey: String,
    val segments: List<SegmentDto>? = null,
)

data class PreviewPoint(
    val observedAt: String,
    val level: Double?,
    val branchUsed: String?,
    val newDischarge: Double?,
    val newQuality: String,
    val oldDischarge: Double?,
    val oldQuality: String?,
    val changed: Boolean,
)

data class PreviewResponse(
    val total: Int,
    val changed: Int,
    val byQuality: Map<String, Int>,
    val points: List<PreviewPoint>,
)

data class RecomputeRequest(
    val jobUuid: String,
    val stationId: String,
    val seriesKey: String,
)

data class JobDto(
    val id: Long,
    val jobUuid: String,
    val stationId: String,
    val seriesKey: String,
    val status: String,
    val pointCount: Int,
    val changedPoints: Int,
    val createdAt: String,
    val finishedAt: String?,
    val detail: String?,
    val retried: Boolean = false,
)

data class StationDto(
    val stationId: String,
    val name: String,
    val branchDeadband: Double,
)

data class ErrorResponse(val error: String, val details: List<String> = emptyList())

data class ConflictResponse(
    val error: String,
    val baseRevision: Int,
    val currentRevision: Int,
    val serverSegments: List<SegmentDto>,
    val onlyInClient: List<SegmentDto>,
    val onlyOnServer: List<SegmentDto>,
    val changedInPlace: List<SegmentPair>,
)

data class SegmentPair(val client: SegmentDto, val server: SegmentDto)

data class ResidualsRequest(
    val stationId: String,
    val curveId: Long? = null,
    val segments: List<SegmentDto>? = null,
)
