package app.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.concurrent.locks.ReentrantLock

/**
 * SQLite 访问入口。
 *  - 文件库：每次事务取独立连接（同一文件对所有连接可见）；
 *  - 内存库：所有事务复用同一条连接（SQLite 每个 :memory: 连接相互隔离），
 *    用锁串行化，也更贴近“离线单用户”的使用方式。
 */
class Database private constructor(
    val url: String,
    private val pinned: Connection?,
) {
    private val lock = ReentrantLock()

    /** 单连接串行事务；异常回滚并向上抛出。 */
    fun <T> tx(block: (Connection) -> T): T {
        if (pinned != null) {
            lock.lock()
            try {
                val result = block(pinned)
                pinned.commit()
                return result
            } catch (t: Throwable) {
                pinned.rollback()
                throw t
            } finally {
                lock.unlock()
            }
        }
        java.sql.DriverManager.getConnection(url).use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }
    }

    companion object {
        fun open(path: Path, initSchema: Boolean = true): Database {
            val parent = path.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            Class.forName("org.sqlite.JDBC")
            val url = "jdbc:sqlite:${path.toAbsolutePath()}"
            val db = Database(url, null)
            if (initSchema) db.tx { conn -> init(conn) }
            return db
        }

        /** 内存库，主要给测试用：复用一条常驻连接。 */
        fun memory(): Database {
            Class.forName("org.sqlite.JDBC")
            val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
            conn.autoCommit = false
            init(conn)
            return Database("jdbc:sqlite::memory:", conn)
        }

        private fun init(conn: Connection) {
            splitStatements(Schema.DDL).forEach { stmt ->
                conn.createStatement().use { it.execute(stmt) }
            }
        }

        /**
         * 按分号切分 SQL 脚本；CREATE TRIGGER 的 BEGIN..END 体内也含分号，
         * 因此进入 BEGIN 后停止切分，直到对应 END 再恢复。
         */
        private fun splitStatements(ddl: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var i = 0
            val upper = ddl.uppercase()
            while (i < ddl.length) {
                if (wordAt(upper, i, "BEGIN")) {
                    depth++
                    current.append(ddl, i, i + 5)
                    i += 5
                    continue
                }
                if (wordAt(upper, i, "END")) {
                    if (depth > 0) depth--
                    current.append(ddl, i, i + 3)
                    i += 3
                    continue
                }
                val ch = ddl[i]
                if (ch == ';' && depth == 0) {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) statements += stmt
                    current.setLength(0)
                } else {
                    current.append(ch)
                }
                i++
            }
            current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements += it }
            return statements
        }

        private fun wordAt(upper: String, index: Int, word: String): Boolean {
            if (index + word.length > upper.length) return false
            if (!upper.startsWith(word, index)) return false
            val beforeOk = index == 0 || !upper[index - 1].isLetter()
            val after = index + word.length
            val afterOk = after >= upper.length || !upper[after].isLetter()
            return beforeOk && afterOk
        }
    }
}

fun Connection.insertAndGetId(sql: String, vararg params: Any?): Long {
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
        params.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
        ps.executeUpdate()
        ps.generatedKeys.use { rs ->
            require(rs.next()) { "未取得生成键: $sql" }
            return rs.getLong(1)
        }
    }
}

fun Connection.update(sql: String, vararg params: Any?): Int {
    prepareStatement(sql).use { ps ->
        params.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
        return ps.executeUpdate()
    }
}

fun <T> Connection.query(sql: String, mapper: (ResultSet) -> T, vararg params: Any?): List<T> {
    prepareStatement(sql).use { ps ->
        params.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
        ps.executeQuery().use { rs ->
            val out = mutableListOf<T>()
            while (rs.next()) out += mapper(rs)
            return out
        }
    }
}

fun <T> Connection.queryOne(sql: String, mapper: (ResultSet) -> T, vararg params: Any?): T? =
    query(sql, mapper, *params).firstOrNull()
