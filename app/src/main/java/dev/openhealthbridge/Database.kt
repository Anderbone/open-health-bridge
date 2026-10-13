package dev.openhealthbridge

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "records")
data class CachedRecord(@PrimaryKey val key: String, val recordId: String, val type: String,
    val origin: String, val modified: String, val start: String, val end: String,
    val data: String, val hash: String, val revision: Long, val deleted: Boolean = false)
@Entity(tableName = "cursors")
data class Cursor(@PrimaryKey val type: String, val token: String, val origin: String, val scanStart: String, val scanEnd: String, val initialComplete: Boolean = false)
@Entity(tableName = "outbox", indices = [Index("sequence", unique = true)])
data class Outbox(@PrimaryKey val eventId: String, val sequence: Long, val payload: String,
    val type: String, val attempts: Int = 0, val nextAttemptAt: Long = 0, val blocked: Boolean = false, val recordKey: String = "")
@Entity(tableName = "summaries")
data class DailySummary(@PrimaryKey val date: String, val steps: Long?, val origin: String, val readAt: String)
@Entity(tableName = "runs")
data class SyncRun(@PrimaryKey(autoGenerate = true) val id: Long = 0, val time: String, val message: String)
@Dao
interface BridgeDao {
    @Query("SELECT * FROM records WHERE `key` = :key") suspend fun record(key: String): CachedRecord?
    @Query("SELECT * FROM records WHERE deleted = 0 AND data != '{}' ORDER BY start DESC") fun observeRecords(): Flow<List<CachedRecord>>
    @Query("SELECT * FROM records WHERE deleted = 0 AND data != '{}' ORDER BY start DESC") suspend fun records(): List<CachedRecord>
    @Upsert suspend fun saveRecord(record: CachedRecord)
    @Query("SELECT * FROM cursors WHERE type = :type") suspend fun cursor(type: String): Cursor?
    @Upsert suspend fun saveCursor(cursor: Cursor)
    @Query("SELECT * FROM outbox ORDER BY sequence LIMIT 1") suspend fun next(): Outbox?
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM outbox") suspend fun nextSequence(): Long
    @Insert suspend fun enqueue(event: Outbox)
    @Upsert suspend fun update(event: Outbox)
    @Query("DELETE FROM outbox WHERE eventId = :id") suspend fun acknowledge(id: String)
    @Query("SELECT COUNT(*) FROM outbox") fun observePending(): Flow<Int>
    @Query("UPDATE outbox SET blocked = 0, nextAttemptAt = 0") suspend fun retry()
    @Insert suspend fun log(run: SyncRun)
    @Query("SELECT * FROM runs ORDER BY id DESC LIMIT 20") fun observeRuns(): Flow<List<SyncRun>>
    @Query("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY id DESC LIMIT 100)") suspend fun pruneLogs()
    @Query("SELECT * FROM summaries ORDER BY date DESC LIMIT 7") fun observeSummaries(): Flow<List<DailySummary>>
    @Upsert suspend fun saveSummary(summary: DailySummary)
    @Query("DELETE FROM summaries WHERE date NOT IN (SELECT date FROM summaries ORDER BY date DESC LIMIT 7)") suspend fun pruneSummaries()
    @Query("DELETE FROM summaries") suspend fun clearSummaries()
    @Query("UPDATE records SET data = '{}' WHERE end < :before AND `key` NOT IN (SELECT recordKey FROM outbox)") suspend fun prunePayloads(before: String)
    @Query("SELECT COALESCE(SUM(length(CAST(data AS BLOB))), 0) FROM records") suspend fun cacheBytes(): Long
    @Query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))), 0) FROM outbox") suspend fun queueBytes(): Long
    @Query("DELETE FROM outbox") suspend fun clearQueue()
    @Query("DELETE FROM cursors") suspend fun clearCursors()
    @Query("DELETE FROM records") suspend fun clearRecords()
    @Query("DELETE FROM runs") suspend fun clearRuns()
}
@Database(entities = [CachedRecord::class, Cursor::class, Outbox::class, SyncRun::class, DailySummary::class], version = 1, exportSchema = true)
abstract class BridgeDatabase : RoomDatabase() { abstract fun dao(): BridgeDao }
