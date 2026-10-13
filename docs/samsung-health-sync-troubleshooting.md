# Samsung Health present, but Health Connect contains only phone steps

Investigated 2026-10-12 on the connected Fold. Samsung Health version: 7.00.6.011. OS reports Samsung Health write access for steps, sleep, heart rate and exercise granted. Bridge's previous reads contained only the native Health Connect phone-step source. The user confirmed that Samsung Health itself displays watch steps, heart rate and sleep, while Health Connect does not.

The missing link is therefore strongly indicated to be Samsung Health -> Health Connect. This is not proof that Bridge has no bugs; compare the same date/category directly in Health Connect when rechecking. Source priority selects contributions to aggregates and cannot force absent source records to be written. Galaxy Watch data normally arrives through Samsung Health rather than a separate Watch app source.

## First check: Samsung Health 7 internal data-processing consent

Samsung's [7.0 sync troubleshooting](https://www.samsung.com/us/support/troubleshoot/TSG10013213/) explicitly identifies missing internal Privacy consent/settings as a cause of synchronization/restoration problems. This is separate from Android Health Connect read/write permissions.

In Samsung Health -> Settings -> Privacy, inspect the consent to processing health and wellness data (wording varies). Review terms and decide personally whether to enable it. Do not automatically accept unrelated cycle tracking, medication, location, or AI-training permissions. The consent state on this Fold has not been confirmed; navigation was interrupted by the foreground terminal app, so no consent toggle was changed.

Several users with the same missing-Samsung-data symptom report that enabling health-data consent restored steps or other vitals, while others still had missing steps. These are [user reports](https://www.reddit.com/r/GalaxyWatch/comments/1onxj6i/samsung_health_is_not_syncing_with_health_connect/), not a guaranteed fix or a reason to consent to everything.

## Then trigger source synchronization

Follow [Samsung's Health Connect FAQ](https://developer.samsung.com/health/health-connect-faq.html):

1. Check updates for phone/watch Samsung Health.
2. Open Health Connect permissions from inside Samsung Health settings and verify the intended write categories.
3. Return to Samsung Health after granting access.
4. Settings -> Sync with Samsung account -> Sync now (older versions call this Samsung Cloud).
5. Verify today's Samsung-origin records directly in Health Connect, including sleep/heart rate, before judging Bridge's totals. Once present, Bridge Read now should pick up inserts/updates through its Changes cursor.

New data provides a useful probe: a deliberate manual watch heart-rate measurement should appear in Samsung Health and then Health Connect. This does not guarantee retrospective backfill; Samsung documents synchronization when data is newly created or changed. Do not silently clear Health Connect history, reset the watch, or delete Samsung/cloud data as a first remedy.

## If native Samsung export remains broken

Some users have used Health Sync to write Samsung data into Health Connect instead of Samsung's native step writer, including a [2026-10 user report](https://www.reddit.com/r/shealth/comments/1x28fjv/shealth_sync_with_health_connect/). Treat this as a separate integration to evaluate, with its own permissions/cost/data scope, not a confirmed necessity for this Fold. Two concurrent writers can require careful duplicate handling. Bridge cannot read Samsung records that were never supplied to Health Connect; any direct-Samsung-SDK fallback would be a separate product decision, not a fabricated replacement for missing data.
