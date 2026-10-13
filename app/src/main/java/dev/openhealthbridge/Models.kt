package dev.openhealthbridge

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.reflect.KClass

enum class HealthType(val label: String, val recordClass: KClass<out Record>) {
    SLEEP("Sleep", SleepSessionRecord::class), STEPS("Steps", StepsRecord::class),
    EXERCISE("Exercise", ExerciseSessionRecord::class), HEART_RATE("Heart rate", HeartRateRecord::class);
    val permission get() = HealthPermission.getReadPermission(recordClass)
    val wire get() = name.lowercase()
}

data class DestinationConfig(val url: String = "", val token: String = "", val enabled: Boolean = false,
    val types: Set<HealthType> = HealthType.entries.toSet(), val origin: String = "", val automatic: Boolean = false)

data class NormalizedRecord(val id: String, val type: HealthType, val origin: String,
    val modified: String, val start: String, val end: String, val data: String) {
    val key get() = "${type.wire}:$id"
    val hash get() = sha256("$modified|$data|$origin")
}
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

fun eventJson(scope: String, eventId: String, type: String, id: String, origin: String,
    revision: Long, modified: String, observed: String, data: String?): String = JSONObject().apply {
    put("schemaVersion", 1); put("bridgeScopeId", scope); put("eventId", eventId)
    put("event", if (data == null) "health.record.delete" else "health.record.upsert")
    put("recordId", id); put("recordType", type)
    put("source", JSONObject().put("platform", "health_connect").put("packageName", origin))
    put("localRevision", revision); put("recordLastModifiedTime", modified); put("observedAt", observed)
    if (data != null) put("data", JSONObject(data))
}.toString()

fun sourceLabel(packageName: String): String = when {
    packageName == "com.sec.android.app.shealth" -> "Samsung Health"
    packageName.startsWith("com.android.healthconnect.phone.") -> "Phone step counter (Health Connect)"
    else -> packageName
}
