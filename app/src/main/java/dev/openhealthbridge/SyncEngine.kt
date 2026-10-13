package dev.openhealthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.*
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncEngine(private val context: Context, private val db: BridgeDatabase, private val store: TokenStore,
    private val health: HealthData, private val webhook: DeliveryTransport, private val schedulingEnabled: Boolean = true) {
    private val lock = Mutex()
    private val dao get() = db.dao()
    private val work get() = WorkManager.getInstance(context)
    suspend fun log(message: String) { dao.log(SyncRun(time = Instant.now().toString(), message = message)); dao.pruneLogs() }
    fun schedule() {
        if (!schedulingEnabled) return
        val config = runCatching { store.load() }.getOrElse { store.disable(); return }
        if (config.automatic && health.backgroundAvailable()) work.enqueueUniquePeriodicWork("health-read", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HealthReadWorker>(2, TimeUnit.HOURS).build())
        else work.cancelUniqueWork("health-read")
        if (config.enabled) work.enqueueUniquePeriodicWork("delivery-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DeliveryWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        else { work.cancelUniqueWork("delivery-periodic"); work.cancelUniqueWork("delivery-now") }
    }
    private fun deliverySoon() {
        if (schedulingEnabled) work.enqueueUniqueWork("delivery-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DeliveryWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    suspend fun save(config: DestinationConfig) = lock.withLock {
        if (config.url.isNotBlank() || config.enabled) validateDestination(config.url, config.token)
        require(config.types.isNotEmpty()) { "Select at least one data category" }
        val previous = runCatching { store.load() }.getOrElse { store.disable(); DestinationConfig() }
        // Any change to routing/scope drops the old queue and requires a fresh snapshot.
        if (previous.url != config.url || previous.types != config.types || previous.origin != config.origin || (!previous.enabled && config.enabled)) {
            db.withTransaction { dao.clearQueue(); dao.clearCursors(); dao.clearRecords(); dao.clearSummaries() }
        }
        store.save(config); dao.retry(); schedule()
        log(if (config.enabled) "Destination enabled with your selected categories" else "Settings saved; upload disabled")
    }
    fun stop() { store.disable(); schedule() }
    suspend fun clear() = lock.withLock {
        stop(); store.clear()
        db.withTransaction { dao.clearQueue(); dao.clearCursors(); dao.clearRecords(); dao.clearSummaries(); dao.clearRuns() }
    }
    suspend fun retry() { lock.withLock { dao.retry() }; deliverySoon() }
    suspend fun read(background: Boolean = false) = lock.withLock {
        check(health.status == HealthConnectClient.SDK_AVAILABLE) { "Health Connect is unavailable; install or update it" }
        val config = store.load()
        val granted = health.granted()
        if (background && (!config.automatic || !health.backgroundAvailable() || "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" !in granted)) return@withLock
        val selected = config.types.filter { it.permission in granted }
        check(selected.isNotEmpty()) { "Grant permission for at least one selected category" }
        dao.prunePayloads(Instant.now().minus(30, ChronoUnit.DAYS).toString())
        var count = 0
        for (type in selected) {
            var cursor = dao.cursor(type.wire)
            if (cursor == null || cursor.origin != config.origin) {
                val end = Instant.now(); val start = end.minus(7, ChronoUnit.DAYS)
                val token = health.token(type, config.origin)
                cursor = Cursor(type.wire, token, config.origin, start.toString(), end.toString())
                dao.saveCursor(cursor)
            }
            if (!cursor.initialComplete) {
                health.scan(type, config.origin, Instant.parse(cursor.scanStart), Instant.parse(cursor.scanEnd)) { records ->
                    db.withTransaction { records.forEach { upsert(it, config.enabled); count++ } }
                }
                cursor = cursor.copy(initialComplete = true)
                dao.saveCursor(cursor)
            }
            var expired = false
            do {
                val changes = health.changes(requireNotNull(cursor).token)
                if (changes.expired) { expired = true; break }
                val next = requireNotNull(cursor).copy(token = changes.nextToken)
                db.withTransaction {
                    changes.changes.forEach { change -> when (change) {
                        is RecordChange.Upsert -> { val record = change.record
                            if (config.origin.isBlank() || record.origin == config.origin) { upsert(record, config.enabled); count++ } }
                        is RecordChange.Delete -> delete(type, change.id, config.enabled)
                    } }
                    dao.saveCursor(next)
                }
                cursor = next
            } while (changes.hasMore)
            if (expired) {
                val end = Instant.now(); val start = end.minus(7, ChronoUnit.DAYS)
                val token = health.token(type, config.origin)
                dao.saveCursor(Cursor(type.wire, token, config.origin, start.toString(), end.toString()))
                log("${type.label}: token expired; 7-day recovery scheduled. Historical deletions need receiver review")
            }
        }
        if (HealthType.STEPS in selected) {
            runCatching { health.dailySteps(config.origin).forEach { dao.saveSummary(it) }; dao.pruneSummaries() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log("Daily step summary unavailable; raw records are still available") }
        }
        log("Read $count records across ${selected.size} categories; ${config.types.size - selected.size} categories not authorized")
        if (config.enabled) deliverySoon()
    }
    private suspend fun upsert(record: NormalizedRecord, upload: Boolean) {
        val old = dao.record(record.key)
        if (old?.hash == record.hash && !old.deleted) return
        check(dao.cacheBytes() + dao.queueBytes() + record.data.toByteArray().size < 50L * 1024 * 1024) {
            "Storage payload budget reached (50 MB). Deliver pending data or clear local data to resume reading"
        }
        val revision = store.nextRevision()
        dao.saveRecord(CachedRecord(record.key, record.id, record.type.wire, record.origin, record.modified,
            record.start, record.end, record.data, record.hash, revision))
        if (upload) enqueue(record.type.wire, record.id, record.origin, revision, record.modified, record.data)
    }
    private suspend fun delete(type: HealthType, id: String, upload: Boolean) {
        val old = dao.record("${type.wire}:$id") ?: return
        if (old.deleted) return
        val revision = store.nextRevision()
        dao.saveRecord(old.copy(deleted = true, revision = revision, data = "{}"))
        if (upload) enqueue(old.type, id, old.origin, revision, old.modified, null)
    }
    private suspend fun enqueue(type: String, id: String, origin: String, revision: Long, modified: String, data: String?) {
        val eventId = UUID.randomUUID().toString()
        val payload = eventJson(store.scope, eventId, type, id, origin, revision, modified, Instant.now().toString(), data)
        check(dao.cacheBytes() + dao.queueBytes() + payload.toByteArray().size < 50L * 1024 * 1024) {
            "Storage payload budget reached (50 MB). Deliver pending data or clear local data to resume reading"
        }
        dao.enqueue(Outbox(eventId, dao.nextSequence(), payload, type, recordKey = "$type:$id"))
    }
    suspend fun deliver(): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        repeat(100) {
            val config = store.load()
            if (!config.enabled) return@withLock false
            val event = dao.next() ?: return@withLock false
            if (event.blocked) return@withLock false
            val type = HealthType.entries.find { it.wire == event.type }
            if (type != null && type.permission !in health.granted()) {
                dao.update(event.copy(blocked = true)); log("${type.label} permission revoked; delivery paused")
                return@withLock false
            }
            if (event.nextAttemptAt > System.currentTimeMillis()) return@withLock true
            val result = try { webhook.send(config, event) } catch (_: java.io.IOException) {
                dao.update(event.copy(attempts = event.attempts + 1, nextAttemptAt = System.currentTimeMillis() + retryDelay(event.attempts)))
                log("Network or TLS failure; queued for retry"); return@withLock true
            }
            when {
                result.code in 200..299 -> { dao.acknowledge(event.eventId); log("Delivered ${event.type} (HTTP ${result.code})") }
                retryable(result.code) -> { dao.update(event.copy(attempts = event.attempts + 1, nextAttemptAt = System.currentTimeMillis() + retryDelay(event.attempts, result.retryAfterSeconds))); log("HTTP ${result.code}; queued for retry"); return@withLock true }
                else -> { dao.update(event.copy(blocked = true)); log("HTTP ${result.code}; delivery paused. Fix destination and retry"); return@withLock false }
            }
        }
        dao.next() != null
    } }
}
