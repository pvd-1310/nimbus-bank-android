# Local demo backend implementation plan

> Execute inline using superpowers:executing-plans. User authorized implementation, with chatbot deferred.

**Goal:** Build a local trusted backend for the banking demo with Spark Auth/Firestore, S3 evidence and Stripe sandbox adapters.
**Architecture:** Express routes validate requests; services authorize and perform atomic operations through a transaction store; external providers live behind adapters.
**Tech Stack:** TypeScript, Node, Firebase Admin, Express, Zod, AWS SDK, Stripe, Vitest.
**Spec:** `docs/superpowers/specs/2026-11-21-local-backend-design.md`

## Constraints and review focus

- No chatbot implementation, deployment, billing changes, or commits.
- Work in the existing checkout as agreed in the spec; preserve Android source and current login.
- Backend authorization must hold even though Admin bypasses rules.
- Duplicate/concurrent operations, expired/replayed OTP, changed payment amounts, and forged webhooks must be tested.
- Configuration checkpoints must document required user actions without printing secrets.

## Tasks

### Task 1: Runtime and store

Files: `backend/package.json`, `tsconfig.json`, `src/config.ts`, `src/store.ts`, `src/errors.ts`, `test/core.test.ts`.
Produces `Store.run<T>(work: (tx: Tx) => Promise<T>): Promise<T>`, with transactional `get`, `set`, and `list`. Firestore and memory test adapters implement it.

- [ ] Write failing configuration and transaction rollback tests; run `npm test`.
- [ ] Implement strict environment validation and durable transaction boundary.
- [ ] Run `npm test` and `npm run build`.

### Task 2: Banking core

Files: `src/banking.ts`, `src/calculations.ts`, `test/banking.test.ts`.
Consumes Store. Produces `Banking` methods for scoped reads, intent/challenge/verify/commit/status, profiles, officer policy and eKYC workflow.

- [ ] Write tests for ownership, claims, validation, balanced ledger, concurrency, idempotency mismatch, OTP expiry/replay, high-value proof and calculation totals.
- [ ] Observe failures; implement transactional methods with explicit status models.
- [ ] Verify suite and build.

### Task 3: Providers and HTTP

Files: `src/providers.ts`, `src/payments.ts`, `src/app.ts`, `src/server.ts`, `test/http.test.ts`, `test/payments.test.ts`.
Consumes Banking and Store. Produces authenticated `/v1` API, S3 tickets, Stripe sandbox webhook/checkout and scoped catalogs/orders, branch directory.

- [ ] Write failing tests for authentication, object scope, webhook forgery/deduplication/order, quote amount and capabilities.
- [ ] Implement provider adapters and raw webhook handling; deny unsupported capabilities explicitly.
- [ ] Run full tests and build.

### Task 4: Demo tooling and verification

Files: `src/seed.ts`, `src/worker.ts`, `README.md`, `.env.example`, `openapi.json`, `test/emulator.test.ts`, `docs/verification/local-backend.md`.

- [ ] Add emulator integration checks for real Firestore atomicity and authorization.
- [ ] Implement idempotent namespaced seed, development OTP inbox, maintenance command, API contract and configuration instructions.
- [ ] Verify emulator suite, local HTTP smoke, build and full unit suite. Record live-provider limitations.
- [ ] Fresh reviewer checks security/requirements; fix material findings and rerun affected checks.

## Progress ledger

Ruling: implement directly in the existing checkout; no new repository/worktree/commits, following the accepted design.
Ruling: chatbot remains deferred; Maps/face/external settlement return explicit unavailable status pending providers.

Task 1: complete — strict config, transaction store, rollback and concurrency tests.
Task 2: complete for local core — accounts/calculations/profile/history, request-only deposits/withdrawals, transfer/OTP/daily policy/ledger, scoped notifications and audit.
Task 3: complete for configured adapters — HTTP API, S3 version-specific evidence, Stripe sandbox signatures/settlement/outbox, officer provisioning/claims repair, demo service catalogs/orders. Live provider validation is pending user configuration.
Task 4: verified local — 41 unit/HTTP tests, 3 separate emulator integration tests, TypeScript build, review fixes. Configuration and Android/provider integration remain staged as documented in verification.
Ruling: OpenAPI is served at `/openapi.json` from `backend/src/openapi.ts`, instead of maintaining a duplicate generated JSON file.
Review fixes: atomic UTC daily limits and policy digest; terminal payment cancellation/lock release; idempotent provisioning claims repair; Firestore query pagination and bounded outbox batches. Configured-gateway forged signature test added.
