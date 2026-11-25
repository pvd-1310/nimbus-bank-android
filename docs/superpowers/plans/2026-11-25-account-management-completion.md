# Account Management Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Complete customer and officer account management flows for checking, savings, and mortgage accounts, including policy-driven savings interest, account history, and settled deposits/withdrawals.

**Architecture:** Keep Android as the presentation layer and the TypeScript backend as the authority for account ownership, rate policy selection, calculations, transaction settlement, and ledger updates. Keep account-specific read models behind authenticated account APIs; make policy versions immutable and apply the effective policy to savings estimates. Use the existing ledger/transaction patterns for movement settlement; translate internal environment and settlement states into plain customer-facing outcomes.

**Tech Stack:** Android Java, Android Navigation/ViewBinding, Firebase Auth/Firestore, TypeScript backend, Firestore store abstraction, Vitest.

**Spec:** Account management requirements supplied by the user in chat on 2026-11-25.

## Global Constraints

- Customers can read only accounts and transactions they own; backend authorization remains authoritative.
- Officers can change rate policies only with the explicit rate-update permission.
- Rate policy versions are immutable after creation; new rates are new versions with effective dates.
- Monetary calculations use integer minor units and deterministic rounding; never calculate authoritative balances in Android.
- UI labels must distinguish estimates/projections from posted values, while presenting successful test-mode transactions with the same simple customer flow and outcome as the completed product flow.
- Never show a deposit or withdrawal as completed until authoritative settlement has updated the account, ledger, and transaction record. In the test environment, test settlement must perform those writes and present the normal customer success experience.
- Customer screens must follow the existing Android customer visual language (gradient background, compact glass cards, navy primary actions, existing spacing/type scale, and Vietnamese copy); do not introduce a separate visual system.
- Customer-facing copy must hide implementation details such as policy IDs, basis points, API/backend/provider names, ledger terminology, simulation/test-mode labels, calculation formulas, and raw status codes. Translate the useful meaning into short plain Vietnamese labels and helper text. Keep environment/provider details in internal diagnostics and operator documentation only.
- When account or transaction information, a form, or a workflow no longer fits comfortably in the current screen, add a dedicated navigable screen. Do not put multi-field forms, full schedules, or detailed transaction/account content in popup dialogs; reserve dialogs for short confirmations or brief messages.

## Review Focus

- A policy scheduled for the future must not affect savings estimates until its effective time; test selection before, at, and after the effective instant.
- A policy for a different savings product must not change this account's rate; test product mapping and missing policy behavior.
- A retry or concurrent settlement must not post a movement twice; test idempotency and atomic account/ledger/transaction writes.
- A customer must not read or mutate another customer's account; test both account details and movement APIs.
- Missing, stale, and failed backend data must be visible as such in Android and must not be replaced by sample values; test loading, empty, and error states.

---

### Task 1: Define account-management contracts and financial rules

**Files:**
- Modify: `backend/src/modules/accounts/accounts.routes.ts`
- Modify: `backend/src/modules/accounts/accounts.service.ts`
- Modify: `backend/src/modules/accounts/calculations.ts`
- Test: `backend/test/banking.test.ts`
- Test: `backend/test/feature-flows.test.ts`
- Modify: `docs/functional-gaps.md`

**Interfaces:**
- Savings detail response must include account balance/principal, currency, annual rate, applicable policy ID/version, `effectiveFrom`, accrual period start/end, day count, calculated monthly profit, calculation convention, and `estimate: true`.
- Mortgage response must include outstanding balance, original principal, monthly and biweekly payment options, itemized schedules, calculation convention, and `projected: true`.
- Account details must be authorized by the existing `Banking.authorize` and `Banking.owned` checks.

- [x] **Step 1: Add failing backend tests for monthly savings estimates**

Test the defined monthly accrual behavior for 28-, 29-, 30-, and 31-day months; rounding at half-minor-unit boundaries; zero balance; and rate changes effective mid-month. Expected behavior: use the policy effective for each accrued day, calculate integer minor units deterministically, and return the convention/period used.

- [x] **Step 2: Run the focused backend tests and confirm the new cases fail**

Run: `npm --prefix backend test -- test/banking.test.ts`
Expected: new monthly policy-selection and accrual assertions fail before implementation.

- [x] **Step 3: Implement canonical savings monthly calculation**

In `calculations.ts`, add a function that takes principal, accrual window, and policy periods and returns integer `profitMinor` using the agreed day-count convention. Use the currently selected policy for the whole period only when no policy boundary occurs; split the period at effective-date boundaries when one does. Keep version identifiers in the result so the UI can explain the calculation.

