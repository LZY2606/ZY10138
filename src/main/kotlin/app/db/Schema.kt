package app.db

object Schema {
    /** 所有时间戳为 UTC ISO-8601；开放端点用常量 SENTINEL_MAX 存储（= +∞）。 */
    const val SENTINEL_MAX = "9999-12-31T00:00:00Z"

    val DDL: String = """
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS station (
    station_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    branch_deadband REAL NOT NULL DEFAULT 0.0,
    created_at TEXT NOT NULL
);

-- 原始实测点：仅人工标记 excluded 会变化，其观测值永不被拟合结果改写。
CREATE TABLE IF NOT EXISTS measured_point (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id TEXT NOT NULL REFERENCES station(station_id),
    observed_at TEXT NOT NULL,
    level REAL NOT NULL,
    discharge REAL NOT NULL,
    trend TEXT,                       -- rising|falling|null（观测时水势，可空）
    excluded INTEGER NOT NULL DEFAULT 0,
    exclude_reason TEXT,
    created_at TEXT NOT NULL,
    UNIQUE(station_id, observed_at)
);

CREATE TABLE IF NOT EXISTS rating_curve (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id TEXT NOT NULL REFERENCES station(station_id),
    revision INTEGER NOT NULL,
    status TEXT NOT NULL,             -- draft|published
    reason TEXT,
    created_at TEXT NOT NULL,
    published_at TEXT,
    UNIQUE(station_id, revision)
);

-- 段 JSON：{"branch","levelLow","levelHigh","lowOpen","highOpen","coeffs","note"}
CREATE TABLE IF NOT EXISTS rating_segment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    curve_id INTEGER NOT NULL REFERENCES rating_curve(id),
    ord INTEGER NOT NULL,
    branch TEXT NOT NULL,
    level_low REAL NOT NULL,
    level_high REAL NOT NULL,
    low_open TEXT NOT NULL,           -- open|closed
    high_open TEXT NOT NULL,
    coeffs_json TEXT NOT NULL,
    note TEXT
);

-- 每次保存草稿都留快照，供乐观并发返回“差异”。
CREATE TABLE IF NOT EXISTS curve_revision (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    curve_id INTEGER NOT NULL REFERENCES rating_curve(id),
    base_revision INTEGER NOT NULL,
    segments_json TEXT NOT NULL,
    reason TEXT,
    edited_at TEXT NOT NULL
);

-- 半开生效期 [start,end)；end=SENTINEL_MAX 表示 +∞。
CREATE TABLE IF NOT EXISTS effective_period (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id TEXT NOT NULL REFERENCES station(station_id),
    curve_id INTEGER NOT NULL REFERENCES rating_curve(id),
    start_at TEXT NOT NULL,
    end_at TEXT NOT NULL,
    CHECK (start_at < end_at)
);

-- 原始水位序列：不可被派生结果改写；level 可空表示原始水位缺失。
CREATE TABLE IF NOT EXISTS level_series (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id TEXT NOT NULL REFERENCES station(station_id),
    series_key TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    level REAL,
    UNIQUE(station_id, series_key, observed_at)
);

-- 派生流量序列；仅由“重算作业”写入，与原始表分离。
CREATE TABLE IF NOT EXISTS derived_discharge (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id TEXT NOT NULL,
    series_key TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    level_ref REAL,
    branch_used TEXT,
    discharge REAL,                   -- 非 ok 时恒为 NULL，绝不补零
    quality TEXT NOT NULL,
    curve_id INTEGER,
    job_id INTEGER,
    UNIQUE(station_id, series_key, observed_at)
);

-- 重算作业：相同 job_id 重试返回同一行，不产生第二套序列。
CREATE TABLE IF NOT EXISTS recompute_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_uuid TEXT NOT NULL UNIQUE,
    station_id TEXT NOT NULL,
    series_key TEXT NOT NULL,
    status TEXT NOT NULL,             -- running|succeeded|failed
    point_count INTEGER NOT NULL DEFAULT 0,
    changed_points INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    finished_at TEXT,
    detail TEXT
);

CREATE INDEX IF NOT EXISTS idx_mp_station ON measured_point(station_id);
CREATE INDEX IF NOT EXISTS idx_period_lookup ON effective_period(station_id, start_at, end_at);
CREATE INDEX IF NOT EXISTS idx_level_lookup ON level_series(station_id, series_key, observed_at);
CREATE INDEX IF NOT EXISTS idx_derived_lookup ON derived_discharge(station_id, series_key, observed_at);

-- 同一站生效期不允许重叠（半开区间重叠：start_a < end_b AND start_b < end_a）。
CREATE TRIGGER IF NOT EXISTS trg_period_no_overlap_insert
BEFORE INSERT ON effective_period
WHEN EXISTS (
    SELECT 1 FROM effective_period p
    WHERE p.station_id = NEW.station_id
      AND NEW.start_at < p.end_at
      AND p.start_at < NEW.end_at
)
BEGIN
    SELECT RAISE(ABORT, 'effective period overlaps an existing period');
END;

CREATE TRIGGER IF NOT EXISTS trg_period_no_overlap_update
BEFORE UPDATE OF station_id, curve_id, start_at, end_at ON effective_period
WHEN EXISTS (
    SELECT 1 FROM effective_period p
    WHERE p.station_id = NEW.station_id
      AND p.id <> NEW.id
      AND NEW.start_at < p.end_at
      AND p.start_at < NEW.end_at
)
BEGIN
    SELECT RAISE(ABORT, 'effective period overlaps an existing period');
END;
"""
}
