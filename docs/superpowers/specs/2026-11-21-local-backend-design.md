# Local demo backend design

Status: Approved for implementation on 2026-11-21; chatbot explicitly deferred by the user. Chat sections below describe future integration only.

## Intent and agreed constraints

Implement the backend for the mobile banking workflows in `/Users/TDTU/Mobile/Template_EN.docx`. Start with a local demo and preserve boundaries needed for a later hosted application. The user selected Firebase Spark for Authentication and Firestore, private Amazon S3 for eKYC evidence, and Stripe in place of VNPay. Firebase Cloud Functions and Firebase Storage are excluded from this deployment. The user requests notification and step-by-step guidance whenever Firebase or S3 configuration is needed; the same practice applies to Stripe and other external providers.

This document supersedes backend/provider assumptions in the November 20 incremental design where they conflict. Existing Android read screens remain the starting point. No deployment, billing changes, real charges, or credential provisioning is authorized by this design.

## Approaches

1. Firebase Cloud Functions: matches the original template but requires Blaze and conflicts with the selected Spark constraint.
2. A local TypeScript HTTP API using Firebase Admin: retains Auth/Firestore and supplies a trusted execution environment independently of Firebase billing. This is the proposed implementation of the user's chosen demo-first direction.
3. AWS Lambda and API Gateway immediately: compatible with Spark and S3, but adds deployment and operational setup before the demo needs it. Keep it as a future hosting option, not an agreed deployment target.

Builder choice: use Node.js LTS, TypeScript, Express, schema validation, and automated tests in a `backend/` directory. Select supported package/runtime versions at implementation time. Keep HTTP handlers separate from domain services, persistence, and provider adapters. Do not create a separate worktree or Git repository merely for this work; the existing plan records the user's preference against repository creation/commits.

## Scope and delivery boundaries

Cover customer profile/preferences, account details and financial calculations, deposit/withdrawal requests, internal and external transfers, OTP, eKYC submission/review, bills/top-up/service orders, Stripe payment status, branches/routes, chatbot actions, officer customer administration, versioned rate policies, notifications, and audit queries.

Implement and verify the trusted core first. Externally dependent capabilities expose `SETUP_REQUIRED` until configured. A simulated external-bank, bill, inventory, fulfillment, or face verification provider is explicitly labelled demo in API responses and the provider matrix. A demo result must not be reported as an actual bank settlement, utility fulfillment, or biometric verification.

S3 stores evidence and does not perform identity verification. Start with manual officer review. High-value transaction face proof requires a separately configured verifier; until available, reject completion when policy requires that proof. Retain OTP requirements independently of Stripe's own payment authentication.

## Runtime and trust boundaries

Android signs in through Firebase Authentication and sends an ID token as a bearer token to the local API. The API verifies it with Firebase Admin, enforces active user status, claims, operation permissions, and resource ownership, then performs authoritative writes. Never accept an actor UID, officer privilege, amount, rate, payment status, or settlement result as trusted merely because Android supplies it.

Use Firebase Emulator Suite for repeatable automated integration tests. Enable connection to the user's Spark project through explicit environment configuration for the connected demo. Fail startup on ambiguous/mixed emulator configuration. Firebase Admin bypasses Firestore rules, so authorization is required in every service as well as client read rules.

Use a localhost listener by default. Android Emulator accesses the host through `10.0.2.2`. Development-only cleartext access must be scoped to the local demo endpoint; release builds require HTTPS. A physical device needs a reachable development address and deliberately configured binding. Stripe CLI forwards sandbox webhook events to the local API; a deployed webhook will require a public HTTPS endpoint.

Secrets stay in ignored local environment files or external credential files. Provide an example configuration containing names and placeholders only. Logs redact tokens, OTPs, presigned URLs, private images, and provider secrets.

## Identity and authorization

Use `CUSTOMER` and `OFFICER` custom claims assigned only by trusted bootstrap/admin tooling. Validate the corresponding server-owned profile and permissions for each request. Role or disabled-status changes invalidate access on subsequent protected operations according to the configured token/session policy. Officer permissions are explicit, such as customer administration, eKYC review, rate-policy update, and audit read.

Supply an idempotent migration/bootstrap command for the two existing demo users. Explain token refresh/re-login after claims are assigned. Coordinate migration of Firestore rules and Android role routing so existing demo login is not silently broken. Customer creation via an officer endpoint creates an inactive/onboarding customer, uses an invitation/reset-password workflow, and records partial Auth/Firestore failures for repair. Officers cannot create other officers or grant themselves permissions.

## Data model and compatibility

