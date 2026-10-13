# Webhook v1

POST a single JSON event to the configured HTTPS URL with `Content-Type: application/json`, `Idempotency-Key: <eventId>`, and an optional `Authorization: Bearer <token>`. Redirects and HTTP cleartext are rejected. Standard TLS validation remains enabled; self-hosted private HTTPS endpoints are supported with trusted certificates.

```json
{
  "schemaVersion": 1,
  "bridgeScopeId": "installation-uuid",
  "eventId": "immutable-event-uuid",
  "event": "health.record.upsert",
  "recordId": "health-connect-id",
  "recordType": "steps",
  "source": {"platform": "health_connect", "packageName": "observed.source"},
  "localRevision": 1791810000000,
  "recordLastModifiedTime": "2026-10-12T12:00:00Z",
  "observedAt": "2026-10-12T13:00:00Z",
  "data": {"startTime": "2026-10-12T11:00:00Z", "endTime": "2026-10-12T12:00:00Z", "count": 123}
}
```

Examples are fictitious. Record identity is `(bridgeScopeId, recordType, recordId)`. Every changed snapshot has a persisted monotonically increasing `localRevision`; it is an ordering key, not a timestamp contract. Retries preserve the exact eventId and payload. A new installation gets a new scope. Revisions survive local-data clearing.

Types: `sleep` has numeric Health Connect `stages` with start/end times and nullable zone offsets; `steps` has count; `exercise` has numeric Health Connect exerciseType; `heart_rate` has timestamped samples with bpm. All have UTC start/end times. No inferred diagnosis or resting heart rate. Android stage/type constants retain Health Connect semantics.

Deletion event: `event: health.record.delete`, same identity and higher localRevision, no `data`. Its recordLastModifiedTime comes from the last known snapshot; observedAt is when the deletion was read, not the source's deletion timestamp.

Receiver requirements:

1. Authenticate and validate schema/type; transact eventId deduplication together with application of the record.
2. Upsert or delete only if localRevision is newer for that record identity; retain tombstone revision to reject older events.
3. Return HTTP 2xx only after durable acceptance. Repeated eventIds should still get 2xx.

The explicit endpoint-test button sends only `{"schemaVersion":1,"event":"health.bridge.ping"}` with a fresh Idempotency-Key and the configured authorization; it includes no health records.

Delivery is ordered within the single destination, at least once. Network errors, 408, 429 and 5xx retry with bounded exponential delay. Numeric and HTTP-date Retry-After are honored up to one day. Other responses pause the queue until the user fixes settings and requests retry. Queue progress is separate from the Health Connect cursor; remote failure does not discard local events. Endpoint/scope changes discard old pending events and trigger a fresh read, never rerouting old payloads to a new endpoint.
