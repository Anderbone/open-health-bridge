package dev.openhealthbridge

import androidx.health.connect.client.HealthConnectClient
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private class FakeHealth : HealthData {
        override val status = HealthConnectClient.SDK_AVAILABLE
        var snapshot = NormalizedRecord("id", HealthType.SLEEP, "source", "2026-10-12T12:00:00Z", "2026-10-11T22:00:00Z", "2026-10-12T06:00:00Z", "{\"stages\":[]}")
        var pages = mutableListOf<ChangePage>()
        var scanned = 0
        var failAfterSnapshot = false
        var allowed = setOf(HealthType.SLEEP.permission)
        override fun backgroundAvailable() = false
        override suspend fun granted() = allowed
        override suspend fun token(type: HealthType, origin: String) = "initial-token"
        override suspend fun scan(type: HealthType, origin: String, start: Instant, end: Instant, consume: suspend (List<NormalizedRecord>) -> Unit) { scanned++; consume(listOf(snapshot)); if (failAfterSnapshot) error("simulated process interruption") }
        override suspend fun changes(token: String): ChangePage = if (pages.isEmpty()) ChangePage(emptyList(), "next", false, false) else pages.removeAt(0)
        override suspend fun dailySteps(origin: String) = emptyList<DailySummary>()
    }
    private class FakeTransport : DeliveryTransport {
        var code = 204
        var networkFailure = false
        val sent = mutableListOf<Outbox>()
        override fun send(config: DestinationConfig, event: Outbox): DeliveryResult {
            sent.add(event)
            if (networkFailure) throw java.io.IOException("simulated offline")
            return DeliveryResult(code, null)
        }
    }
    @Test fun snapshotDedupUpdateDeleteAndNetworkRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).build()
        val store = TokenStore(context, "test-${UUID.randomUUID()}")
        val health = FakeHealth(); val transport = FakeTransport()
        val engine = SyncEngine(context, db, store, health, transport, false)
        store.save(DestinationConfig("https://example.org/events", "test", true, setOf(HealthType.SLEEP)))
        try {
            engine.read()
            val initial = db.dao().next()!!
            engine.read()
            assertEquals(1, health.scanned)
            assertEquals(initial.eventId, db.dao().next()!!.eventId)
            transport.networkFailure = true
            assertTrue(engine.deliver())
            assertEquals(initial.eventId, db.dao().next()!!.eventId)
            assertTrue(db.dao().next()!!.nextAttemptAt > System.currentTimeMillis())
            transport.networkFailure = false
            db.dao().retry()
            assertFalse(engine.deliver())
            assertNull(db.dao().next())
            assertEquals(transport.sent[0].eventId, transport.sent[1].eventId)
            assertEquals(transport.sent[0].payload, transport.sent[1].payload)
            health.pages.add(ChangePage(listOf(RecordChange.Upsert(health.snapshot.copy(modified = "2026-10-12T13:00:00Z", data = "{\"stages\":[1]}"))), "page2", true, false))
            health.pages.add(ChangePage(listOf(RecordChange.Delete("id")), "page3", false, false))
            engine.read()
            assertTrue(db.dao().record("sleep:id")!!.deleted)
            assertEquals("page3", db.dao().cursor("sleep")!!.token)
            assertFalse(engine.deliver())
            val delivered = transport.sent.takeLast(2).map { JSONObject(it.payload) }
            assertEquals("health.record.upsert", delivered[0].getString("event"))
            assertEquals("health.record.delete", delivered[1].getString("event"))
            assertTrue(delivered[1].getLong("localRevision") > delivered[0].getLong("localRevision"))
        } finally { store.clear(); db.close() }
    }
    @Test fun rejectedDeliveryPausesAndRouteChangeDoesNotRerouteOldEvent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).build()
        val store = TokenStore(context, "test-${UUID.randomUUID()}")
        val health = FakeHealth(); val transport = FakeTransport().apply { code = 401 }
        val engine = SyncEngine(context, db, store, health, transport, false)
        val config = DestinationConfig("https://example.org/events", "test", true, setOf(HealthType.SLEEP))
        store.save(config)
        try {
            engine.read(); assertFalse(engine.deliver()); assertTrue(db.dao().next()!!.blocked)
            assertFalse(engine.deliver()); assertEquals(1, transport.sent.size)
            engine.save(config.copy(url = "https://new.example.org/events"))
            assertNull(db.dao().next()); assertNull(db.dao().cursor("sleep"))
            engine.stop(); engine.read()
            assertNull(db.dao().next())
        } finally { store.clear(); db.close() }
    }
    @Test fun expiredCursorSchedulesRecoveryWithoutInventingDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).build()
        val store = TokenStore(context, "test-${UUID.randomUUID()}")
        val health = FakeHealth(); val engine = SyncEngine(context, db, store, health, FakeTransport(), false)
        store.save(DestinationConfig(types = setOf(HealthType.SLEEP)))
        try {
            engine.read()
            health.pages.add(ChangePage(emptyList(), "expired", false, true))
            engine.read()
            assertFalse(db.dao().cursor("sleep")!!.initialComplete)
            assertFalse(db.dao().record("sleep:id")!!.deleted)
            engine.read()
            assertTrue(db.dao().cursor("sleep")!!.initialComplete)
            assertEquals(2, health.scanned)
        } finally { store.clear(); db.close() }
    }
    @Test fun interruptedInitialScanResumesAndRevokedPermissionBlocksUpload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).build()
        val store = TokenStore(context, "test-${UUID.randomUUID()}")
        val health = FakeHealth().apply { failAfterSnapshot = true }
        val transport = FakeTransport()
        val engine = SyncEngine(context, db, store, health, transport, false)
        store.save(DestinationConfig("https://example.org/events", "test", true, setOf(HealthType.SLEEP)))
        try {
            try { engine.read(); fail("Expected interruption") } catch (_: IllegalStateException) { }
            val eventId = db.dao().next()!!.eventId
            assertFalse(db.dao().cursor("sleep")!!.initialComplete)
            health.failAfterSnapshot = false
            engine.read()
            assertEquals(eventId, db.dao().next()!!.eventId)
            assertTrue(db.dao().cursor("sleep")!!.initialComplete)
            health.allowed = emptySet()
            assertFalse(engine.deliver())
            assertEquals(0, transport.sent.size)
            assertTrue(db.dao().next()!!.blocked)
            health.allowed = setOf(HealthType.SLEEP.permission)
            db.dao().retry()
            assertFalse(engine.deliver())
            assertEquals(eventId, transport.sent.single().eventId)
        } finally { store.clear(); db.close() }
    }

}
