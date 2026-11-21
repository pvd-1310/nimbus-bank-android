# Phase 2 Native UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved Phase 2 customer UI in native Java/XML, including account details, filtered history, profile/logout, and request-only deposit/withdrawal screens.

**Architecture:** Extend the existing single-activity Navigation Component app with focused Fragments and XML layouts. Reuse the current auth/read repositories and keep authoritative mutations behind backend/provider boundaries; request UI reports requested/setup-required states only.

**Tech Stack:** Java 11, Android XML/View Binding, AndroidX Navigation, LiveData, Firebase Auth/Firestore, JUnit, AndroidX instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-11-21-phase-2-native-ui-design.md`

## Global Constraints

- Use native Java/XML; do not ship the reference HTML through WebView.
- Preserve the existing navy/cream/glass visual system and dependencies.
- Android must not write balances, ledger entries, roles, audit records, OTP, or settlement results.
- Cached data is labelled read-only; missing providers show setup-required/error states.
- Logout must clear authenticated navigation history and return to `login`.

## Review Focus

- A signed-in user must see a real Profile destination and logout action, not a toast placeholder; cover with a navigation/logout test.
- After logout, back navigation and restored state must not reopen Customer/Officer screens; cover with a route-state test.
- Unknown account type or missing detail data must render a safe fallback rather than crash; cover with detail rendering tests.
- Empty/error/cached history must keep filters usable and never imply successful financial mutation; cover with formatter/state tests.
- Deposit/withdrawal submission must remain request-only/setup-required; cover with request-state tests.

### Task 1: Add testable Phase 2 presentation models and logout route behavior

**Files:**
- Create: `app/src/main/java/com/example/final_term/ui/customer/Phase2Presentation.java`
- Modify: `app/src/main/java/com/example/final_term/navigation/BottomNavigationBinder.java`
- Modify: `app/src/main/java/com/example/final_term/auth/FirebaseAuthRepository.java`
- Modify: `app/src/test/java/com/example/final_term/navigation/BottomNavigationRouteTest.java`
- Create: `app/src/test/java/com/example/final_term/ui/customer/Phase2PresentationTest.java`

**Interfaces:**
- `Phase2Presentation.accountTitle(String type)` and `accountSubtitle(String type)` return safe localized labels.
- `Phase2Presentation.requestStatusMessage(String operation)` returns truthful request-only copy.
- Profile navigation uses `R.id.profile` and logout calls `AuthRepository.signOut()` before navigating to `login` with cleared back stack.

- [ ] Write failing tests for account-type label fallback, request-only copy, profile route selection, and post-logout route expectations.
- [ ] Run `./gradlew testDebugUnitTest --tests '*Phase2PresentationTest' --tests '*BottomNavigationRouteTest'` and verify the new assertions fail.
- [ ] Implement the pure presentation helpers and make `BottomNavigationBinder` navigate the profile item instead of showing the Phase 2 placeholder toast.
- [ ] Keep `FirebaseAuthRepository.signOut()` idempotent and ensure it publishes `AuthState.signedOut()` even when Firebase is unavailable.
- [ ] Run the focused tests and verify they pass.

### Task 2: Implement account detail screens

**Files:**
- Create: `app/src/main/java/com/example/final_term/ui/customer/AccountDetailFragment.java`
- Create: `app/src/main/java/com/example/final_term/ui/customer/SavingsDetailFragment.java`
- Create: `app/src/main/java/com/example/final_term/ui/customer/MortgageDetailFragment.java`
- Create: `app/src/main/res/layout/fragment_account_detail.xml`
- Create: `app/src/main/res/layout/fragment_savings_detail.xml`
- Create: `app/src/main/res/layout/fragment_mortgage_detail.xml`
- Modify: `app/src/main/res/navigation/nav_graph.xml`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/AccountListFragment.java`

**Interfaces:**
- Account list navigates with `accountId` and `accountType` arguments.
- Detail fragments render title, balance/status, detail sections, and loading/error/empty/read-only states.

- [ ] Add navigation arguments and failing tests for checking, savings, mortgage, and unknown account types.
- [ ] Add native XML cards, headers, action rows, and read-only state labels based on `Account_Details.html`, `Savings_Detail.html`, and `Mortgage_Detail.html`.
- [ ] Add repository detail methods or safe fallback projections using the existing Firestore model without changing authoritative data.
- [ ] Wire account rows to the appropriate detail destination and keep officer/customer ownership routing intact.
- [ ] Run focused unit tests and `./gradlew assembleDebug`.

