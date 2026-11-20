# Mobile Banking Spark Demo Design

## Intent

Build one Android APK that demonstrates every use case in the project template and the course brief. The demo must support two seeded roles, Customer and Bank Officer, while maximizing Firebase Spark usage and preserving the same boundaries, data model, states, and security concepts needed for a later production backend.

## Goals

- Cover the template's customer and officer workflows in one APK.
- Use Android Java/XML with MVVM-style separation, Navigation, ViewModel, Repository, and service interfaces.
- Use Firebase Authentication and Cloud Firestore on the Spark plan where available, with AWS S3/Lambda added for real eKYC file transfer.
- Keep transaction, payment, eKYC, authorization, audit, and reconciliation concepts aligned with the template.
- Make simulated behavior visible and explicitly labeled as demo behavior.
- Allow later replacement of demo services with Cloud Functions, Stripe webhooks, and production storage without rewriting screens.

## Non Goals

- Production-grade financial security during the Spark-only phase.
- Real interbank settlement, real biometric liveness, or regulatory eKYC approval.
- Claiming that client-side role checks protect a deployed banking system.
- Deploying Cloud Functions or using Firebase Storage in the Spark phase; sensitive eKYC media is stored in a private S3 bucket through presigned URLs.

## Anti Goals

- Do not let a client edit its own role or directly mutate authoritative balances without going through the service boundary.
- Do not show a simulated payment, facial check, or transfer as a real external settlement without a demo label.
- Do not embed Stripe secret keys, OpenAI keys, or other provider secrets in the APK.

## Constraints

- The course brief requires an Android app, two user types, security flows, three account types, transactions, utilities, navigation, chatbot, cloud data, report, and demo video.
- The supplied template specifies Java/XML, Firebase Authentication, Firestore, Storage, Cloud Functions, VNPay-style payment states, Maps/Routes, OpenAI action allowlisting, audit logs, and explicit transaction invariants.
- The Spark plan can support no-cost Firebase products and Firestore within quota, but Cloud Functions cannot be deployed and Firebase Storage is not available for Spark projects. AWS S3 and Lambda are an external storage/signing stack. Local Emulator Suite can be used for development and function tests.
- Stripe replaces VNPay as the payment provider abstraction. Stripe test/mock behavior is used in the Spark demo; provider secrets and webhook settlement remain a later backend concern.

## Approaches

### Firebase-first production backend

Android calls Cloud Functions for financial operations, eKYC storage, Stripe intents/webhooks, and role enforcement. This matches the template most closely but requires Blaze and is deferred.

### Spark-compatible demo with replaceable services

Android keeps the production-shaped service interfaces, but local implementations execute demo workflows. Firestore stores non-file demo data, Firebase Auth handles sign-in, and local/emulator providers represent unavailable backend or storage capabilities. This is the selected approach because it maximizes free Spark usage while preserving the template's architecture.

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
        -> Local demo services or future Firebase callable services
        -> Firestore / Auth / provider adapters
