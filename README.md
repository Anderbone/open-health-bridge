# Open Health Bridge

An independent, local-first Android prototype for viewing Health Connect records and sending them to your own HTTPS webhook. Compatible with data Samsung Health writes to Health Connect; not affiliated with Samsung or Google.

## Available now

- Read sleep sessions/stages, steps, exercise sessions and heart-rate samples after explicit permission.
- Filter by originating package; show recent raw records by category, expandable details and seven-day Health Connect step aggregation. Source labels distinguish Samsung Health from Health Connect’s native phone counter; category diagnostics distinguish missing read permission from no cached records.
- Configure one HTTPS endpoint and optional Keystore-encrypted Bearer token; explicitly consent to selected categories.
- Manual reads and uploads; incremental Changes API with transactional Room queue and ordered, at-least-once delivery.
- Optional background reads where supported and authorized, approximately every two hours; network-constrained queued uploads.
- JSON export using Android's document picker, pause/retry, stop sync and local-data removal.

No account, developer backend or analytics. Uploading defaults to off. No Health Connect writes.

## Build

Requires Android SDK 36, JDK 17 or newer compatible with Gradle 9.5, and network access for dependencies. This project has been built using JDK 25. Set `ANDROID_HOME` or an ignored `local.properties` with `sdk.dir=...`.

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Development applicationId is `dev.openhealthbridge`; finalize developer namespace and signing before public release.

## First use

1. Enable Samsung Health sharing with Health Connect on your phone. The watch needs no additional app.
2. Open Bridge, choose categories, then **Connect / update permissions**.
3. Leave source blank for initial source discovery, keep upload off, save settings and **Read now**. Tap an observed source to select it, then save again. Blank origin reads all apps; raw records are not mixed into daily step totals.
4. Enter your HTTPS webhook, optional token and selected categories. Enable sending, review the sharing notice, check consent and save.
5. **Read now** populates the queue and schedules delivery. **Send now** attempts immediate delivery. Changes to endpoint or selected scope clear the old snapshot/queue and require a new read.
6. For automatic reading, grant background permission separately, switch it on and save. Scheduling is approximate and Samsung Health itself may lag.

See [Webhook contract](docs/webhook-v1.md), [privacy](docs/privacy.md), [validation status](docs/device-validation.md), and [remaining release work](docs/release-checklist.md).

## Prototype limits

Initial and recovery scans cover seven days within granted visibility. Changes can include older records. Token expiry recovery preserves known records and reports an explicit historical-deletion gap; it does not infer deletion from missing or unauthorized history. Existing cursor scans resume safely after interruption, but complete reconciliation of expired-token deletions remains release work.

Cached record payloads older than 30 days are cleared on reads unless delivery is pending. A 50 MB payload budget pauses new ingestion; minimal identity mappings remain until explicitly cleared. This budget is not a total database-file size guarantee. No synthetic resting-heart-rate estimate or diagnosis. Seven-day step trends use the platform aggregation API with the saved source filter. Heart rate samples are not resting heart rate; sleep interval is not sleep duration. HTTP 2xx is trusted as durable acceptance by the receiver. The receiver must implement idempotency and revision ordering. Uninstalling creates a new bridge scope; cross-install deduplication is receiver-specific.

Google Play publication, permissions approval, production signing and real health-data validation are pending. This repository does not claim Play policy approval.
