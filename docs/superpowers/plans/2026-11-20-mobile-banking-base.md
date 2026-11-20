# Mobile Banking Incremental Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the mobile banking product incrementally from the existing base, completing UC-01 to UC-12 through independently testable read, mutation-contract, and provider phases.

**Architecture:** Use a single-activity Java/XML app with Fragments and Navigation Component. ViewModels call repositories, repositories call provider-neutral domain services, and each capability is enabled behind an explicit backend/provider adapter. The Android client never authoritatively writes balances, ledger entries, role/status, audit, OTP, or settlement results.

**Tech Stack:** Android Studio, Java, XML, AndroidX, Material Components, Navigation Component, ViewModel, LiveData or StateFlow-equivalent Java observable state, Firebase Authentication, Cloud Firestore, Room/cache only where needed, JUnit, AndroidX Test.

**Spec:** `docs/superpowers/specs/2026-11-20-mobile-banking-spark-demo-design.md`

## Implementation Status

Updated after the current base pass:

- Foundation, Firebase configuration boundary, View Binding, native XML login, Customer/Officer shells, role routing, Firestore rules/indexes, Firebase Console setup documentation, and external seed/reset utility are implemented. Firebase Console setup has been completed.
- `google-services.json` is intentionally local-only and excluded by `.gitignore`.
- Build output was produced successfully after Android Studio Gradle sync, and Firebase login/role routing was verified successfully on the emulator. Unit tests and lint still require a complete Gradle test run.
- Auth repository contracts and minimal provider-neutral DTO/service interfaces are present. The next controlled step is Phase 1 read-model work; trusted seed tooling remains outside the APK, and complete UC-01–UC-12 flows remain pending.
- No Git repository or commit was created, per user instruction.

## Global Constraints

- One APK must contain both Customer and Bank Officer flows.
- Seed accounts are `customer.demo@example.com` and `officer.demo@example.com`.
- Offline mode is read-only; transfer, OTP, payment, eKYC review, officer policy updates, and other mutations require online access.
- The current base uses Firestore/Auth where available, local app-private storage only as temporary capture/cache, and explicit setup-required states for unavailable providers.
- Client-side validation is UX validation only; authoritative role, balance, ledger, audit, OTP, payment, and settlement checks belong to rules/backend/provider adapters.
- Preserve the template collection names and domain terminology.

## Review Focus

- Missing or stale Firebase configuration must show a recoverable setup error instead of crashing; test in the configuration validator task.
- A signed-in user with an unknown, inactive, or malformed role must be denied protected navigation; test in the role-routing task.
- Firestore read failure/offline state must expose cached/read-only state and must not allow mutation; test in the repository/service task.
- Trusted seed/reset tooling must be idempotent, namespace-scoped, and must not be exposed as an Android privilege; test in the tooling task.
- Customer and Officer must not reach each other’s destinations through back navigation, restored state, or deep links; test in the navigation task.

## Incremental Execution Order

Work is intentionally gated. Complete and verify one phase before starting the next:

1. **Phase 1 — Read foundation:** profile, account summaries, transaction history, notifications, loading/error/empty/offline-read-only states, and Firestore rules tests.
2. **Phase 2 — Customer read workflows:** checking, savings, mortgage details, history filters, profile/preferences, and request screens that do not mutate authoritative money.
3. **Phase 3 — Mutation contracts:** transfer/payment/eKYC/OTP DTOs, validation, idempotency, state machines, repositories, and blocked/setup-required UI. No fake success.
4. **Phase 4 — Backend operations:** callable/backend adapter for transfer, ledger, OTP challenge verification, audit, and officer authorization.
5. **Phase 5 — eKYC provider:** capture metadata and local temporary cache, then private S3 upload through backend-issued presigned URLs and officer review.
6. **Phase 6 — Payments and services:** Stripe PaymentIntent/webhook adapter, bills/top-up/orders, and explicit pending/failed/settled states.
7. **Phase 7 — Branch and chatbot:** branch data, permission/manual-origin fallback, Routes provider, and server-side allowlisted chatbot actions.
8. **Phase 8 — Officer workflows and hardening:** customer provisioning, eKYC review, rate policy, audit views, rules/security tests, accessibility, screenshot comparison, and release checklist.

Each phase must leave the app buildable and must record its verification result before the next phase begins.

### Task 1: Establish the Android project foundation

