# Backend feature refactor verification

Scope: organize backend by feature and remove unused/duplicate code before Phase 2. No Android, .env, Firebase/S3 configuration, live database or provider resource was modified by this refactor.

## Changes

- `app.ts` reduced from 529 to 52 lines by extracting feature routers and HTTP middleware; `server.ts` reduced from 62 to 21 lines.
- The former 516-line Banking service was separated into feature services; shared authorization/context now occupies 122 lines in `core/banking.ts`.
- Firebase persistence/runtime, S3/Stripe adapters, schema/migration logic and trusted CLI commands have dedicated locations.
- Removed the superseded flat source files after updating all imports and npm command targets. `MemoryStore` moved to `test/support` and is no longer shipped in production code.
- Removed unused imports, consolidated payment request parsing, enabled unused symbol checks, and made build regenerate dist so old output cannot remain executable by mistake.
- Existing compatibility aliases and unavailable-provider states are intentional behavior and were preserved.

## Evidence

- Before refactor: `npm test` reported 53 passing unit/HTTP tests; TypeScript build passed.
- After refactor and review: `npm test` reported 60 passing unit/HTTP tests; four emulator tests intentionally skipped in this command.
- `npm run build` passed with strict mode, noUnusedLocals and noUnusedParameters.
- `firebase emulators:exec --config firebase.backend-test.json --project demo-schema-tests --only auth,firestore "npm --prefix backend run test:emulator"` reported four passing tests and shut down its emulators.

The emulator run covered complete schema seed, additive/idempotent migration, pagination above 500 records, production server/Auth verification, rules denial of client writes, atomic transfer concurrency and duplicate receipt recovery. The separate demo project and ports avoid using the user's configured real Firebase project.

Additional feature-flow tests cover scoped checking details, savings and mortgage calculations, profile/preferences updates, notification acknowledgement, request-only deposits, order invoice/idempotency, branch reads and officer denial.

Independent review confirmed all 42 documented routes are mounted, raw Stripe middleware ordering is preserved, and emitted imports contain no runtime cycles. It identified a missing guard in the pre-existing emulator harness. Added a fail-fast check requiring a demo project and both loopback emulator hosts before any Admin SDK initialization, with three regression tests; reran the suite and emulator checks.
