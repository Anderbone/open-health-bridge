# Privacy and data use — prototype

Open Health Bridge reads user-selected Health Connect categories solely for on-device viewing, export and explicitly enabled delivery to a user-supplied HTTPS endpoint. It does not write to Health Connect, require an account, use a developer server, or include advertising/analytics SDKs.

Private Room storage contains health snapshots, incremental cursors, queued payloads and short activity messages. API tokens use Android Keystore AES-GCM encryption; settings and app databases are excluded from backup and device transfer. Activity logs contain counts and HTTP status codes, not payloads, tokens or response bodies. Cached payloads older than 30 days are cleared on reads unless delivery is pending. Minimal identity mappings remain until explicit local clearing to preserve deletion handling. Successful outbox payloads are removed immediately after acknowledgment. Activity is bounded to 100 entries, summaries to seven days, and ingestion pauses at a 50 MB payload budget (not a database-file size limit).

The destination receives selected record data, times, origin-package identifiers and IDs. Its operator controls onward access and deletion. The user must review the URL and explicitly enable sharing. Changing endpoint or data scope clears the prior queue. JSON export is a separate user action and produces a sensitive file at the selected location.

Stop sync disables scheduled reading and delivery. An already in-flight request may complete. Clear local data removes settings, token, snapshots, cursors and queued deliveries; it does not delete Health Connect data or data already accepted by a server. Health permissions can be revoked in Android Settings. Restoring tokens after a key failure requires re-entering credentials.

This document describes a development prototype. A public policy with developer contact, retention guarantees and reviewed Play Data safety disclosure must be completed before distribution. Google Play health permission approval has not been obtained.
