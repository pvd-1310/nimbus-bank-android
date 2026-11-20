# Phase 1 read-foundation verification

## Scope

Phase 1 exposes authenticated read-only views for accounts, transaction history,
and in-app notifications. Firestore remains the source of truth. Cached data is
marked read-only and no screen in this phase writes balances, ledger entries,
transactions, roles, audit events, or notification state.

## Automated checks

Run from the repository root:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The unit suite covers the explicit read result states and the online-only
mutation gate. Instrumented flow coverage requires an available Android device
or emulator and Firebase configuration:

```bash
./gradlew connectedDebugAndroidTest
```

## Manual acceptance flow

1. Sign in as `customer.demo@example.com`.
2. Open **Tài khoản và thông báo** and confirm account data, empty state, or a
   recoverable Firebase error is shown.
3. Confirm cached/offline data is labelled read-only.
4. Open **Lịch sử giao dịch** and confirm transaction data is scoped to the
   signed-in customer.
5. Sign out and sign in as `officer.demo@example.com`.
6. Confirm the Officer screens can read permitted account/history views while
   customer-only navigation is not exposed.

If Firebase is unavailable, the app must show setup-required/error state and
must not fabricate balances or successful transactions.
