# Mobile Banking Base Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first runnable Android APK foundation for the template-aligned mobile banking demo, with Firebase Authentication, two seeded roles, role-based navigation, Firestore-backed demo state, and replaceable service boundaries.

**Architecture:** Use a single-activity Java/XML app with Fragments and Navigation Component. ViewModels call repositories, repositories call domain services, and domain services use Spark-compatible implementations now while preserving interfaces for later Cloud Functions, S3/Firebase Storage, Stripe, Maps, and OpenAI adapters.

**Tech Stack:** Android Studio, Java, XML, AndroidX, Material Components, Navigation Component, ViewModel, LiveData or StateFlow-equivalent Java observable state, Firebase Authentication, Cloud Firestore, Room/cache only where needed, JUnit, AndroidX Test.

**Spec:** `docs/superpowers/specs/2026-11-20-mobile-banking-spark-demo-design.md`

## Global Constraints

- One APK must contain both Customer and Bank Officer flows.
- Seed accounts are `customer.demo@example.com` and `officer.demo@example.com`.
- Offline mode is read-only; transfer, OTP, payment, eKYC review, officer policy updates, and other mutations require online access.
- The base phase uses Firestore/Auth where available, local app-private storage only as temporary capture/cache, a private AWS S3 target accessed through backend-issued presigned URLs, local demo services instead of deployed Cloud Functions, and deterministic mock Stripe behavior.
- Client-side role and transaction checks are demonstrational until server-side claims, rules, and Cloud Functions are deployed.
- Preserve the template collection names and domain terminology.

## Review Focus

- Missing or stale Firebase configuration must show a recoverable setup error instead of crashing; test in the configuration validator task.
- A signed-in user with an unknown, inactive, or malformed role must be denied protected navigation; test in the role-routing task.
- Firestore read failure/offline state must expose cached/read-only state and must not allow mutation; test in the repository/service task.
- Repeated reset-seed requests must be idempotent and must not duplicate demo records; test in the seed task.
- Customer and Officer must not reach each other’s destinations through back navigation or deep links; test in the navigation task.

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

  Define one composition root that owns Firebase Auth, Firestore, repositories, and demo service implementations; later tasks must receive dependencies from this container rather than constructing Firebase clients inside screens.

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

  Keep role names aligned with the template. Treat Firestore profile role as demo authority and document that production will use custom claims/server checks.

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

### Task 4: Add Firestore demo repositories and seed/reset support

**Files:**
- Create: `app/src/main/java/<package>/data/FirestoreCollections.java`
- Create: `app/src/main/java/<package>/data/DemoSeedRepository.java`
- Create: `app/src/main/java/<package>/data/FirestoreDemoRepository.java`
- Create: `app/src/main/java/<package>/data/ConnectivityGate.java`
- Create: `app/src/main/java/<package>/data/DemoResetResult.java`
- Create: `app/src/test/java/<package>/data/DemoSeedRepositoryTest.java`
- Create: `scripts/seed-demo-data.md`

**Interfaces:**
- `DemoSeedRepository.seedIfMissing(): Result<SeedSummary>`
- `DemoSeedRepository.resetDemo(): Result<SeedSummary>`
- `ConnectivityGate.requireOnlineForMutation(): Result<Void>`
- `FirestoreCollections` constants for `users`, `customers`, `officers`, `accounts`, `transactions`, `auditLogs`, and the remaining template collections.

- [ ] **Step 1: Write failing seed/reset tests**

  Test idempotent seed creation, reset replacement of known demo records, preservation of non-demo records, and rejection of mutations when offline.

- [ ] **Step 2: Run the tests to verify failure**

  Run: `./gradlew testDebugUnitTest --tests '*DemoSeedRepositoryTest'`

  Expected: FAIL because seed/reset and connectivity gate are not implemented.

- [ ] **Step 3: Define collection constants and demo record keys**

  Use stable IDs such as `demo_customer_user`, `demo_officer_user`, and prefixed account/branch/transaction IDs so reset is scoped and repeatable.

- [ ] **Step 4: Implement idempotent seed/reset**

  Seed both users, role profiles, representative checking/savings/mortgage accounts, branches, bills, and catalog records required by the template. Reset only records carrying the demo marker.

- [ ] **Step 5: Add read-only offline behavior**

  Allow Firestore cached reads where available, but make every mutation path call `ConnectivityGate` first and return a user-visible online-required state.

- [ ] **Step 6: Run tests and emulator verification**

  Run: `./gradlew testDebugUnitTest --tests '*DemoSeedRepositoryTest'`

  Expected: PASS. Run the documented seed/reset procedure against the Firebase Emulator Suite if available.

- [ ] **Step 7: Commit**

  Commit: `feat: add firestore demo seed and reset support`

### Task 5: Introduce replaceable domain service interfaces