Preserve the template collections: `users`, `customers`, `officers`, `accounts`, `checkingDetails`, `savingsDetails`, `mortgageDetails`, `transactions`, `transactionIntents`, `ledgerEntries`, `otpChallenges`, `ekycRequests`, `bills`, `payments`, `orders`, `ratePolicies`, `bankBranches`, `notifications`, `chatHistory`, and `auditLogs`.

Add small internal collections only where needed for durable idempotency, webhook event deduplication, outbox processing, quotes/reservations, and reconciliation. Document each addition. Authoritative account fields are `availableMinor`, `bookedMinor`, `currency`, `status`, and `version`. Money uses integer minor units with currency-aware scale and safe-integer checks; VND must not receive an assumed universal division by 100.

The existing Android model reads `balanceMinor`, transactions by `ownerUid`, and notifications using `body`/`read`. Introduce an explicit compatibility migration or safe read projection during integration. For transfers, avoid publishing another customer's private fields through a shared transaction document: use scoped API projections or participant read projections. Document status mapping rather than reusing `SETTLED` and `SUCCEEDED` interchangeably.

Seed at least one account of each type, versioned savings rates, mortgage terms, bills, catalogs, branches, histories, and two customer identities for ownership/concurrency tests. Seed data and reset operations require a named demo namespace, explicit project selection, and a dry run. Existing broad `demo: true` reset behavior must not be extended to unrelated records. Seeding runs outside the APK and public API.

## API groups

Use `/v1` JSON endpoints with stable error codes, request IDs, bounded input sizes, and cursor pagination. Publish an OpenAPI contract alongside implementation. Return provider capability status so UI can describe disabled features accurately.

| Group | Operations |
| --- | --- |
| Session and profile | Current identity, allowed profile/contact edits, preferences, capabilities |
| Accounts | Owned account list/details, checking policy, savings estimate, mortgage schedule |
| History | Scoped transaction list/detail, account/type/status/date filters |
| Requests and transfers | Deposit/withdrawal requests, transfer intent, OTP challenge/verify, commit, status lookup |
| eKYC | Consent/request creation, upload ticket, uploaded-object validation, submit, review status |
| Bills and services | Invoice/catalog lookup, quotes, reservations/orders, top-up requests |
| Payments | Server-priced Stripe payment creation, status, signed webhook processing |
| Branches | Active directory, manual origin support, walking-route recommendation when configured |
| Chat | Redacted context, authorized data queries, validated allowlisted actions |
| Officer | Customer provisioning/controlled edits, eKYC decisions, future-effective rates, scoped audit queries |
| Notifications | Owner-scoped list and permitted read acknowledgement |

## Financial operations

Create an immutable intent from server-validated account, destination, amount, currency, policy version, and expiration. Bind authorization to an intent digest. Request IDs are scoped by actor and operation; retries with an identical payload return the stored result, while reused keys with changed payloads fail.

Internal transfer commit uses one Firestore transaction to recheck ownership, active status, funds, intent/OTP authorization, and required step-up proof; debit/credit accounts; write balanced ledger entries, transaction result, idempotency record, notifications, and audit. A failed commit produces no partial debit. Concurrent requests cannot overspend. After a connection failure, status lookup by request ID returns the authoritative result.

Deposit/withdrawal request submission alone does not change funds. Any later simulated settlement needs an explicit privileged demo processor and matching system/cash clearing ledger account. No public endpoint can credit arbitrary funds.

External transfers use reserved funds and explicit pending/failed/reconciliation states through an adapter. The first milestone may leave this adapter unavailable. Any demo processor must have documented reservation, release, and settlement semantics and deterministic tests before it is enabled.

OTP challenges use cryptographically random codes, protected verification material with a server secret, expiry, bounded attempts, resend throttling, and atomic one-use consumption with commit. Verification produces a proof bound to the same intent; it does not consume funds. OTP never appears in API responses or logs. A local-only development delivery adapter may write messages to an ignored local inbox for demonstration; remote/live modes must refuse this adapter. A real independent OTP provider remains required for deployment.

## Accounts and policy calculations

Checking exposes server-managed daily limits and overdraft policy. Savings estimates use a versioned rate policy, explicit day-count convention, and integer rounding; the estimate does not accrue funds. Mortgage schedules freeze the loan terms, specify frequency including biweekly, handle zero interest, preserve rounded totals, and separate projected installments from paid installments. Builder proposes ACT/365 simple interest for demo savings and a documented amortization convention for mortgages; expose calculation assumptions in results and test known fixtures.

Officer rate changes create immutable versions with future effective dates and audit summaries. Customers cannot modify rates, roles, identity ownership, balances, or review outcomes through profile endpoints.

## S3 eKYC flow

Create a consented eKYC request before issuing upload access. Generate unpredictable server-owned object keys under customer/request scope in a private bucket. Issue short-lived upload access with allowed media types and size limits; prefer presigned POST where enforcing a content-length range is needed. Validate actual object metadata and content checks before moving to review. Pending submissions cannot be approved merely because Android reports upload success.

