package app

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant

/** 打开/创建 SQLite 数据库并启用外键、WAL、忙等。 */
object Db {
    fun open(path: String): Connection {
        val p = Path.of(path)
        if (p.parent != null) Files.createDirectories(p.parent)
        // 默认 autoCommit=true：PRAGMA（尤其 journal_mode）不能在事务内执行。
        val c = java.sql.DriverManager.getConnection("jdbc:sqlite:$path")
        c.createStatement().use { st ->
            st.execute("PRAGMA busy_timeout = 5000")
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("PRAGMA journal_mode = WAL")
        }
        return c
    }
}

fun Connection.tx(block: (Connection) -> Unit) {
    autoCommit = false
    try {
        block(this)
        commit()
    } catch (t: Throwable) {
        rollback()
        throw t
    } finally {
        autoCommit = true
    }
}

fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

fun Connection.query(sql: String, vararg args: Any?, block: ResultSet.() -> Unit): Unit =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, v -> ps.setObject(i + 1, v.toSql()) }
        ps.executeQuery().use { rs -> while (rs.next()) rs.block() }
    }

fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
        args.forEachIndexed { i, v -> ps.setObject(i + 1, v.toSql()) }
        val n = ps.executeUpdate()
        ps.generatedKeys.use { if (it.next()) {} }
        n
    }

/** 插入并返回自增主键 */
fun Connection.insert(sql: String, vararg args: Any?): Long =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
        args.forEachIndexed { i, v -> ps.setObject(i + 1, v.toSql()) }
        ps.executeUpdate()
        ps.generatedKeys.use { if (it.next()) it.getLong(1) else error("no generated key") }
    }

private fun Any?.toSql(): Any? = when (this) {
    is Instant -> this.toString()
    is Enum<*> -> name
    else -> this
}

fun ResultSet.getInstant(col: String): Instant = Instant.parse(getString(col))
fun ResultSet.getInstantOrNull(col: String): Instant? =
    getString(col)?.takeIf { it.isNotBlank() }?.let { Instant.parse(it) }

/**
 * 建表。所有原始表（station / measurement / stage_series）只追加、不由拟合流程改写；
 * measurement 仅允许切换 excluded 标志与备注，原始水位/流量列不变。
 */
fun Connection.initSchema() {
    exec(
        """
        CREATE TABLE IF NOT EXISTS station (
            id              INTEGER PRIMARY KEY,
            code            TEXT NOT NULL UNIQUE,
            name            TEXT NOT NULL,
            deadband_m      REAL NOT NULL,
            max_age_seconds INTEGER NOT NULL
        )""".trimIndent()
    )
    exec(
        """
        CREATE TABLE IF NOT EXISTS measurement (
            id                INTEGER PRIMARY KEY,
            station_id        INTEGER NOT NULL REFERENCES station(id),
            observed_at       TEXT NOT NULL,
            stage_m           REAL NOT NULL,
            discharge_m3s     REAL NOT NULL,
            branch_hint       TEXT,
            excluded          INTEGER NOT NULL DEFAULT 0,
            note              TEXT,
            UNIQUE(station_id, observed_at)
        )""".trimIndent()
    )
    exec(
        """
        CREATE TABLE IF NOT EXISTS rating_curve (
            id             INTEGER PRIMARY KEY,
            station_id     INTEGER NOT NULL REFERENCES station(id),
            status         TEXT NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
            version        INTEGER NOT NULL,
            revised_reason TEXT,
            rev            TEXT NOT NULL,
            created_at     TEXT NOT NULL,
            updated_at     TEXT NOT NULL,
            UNIQUE(station_id, version)
        )""".trimIndent()
    )
    exec(
        """
        CREATE TABLE IF NOT EXISTS curve_segment (
            id           INTEGER PRIMARY KEY,
            curve_id     INTEGER NOT NULL REFERENCES rating_curve(id) ON DELETE CASCADE,
            branch       TEXT NOT NULL CHECK (branch IN ('COMMON','RISING','FALLING')),
            stage_low    REAL NOT NULL,
            stage_high   REAL NOT NULL,
            low_include  INTEGER NOT NULL,
            high_include INTEGER NOT NULL,
            position     INTEGER NOT NULL,
            coefficients TEXT NOT NULL
        )""".trimIndent()
    )
    // 发布记录：同站 valid_from 唯一，配合应用层“严格递增”形成半开 [valid_from, next.valid_from)
    exec(
        """
        CREATE TABLE IF NOT EXISTS curve_publication (
            id             INTEGER PRIMARY KEY,
            station_id     INTEGER NOT NULL REFERENCES station(id),
            curve_id       INTEGER NOT NULL REFERENCES rating_curve(id),
            valid_from     TEXT NOT NULL,
            published_at   TEXT NOT NULL,
            reason         TEXT NOT NULL,
            UNIQUE(station_id, valid_from)
        )""".trimIndent()
    )
    // 原始水位序列：只追加。没有 update/delete 接口。
    exec(
        """
        CREATE TABLE IF NOT EXISTS stage_series (
            id         INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL REFERENCES station(id),
            ts         TEXT NOT NULL,
            stage_m    REAL,
            UNIQUE(station_id, ts)
        )""".trimIndent()
    )
    // 派生流量序列：每次重算原子替换目标区间，每行记录所用曲线，保证旧版本可复现。
    exec(
        """
        CREATE TABLE IF NOT EXISTS discharge_series (
            id             INTEGER PRIMARY KEY,
            station_id     INTEGER NOT NULL REFERENCES station(id),
            ts             TEXT NOT NULL,
            stage_m        REAL,
            discharge_m3s  REAL,
            quality        TEXT NOT NULL CHECK (quality IN
                             ('OK','OUT_OF_RANGE','BRANCH_UNDETERMINED','MISSING_STAGE','OVERFLOW')),
            branch         TEXT,
            curve_id       INTEGER REFERENCES rating_curve(id),
            job_id         TEXT REFERENCES recompute_job(id),
            UNIQUE(station_id, ts)
        )""".trimIndent()
    )
    // 重算作业：job ID 幂等，重试返回既有结果，绝不生成第二套序列。
    exec(
        """
        CREATE TABLE IF NOT EXISTS recompute_job (
            id            TEXT PRIMARY KEY,
            station_id    INTEGER NOT NULL REFERENCES station(id),
            state         TEXT NOT NULL CHECK (state IN ('DONE','FAILED')),
            series_from   TEXT NOT NULL,
            series_to     TEXT NOT NULL,
            mode          TEXT NOT NULL CHECK (mode IN ('PUBLISHED','CURVE')),
            curve_id      INTEGER REFERENCES rating_curve(id),
            changed_count INTEGER NOT NULL,
            written_count INTEGER NOT NULL,
            created_at    TEXT NOT NULL,
            finished_at   TEXT
        )""".trimIndent()
    )
}