```

Interfaces such as `TransferService`, `PaymentService`, `EkycService`, `AccountService`, `BranchService`, and `ChatbotService` are provider-neutral. The Spark phase supplies local demo implementations; a later phase can supply Cloud Function implementations without changing screen contracts.

## Functional Scope

### Authentication and roles

Firebase Email/Password Authentication signs in seeded accounts. A trusted seed profile maps each UID to `CUSTOMER` or `OFFICER`. The app hides and guards destinations by role. The report must state that client-side role enforcement is demonstrational until claims and server rules are deployed.

### Customer workflows

- Profile and preferences.
- eKYC consent, capture/upload state, review status, rejection, and resubmission.
- Checking, savings, and mortgage account details.
- Balance, savings rate/profit estimate, mortgage payment schedule, and transaction history.
- Deposit and withdrawal demo flows.
- Internal transfer and simulated interbank transfer with intent, OTP, status, receipt, and idempotency key.
- Electricity/water bill, mobile top-up, and service-order flows for flight, movie, hotel, and e-commerce seeded catalogs.
- Branch map/list with manual origin fallback and demo walking-route recommendation.
- Chatbot responses with allowlisted action IDs only.

### Officer workflows

- Create and manage customer demo profiles.
- Review eKYC requests and set review outcomes.
- Modify versioned savings-rate policy in demo data.
- View relevant transaction/payment/order records and audit events.

## Data Model

Preserve the template's collection names and important fields: `users`, `customers`, `officers`, `accounts`, `checkingDetails`, `savingsDetails`, `mortgageDetails`, `transactions`, `transactionIntents`, `ledgerEntries`, `otpChallenges`, `ekycRequests`, `bills`, `payments`, `orders`, `ratePolicies`, `bankBranches`, `notifications`, `chatHistory`, and `auditLogs`.

Demo writes must still follow the template invariants: positive amount, matching currency, owner check, sufficient balance, idempotent request ID, balanced internal ledger effects, transaction-bound one-use OTP semantics, and audit records. Local services validate these rules before writing Firestore, but the report must clearly state that client-side enforcement is demonstrational until Cloud Functions and server rules are deployed.

## Provider Strategy

- Auth: Firebase Authentication.
- Structured data: Cloud Firestore with seeded data and conservative reads/writes.
- Local persistence: Room or a small local cache for demo resilience; authoritative demo state remains Firestore when connected. Offline access is read-only; all mutations require an online connection.
- File provider: local app-private files are temporary capture/cache only; the target storage is a private AWS S3 bucket accessed through backend-issued presigned URLs. Firestore stores only metadata and object references.
- Payment provider: deterministic `StripePaymentAdapter` mock in the base phase. Payment state transitions are stored in `payments`; settlement is never inferred solely from an Android return callback.
- Maps: Google Maps SDK if a usable key is available; otherwise branch list plus seeded route visualization. Routes are labeled demo when not calculated by the supported Routes API.
- Chatbot: deterministic demo intent parser with the same allowlisted action IDs as the template; future implementation can call OpenAI only through a server-side function.
- Notifications: Firebase Cloud Messaging where configured; in-app notifications remain available as Firestore records.

## Demo Acceptance Flow

The video/demo should show:

1. Customer sign-in and dashboard.
2. Account details and history for all three account types.
3. Transfer creation, OTP verification, receipt, and duplicate-request prevention.
4. Bill payment or mobile top-up with Stripe test/mock status.
5. eKYC review state and officer decision.
6. Officer rate-policy update and audit event.
7. Branch recommendation with permission-denied/manual-origin fallback.
8. Chatbot question producing a safe action link.
9. Re-login as Officer in the same APK and show different navigation and permissions.

## Migration to Blaze

When the project is upgraded, replace local implementations with Cloud Function/Lambda-backed implementations, keep eKYC media in private S3 through presigned URLs (or explicitly migrate to restricted Firebase Storage later), enforce roles with claims and server rules, move Stripe secret/webhook handling to the backend, and use authoritative provider callbacks for settlement. The Firestore collections, service interfaces, UI state names, and report diagrams should remain stable.

## Decisions Closed for Base Phase

- Offline mode is read-only; transfer, OTP, payment, eKYC review, officer policy updates, and other mutations require online access.
- The demo does not add the Stripe Android SDK in the base phase; the mock adapter exposes the template's payment states and can be replaced later.
- OTP is represented as a one-use, transaction-bound challenge with expiry and attempt count; the demo may use a visible deterministic code for repeatable video demonstrations.
- A reset-demo operation restores seeded Firestore documents and local demo files so the video flow can be repeated.

## Open Builder Decisions

- Exact Android package name and minimum SDK.
- Final dependency versions compatible with the starter repository.
- Whether Maps API access is available for the demo device.
- Whether Spark demo data should support offline mutation or only cached read access.
- Exact seeded customer/officer profile values.