- [x] **Step 4: Update savings API and tests**

Update `AccountService.savings` to return a calendar-month estimate, resolve all matching policies for the account's `productId`, ignore future policies until effective, and fail with a clear domain error if no valid policy exists. Add tests for policy boundaries, wrong products, missing policies, and account ownership.

- [x] **Step 5: Correct mortgage schedule basis and test it**

Use the current outstanding principal as the schedule's starting balance when present, while retaining original principal for display. Return both options on the same request so the UI can show a side-by-side monthly/biweekly comparison without implying either schedule has been paid. Add tests verifying both options start from the outstanding balance and have valid final residuals.

- [x] **Step 6: Run focused backend tests**

Run: `npm --prefix backend test -- test/banking.test.ts test/feature-flows.test.ts`
Expected: all account detail, rate selection, calculation, and ownership assertions pass.

### Task 2: Make officer rate-policy management enforceable and auditable

**Files:**
- Modify: `backend/src/modules/rates/rates.routes.ts`
- Modify: `backend/src/modules/rates/rates.service.ts`
- Modify: `backend/src/core/config.ts` (only if policy limits/product catalog are configured here)
- Modify: `app/src/main/java/com/example/final_term/ui/officer/OfficerFeatureFragment.java`
- Create: `app/src/main/java/com/example/final_term/ui/officer/RatePolicyEditorFragment.java` and `app/src/main/res/layout/fragment_rate_policy_editor.xml`
- Modify: `app/src/main/res/navigation/nav_graph.xml`
- Test: `backend/test/banking.test.ts`
- Test: `backend/test/http.test.ts`

**Interfaces:**
- `POST /officer/rate-policies` accepts `productId`, `annualRateBasisPoints`, `effectiveFrom`, and a required non-sensitive `reason`.
- `GET /officer/rate-policies` returns version, product, rate, effective date, creator, reason, and current/scheduled status.
- Only the `RATE_UPDATE` permission can list or create policies.

- [x] **Step 1: Add failing tests for policy permissions, product validity, and audit reason**

Cover customer denial, officer missing permission, unknown product, configured rate bounds, past/current effective time rejection, empty reason rejection, immutable version increments, and audit entries containing actor/product/rate/effective date/reason.

- [x] **Step 2: Run focused backend tests and confirm failures**

Run: `npm --prefix backend test -- test/banking.test.ts test/http.test.ts`
Expected: new validation and audit tests fail.

- [x] **Step 3: Implement policy validation and audit fields**

Validate `productId` against the savings product catalog used by account details. Enforce bank-approved limits from configuration (do not invent business limits in UI). Require a reason and retain policy versions as append-only records. Return enough policy status for officer review.

- [x] **Step 4: Update officer policy UI**

Replace the free-text product field with a product list supplied by the backend. Move policy creation/editing to a dedicated Officer screen with product, rate, effective date, and reason fields; do not use a popup for this multi-field workflow. Display active and scheduled policy versions distinctly and refresh the list after successful creation. Show server validation messages next to the relevant field instead of only a generic toast.

- [x] **Step 5: Run focused backend tests and review UI state handling**

Run: `npm --prefix backend test -- test/banking.test.ts test/http.test.ts`
Expected: all policy authorization, validation, effective-time, versioning, and audit tests pass; UI has loading, empty, error, and success states.

### Task 3: Complete customer account details and history presentation

**Files:**
- Modify: `app/src/main/java/com/example/final_term/ui/customer/AccountDetailFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java`
- Modify: `app/src/main/res/layout/fragment_account_detail.xml`
- Modify: `app/src/main/res/navigation/nav_graph.xml`
- Create: `app/src/main/java/com/example/final_term/ui/customer/TransactionDetailFragment.java` and its layout if transaction details exceed a short summary
- Create: a dedicated account information/schedule screen and layout only if the existing account detail screen becomes too dense
- Modify: `app/src/main/java/com/example/final_term/data/ReadSummaryFormatter.java`
- Test: `app/src/test/java/com/example/final_term/ui/customer/TransactionHistoryFilterTest.java` (create if absent)

**Interfaces:**
- Checking detail shows available/booked balance, account status, and recent history.
- Savings detail shows principal/balance, current annual rate, monthly profit estimate with its period, and recent history; policy identifiers and formula/convention strings stay in backend/admin diagnostics.
- Mortgage detail shows outstanding balance, original principal, monthly/biweekly payment choices, schedule, and a plain-language projected-payment label.
- Full history remains available from a single customer entry point and is filterable by account, date, type, and status.

