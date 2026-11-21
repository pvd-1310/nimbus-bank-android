# Phase 2 native Android UI design

## Intent

Implement the customer-facing Phase 2 read workflows using native Java/XML
Fragments, following the visual language and content hierarchy in
`/Users/TDTU/Mobile/UI`. Also repair the authenticated logout path by exposing a
visible Profile screen and a working sign-out action.

## Goals

- Add native account detail presentation for checking, savings, and mortgage
  accounts.
- Add transaction history search/filter controls without changing authoritative
  financial data.
- Add profile/preferences presentation and a visible logout button.
- Add deposit/withdrawal request UI that clearly remains request-only and does
  not claim a balance mutation.
- Preserve loading, empty, error, cached/read-only, and setup-required states.
- Keep the existing Java/XML, single-activity, Navigation Component architecture.

## Non-goals

- No WebView or direct reuse of the HTML files as runtime screens.
- No client-side writes to balances, ledger entries, roles, audit records, OTP,
  or settlement state.
- No transfer, payment, eKYC, or provider integration beyond existing blocked
  or setup-required contracts.

## Approaches considered

1. Native Java/XML: selected because it matches the current app, supports
   Android navigation/tests, and can later consume the backend adapters.
2. WebView HTML: rejected because it makes Firebase/backend state, navigation,
   accessibility, and Android tests harder to integrate.
3. Hybrid HTML prototype then native conversion: rejected as unnecessary
   duplication for the current scope.

## Design decisions

- Use one Fragment per major destination: account detail, savings detail,
  mortgage detail, filtered history, profile, and money request.
- Reuse current `FirestoreReadRepository`, domain DTO boundaries, and auth
  repository; add only read/request methods needed by the screens.
- Pass account IDs and filter state through safe Navigation arguments or local
  ViewModel state; never infer ownership from UI text.
- Put logout in Profile as a clearly labelled button matching the reference
  design. On sign-out, clear the back stack and return to Login.
- Use the existing navy/cream/glass visual system and XML drawables rather than
  introducing a new UI toolkit or dependency.
- Keep request submission visibly labelled as pending/requested until an
  authoritative backend result exists.

## Acceptance criteria

- Customer can open checking, savings, and mortgage details from the account
  area and see the correct empty/error/read-only states when data is absent.
- Customer can search/filter transaction history without leaving the screen.
- Customer can open Profile, see identity/preferences, tap “Đăng xuất tài khoản”,
  and cannot return to authenticated screens with back navigation.
- Customer can open deposit/withdrawal request UI and receives a truthful
  request-only/setup-required result.
- Existing unit tests, connected Android tests, and debug build pass.

## Open implementation calls

- Use simple native `RecyclerView`/linear layouts already compatible with the
  project dependencies; do not add a new design system dependency.
- Use local filtering for already-loaded history until the Android API adapter
  is connected; label remote/backend data boundaries clearly in code.
- Keep profile preference edits read-only or unavailable until the backend
  adapter is wired, while rendering the intended Phase 2 surface.
