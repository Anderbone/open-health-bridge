package dev.openhealthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.*
import androidx.health.connect.client.changes.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.*
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface RecordChange {
    data class Upsert(val record: NormalizedRecord) : RecordChange
    data class Delete(val id: String) : RecordChange
}
data class ChangePage(val changes: List<RecordChange>, val nextToken: String, val hasMore: Boolean, val expired: Boolean)
interface HealthData {
    val status: Int
    fun backgroundAvailable(): Boolean
    suspend fun granted(): Set<String>
    suspend fun token(type: HealthType, origin: String): String
    suspend fun scan(type: HealthType, origin: String, start: Instant, end: Instant, consume: suspend (List<NormalizedRecord>) -> Unit)
    suspend fun changes(token: String): ChangePage
    suspend fun dailySteps(origin: String): List<DailySummary>
}
class HealthGateway(private val context: Context) : HealthData {
    override val status get() = HealthConnectClient.getSdkStatus(context)
    val client get() = HealthConnectClient.getOrCreate(context)
    override fun backgroundAvailable() = status == HealthConnectClient.SDK_AVAILABLE &&
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    override suspend fun granted() = client.permissionController.getGrantedPermissions()
    private fun origins(origin: String) = if (origin.isBlank()) emptySet() else setOf(DataOrigin(origin))
    override suspend fun token(type: HealthType, origin: String) = client.getChangesToken(ChangesTokenRequest(setOf(type.recordClass), origins(origin)))
    override suspend fun scan(type: HealthType, origin: String, start: Instant, end: Instant, consume: suspend (List<NormalizedRecord>) -> Unit) {
        when (type) {
            HealthType.SLEEP -> read<SleepSessionRecord>(origin, start, end, consume)
            HealthType.STEPS -> read<StepsRecord>(origin, start, end, consume)
            HealthType.EXERCISE -> read<ExerciseSessionRecord>(origin, start, end, consume)
            HealthType.HEART_RATE -> read<HeartRateRecord>(origin, start, end, consume)
        }
    }
    private suspend inline fun <reified T : Record> read(origin: String, start: Instant, end: Instant, noinline consume: suspend (List<NormalizedRecord>) -> Unit) {
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(T::class, TimeRangeFilter.between(start, end), dataOriginFilter = origins(origin), pageSize = 250, pageToken = page))
            consume(response.records.map(::normalize))
            page = response.pageToken?.takeIf { it.isNotEmpty() }
        } while (page != null)
    }
    override suspend fun changes(token: String): ChangePage {
        val response = client.getChanges(token)
        return ChangePage(response.changes.map { change -> when (change) {
            is UpsertionChange -> RecordChange.Upsert(normalize(change.record))
            is DeletionChange -> RecordChange.Delete(change.recordId)
            else -> error("Unknown change")
        } }, response.nextChangesToken, response.hasMore, response.changesTokenExpired)
    }
    override suspend fun dailySteps(origin: String): List<DailySummary> {
        val today = LocalDate.now()
        return (0L..6L).map { offset ->
            val date = today.minusDays(offset)
            val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val end = minOf(date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant(), Instant.now())
            val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end), origins(origin)))
            DailySummary(date.toString(), result[StepsRecord.COUNT_TOTAL], origin, Instant.now().toString())
        }
    }
    fun normalize(record: Record): NormalizedRecord {
        val data = JSONObject()
        val type: HealthType
        val start: Instant
        val end: Instant
        when (record) {
            is SleepSessionRecord -> {
                type = HealthType.SLEEP; start = record.startTime; end = record.endTime
                data.put("stages", JSONArray().apply { record.stages.forEach { put(JSONObject().put("startTime", it.startTime.toString()).put("endTime", it.endTime.toString()).put("stage", it.stage)) } })
                data.put("startZoneOffset", record.startZoneOffset?.toString() ?: JSONObject.NULL)
                data.put("endZoneOffset", record.endZoneOffset?.toString() ?: JSONObject.NULL)
            }
            is StepsRecord -> { type = HealthType.STEPS; start = record.startTime; end = record.endTime; data.put("count", record.count) }
            is ExerciseSessionRecord -> { type = HealthType.EXERCISE; start = record.startTime; end = record.endTime; data.put("exerciseType", record.exerciseType) }
            is HeartRateRecord -> {
                type = HealthType.HEART_RATE; start = record.startTime; end = record.endTime
                data.put("samples", JSONArray().apply { record.samples.forEach { put(JSONObject().put("time", it.time.toString()).put("bpm", it.beatsPerMinute)) } })
            }
            else -> error("Unsupported record type")
        }
        data.put("startTime", start.toString()); data.put("endTime", end.toString())
        return NormalizedRecord(record.metadata.id, type, record.metadata.dataOrigin.packageName,
            record.metadata.lastModifiedTime.toString(), start.toString(), end.toString(), data.toString())
    }
}