- [x] **Step 1: Add failing formatter/filter tests**

Test transaction direction labels for deposits, withdrawals, incoming/outgoing transfers, failed movements, and completed test settlements. Test account/date/type/status filter combinations; pending rows must not appear as completed, while a successfully settled test transaction uses the normal customer success wording.

- [x] **Step 2: Run focused Android unit tests and confirm failures**

Run: `./gradlew --offline testDebugUnitTest`
Expected: new formatter/filter cases fail before implementation.

- [x] **Step 3: Add checking history to account detail and account-scoped history navigation**

Show the latest transactions for checking as already done for savings/mortgage. Add an account filter or account-specific entry into the full history screen while retaining pagination.

- [x] **Step 4: Extend the existing customer UI style to all account detail states**

Keep the current customer gradient background, glass-card treatment, navy actions, established corner radii, typography, and spacing from `fragment_account_detail.xml`, `fragment_account_list.xml`, and `fragment_customer_home.xml`. Use compact information hierarchy and avoid adding technical dashboards, debug panels, or dense tables. Verify that values remain readable at larger font sizes and on narrow screens.

- [x] **Step 5: Split dense account or transaction content into navigable screens**

Keep account overview focused on the essential balance/rate/payment summary. If the full mortgage schedule, savings terms, or transaction detail makes that screen crowded, navigate to a dedicated detail screen using the existing Navigation graph and pass only the account/transaction identifier; load authoritative details from the backend. Replace any long transaction-detail popup with a full screen. Do not use a popup to display a full schedule or multi-field financial details.

- [x] **Step 6: Align customer copy with system data without exposing implementation terms**

Display “Lãi suất hiện tại”, “Lãi dự kiến trong tháng”, and the estimate period in customer language; do not show policy ID/version, basis points, `ACT/365`, rounding convention, endpoint/API/backend/provider names, simulation/test-mode labels, or raw state codes. Display “Trả hàng tháng” and “Trả hai tuần một lần” options and amounts; describe the schedule as “Dự kiến theo dư nợ hiện tại”. Map processing, completed, and failed outcomes to clear Vietnamese messages. A test-mode transaction that completes its test settlement shows the normal successful receipt and refreshed balance, without technical environment disclosure. Keep technical calculation/policy details in operator diagnostics. Remove sample/static financial values from live paths.

- [x] **Step 7: Improve customer account discovery and transaction copy**

Present checking, savings, and mortgage under an account-focused heading/grouping; retain the existing card visual for checking but do not make “Thẻ” the only way to find account details. Show human-readable transaction names and status labels; when showing cached information, use a brief “Dữ liệu có thể chưa cập nhật” message instead of technical offline/cache wording.

- [x] **Step 8: Run Android unit tests**

Run: `./gradlew --offline testDebugUnitTest`
Expected: formatter/filter tests pass and generated resources compile.

### Task 4: Complete settled deposit and withdrawal flows

**Files:**
- Modify: `backend/src/modules/requests/requests.routes.ts`
- Modify: `backend/src/modules/requests/requests.service.ts`
- Modify: `backend/src/modules/payments/payment.service.ts` (deposit settlement integration)
- Modify: `app/src/main/java/com/example/final_term/ui/customer/MoneyRequestFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/TransferFragment.java`
- Test: `backend/test/feature-flows.test.ts`
- Test: `backend/test/demo-settlement.test.ts`

**Interfaces:**
- A deposit/withdrawal intent records account, amount, currency, unique request ID, authorization state, and provider/settlement state.
- Settlement atomically updates account balance, balanced ledger entries, and a transaction history row; duplicate webhook/reconcile calls return the existing outcome.
- Failed, pending, and demo outcomes are returned as explicit status values; a request receipt alone never means the balance changed.

- [x] **Step 1: Add failing tests for settled movements**

Cover successful deposit, successful withdrawal, insufficient funds, daily limit, inactive/non-checking account rejection, duplicate request, concurrent duplicate settlement, provider pending/failure, and reconciliation after timeout. Assert account, ledger, and transaction are updated atomically or not updated at all.

- [x] **Step 2: Run focused backend tests and confirm failures**

Run: `npm --prefix backend test -- test/feature-flows.test.ts test/demo-settlement.test.ts`
Expected: new end-to-end movement assertions fail until settlement behavior is complete.

- [x] **Step 3: Consolidate money movement API semantics**

