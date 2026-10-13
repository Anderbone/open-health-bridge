# Remaining work before public release

- [ ] Confirm developer namespace, license acceptance, signing custody and public privacy-policy contact/hosting.
- [ ] Validate all four categories from a real Samsung Health + Galaxy Watch setup; document delay and missing categories.
- [ ] Add deliberate permission-revocation and mid-sync source/configuration-change coverage.
- [ ] Complete expired-token reconciliation for fully readable ranges and communicate unverifiable historical deletions.
- [x] Add 30-day cache payload cleanup, visible 50 MB ingestion budget failure, and preserve pending deliveries.
- [ ] Stress-test disk usage, identity-map growth and actual database/WAL sizes.
- [x] Add seven-day steps summaries/trends using Health Connect aggregation.
- [ ] Extend sleep/exercise summaries using documented source-aware aggregation and interval semantics; do not sum overlapping/mixed step sources or label session spans as actual sleep.
- [ ] Improve source discovery, locale/accessibility UX and permission prompts on all supported versions.
- [x] Add non-health destination ping and HTTP-date Retry-After support.
- [ ] Add export-failure integration coverage.
- [ ] Exercise offline delivery, death after remote acceptance, restart, Doze and server rejection end to end using synthetic data.
- [ ] Assess current Play-approved health use cases for generic webhooks; prepare honest health permissions and Data safety declarations. Visible graphs do not override policy requirements.
- [ ] Check production eligibility; run applicable closed test (12 opted-in testers, continuous 14 days), then submit for review.

No release, remote repository, account setup, messaging or publication is performed automatically by this implementation.
