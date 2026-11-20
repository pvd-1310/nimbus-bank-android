# Mobile Banking Incremental Production Design

## Intent

Build one Android APK that can grow through UC-01 to UC-12 without replacing its UI and domain boundaries. The first implementation uses Firebase Spark-compatible services, but each feature is added in a separately testable slice and financial mutations are not presented as successful until an authoritative backend/provider confirms them.

## Goals

- Cover the template's customer and officer workflows in one APK.
- Use Android Java/XML with MVVM-style separation, Navigation, ViewModel, Repository, and service interfaces.
- Use Firebase Authentication and Cloud Firestore on the Spark plan where available, with AWS S3/Lambda added for real eKYC file transfer.
- Keep transaction, payment, eKYC, authorization, audit, and reconciliation concepts aligned with the template.
- Make unavailable provider capabilities visible as setup-required or not-yet-available states.
- Allow each provider implementation to be added behind stable repositories and service contracts without rewriting screens.

## Non Goals

- Deploying paid backend infrastructure during the Spark-only phase.
- Real interbank settlement, real biometric liveness, or regulatory eKYC approval.
- Claiming that client-side role checks protect a deployed banking system.
- Deploying Cloud Functions, S3, Stripe, Maps, or AI providers before their credentials and backend runtime are available.

## Anti Goals

- Do not let a client edit its own role or directly mutate authoritative balances without going through the service boundary.
- Do not show a pending, simulated, or client-only operation as a completed transfer, payment, eKYC approval, or external settlement.
- Do not embed Stripe secret keys, OpenAI keys, or other provider secrets in the APK.

## Constraints

- The course brief requires an Android app, two user types, security flows, three account types, transactions, utilities, navigation, chatbot, cloud data, report, and demo video.
- The supplied template specifies Java/XML, Firebase Authentication, Firestore, Storage, Cloud Functions, VNPay-style payment states, Maps/Routes, OpenAI action allowlisting, audit logs, and explicit transaction invariants.
- The Spark plan can support no-cost Firebase products and Firestore within quota, but Cloud Functions cannot be deployed and Firebase Storage is not available for Spark projects. AWS S3 and Lambda are an external storage/signing stack. Local Emulator Suite can be used for development and function tests.
- Stripe replaces VNPay as the payment provider abstraction. Stripe test mode is enabled only through a backend adapter; provider secrets and webhook settlement remain outside the Android client.

## Approaches

### Firebase-first production backend

Android calls Cloud Functions for financial operations, eKYC storage, Stripe intents/webhooks, and role enforcement. This matches the template most closely but requires Blaze and is deferred.

### Incremental production-shaped implementation

Android first implements the complete read model, UI states, repositories, validation, and provider-neutral contracts. Each real mutation is then connected to a backend/provider in a later phase. Firebase Auth/Firestore remain usable immediately, while unavailable capabilities return explicit setup-required or pending states. This is the selected approach because it makes progress in controlled checkpoints without locking financial behavior into Android-only demo code.

### Separate customer and officer applications

Two APKs would reduce cross-role navigation but duplicate code and make the course demonstration harder. This approach is rejected.

## Selected Architecture

One APK contains two seeded accounts and role-based navigation:

- `customer.demo@example.com`: customer dashboard and customer workflows.
- `officer.demo@example.com`: officer dashboard and officer workflows.

The app uses these layers:

```text
XML Screens / Fragments
        -> ViewModels
        -> Repositories
        -> Domain services
        -> Backend/provider adapter or explicit unavailable state
        -> Firestore / Auth / provider adapters
```

Interfaces such as `TransferService`, `PaymentService`, `EkycService`, `AccountService`, `BranchService`, and `ChatbotService` are provider-neutral. The Android app must not write authoritative balances, ledger entries, audit events, role/status fields, or provider settlement results directly. A phase may ship read-only UI and validation before its backend mutation adapter is available.

## Incremental Delivery Gates

1. **Foundation gate:** Auth, profile loading, role routing, protected back stack, design system, rules, and configuration states pass unit/instrumented checks.
2. **Read-model gate:** Customer and Officer can load permitted accounts, histories, requests, and audit views; offline behavior is cached read-only.
3. **Mutation-contract gate:** Transfer, OTP, eKYC, payment, and officer operations have DTOs, validation, idempotency/status models, and failure states, but no fake success.
4. **Provider gate:** A backend/provider is connected one capability at a time: callable backend for financial operations, SMS OTP, S3 presigned upload, Stripe, Maps/Routes, and chatbot backend.
5. **Release gate:** Rules tests, backend contract tests, Android UI tests, accessibility checks, screenshot comparison, and security review pass for every enabled capability.

## Functional Scope

### Authentication and roles