**Files:**
- Modify: `settings.gradle` or `settings.gradle.kts`
- Modify: `build.gradle` or `build.gradle.kts`
- Modify: `app/build.gradle` or `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/<package>/core/AppContainer.java`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values/colors.xml`
- Test: `app/src/test/java/<package>/core/BuildConfigurationTest.java`

**Interfaces:**
- Produces the application package, Firebase dependency boundary, app-level container, and theme used by every later task.

- [ ] **Step 1: Write the failing build/configuration test**

  Add `BuildConfigurationTest` asserting the application exposes the expected package/application ID, Java compatibility, and required Firebase/AndroidX dependencies through the project configuration.

- [ ] **Step 2: Run the test/build to verify the foundation is incomplete**

  Run: `./gradlew testDebugUnitTest`

  Expected: FAIL or configuration failure because the required base configuration is not yet present.

- [ ] **Step 3: Configure the Android module**

  Preserve the starter repository's existing package, compile SDK, and min SDK when present; otherwise use Java/XML with `minSdk 24`. Add only dependencies needed for Auth, Firestore, Navigation, ViewModel, Material, and tests. Do not add Stripe, Maps, OpenAI, or S3 SDKs in the base phase.

- [ ] **Step 4: Implement `AppContainer`**

  Define one composition root that owns Firebase Auth, Firestore, repositories, and provider-neutral service adapters; later tasks must receive dependencies from this container rather than constructing Firebase clients inside screens.

- [ ] **Step 5: Run verification**

  Run: `./gradlew assembleDebug testDebugUnitTest`

  Expected: BUILD SUCCESSFUL and unit tests pass.

- [ ] **Step 6: Commit**

  Commit: `chore: establish mobile banking android foundation`

### Task 2: Add authentication and seeded identity model

**Files:**
- Create: `app/src/main/java/<package>/auth/AuthRepository.java`
- Create: `app/src/main/java/<package>/auth/FirebaseAuthRepository.java`
- Create: `app/src/main/java/<package>/auth/AppUser.java`
- Create: `app/src/main/java/<package>/auth/UserRole.java`
- Create: `app/src/main/java/<package>/auth/AuthState.java`
- Create: `app/src/test/java/<package>/auth/AuthRepositoryContractTest.java`

**Interfaces:**
- `AuthRepository.observeAuthState(): LiveData<AuthState>`
- `AuthRepository.signIn(email: String, password: String): Result<AppUser>`
- `AuthRepository.signOut(): void`
- `AppUser(uid: String, email: String, role: UserRole, active: boolean)`
- `UserRole.CUSTOMER`, `UserRole.OFFICER`, `UserRole.UNKNOWN`

- [ ] **Step 1: Write failing contract tests**

  Test signed-out, signed-in customer, signed-in officer, invalid credentials, unknown role, inactive user, and sign-out transitions.

- [ ] **Step 2: Run the tests to verify failure**

  Run: `./gradlew testDebugUnitTest --tests '*AuthRepositoryContractTest'`

  Expected: FAIL because the contract and implementation are not complete.

- [ ] **Step 3: Implement the role and auth contracts**

  Keep role names aligned with the template. Treat Firestore profile role as the current configured authority and document that production will use custom claims/server checks.

- [ ] **Step 4: Implement Firebase Auth integration**

  Sign in through Firebase Authentication, load the corresponding `users/{uid}` profile from Firestore, reject missing/unknown/inactive roles, and map failures to safe UI-readable states.

- [ ] **Step 5: Run tests and emulator-compatible verification**

  Run: `./gradlew testDebugUnitTest --tests '*AuthRepositoryContractTest'`

  Expected: PASS. If Firebase Emulator configuration exists, also verify both seed accounts resolve to their intended roles.

- [ ] **Step 6: Commit**

  Commit: `feat: add firebase authentication and demo roles`

### Task 3: Build role-based navigation shell

**Files:**
- Create: `app/src/main/java/<package>/MainActivity.java`
- Create: `app/src/main/java/<package>/navigation/RootViewModel.java`
- Create: `app/src/main/java/<package>/ui/auth/LoginFragment.java`
- Create: `app/src/main/java/<package>/ui/customer/CustomerHomeFragment.java`
- Create: `app/src/main/java/<package>/ui/officer/OfficerHomeFragment.java`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/layout/fragment_login.xml`
- Create: `app/src/main/res/layout/fragment_customer_home.xml`
- Create: `app/src/main/res/layout/fragment_officer_home.xml`
- Create: `app/src/main/res/navigation/nav_graph.xml`
- Create: `app/src/test/java/<package>/navigation/RoleNavigationTest.java`