### Task 3: Add searchable and filterable transaction history

**Files:**
- Modify: `app/src/main/java/com/example/final_term/ui/customer/TransactionHistoryFragment.java`
- Modify: `app/src/main/res/layout/fragment_transaction_history.xml`
- Create: `app/src/main/java/com/example/final_term/ui/customer/TransactionHistoryFilter.java`
- Create: `app/src/test/java/com/example/final_term/ui/customer/TransactionHistoryFilterTest.java`

**Interfaces:**
- `TransactionHistoryFilter.apply(List<TransactionSummary>, String query, String type, String status)` returns a new filtered list.

- [ ] Write failing tests for empty query, case-insensitive text match, type/status filters, and null-safe fields.
- [ ] Implement the pure filter and add search/filter controls styled after `Transactions_History.html`.
- [ ] Render filtered counts and empty results without mutating repository data.
- [ ] Verify focused tests and connected UI compilation.

### Task 4: Add Profile/preferences and functional logout

**Files:**
- Create: `app/src/main/java/com/example/final_term/ui/profile/ProfileFragment.java`
- Create: `app/src/main/res/layout/fragment_profile.xml`
- Modify: `app/src/main/res/navigation/nav_graph.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/CustomerHomeFragment.java`
- Modify: `app/src/main/java/com/example/final_term/ui/officer/OfficerHomeFragment.java`
- Modify: `app/src/main/java/com/example/final_term/navigation/BottomNavigationBinder.java`
- Modify: `app/src/androidTest/java/com/example/final_term/BaseFlowInstrumentedTest.java`

**Interfaces:**
- Profile observes `AuthState`, displays name/email/role, shows preferences as read-only/unavailable until backend adapter wiring, and exposes `logoutButton`.
- Logout invokes `authRepository.signOut()` and `NavOptions.setPopUpTo(R.id.login, true)` before navigating to Login.

- [ ] Add a failing instrumentation assertion that Profile contains a visible logout button and logout returns to Login.
- [ ] Implement the Profile XML following `Profile_Screen.html`, including a visible danger-styled “Đăng xuất tài khoản” button.
- [ ] Wire profile navigation for both Customer and Officer bottom navigation shells.
- [ ] Ensure authenticated destinations cannot be restored after logout.
- [ ] Run connected Android tests on an emulator.

### Task 5: Add request-only deposit/withdrawal UI and final verification

**Files:**
- Create: `app/src/main/java/com/example/final_term/ui/customer/MoneyRequestFragment.java`
- Create: `app/src/main/res/layout/fragment_money_request.xml`
- Modify: `app/src/main/res/navigation/nav_graph.xml`
- Modify: `app/src/main/java/com/example/final_term/ui/customer/CustomerHomeFragment.java`
- Create: `app/src/test/java/com/example/final_term/ui/customer/MoneyRequestStateTest.java`
- Modify: `docs/verification/phase-2-native-ui.md`

**Interfaces:**
- `MoneyRequestFragment` accepts `DEPOSIT` or `WITHDRAWAL`, validates positive input for UX, and renders `REQUESTED`, `SETUP_REQUIRED`, or safe error; it never edits account balance locally.

- [ ] Write failing tests asserting request-only wording and non-mutating state transitions.
- [ ] Implement the native form based on `Deposit_Withdraw.html` with amount, account, note, and explicit request-only disclaimer.
- [ ] Wire Home quick deposit to the request screen; keep transfer/payment/QR placeholders unchanged.
- [ ] Run `./gradlew testDebugUnitTest assembleDebug connectedDebugAndroidTest`.
- [ ] Run backend `npm test` and `npm run build` to confirm the Phase 2 API foundation remains green.
- [ ] Record commands, results, limitations, and the remaining Android-backend integration boundary in `docs/verification/phase-2-native-ui.md`.

## Self-review

- The plan covers all design acceptance criteria: detail screens, history filtering, profile/logout, request-only UI, truthful states, and verification.
- The plan keeps each task independently testable and avoids adding provider SDKs or authoritative client writes.
- The only deferred behavior is remote profile preference persistence and backend API wiring, which the approved design explicitly keeps unavailable until the adapter is connected.