Firebase Email/Password Authentication signs in seeded accounts. A trusted seed profile maps each UID to `CUSTOMER` or `OFFICER`. The app hides and guards destinations by role. The report must state that client-side role enforcement is not authoritative until claims and server rules are deployed.

### Customer workflows

- Profile and preferences.
- eKYC consent, capture/upload state, review status, rejection, and resubmission.
- Checking, savings, and mortgage account details.
- Balance, savings rate/profit estimate, mortgage payment schedule, and transaction history.
- Deposit and withdrawal request flows; authoritative balance mutation requires the backend phase.
- Internal transfer and interbank transfer with intent, OTP, status, receipt, and idempotency key; completion requires the backend phase.
- Electricity/water bill, mobile top-up, and service-order flows for flight, movie, hotel, and e-commerce seeded catalogs.
- Branch map/list with manual origin fallback and a route recommendation only when a supported Routes provider is enabled.
- Chatbot responses with allowlisted action IDs only.

### Officer workflows

- Create and manage customer profiles through the officer/backend authorization path.
- Review eKYC requests and set review outcomes.
- Modify versioned savings-rate policy through an authorized officer/backend path.
- View relevant transaction/payment/order records and audit events.

## Data Model

Preserve the template's collection names and important fields: `users`, `customers`, `officers`, `accounts`, `checkingDetails`, `savingsDetails`, `mortgageDetails`, `transactions`, `transactionIntents`, `ledgerEntries`, `otpChallenges`, `ekycRequests`, `bills`, `payments`, `orders`, `ratePolicies`, `bankBranches`, `notifications`, `chatHistory`, and `auditLogs`.

All mutation paths must follow the template invariants: positive amount, matching currency, owner check, sufficient balance, idempotent request ID, balanced internal ledger effects, transaction-bound one-use OTP semantics, and audit records. Android may validate input for UX, but authoritative enforcement and writes belong to the backend/rules phase.

## Provider Strategy

- Auth: Firebase Authentication.
- Structured data: Cloud Firestore with seeded data and conservative reads/writes.
- Local persistence: Room or a small local cache for resilience; authoritative state remains Firestore/backend when connected. Offline access is read-only; all mutations require an online connection and authoritative service.
- File provider: local app-private files are temporary capture/cache only; the target storage is a private AWS S3 bucket accessed through backend-issued presigned URLs. Firestore stores only metadata and object references.
- Payment provider: Stripe is enabled only through a backend-backed adapter. Payment state transitions are stored in `payments`; settlement is never inferred solely from an Android return callback.
- Maps: Google Maps SDK if a usable key is available; otherwise branch list plus manual-origin state. Routes are available only when calculated by the supported Routes API.
- Chatbot: server-side allowlisted action IDs; until enabled, show an unavailable state rather than a fabricated response.
- Notifications: Firebase Cloud Messaging where configured; in-app notifications remain available as Firestore records.

## Acceptance Flow

The acceptance run should show only capabilities that have passed their phase gate:

1. Customer sign-in and dashboard.
2. Account details and history for all three account types.
3. Transfer creation, OTP verification, receipt, and duplicate-request prevention only after the backend/provider phase is enabled; otherwise show the blocked/setup-required state.
4. Bill payment or mobile top-up with Stripe test status only after webhook-backed settlement is enabled.
5. eKYC review state and officer decision.
6. Officer rate-policy update and audit event.
7. Branch recommendation with permission-denied/manual-origin fallback.
8. Chatbot question producing a safe action link.
9. Re-login as Officer in the same APK and show different navigation and permissions.

## Provider Enablement

When a backend/provider becomes available, replace the unavailable adapter with the callable/backend implementation, keep eKYC media in private S3 through presigned URLs, enforce roles with claims and server rules, move Stripe secret/webhook handling to the backend, and use authoritative callbacks for settlement. The Firestore collections, service interfaces, UI state names, and report diagrams should remain stable.

## Decisions Closed for Base Phase

- Offline mode is read-only; transfer, OTP, payment, eKYC review, officer policy updates, and other mutations require online access.
- The base phase does not add the Stripe Android SDK; payment screens expose pending/setup-required states until a backend-backed Stripe adapter is enabled.
- OTP is represented as a one-use, transaction-bound challenge with expiry and attempt count. Production OTP must be issued and verified by a backend using an SMS provider; no deterministic code is displayed in the production path.
- Any seed/reset utility is trusted tooling outside the APK and is scoped to a named namespace; it is not a banking feature and does not replace production provisioning.

## Open Builder Decisions

- Exact Android package name and minimum SDK.
- Final dependency versions compatible with the starter repository.
- Whether Maps API access is available for the demo device.
- Which backend runtime will host callable financial operations and OTP.
- Which SMS provider and AWS/Stripe credentials will be supplied for provider phases.
- Exact seeded customer/officer profile values.