**Files:**
- Create: `app/src/main/java/<package>/domain/AccountService.java`
- Create: `app/src/main/java/<package>/domain/TransferService.java`
- Create: `app/src/main/java/<package>/domain/EkycService.java`
- Create: `app/src/main/java/<package>/domain/PaymentService.java`
- Create: `app/src/main/java/<package>/domain/BranchService.java`
- Create: `app/src/main/java/<package>/domain/ChatbotService.java`
- Create: `app/src/main/java/<package>/domain/LocalAccountService.java`
- Create: `app/src/main/java/<package>/domain/LocalTransferService.java`
- Create: `app/src/main/java/<package>/domain/LocalEkycService.java`
- Create: `app/src/main/java/<package>/domain/LocalPaymentService.java`
- Create: `app/src/main/java/<package>/domain/LocalBranchService.java`
- Create: `app/src/main/java/<package>/domain/LocalChatbotService.java`
- Create: `app/src/test/java/<package>/domain/LocalTransferServiceTest.java`

**Interfaces:**
- `TransferService.createTransferIntent(TransferRequest): Result<TransactionIntent>`
- `TransferService.verifyOtp(OtpVerification): Result<AuthorizationResult>`
- `TransferService.commitTransfer(TransactionIntent, AuthorizationResult): Result<TransactionReceipt>`
- `PaymentService.createPayment(PaymentRequest): Result<PaymentRecord>`
- `EkycService.submit(EkycSubmission): Result<EkycRequest>`
- `BranchService.findNearest(Origin): Result<BranchRecommendation>`
- `ChatbotService.reply(ChatMessage): Result<ChatReply>`

- [ ] **Step 1: Write failing domain tests**

  Test transfer validation for positive amount, owner, sufficient balance, transaction-bound OTP, one-use OTP, duplicate request ID, balanced ledger records, and audit record creation. Test that mock payment, eKYC, branch, and chatbot results use template states/action IDs.

- [ ] **Step 2: Run the tests to verify failure**

  Run: `./gradlew testDebugUnitTest --tests '*LocalTransferServiceTest'`

  Expected: FAIL because the domain models and services are not implemented.

- [ ] **Step 3: Define domain request/result models and interfaces**

  Keep money in minor units, preserve the template status names, and use stable IDs/request IDs. Keep providers out of UI contracts.

- [ ] **Step 4: Implement local services**

  Local services validate the template rules, write Firestore demo state when online, and expose explicit `DEMO_SIMULATED` metadata. They must not claim production authorization or external settlement.

- [ ] **Step 5: Wire services through `AppContainer`**

  Screens and ViewModels receive interfaces only. The later Blaze migration will replace implementations rather than screen calls.

- [ ] **Step 6: Run tests**

  Run: `./gradlew testDebugUnitTest --tests '*LocalTransferServiceTest'`

  Expected: PASS.

- [ ] **Step 7: Commit**

  Commit: `feat: add replaceable demo domain services`

### Task 6: Add base demo controls and verification documentation

**Files:**
- Modify: `app/src/main/java/<package>/ui/customer/CustomerHomeFragment.java`
- Modify: `app/src/main/java/<package>/ui/officer/OfficerHomeFragment.java`
- Create: `app/src/main/java/<package>/ui/demo/DemoControlsFragment.java`
- Create: `app/src/main/res/layout/fragment_demo_controls.xml`
- Create: `docs/demo/base-demo-checklist.md`
- Create: `app/src/androidTest/java/<package>/BaseFlowInstrumentedTest.java`

**Interfaces:**
- Demo controls expose `Seed demo`, `Reset demo`, current role, connectivity state, and an explicit demo-mode notice.

- [ ] **Step 1: Write the failing instrumented flow test**

  Test launch → login → Customer home, sign out → Officer login → Officer home, role-specific screen visibility, and reset-demo confirmation.

- [ ] **Step 2: Run the test to verify the environment is ready**

  Run: `./gradlew connectedDebugAndroidTest`

  Expected before implementation: FAIL at missing screens or IDs; if no device/emulator is available, record the environment limitation rather than skipping the test silently.

- [ ] **Step 3: Implement demo controls and notices**

  Keep reset destructive only within the demo-marked dataset. Show that Spark mode is a classroom demo and that production server enforcement is not active.

- [ ] **Step 4: Run all verification**

  Run: `./gradlew testDebugUnitTest assembleDebug connectedDebugAndroidTest`

  Expected: unit tests pass, APK assembles, and instrumented tests pass on an available emulator/device.

- [ ] **Step 5: Commit**

  Commit: `test: verify mobile banking base demo flow`

## Self-Review Coverage

The plan covers the approved base scope: Android shell, Auth, two seeded roles, Firestore data boundary, Spark-compatible offline behavior, demo seed/reset, replaceable service interfaces, and a repeatable base demo flow. Full account, transfer, eKYC, utility, maps, chatbot, and payment screens remain follow-on implementation tasks that consume the interfaces created here; they are intentionally not hidden inside the base plan.

