# Local backend verification

Date: 2026-11-21. Scope: local backend implementation; chatbot explicitly deferred.

## Verified commands

- `cd backend && npm run build`: TypeScript build passed.
- `cd backend && npm test`: 41 passed; 3 emulator-only tests skipped intentionally in this command.
- `firebase emulators:exec --project demo-mobile-banking --only auth,firestore "npm --prefix backend run test:emulator"`: 3 passed. Emulators stopped after the run.

The integration run verified real Firestore atomicity under concurrent outgoing transfers, idempotent receipt recovery, traversal of a collection containing 510 records, date cursor pagination, production HTTP entrypoint startup, Firebase Auth emulator token verification, denial of customer officer access, and Firestore rules denial of client financial writes.

Unit/HTTP coverage includes invalid amounts, owner/profile-claim mismatch, balanced ledger, duplicate/conflicting requests, expired/missing/brute-forced OTP, high-value provider blocking, daily limit concurrency, calculations, consent/evidence requirements, missing provider states, signed-webhook boundary rejection, duplicate/amount-mismatched/out-of-order events, cancelled payment lock release, safe profile edits, safe errors, demo fulfillment, and claims repair after provisioning failure.

## Review outcome

Independent read-only review identified four material issues. Each was reproduced with a failing regression test and fixed: daily limit bypass, cancelled Stripe invoice lock and state regression, partially provisioned Auth claims, and failure above 500 records. Final unit/build and emulator runs passed after these changes. No live project, AWS, Stripe resources, billing configuration, or Android source was modified.

## Requirement mapping

| Area | Implemented backend surface | Remaining integration |
| --- | --- | --- |
| Identity and roles | Firebase token verification, profile check, officer permission gates, trusted namespaced seed | Existing UID claims migration and customer activation/invitation UX |
| Checking/savings/mortgage | Scoped reads/details, ACT/365 estimate, biweekly/monthly schedule | Android screens/adapters and final product conventions |
| Money operations | Immutable intents, OTP, atomic internal ledger, daily limits, idempotent receipts, request-only deposit/withdrawal | External bank, cash settlement and real independent OTP providers |
| eKYC | Consent, S3 upload tickets, object-version validation, submission and manual officer review | User S3 configuration and connected upload tests; retention automation and face provider |
| Payments/services | Stripe test adapter, signed/deduplicated webhooks, separate fulfillment outbox, seeded demo catalogs/orders | User sandbox configuration, Android checkout, real inventory/utility fulfillment |
| Officer | Customer provisioning/claims repair, controlled name edits, eKYC decisions, future rate versions, audit list | Deployment claims migration and full customer/account administration UX |
| Branches | Seeded directory and explicit missing Routes capability | Maps/Routes credentials and route adapter |
| Chatbot | Deferred capability flag only | User will integrate later |

## Configuration checkpoint

No credentials are required to repeat the emulator tests. To run a persistent demo, follow `backend/README.md`: start emulators, configure local `.env`, run seed dry run then `--apply`, start API. OTP local inbox is limited to the emulator/loopback environment.

Firebase Spark connected mode, private S3, and Stripe sandbox have not been tested against real accounts. At each checkpoint, guide the user using the README; secrets must remain in local credential files/environment variables. S3 requires bucket versioning, block-public-access, and scoped IAM. Stripe requires test secret and a local webhook signing secret. Connected financial operations require an OTP delivery provider.

This verification does not claim production readiness, hosted deployment, completed Android integration, real biometric verification, or external settlement. The implementation has provider boundaries and explicit unavailable states for those future steps.