Use the intent/commit/reconcile flow as the single user-facing movement path. Retain a separate request record only when an external operation genuinely remains pending; return its status and link it to the eventual transaction. Do not leave a UI path that reports “accepted” as if it were completed.

- [x] **Step 4: Implement authoritative settlement and idempotency**

For deposits, settle only from a verified payment event or the authoritative test settlement path. For withdrawals, check balance and account limits within the settlement transaction, write the account update, balanced ledger entries, and transaction history row in that same transaction, and make retries idempotent. Keep provider and test-environment details internal.

- [x] **Step 5: Update Android confirmation and receipt screens**

Before confirmation, show operation, account, amount, and fees (if any), without provider/API/backend terminology or environment labels. After submission, show processing/completed/failed in plain Vietnamese; test settlement must produce the same customer-facing success receipt and refreshed balance as the normal completed flow. Make retry recover by the same request ID.

- [x] **Step 6: Run focused backend and Android unit tests**

Run: `npm --prefix backend test -- test/feature-flows.test.ts test/demo-settlement.test.ts`
Run: `./gradlew --offline testDebugUnitTest`
Expected: settlement/idempotency tests and UI state/formatting tests pass.

### Task 5: Verify access control, end-to-end behavior, and operator setup

**Files:**
- Modify: `firestore.rules` only if account/transaction read paths change
- Modify: `backend/test/emulator.test.ts`
- Modify: `backend/test/schema.test.ts`
- Modify: `docs/functional-gaps.md`
- Modify: `docs/firebase-setup.md` (deployment/configuration instructions)

**Interfaces:**
- Firestore client reads remain read-only; all financial writes go through authenticated backend routes.
- Backend API and UI use the same account IDs, policy product IDs, currencies, and transaction statuses.

- [x] **Step 1: Add/extend integration tests for customer and officer access**

Verify a customer can read their own three account types and history, cannot read another customer's resources or change policies, and an officer without `RATE_UPDATE` cannot modify rates. Verify officer history/account permissions remain scoped to their granted capabilities.

- [x] **Step 2: Verify seed and migration data contracts**

Ensure demo data contains a checking account, savings product/policy/details, mortgage terms with outstanding balance, and transaction examples for deposits/withdrawals. Ensure seed reruns preserve balances and do not duplicate policy versions or ledger movements.

- [x] **Step 3: Run complete verification suite**

Run: `npm --prefix backend test`
Run: `./gradlew --offline testDebugUnitTest assembleDebug`
Run emulator integration tests when Firebase emulators are available.
Expected: all tests/builds pass; emulator tests prove authorization and persistence across API and Firestore boundaries.

- [x] **Step 4: Perform UI acceptance walkthrough**

On a customer emulator, verify checking/savings/mortgage account discovery, balances, savings rate and monthly estimate, both mortgage frequencies, checking/savings/mortgage histories, deposit, withdrawal, pending/failure/retry states, and no misleading success state. On an officer emulator, verify policy list, creating a future-effective policy, authorization failure for an unauthorized role, and the customer's estimate changing only after policy effectiveness.

- [x] **Step 5: Update completion documentation**

Record the implemented monthly accrual convention, policy effective-date behavior, mortgage projection assumptions, settlement/provider mode, tests run, emulator results, and any production provider setup still required. Mark a requirement complete only when the end-to-end path is verified.

## Implementation Assumptions to Confirm During Execution

- Monthly savings profit is an estimate, not a posted credit, unless a separate monthly interest posting job and ledger process is added. The initial estimate uses simple daily accrual over the selected calendar month, ACT/365, with half-up rounding in minor currency units and effective-dated rate policies. Formula/convention details stay internal; customer copy uses plain language.
- Customer presentation follows current Android customer UI styles and intentionally omits technical policy/calculation/provider metadata; detailed diagnostics remain in backend/officer surfaces.
- Dedicated screens are preferred over popup dialogs when content or a multi-step form needs room; short confirmations and brief notices may still use dialogs.
- Rate bounds, eligible savings products, and any approval requirements must come from documented bank policy/configuration; implementation must not invent them.
- Mortgage installments are projections based on the current outstanding balance, stated annual rate, remaining term, and selected frequency. The bank's exact biweekly convention and due-date calendar must be agreed before production use.
- Deposits and withdrawals execute through the configured real or test settlement mechanism. Test settlements write the same account, ledger, and transaction records and exercise the same customer states as a completed product flow; environment/provider distinctions belong only in internal diagnostics and operator documentation.
