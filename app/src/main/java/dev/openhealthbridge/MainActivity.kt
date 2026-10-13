package dev.openhealthbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { BridgeTheme { BridgeScreen(this) } } }
}
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { BridgeTheme {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your health data, your choice", style = MaterialTheme.typography.headlineMedium)
            Text(PRIVACY)
            Button(onClick = { finish() }) { Text("Close") }
        } }
    } } }
}
private const val PRIVACY = "Open Health Bridge reads only the Health Connect categories you authorize. It stores records privately on this phone to show them and queue delivery. No developer server, account, advertising or analytics is used.\n\nUploading is off until you save and explicitly enable your HTTPS destination. Selected records, their times, originating app and record identifiers are sent to that server, protected by TLS. The server owner controls their retention and access. API tokens are encrypted with Android Keystore.\n\nBackground reading requires separate permission and may be delayed by Android. You can stop syncing, revoke Health Connect permissions or clear local data. An in-flight request may already have reached the destination. Clearing this phone does not erase Health Connect or your server.\n\nJSON exports contain sensitive health data; save them only where you intend. Backups and device transfer of app data are disabled. Record payloads older than 30 days are cleared unless delivery is pending; minimal record identifiers remain until you clear local data; do not use it for diagnosis or emergency decisions."
@Composable fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176B5B), background = Color(0xFFF4F6F2), surface = Color(0xFFF4F6F2)), content = content)
}
@Composable private fun BridgeScreen(activity: MainActivity) {
    val app = activity.bridge()
    val health = remember { HealthGateway(activity) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(runCatching { app.store.load() }.getOrElse { app.store.disable(); DestinationConfig() }) }
    var url by remember { mutableStateOf(config.url) }
    var token by remember { mutableStateOf(config.token) }
    var origin by remember { mutableStateOf(config.origin) }
    var types by remember { mutableStateOf(config.types) }
    var auto by remember { mutableStateOf(config.automatic) }
    var enabled by remember { mutableStateOf(config.enabled) }
    var consent by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(emptySet<String>()) }
    var status by remember { mutableStateOf("Ready. Connect Health Connect to read your records.") }
    var busy by remember { mutableStateOf(false) }
    val records by app.database.dao().observeRecords().collectAsState(initial = emptyList())
    val runs by app.database.dao().observeRuns().collectAsState(initial = emptyList())
    val summaries by app.database.dao().observeSummaries().collectAsState(initial = emptyList())
    val pending by app.database.dao().observePending().collectAsState(initial = 0)
    fun action(block: suspend () -> Unit) { scope.launch { busy = true; try { block() } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        status = when (e) { is SecurityException -> "Permission changed. Reconnect Health Connect."; is IllegalArgumentException, is IllegalStateException -> e.message ?: "Operation could not complete"; else -> "Operation failed. Check Health Connect and destination settings." }
    } finally { busy = false } } }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { permissions -> granted = permissions; status = "Granted ${permissions.count { it in types.map { type -> type.permission } }} selected categories" }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { if (health.status == HealthConnectClient.SDK_AVAILABLE) granted = runCatching { health.granted() }.getOrDefault(emptySet()) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) action {
            withContext(Dispatchers.IO) {
                val data = app.database.dao().records().filter { cached -> types.any { it.wire == cached.type } && (origin.isBlank() || origin == cached.origin) }
                val json = JSONObject().put("schemaVersion", 1).put("exportedAt", Instant.now().toString()).put("records", JSONArray().apply { data.forEach {
                    put(JSONObject().put("recordId", it.recordId).put("recordType", it.type).put("originPackage", it.origin).put("data", JSONObject(it.data)))
                } })
                requireNotNull(activity.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json.toString(2)) }
            }
            status = "JSON exported to the location you selected"
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear data on this phone?") }, text = { Text("This removes records, pending deliveries, settings and token. Health Connect and the receiving server are unchanged.") }, confirmButton = { TextButton(onClick = { confirmClear = false; action { app.engine.clear(); config = DestinationConfig(); url = ""; token = ""; origin = ""; types = config.types; auto = false; enabled = false; consent = false; status = "Local data cleared" } }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("OPEN HEALTH BRIDGE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("Your data.\nYour destination.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("A private bridge from Health Connect to your own service.", style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text("01  /  HEALTH CONNECT", style = MaterialTheme.typography.labelLarge)
            Text(when (health.status) { HealthConnectClient.SDK_AVAILABLE -> "Available · ${granted.count { it in HealthType.entries.map { t -> t.permission } }} categories authorized"; HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Install or update Health Connect"; else -> "Health Connect is not supported on this device" })
            HealthType.entries.forEach { type -> Row { Checkbox(type in types, onCheckedChange = { checked -> types = if (checked) types + type else types - type }, enabled = !busy); Text(type.label, Modifier.padding(top = 12.dp)) } }
            Button(enabled = !busy && types.isNotEmpty(), onClick = {
                if (health.status == HealthConnectClient.SDK_AVAILABLE) permissionLauncher.launch(types.map { it.permission }.toSet())
                else runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))) }
            }) { Text("Connect / update permissions") }
            OutlinedTextField(origin, { origin = it.trim() }, label = { Text("Source package filter (optional)") }, supportingText = { Text("Blank reads all sources. Select an observed source below to avoid mixed totals.") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            records.map { it.origin }.distinct().forEach { source -> TextButton(onClick = { origin = source }, enabled = !busy) { Text(sourceLabel(source)) } }
            Text("DATA AVAILABILITY", style = MaterialTheme.typography.labelLarge)
            HealthType.entries.forEach { type ->
                val cached = records.count { it.type == type.wire }
                val state = when {
                    type !in config.types -> "Not selected in saved settings"
                    type.permission !in granted -> "Read permission needed"
                    cached == 0 -> "No cached data yet · read after source sync"
                    else -> "$cached cached records"
                }
                Text("${type.label}: $state")
            }
            val cachedSources = records.map { it.origin }.distinct()
            cachedSources.forEach { source -> Text("Source: ${sourceLabel(source)}", style = MaterialTheme.typography.bodySmall) }
            if (cachedSources.isNotEmpty() && cachedSources.all { it.startsWith("com.android.healthconnect.phone.") }) {
                Text("Only the phone's Health Connect step counter has been read. Samsung Health / watch records have not been received. Check that Samsung Health on this phone contains your watch records, has write access to Health Connect, and has finished syncing. Then Read now again.", Modifier.background(Color(0xFFFFF0CE)).padding(12.dp))
            }
            Text("02  /  DELIVERY", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS webhook URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(token, { token = it }, label = { Text("Bearer token (optional)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            Row { Switch(enabled, { enabled = it; consent = false }, enabled = !busy); Text("Send selected records", Modifier.padding(12.dp)) }
            if (enabled) Row { Checkbox(consent, { consent = it }, enabled = !busy); Text("I authorize sending selected health records, timestamps and source identifiers to this URL. Its owner controls their storage.", Modifier.weight(1f)) }
            Row { Switch(auto, { auto = it }, enabled = !busy && health.backgroundAvailable()); Text("Read about every 2 hours", Modifier.padding(12.dp)) }
            Text(if (health.backgroundAvailable()) "Background reads need separate permission. Android may delay scheduled work." else "Background reads unavailable; use Read now. Queued uploads can still run in the background.", style = MaterialTheme.typography.bodySmall)
            if (health.backgroundAvailable()) TextButton(onClick = { permissionLauncher.launch(setOf("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")) }, enabled = !busy) { Text("Grant background read permission") }
            Button(enabled = !busy && (!enabled || consent), onClick = { action {
                if (auto) require("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" in health.granted()) { "Grant background read permission first" }
                val next = DestinationConfig(url.trim(), token, enabled, types, origin, auto)
                app.engine.save(next); config = next; status = "Settings saved. Read now to populate the queue."
            } }) { Text("Save settings") }
            TextButton(enabled = !busy, onClick = { action {
                val result = withContext(Dispatchers.IO) { WebhookClient().ping(url.trim(), token) }
                status = "Endpoint test: HTTP ${result.code}. No health data was sent."
            } }) { Text("Test endpoint (no health data)") }
            Text("Saved upload: ${if (config.enabled) "enabled" else "off"} · $pending queued", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { action { app.engine.read(); status = "Read complete; selected records queued if upload is enabled" } }) { Text("Read now") }
                OutlinedButton(enabled = !busy && config.enabled, onClick = { action { val retry = app.engine.deliver(); status = if (retry) "Delivery pending; automatic retry scheduled" else "Delivery checked. See recent activity." } }) { Text("Send now") }
            }
            TextButton(enabled = !busy && config.enabled, onClick = { action { app.engine.retry(); status = "Retry requested" } }) { Text("Retry paused deliveries") }
            TextButton(onClick = { app.engine.stop(); config = config.copy(enabled = false, automatic = false); enabled = false; auto = false; consent = false; status = "Sync stopped. An in-flight request may already have reached your server." }) { Text("Stop sync") }
            Text(status, Modifier.fillMaxWidth().background(Color(0xFFE5EDE5)).padding(16.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("LAST 7 DAYS / STEPS", style = MaterialTheme.typography.labelLarge)
            if (summaries.isEmpty()) Text("Daily summaries appear after reading authorized steps.")
            summaries.forEach { summary ->
                Text("${summary.date}  ·  ${summary.steps?.let { "$it steps" } ?: "No data"}")
                if (summary.steps != null) LinearProgressIndicator(progress = { (summary.steps.toFloat() / maxOf(1L, summaries.mapNotNull { it.steps }.maxOrNull() ?: 1L)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text("Health Connect aggregation · selected source or platform source priority. Last read: ${summaries.firstOrNull()?.readAt?.let(::formatTime) ?: "never"}", style = MaterialTheme.typography.bodySmall)
            Text("03  /  RECENT RECORDS", style = MaterialTheme.typography.labelLarge)
            Text("${records.size} cached records · newest first. These are raw source records, not summed daily totals.")
            if (records.isEmpty()) Text("No records yet. Enable Samsung Health sharing in Health Connect, grant categories, save your selection and read now.")
            var recordFilter by remember { mutableStateOf<HealthType?>(null) }
            TextButton(onClick = { recordFilter = null }) { Text("All categories (${records.size})") }
            HealthType.entries.forEach { type ->
                TextButton(onClick = { recordFilter = type }) { Text("${type.label} (${records.count { it.type == type.wire }})${if (recordFilter == type) " · selected" else ""}") }
            }
            val visibleRecords = records.filter { recordFilter == null || it.type == recordFilter?.wire }
            if (visibleRecords.isEmpty() && recordFilter != null) Text("No cached ${recordFilter?.label?.lowercase()} records. Check source sharing and Read now.")
            visibleRecords.take(30).forEach { record ->
                var expanded by remember(record.key) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Column(Modifier.fillMaxWidth()) {
                    Text("${record.type.replace('_', ' ')} · ${formatTime(record.start)}", fontWeight = FontWeight.Medium)
                    Text(recordSummary(record), color = MaterialTheme.colorScheme.onSurface)
                    Text(sourceLabel(record.origin), style = MaterialTheme.typography.bodySmall)
                } }
                if (expanded) Text(JSONObject(record.data).toString(2), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
            Text("04  /  RECENT ACTIVITY", style = MaterialTheme.typography.labelLarge)
            runs.forEach { Text("${formatTime(it.time)}  ${it.message}", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(enabled = !busy && records.isNotEmpty(), onClick = { exportLauncher.launch("health-records.json") }) { Text("Export selected records as JSON") }
            TextButton(onClick = { activity.startActivity(Intent(activity, PrivacyActivity::class.java)) }) { Text("Privacy & data use") }
            TextButton(onClick = { runCatching { activity.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }.onFailure { status = "Open Health Connect in Android Settings to revoke permissions" } }) { Text("Manage / revoke Health Connect permissions") }
            TextButton(enabled = !busy, onClick = { confirmClear = true }) { Text("Clear local data & settings") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
private fun formatTime(time: String) = runCatching { DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(time)) }.getOrDefault(time)
private fun recordSummary(record: CachedRecord): String = runCatching {
    val data = JSONObject(record.data)
    when (record.type) {
        "steps" -> "${data.getLong("count")} steps in this interval"
        "sleep" -> "Session interval · ${java.time.Duration.between(Instant.parse(record.start), Instant.parse(record.end)).toMinutes()} min · ${data.getJSONArray("stages").length()} stages"
        "heart_rate" -> "${data.getJSONArray("samples").length()} heart rate samples (bpm)"
        "exercise" -> "Exercise type ${data.getInt("exerciseType")} · ends ${formatTime(record.end)}"
        else -> "Record"
    }
}.getOrDefault("Record details available")
