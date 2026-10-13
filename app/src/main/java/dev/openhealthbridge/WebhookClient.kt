package dev.openhealthbridge

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

fun validateDestination(url: String, token: String) {
    val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS URL")
    require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null) { "Use HTTPS without embedded credentials or a fragment" }
    require(token.all { it.code in 32..126 }) { "Token contains invalid header characters" }
}
data class DeliveryResult(val code: Int, val retryAfterSeconds: Long?)
interface DeliveryTransport { fun send(config: DestinationConfig, event: Outbox): DeliveryResult }
class WebhookClient(baseClient: OkHttpClient = OkHttpClient()) : DeliveryTransport {
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    fun ping(url: String, token: String): DeliveryResult = send(DestinationConfig(url, token),
        Outbox(java.util.UUID.randomUUID().toString(), 0, "{\"schemaVersion\":1,\"event\":\"health.bridge.ping\"}", "ping"))
    override fun send(config: DestinationConfig, event: Outbox): DeliveryResult {
        validateDestination(config.url, config.token)
        val request = Request.Builder().url(config.url).header("Idempotency-Key", event.eventId)
            .post(event.payload.toRequestBody("application/json".toMediaType())).apply {
                if (config.token.isNotEmpty()) header("Authorization", "Bearer ${config.token}")
            }.build()
        return client.newCall(request).execute().use { DeliveryResult(it.code, parseRetryAfter(it.header("Retry-After"))) }
    }
}
fun retryable(code: Int) = code == 408 || code == 429 || code in 500..599
fun retryDelay(attempts: Int, retryAfterSeconds: Long? = null): Long =
    maxOf((30L * (1L shl attempts.coerceIn(0, 10))).coerceAtMost(21600), retryAfterSeconds ?: 0) * 1000

fun parseRetryAfter(value: String?, now: java.time.Instant = java.time.Instant.now()): Long? {
    if (value == null) return null
    return (value.toLongOrNull() ?: runCatching {
        java.time.Duration.between(now, java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds
    }.getOrNull())?.coerceIn(0, 86400)
}
