package dev.openhealthbridge

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WebhookTest {
    @Test fun tlsPostPreservesIdempotencyAndDoesNotFollowRedirects() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        try {
            val client = WebhookClient(OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            val config = DestinationConfig(server.url("/events").toString(), "secret", true)
            val event = Outbox("stable-id", 1, "{\"schemaVersion\":1}", "sleep")
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "120"))
            val transient = client.send(config, event)
            assertEquals(503, transient.code); assertEquals(120L, transient.retryAfterSeconds)
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(204, client.send(config, event).code)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("stable-id", request.getHeader("Idempotency-Key"))
                assertEquals("Bearer secret", request.getHeader("Authorization"))
                assertEquals(event.payload, request.body.readUtf8())
            }
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
            assertEquals(302, client.send(config, event).code)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun httpDateRetryAfterIsBounded() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        assertEquals(120L, parseRetryAfter("Mon, 12 Oct 2026 12:02:00 GMT", now))
        assertEquals(86400L, parseRetryAfter("999999", now))
        assertEquals(0L, parseRetryAfter("-1", now))
        assertNull(parseRetryAfter("invalid", now))
    }
}