**Interfaces:**
- `RootViewModel.routeFor(AuthState): RootRoute`
- `RootRoute.LOGIN`, `RootRoute.CUSTOMER_HOME`, `RootRoute.OFFICER_HOME`
- Navigation destinations must be named `login`, `customerHome`, and `officerHome`.

- [ ] **Step 1: Write failing route tests**

  Assert that signed-out users route to login, Customer routes only to customer home, Officer routes only to officer home, and unknown/inactive users route to login with an error state.

- [ ] **Step 2: Run the tests to verify failure**

  Run: `./gradlew testDebugUnitTest --tests '*RoleNavigationTest'`

  Expected: FAIL because the route resolver and destinations are not implemented.

- [ ] **Step 3: Implement `RootViewModel.routeFor`**

  Make routing a pure function of `AuthState` so it can be tested without Android UI. Do not route based on email text or hidden UI flags.

- [ ] **Step 4: Implement the single-activity navigation shell**

  Add login, Customer home, and Officer home screens with visibly different role labels and a sign-out action. Guard destinations in code and navigation arguments so a deep link cannot cross roles.

- [ ] **Step 5: Run tests and build the APK**

  Run: `./gradlew testDebugUnitTest assembleDebug`

  Expected: PASS and a debug APK is produced.

- [ ] **Step 6: Commit**

  Commit: `feat: add seeded role based navigation shell`

### Task 4: Add Firestore read repositories and trusted seed/reset support

**Files:**
- Create: `app/src/main/java/<package>/data/FirestoreCollections.java`
- Create: `app/src/main/java/<package>/data/FirestoreProfileRepository.java`
- Create: `app/src/main/java/<package>/data/FirestoreReadRepository.java`
- Create: `app/src/main/java/<package>/data/ConnectivityGate.java`
- Create: `app/src/main/java/<package>/data/ReadModelResult.java`
- Create: `app/src/test/java/<package>/data/FirestoreReadRepositoryTest.java`
- Create: `scripts/seed-demo-data.md`

**Interfaces:**
- `FirestoreReadRepository.observeAccounts(uid: String): LiveData<ReadModelResult<List<AccountSummary>>>`
- `FirestoreReadRepository.observeTransactions(uid: String): LiveData<ReadModelResult<List<TransactionSummary>>>`
- `ConnectivityGate.requireOnlineForMutation(): Result<Void>`
- Seed/reset remains external trusted tooling and must not be callable from Android UI.
- `FirestoreCollections` constants for `users`, `customers`, `officers`, `accounts`, `transactions`, `auditLogs`, and the remaining template collections.

- [ ] **Step 1: Write failing seed/reset tests**

  Test permitted account/history reads, cached-read metadata, rejection of unauthorized profile access, idempotent external seed creation, namespace-scoped reset, and rejection of mutations when offline.

- [ ] **Step 2: Run the tests to verify failure**

  Run: `./gradlew testDebugUnitTest --tests '*FirestoreReadRepositoryTest'`

  Expected: FAIL because the read repository and its result states are not implemented.

- [ ] **Step 3: Define collection constants and demo record keys**

  Preserve template collection names. Use stable IDs only in trusted seed tooling so reset is scoped and repeatable; Android repositories must not assume demo IDs.

- [ ] **Step 4: Implement Firestore read repositories**

  Read only documents allowed by Firestore rules, expose loading/success/empty/error/cache metadata, and never treat cached role data as sufficient authorization at startup.

- [ ] **Step 5: Add read-only offline behavior**

  Keep UID-based seed/reset in `scripts/seed-demo-data.mjs`; require external Admin credentials and a named demo namespace. Do not add seed/reset controls to customer or officer screens.

- [ ] **Step 6: Run tests and emulator verification**

  Run: `./gradlew testDebugUnitTest --tests '*FirestoreReadRepositoryTest'`

  Expected: PASS. Run rules/read verification against the Firebase Console project or Emulator Suite when available.

- [ ] **Step 7: Commit**

  Commit: `feat: add firestore demo seed and reset support`

### Task 5: Introduce production-shaped domain contracts