Firestore stores object keys and metadata, not permanent public URLs. Authorized officer viewing gets short-lived download access and an access audit event. Review transitions include reviewing, approved, rejected, and resubmission required; approval stores reviewer, reason, and timestamp atomically. Define cleanup/retention for abandoned uploads and rejected submissions and provide a local scheduled maintenance command for the demo. Use synthetic evidence only in demo fixtures.

## Stripe and service fulfillment

Use Stripe test credentials initially. Server prices come from a current invoice or quote, with owner/currency checks and idempotent creation. Persist a payment record before calling Stripe and reconcile retries after provider timeouts. Validate webhook signature using the original raw request body, account/environment, provider reference, amount, and currency. Deduplicate events durably and prevent out-of-order events from regressing terminal states. The Android return callback is a navigation signal.

Keep payment, internal money movement, and service fulfillment separate. A successful Stripe payment can still have pending ticket/top-up fulfillment. Durable outbox work retries fulfillment and records reconciliation needs. A local worker/maintenance command runs pending work during the demo. Never debit the internal account a second time merely because a Stripe payment succeeded. Document the funding model for each operation before enabling it.

## Branches and chatbot

Seeded branch lists work without Maps credentials. Walking-route selection compares successful route distances from a configured provider and reports unavailable routes explicitly. Straight-line fallback must be labelled as such.

Chat uses a server adapter with minimum necessary redacted context. Validate `reply`, `actionId`, and parameters against an allowlist and current role before returning navigation actions. The bot may open a protected transfer screen; it cannot commit a financial transaction. Until credentials exist, return unavailable capability status. Never send OTP or eKYC evidence to the model.

## Configuration checkpoints and user guidance

1. Before connected Firebase testing, notify the user and provide instructions to confirm project/package, enable Auth and Firestore, configure local Admin credentials, bootstrap claims, and deploy reviewed rules/indexes. Verify the project remains Spark. Never request private keys in chat.
2. Before S3 testing, guide the user through region/bucket selection, public-access blocking, scoped IAM permissions, encryption, local credentials, and retention configuration. Configure CORS only if a browser client needs it. Verify owner upload and authorized officer access with synthetic images.
3. Before Stripe testing, guide sandbox/test credential and webhook secret setup, then local CLI forwarding. Verify a signed event and duplicate delivery.
4. Before enabling real OTP, Maps/Routes, chatbot, or face verification, identify the provider, required credentials and any separate billing. Keep each feature unavailable until configured.

Configuration work must be explicit and checkpointed when reached. Local code, contracts, and emulator tests can progress without production credentials.

## Verification and completion criteria

Create a template requirement-to-endpoint/test matrix. Unit tests cover money/calculations, policy validation, state transitions, and action allowlisting. Emulator integration tests cover claims/ownership, client rules, atomic double entry, concurrency/overspend, idempotency conflicts, OTP expiry/replay/attempts, and denied high-value completion. Adapter tests cover upload scope/validation, signed/forged/duplicate/out-of-order Stripe webhooks, provider timeouts, and fulfillment retry. Run separate opt-in connected tests only after configuration.

Each milestone records commands, results, and limitations. The demo is ready when the local API starts from documented commands, seed/reset is scoped, verified core workflows run against emulators and configured Spark resources, external capability states are truthful, and Android integration instructions are available. Successful local tests do not imply live provider deployment or completed Android feature screens.

## Proposed implementation order

1. Local API skeleton, OpenAPI, environment validation, emulator harness, identity/claims migration and rules.
2. Profile/account read models, three account types, financial calculations, filters, preferences and scoped fixtures.
3. Intent/OTP/idempotency core, balanced internal transfers, request/status APIs, audit and notification behavior.
4. S3 upload/validation and manual eKYC review with configuration guidance.
5. Stripe sandbox, bills/orders/quotes, durable webhook/outbox and fulfillment state machines.
6. Officer administration, policy versioning, branch/chat adapters, provider matrix and full verification report.
7. Wire Android service adapters incrementally; later prepare hosted HTTPS runtime, managed secrets, real OTP, monitoring, backups and provider integration as a separate deployment stage.

## References checked

- Firebase Functions quotas and Blaze requirement: https://firebase.google.com/docs/functions/quotas
- Firebase Admin ID token verification: https://firebase.google.com/docs/auth/admin/verify-id-tokens
- S3 presigned uploads: https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html
- Stripe webhooks and local testing: https://docs.stripe.com/webhooks

Backend hosting, real SMS/face providers, live Stripe eligibility, and production operational requirements remain future deployment decisions. They do not block a local core with explicitly disabled external capabilities.
