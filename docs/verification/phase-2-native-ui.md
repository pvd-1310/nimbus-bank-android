# Phase 2 native UI verification

## Scope

Implemented the approved native Java/XML Phase 2 customer surfaces using the
reference UI in `/Users/TDTU/Mobile/UI`:

- account detail presentation for checking, savings, and mortgage;
- searchable transaction history with type/status filters;
- Profile screen with identity/preferences boundary and visible logout;
- request-only deposit/withdrawal form with truthful non-mutating status.
- corrected bottom navigation active state so `Ví` and `Tài khoản` never render
  as active simultaneously;
- added bottom-scroll padding so the last content row is not hidden behind the
  floating bottom navigation bar.
- moved bottom navigation to `MainActivity` as one shared fixed overlay; feature
  Fragments no longer create their own navigation bar or alter its padding.

## Verification

From the repository root:

```sh
./gradlew testDebugUnitTest assembleDebug connectedDebugAndroidTest
```

Result: passed. The connected test ran on `Pixel_10a(AVD) - 17`.

Latest verification (2026-11-21): 29 JVM tests and 4 connected Android tests
passed on Pixel 10a API 37; debug build passed. Connected coverage includes:

- launch and navigation graph;
- wallet/accounts selection in both directions on the shared destination;
- checking/savings/mortgage destinations with missing account IDs, without
  displaying an invented zero balance;
- visible logout action, clearing all prior authenticated entries, hiding the
  bottom bar on Login, and recreating the Activity after logout.

These automated checks are not a full visual comparison against every HTML
reference or an end-to-end test with populated savings/mortgage backend data.

Backend regression checks:

```sh
cd backend && npm test
cd backend && npm run build
```

Result: 60 unit/HTTP tests passed, 4 emulator-only tests skipped by the normal
unit command, and the TypeScript build passed.

## Logout acceptance

Customer and Officer shells expose the Profile destination through bottom
navigation. Profile shows `Đăng xuất tài khoản`; the action calls the shared
auth repository, pops the root navigation graph inclusively, and returns to Login. MainActivity
also observes the signed-out state and protects against returning to an
authenticated destination.

## Boundaries and limitations

Account details reload the authorized Firestore account list and select by ID;
navigation-provided balances are not used. Loading/error/missing-account/cache
states are explicit. Savings and mortgage sections identify unavailable rate,
term, maturity and schedule data instead of fabricating values. Dedicated backend
detail/calculation adapters remain deferred under the approved UI scope.

History filtering remains local. Deposit/withdrawal offers an operation selector,
an eligible ACTIVE CHECKING VND account selector, a note and validation of positive
whole-VND amounts (up to 18 digits). Cached/error/empty account data disables the
action. The API is not wired, so even valid input reports that the request has
NOT been sent; no pending/success status is fabricated and no balance is changed.
Both form and detail content can scroll above the fixed navigation bar.

Profile preference edits remain unavailable until the backend API adapter is wired.
Wallet and Accounts currently share the account-list content; only selection
differs. Separate wallet content is not part of the approved Phase 2 spec.
