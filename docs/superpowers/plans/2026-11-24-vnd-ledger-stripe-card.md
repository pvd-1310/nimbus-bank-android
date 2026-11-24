# Firebase VND Ledger and Stripe Card Payments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Firestore VND the authoritative wallet ledger and Stripe test PaymentIntents the card collection path for top-ups, bills, and utility orders.

**Architecture:** Add purpose-aware Stripe payment records and webhook settlement for top-ups; use the existing Firestore internal transfer service for VND. Keep external bank transfers and withdrawals demo-only. Change Android screens to submit VND and display Firebase balances without Treasury cent conversion.

**Tech Stack:** TypeScript, Express, Firestore transaction abstraction, Stripe PaymentIntents/Webhooks, Android Java, Stripe PaymentSheet.

**Spec:** `docs/superpowers/specs/2026-11-24-vnd-ledger-stripe-card-design.md`

## Global Constraints

- Stripe must remain test mode (`livemode === false`).
- Only a verified Stripe webhook can settle card top-ups or card-paid bills/orders.
- Firebase is the only displayed wallet balance for VND.
- Interbank transfer is demo-only; do not claim real external settlement.
- Do not modify `.env`, service-account files, unrelated uncommitted work, or commit changes.
- No new tests or test runs unless requested by the user.

## Review Focus

- Duplicate or replayed success webhook: must not credit a top-up more than once.
- Mismatched Stripe amount/currency/payment purpose: must reject settlement without ledger mutation.
- Concurrent top-up/payment retries: must reuse idempotent intent/payment identities.
- Internal transfer with insufficient balance or changed policy: must not post either side of the transfer.
- Android retry/recovery after process death: must query backend status and never infer settlement from PaymentSheet alone.

---

### Task 1: Purpose-aware Stripe payment records and Firebase settlement

**Files:**
- Modify: `backend/src/modules/payments/payment.service.ts`
- Modify: `backend/src/modules/payments/payments.routes.ts`
- Modify: `backend/src/modules/payments/stripe.provider.ts`

**Interfaces:**
- Add dedicated `/topups/intents`, `/topups`, and `/topups/:id` endpoints.
- Top-up intent/payment records carry `purpose: TOP_UP`, owned `accountId`, VND amount, request ID, and authorization digest.
- Signed success webhook writes a balanced `TOP_UP` ledger entry, updates the owned checking account once, and stores the transaction record atomically.
- Existing bill payment stays as `purpose: BILL_PAYMENT`; catalog bill/order relation is settled and enqueued only once.

- [x] Implement purpose-specific prepare/create/recovery validation and idempotency using existing PaymentIntent gateway.
- [x] Set Stripe metadata sufficient to resolve the Nimbus payment record and purpose.
- [x] Extend webhook handling to settle top-ups and bills/orders with exact amount/currency/owner/purpose checks and duplicate-event protection.
- [x] Update payment status and transaction history for failure/cancellation without changing balances.

### Task 2: Route all VND wallet transfers through the Firebase ledger

**Files:**
- Modify: `backend/src/modules/transfers/transfers.routes.ts`
- Modify: `backend/src/modules/transfers/transfers.service.ts`
- Modify: `backend/src/modules/requests/requests.routes.ts`
- Modify: `backend/src/modules/requests/requests.service.ts` only for demo-only withdrawal/external semantics.
- Modify: `backend/src/modules/capabilities/capabilities.routes.ts` if status wording needs clarification.

**Interfaces:**
- VND internal `/transfers/intents` and commit continue using `TransferService` and update Firebase balance atomically.
- `/external-transfers` remains restricted to `DEMO_BANK` and records only demo clearing settlement.
- VND top-up no longer calls demo deposit/reconcile; it calls Task 1 top-up card endpoints.
- Withdrawal remains clearly demo-only and uses Firebase ledger only if its demo settlement succeeds.

- [x] Ensure request routing cannot send VND internal transfers through Stripe Treasury.
- [x] Remove or bypass linked Stripe sandbox restrictions for VND ledger transfers where account data is eligible for the Firebase VND ledger.
- [x] Preserve final commit-time balance, account, daily-limit, intent digest, OTP, and face checks.
- [x] Keep any remaining Treasury movement route isolated as legacy and prevent it from being presented as wallet balance.

### Task 3: Align Android money flows with Firebase VND and card PaymentSheet

**Files:**
- Modify: `app/src/main/java/com/example/final_term/ui/customer/TransferFragment.java`
- Modify: `app/src/main/java/com/example/final_term/data/ReadSummaryFormatter.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/CustomerHomeFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/AccountDetailFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/TransactionHistoryFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/UtilitiesHubDialogFragment.java`

**Interfaces:**
- Wallet transfer payloads use whole VND values and `currency: VND`.
- Top-up invokes Task 1 top-up API and presents returned Stripe PaymentIntent through PaymentSheet.
- PaymentSheet completion triggers backend status recovery; it does not update wallet or mark bill paid locally.
- Wallet/account screens read Firebase VND summaries and do not query Stripe Treasury balances.
- External transfer UI states that it is simulated; no USD sandbox conversion text remains in wallet flows.

- [x] Replace VND-to-USD-cent conversion and fixed-rate display in transfer and money formatting.
- [x] Wire top-up card flow into PaymentSheet with idempotent request recovery and backend status polling/retrieval.
- [x] Remove Treasury-balance reads from user-visible account and home totals; retain only ledger transactions.
- [x] Keep bill/order checkout using PaymentSheet and recover settlement from backend webhook state.
- [x] Ensure history titles and status details distinguish top-up, bill payment, utility order, demo withdrawal, and demo interbank transfer.

### Task 4: Keep utility fulfillment tied to verified card settlement

**Files:**
- Modify: `backend/src/modules/payments/payment.service.ts`
- Modify: `app/src/main/java/com/example/final_term/ui/utilities/UtilitiesHubDialogFragment.java`

**Interfaces:**
- Catalog orders continue to create a priced, expiring order/bill pair.
- A successful verified Stripe payment marks the bill paid and queues a single outbox job.
- Demo fulfillment is clearly represented as demo; failed/expired payments do not fulfill.

- [x] Confirm checkout binds payment amount/currency to the current unexpired order quote.
- [x] Ensure outbox fulfillment is idempotent and only follows a settled payment; webhook processing drains it after settlement.
- [x] Align Android checkout success/recovery with backend order and payment state.

### Task 5: Documentation and configuration guidance

**Files:**
- Modify: `backend/README.md`
- Modify: `backend/src/docs/openapi.ts`

- [x] Document Stripe test-card flow, webhook requirement, Firebase VND source of balance, and demo-only interbank/withdrawal behavior.
- [x] Document local Stripe CLI webhook forwarding without including credentials.
- [x] Update API descriptions so they match the actual implemented routes.

## Verification

- [x] Backend `npm run build` completed successfully.
- [x] Android `gradlew.bat :app:compileDebugJavaWithJavac --offline` completed successfully.
- [x] `git diff --check` completed with no whitespace errors.
- [x] No test suites were added or run.
