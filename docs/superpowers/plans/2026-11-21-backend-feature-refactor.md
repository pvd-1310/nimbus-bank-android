# Backend feature refactor

User requested removing unused code and organizing backend by feature before Phase 2. This is a behavior-preserving structural change; no live Firebase/S3 operations, schema migration, package upgrade, or Android changes.

## Structure

- `src/modules/<feature>/`: feature routes, services and provider adapters. Accounts, profile, transfers, transactions, requests, notifications, payments, eKYC, officer, rates, orders, branches and capabilities.
- `src/core/`: shared authorization/transaction context, HTTP composition/middleware and common contracts.
- `src/infrastructure/`: Firebase store and composition/runtime; Firebase auth adapter lives with `modules/auth`.
- `src/shared/`: errors, request schemas, pagination and hashing helpers.
- `src/database/`: template schema and migration plan logic.
- `src/scripts/`: trusted seed/migrate/outbox commands.
- `src/docs/`: OpenAPI.
- `src/app.ts`, `src/server.ts`: stable entrypoints.

## Tasks

1. Run current unit/API and build as a baseline. Add/retain route contract coverage as needed.
2. Extract feature services from Banking, keeping shared authorization and atomic store APIs. Extract routes and HTTP middleware from app.ts. Deduplicate payment request validation.
3. Relocate persistence/providers/scripts, update imports, package scripts, emulator test child paths and documentation. Remove old files only after references move; isolate MemoryStore in test support.
4. Run strict type checks, all unit/API tests and isolated emulator tests against a disposable demo project. No tests may connect to the configured real Firebase project.
5. Independent review and final verification; document the new tree and preserved compatibility.

## Progress

Tasks 1–5 complete: baseline 53 unit/HTTP tests, refactor, strict/unused checks, updated npm script paths, 60 passing unit/HTTP tests and four passing isolated emulator integration tests. No live project was accessed by refactor tests. Independent review confirmed all 42 documented routes and identified one emulator-harness environment guard issue; added demo/loopback validation and three regression tests before final verification.

## Verification constraints

Preserve URLs, input validation, response fields, authentication/ownership checks, raw Stripe webhook ordering, OTP semantics, idempotency and atomic transfer writes. Keep the old transfer/payment/rate input aliases. Keep current .env files and secrets untouched. Existing work is uncommitted and belongs to the user; no commits or worktree changes.
