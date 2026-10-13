# Validation evidence

Date: 2026-10-12. Local SDK 36, JDK 25, Gradle 9.5.0, AGP 9.3.1, Kotlin/Compose plugin 2.4.10, Health Connect stable 1.1.0.

## Completed

- Debug APK builds; Android Lint completes with no errors (dependency freshness, pinned SDK and synchronous durability-write warnings remain).
- Seven JVM tests: source-label distinction, event identity and samples, tombstone payload shape, HTTPS/header validation, retry classification, HTTP-date delay, and a real local TLS MockWebServer test verifying stable event IDs/Bearer headers and rejected redirects.
- Six instrumentation tests on a USB-connected Samsung SM-S911B, Android API 36: Room atomic rollback/order; initial snapshot dedup and paginated update/delete; network retry with unchanged event ID; permanent rejection and endpoint reset; expired cursor recovery preserving historical records. Tests also cover interrupted initial-scan recovery, revoked-permission delivery blocking, and retention preserving pending deliveries/record identity.
- Installed and launched debug app on that device. Health Connect reports available; zero health-category permissions granted. No crash was observed during launch.
- Actual-data uploads are disabled by default. All automated synchronization tests use synthetic records and a fake transport; no Samsung Health data was read or sent.

## Still requires user/device validation

Grant selected Health Connect categories in the app, confirm Samsung Health sharing and run Read now. Compare all four categories and origin IDs against the real phone/watch data. Then configure an endpoint you control, explicitly enable selected-category sharing, read and send. No endpoint or credential was supplied for this task.

Background read permission/Doze behavior, actual Samsung watch latency, real JSON export and end-to-end receiver acceptance remain unverified. No Google Play review has been submitted or passed. Historical deletions during an expired-token gap cannot be guaranteed and are explicitly reported.

## Follow-up: Fold shows only steps

Read-only diagnosis on the connected Fold confirmed all four Bridge read permissions and all four Samsung Health write permissions granted. The cached records came exclusively from Health Connect’s synthetic native-phone step source; no Samsung Health sleep/exercise/heart-rate records had been cached. This is evidence about the last completed reads, not a guarantee about future provider contents. The initial interpretation of 181 as possibly a raw interval was revised: the cached daily aggregate also contained that count. Native phone counting is separate from the complete Samsung Health/watch total. No raw health payloads or credentials were exported into this repository.

The UI now names that source, shows per-category cached counts and permission status, warns when only native phone steps have been read, and lets each category be viewed independently instead of letting the latest 30 step records hide other categories.
