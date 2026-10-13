package dev.openhealthbridge

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    @Test fun transactionRollsBackQueueAndCursorTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).build()
        try {
            try {
                db.withTransaction {
                    db.dao().enqueue(Outbox("event", 1, "{}", "sleep"))
                    db.dao().saveCursor(Cursor("sleep", "token", "source", "start", "end"))
                    error("simulate process failure before commit")
                }
            } catch (_: IllegalStateException) { }
            assertNull(db.dao().next()); assertNull(db.dao().cursor("sleep"))
            db.withTransaction {
                db.dao().enqueue(Outbox("first", 1, "{}", "sleep"))
                db.dao().enqueue(Outbox("second", 2, "{}", "sleep"))
                db.dao().saveCursor(Cursor("sleep", "committed", "source", "start", "end"))
            }
            assertEquals("first", db.dao().next()!!.eventId)
            db.dao().acknowledge("first")
            assertEquals("second", db.dao().next()!!.eventId)
            assertEquals("committed", db.dao().cursor("sleep")!!.token)
        } finally { db.close() }
    }
    @Test fun retentionRemovesPayloadWithoutDroppingPendingDeliveryOrIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).build()
        try {
            val record = CachedRecord("sleep:old", "old", "sleep", "source", "2000-01-01T00:00:00Z", "2000-01-01T00:00:00Z", "2000-01-02T00:00:00Z", "{\"stages\":[]}", "hash", 1)
            db.dao().saveRecord(record)
            db.dao().enqueue(Outbox("pending", 1, "{}", "sleep", recordKey = record.key))
            db.dao().prunePayloads("2026-01-01T00:00:00Z")
            assertEquals(record.data, db.dao().record(record.key)!!.data)
            db.dao().acknowledge("pending")
            db.dao().prunePayloads("2026-01-01T00:00:00Z")
            val identity = db.dao().record(record.key)!!
            assertEquals("{}", identity.data)
            assertEquals("old", identity.recordId)
            assertFalse(identity.deleted)
        } finally { db.close() }
    }

}
