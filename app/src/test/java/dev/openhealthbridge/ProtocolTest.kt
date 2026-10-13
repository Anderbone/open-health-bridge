package dev.openhealthbridge

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ProtocolTest {
    @Test fun upsertPreservesRecordIdentityAndSamples() {
        val payload = eventJson("scope", "event", "heart_rate", "id", "source", 5, "modified", "observed", """{"samples":[{"time":"2026-10-12T12:00:00Z","bpm":65}]}""")
        val json = JSONObject(payload)
        assertEquals("health.record.upsert", json.getString("event"))
        assertEquals("scope", json.getString("bridgeScopeId"))
        assertEquals(5, json.getLong("localRevision"))
        assertEquals(65, json.getJSONObject("data").getJSONArray("samples").getJSONObject(0).getInt("bpm"))
    }
    @Test fun deleteContainsIdentityWithoutHealthPayload() {
        val json = JSONObject(eventJson("scope", "event", "sleep", "id", "source", 6, "modified", "observed", null))
        assertEquals("health.record.delete", json.getString("event"))
        assertFalse(json.has("data"))
        assertEquals("id", json.getString("recordId"))
    }
    @Test fun destinationRejectsUnsafeUrlsAndHeaderInjection() {
        listOf("http://example.org", "https://user:pass@example.org", "https://example.org/#fragment", "garbage").forEach {
            assertThrows(IllegalArgumentException::class.java) { validateDestination(it, "") }
        }
        assertThrows(IllegalArgumentException::class.java) { validateDestination("https://example.org", "abc\r\nOther: bad") }
        validateDestination("https://192.168.1.5/health", "token")
    }
    @Test fun retryClassifiesTransientErrorsAndHonorsBoundedDelay() {
        listOf(408, 429, 500, 503).forEach { assertTrue(retryable(it)) }
        listOf(200, 302, 400, 401, 403, 404).forEach { assertFalse(retryable(it)) }
        assertEquals(30000, retryDelay(0))
        assertEquals(120000, retryDelay(0, 120))
        assertEquals(21600000, retryDelay(30))
    }
    @Test fun sourceLabelsDistinguishNativePhoneStepsFromSamsungHealth() {
        assertEquals("Phone step counter (Health Connect)", sourceLabel("com.android.healthconnect.phone.j123"))
        assertEquals("Samsung Health", sourceLabel("com.sec.android.app.shealth"))
        assertEquals("other.source", sourceLabel("other.source"))
    }

}
