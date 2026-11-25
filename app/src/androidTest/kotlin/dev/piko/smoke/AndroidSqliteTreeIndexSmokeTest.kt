package dev.piko.smoke

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.shared.data.AndroidPikoTreeDatabaseFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 在真实 Android 系统 SQLite（API 26 = 3.18、API 29 = 3.22、API 34 = 3.39）与真实
 * AndroidPikoTreeDatabaseFactory / CursorWindow 上验证本地目录树索引的高风险边界：
 * - WAL 启用与版本诊断；
 * - 嵌套 SAVEPOINT 回滚（API 26-30 DatabaseUtils 分类绕过）；
 * - 单行 > 256 KiB payload 经点查与 LIMIT 256 增量查询读回（直击 API 29 256 KiB CursorWindow SQLiteBlobTooBigException）；
 * - 超过 SQLite 999 变量上限（1,001 项）的批量 IN 查询与 WITH RECURSIVE 递归删除；
 * - 跨多窗口大结果集扫描无漏行。
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteTreeIndexSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private inline fun withTempDir(block: (File) -> Unit) {
        val dir = File(context.cacheDir, "sqlite-tree-smoke-${UUID.randomUUID()}")
        check(dir.mkdirs()) { "Failed to create temp directory: $dir" }
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sqliteVersionAndWalModeAreHealthy() = withTempDir { dir ->
        val factory = AndroidPikoTreeDatabaseFactory(dir)
        val db = factory.open("version-check@piko.dev")
        try {
            val version = db.scalarString("SELECT sqlite_version()")
            val journalMode = db.scalarString("PRAGMA journal_mode")
            Log.i(TAG, "API=${Build.VERSION.SDK_INT}, ABI=${Build.SUPPORTED_ABIS.firstOrNull()}, sqlite=$version, journal=$journalMode")
            assertNotNull("sqlite_version() returned null", version)
            assertEquals("wal", journalMode?.lowercase())
        } finally {
            db.close()
        }
    }

    @Test
    fun nestedSavepointRollsBackCleanlyWithoutAbortingOuterTransaction() = withTempDir { dir ->
        val factory = AndroidPikoTreeDatabaseFactory(dir)
        val db = factory.open("savepoint@piko.dev")
        try {
            db.execute("CREATE TABLE IF NOT EXISTS sp_test (id INTEGER PRIMARY KEY)")
            db.transaction {
                db.execute("INSERT INTO sp_test VALUES (1)")
                runCatching {
                    db.transaction {
                        db.execute("INSERT INTO sp_test VALUES (2)")
                        error("force inner rollback")
                    }
                }
                db.execute("INSERT INTO sp_test VALUES (3)")
            }
            assertEquals("1", db.scalarString("SELECT COUNT(*) FROM sp_test WHERE id=1"))
            assertEquals("0", db.scalarString("SELECT COUNT(*) FROM sp_test WHERE id=2"))
            assertEquals("1", db.scalarString("SELECT COUNT(*) FROM sp_test WHERE id=3"))
        } finally {
            db.close()
        }
    }

    @Test
    fun oversized300KiBPayloadReadsBackViaPointAndDeltaLimit256Queries() = withTempDir { dir ->
        val factory = AndroidPikoTreeDatabaseFactory(dir)
        val db = factory.open("large-row@piko.dev")
        try {
            db.execute(
                "CREATE TABLE IF NOT EXISTS tree_nodes (generation INTEGER NOT NULL, id TEXT NOT NULL, parent TEXT NOT NULL, duration INTEGER, payload TEXT NOT NULL, PRIMARY KEY(generation,id))",
            )
            db.execute("CREATE TABLE IF NOT EXISTS tree_delta (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            val hugePayload = "{\"id\":\"huge-1\",\"pad\":\"" + "x".repeat(300 * 1024) + "\"}"
            db.transaction {
                db.execute("INSERT OR REPLACE INTO tree_nodes VALUES (0,?,?,?,?)", listOf("huge-1", "root", 120L, hugePayload))
                db.execute("INSERT OR REPLACE INTO tree_delta VALUES (?,?)", listOf("huge-1", hugePayload))
            }

            // 1. Scalar lookup (simpleQueryForString path used by SqliteTreeStore.file)
            val scalarPayload = db.scalarString("SELECT payload FROM tree_nodes WHERE generation=? AND id=?", listOf(0L, "huge-1"))
            assertEquals(hugePayload.length, scalarPayload?.length)

            // 2. Point-shaped cursor query with payload (previously hit 256 KiB POINT_WINDOW_BYTES -> SQLiteBlobTooBigException on API 29)
            db.query("SELECT payload,duration FROM tree_nodes WHERE generation=? AND id=?", listOf(0L, "huge-1")).use { cursor ->
                assertTrue("Expected row from point cursor query", cursor.next())
                assertEquals(hugePayload.length, cursor.string(0)?.length)
                assertEquals(120L, cursor.long(1))
            }

            // 3. Delta LIMIT 256 query with payload (previously hit 256 KiB POINT_WINDOW_BYTES -> SQLiteBlobTooBigException on API 29)
            db.query("SELECT payload FROM tree_delta WHERE id>? ORDER BY id LIMIT 256", listOf("")).use { cursor ->
                assertTrue("Expected row from delta LIMIT 256 query", cursor.next())
                assertEquals(hugePayload.length, cursor.string(0)?.length)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun batch1001IdsLookupAndRecursiveDeleteRespectSqlite999Limit() = withTempDir { dir ->
        val factory = AndroidPikoTreeDatabaseFactory(dir)
        val db = factory.open("batch-1001@piko.dev")
        try {
            db.execute(
                "CREATE TABLE IF NOT EXISTS tree_nodes (generation INTEGER NOT NULL, id TEXT NOT NULL, parent TEXT NOT NULL, folder INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(generation,id))",
            )
            db.execute("CREATE TABLE IF NOT EXISTS tree_deleted (id TEXT PRIMARY KEY, seq INTEGER NOT NULL)")
            val total = 1_001
            val ids = (0 until total).map { "file-$it" }
            db.transaction {
                for (i in 0 until total) {
                    db.execute(
                        "INSERT OR REPLACE INTO tree_nodes VALUES (0,?,?,0,?)",
                        listOf("file-$i", "folder-${i % 20}", "{\"id\":\"file-$i\"}"),
                    )
                }
                db.execute("INSERT OR REPLACE INTO tree_nodes VALUES (0,'file-keep','folder-0',0,'{\"id\":\"file-keep\"}')")
            }

            // Chunked 400 narrow parent lookup (matches SqliteTreeStore.parentIdsByIds)
            val parents = HashMap<String, String>(total)
            for (chunk in ids.chunked(400)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.query("SELECT id,parent FROM tree_nodes WHERE generation=0 AND id IN ($placeholders)", chunk).use { c ->
                    while (c.next()) parents[c.string(0)!!] = c.string(1)!!
                }
            }
            assertEquals(total, parents.size)
            assertEquals("folder-0", parents["file-0"])
            assertEquals("folder-${1000 % 20}", parents["file-1000"])

            // Chunked 256 WITH RECURSIVE delete (matches SqliteTreeStore.remove)
            db.transaction {
                for (batch in ids.chunked(256)) {
                    val roots = batch.joinToString(",") { "(?)" }
                    db.execute(
                        "WITH RECURSIVE removed(id) AS (VALUES $roots UNION SELECT n.id FROM removed r CROSS JOIN tree_nodes n ON n.generation=0 AND n.parent=r.id) INSERT OR REPLACE INTO tree_deleted SELECT id,? FROM removed",
                        batch + 1L,
                    )
                }
                db.execute("DELETE FROM tree_nodes WHERE generation=0 AND id IN (SELECT id FROM tree_deleted WHERE seq=?)", listOf(1L))
            }
            assertEquals("1", db.scalarString("SELECT COUNT(*) FROM tree_nodes WHERE generation=0"))
            assertEquals("file-keep", db.scalarString("SELECT id FROM tree_nodes WHERE generation=0"))
        } finally {
            db.close()
        }
    }

    @Test
    fun multiWindowScanReadsAllRowsWithoutLoss() = withTempDir { dir ->
        val factory = AndroidPikoTreeDatabaseFactory(dir)
        val db = factory.open("multi-window@piko.dev")
        try {
            db.execute("CREATE TABLE IF NOT EXISTS tree_nodes (generation INTEGER NOT NULL, id TEXT NOT NULL, parent TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(generation,id))")
            val count = 1_500
            val payload = "{\"pad\":\"" + "a".repeat(2_048) + "\"}" // ~3 MiB+ total across rows
            db.transaction {
                for (i in 0 until count) {
                    db.execute("INSERT OR REPLACE INTO tree_nodes VALUES (0,?,'mw-root',?)", listOf("mw-$i", payload))
                }
            }
            var seen = 0
            db.query("SELECT id,payload FROM tree_nodes WHERE generation=0 AND parent=?", listOf("mw-root")).use { c ->
                while (c.next()) {
                    assertNotNull(c.string(0))
                    assertEquals(payload.length, c.string(1)?.length)
                    seen++
                }
            }
            assertEquals(count, seen)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TAG = "AndroidSqliteSmoke"
    }
}