**Files:**
- Create: `app/src/main/java/<package>/domain/AccountService.java`
- Create: `app/src/main/java/<package>/domain/TransferService.java`
- Create: `app/src/main/java/<package>/domain/EkycService.java`
- Create: `app/src/main/java/<package>/domain/PaymentService.java`
- Create: `app/src/main/java/<package>/domain/BranchService.java`
- Create: `app/src/main/java/<package>/domain/ChatbotService.java`
- Create: `app/src/main/java/<package>/domain/OperationState.java`
- Create: `app/src/main/java/<package>/domain/MutationUnavailableException.java`
- Create: `app/src/test/java/<package>/domain/MutationContractTest.java`

**Interfaces:**
- `TransferService.createTransferIntent(TransferRequest): Task<TransactionIntent>`
- `TransferService.verifyOtp(OtpVerification): Task<AuthorizationResult>`
- `TransferService.commitTransfer(TransactionIntent, AuthorizationResult): Task<TransactionReceipt>`
- `PaymentService.createPayment(PaymentRequest): Task<PaymentRecord>`
- `EkycService.submit(EkycSubmission): Task<EkycRequest>`
- `BranchService.findNearest(Origin): Task<BranchRecommendation>`
- `ChatbotService.reply(ChatMessage): Task<ChatReply>`

- [ ] **Step 1: Write failing domain tests**

  Test positive amount, owner/currency validation, idempotency keys, transaction-bound one-use OTP semantics, explicit pending/failed/setup-required states, and the rule that Android cannot claim authoritative ledger/audit/settlement success.

- [ ] **Step 2: Run the tests to verify failure**

  Run: `./gradlew testDebugUnitTest --tests '*MutationContractTest'`

  Expected: FAIL because the production-shaped contracts and operation states are not implemented.

- [ ] **Step 3: Define domain request/result models and interfaces**

  Keep money in minor units, preserve the template status names, and use stable IDs/request IDs. Keep providers out of UI contracts.

- [ ] **Step 4: Implement provider-neutral contract adapters**

  Implement validation and unavailable/pending states. Do not write balances, ledger entries, audit records, role/status, or external settlement from Android. Backend/provider adapters are added only in later phases.

- [ ] **Step 5: Wire services through `AppContainer`**

  Screens and ViewModels receive interfaces only. The later Blaze migration will replace implementations rather than screen calls.

- [ ] **Step 6: Run tests**

  Run: `./gradlew testDebugUnitTest --tests '*MutationContractTest'`

  Expected: PASS.

- [ ] **Step 7: Commit**

  Commit: `feat: add production shaped domain contracts`

### Task 6: Add phase-1 read UI and verification documentation

**Files:**
- Modify: `app/src/main/java/<package>/ui/customer/CustomerHomeFragment.java`
- Modify: `app/src/main/java/<package>/ui/officer/OfficerHomeFragment.java`
- Create: `app/src/main/java/<package>/ui/customer/AccountListFragment.java`
- Create: `app/src/main/java/<package>/ui/customer/TransactionHistoryFragment.java`
- Create: `docs/verification/phase-1-read-foundation.md`
- Create: `app/src/androidTest/java/<package>/BaseFlowInstrumentedTest.java`

**Interfaces:**
- Read screens expose loading, empty, stale-cache, offline-read-only, permission-denied, and retry states.

- [ ] **Step 1: Write the failing instrumented flow test**

  Test launch → login → Customer home → permitted account/history reads, then sign out → Officer login → Officer home with role-specific read access.

- [ ] **Step 2: Run the test to verify the environment is ready**

  Run: `./gradlew connectedDebugAndroidTest`

  Expected before implementation: FAIL at missing screens or IDs; if no device/emulator is available, record the environment limitation rather than skipping the test silently.

- [ ] **Step 3: Implement phase-1 read states**

  Show cached data as read-only, require retry for transient errors, and show setup-required when the provider/backend is unavailable. Do not display fabricated balances or transaction success.

- [ ] **Step 4: Run all verification**

  Run: `./gradlew testDebugUnitTest assembleDebug connectedDebugAndroidTest`

  Expected: unit tests pass, APK assembles, and instrumented tests pass on an available emulator/device.

- [ ] **Step 5: Commit**

  Commit: `test: verify mobile banking base demo flow`

## Self-Review Coverage

The plan covers the approved incremental direction: Android shell, Auth, two roles, Firestore read boundary, trusted external seed tooling, production-shaped mutation contracts, explicit unavailable states, and a phase-1 read flow. Transfer/OTP, eKYC/S3, Stripe payments, Maps/Routes, chatbot backend, and officer mutations remain separate gated phases; they must not be represented as completed by local Android-only writes.

