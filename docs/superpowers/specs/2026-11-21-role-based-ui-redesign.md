# Nimbus Bank role-based UI redesign

## Intent

Redesign the Android UI so Customer and Officer/Admin can be tested as two clearly different products while sharing the Nimbus visual language from `/Users/TDTU/Mobile/UI/DESIGN.md`.

The current app has separate home destinations but several shared customer screens and a shared bottom navigation. That makes an officer appear to be using customer UI. The redesign makes role ownership visible in every primary screen and keeps production backend states explicit.

## Goals

- Keep the Nimbus mist/slate frosted-glass visual system.
- Give Customer and Officer separate navigation labels and action priorities.
- Make every role-specific action reachable in the simulator.
- Preserve Firebase Auth role routing and backend authorization.
- Never show a customer mutation action in Officer UI or an officer administration action in Customer UI.
- Keep unavailable provider states truthful.

## Non-goals

- No new banking authority in Android; backend remains authoritative.
- No client-side balance mutation.
- No automatic Firebase, OTP, S3, Stripe, maps, or deployment provisioning.
- No WebView or HTML screen replacement; use native Java/XML.

## Shared visual system

- Background: `bg_mist` to `bg_slate` gradient.
- Surfaces: translucent glass cards, 24–28dp corners, 1dp glass border.
- Primary: `navy` / `navy_deep`.
- Text: `ink`, `ink_muted`, `ink_faint`.
- Screen gutter: 20dp; minimum touch target: 48dp.
- Shared components: top identity header, status chip, glass metric card, action tile, empty/error state, and role-aware bottom navigation.

## Customer experience

### Customer home

- Greeting, avatar, notification action.
- Total available balance and backend connection state.
- Four primary actions: Transfer, Deposit/Withdraw, Pay, QR.
- Recent transactions and notifications.
- Bottom navigation: Home, Wallet, Quick Actions, History, Profile.

### Customer screens

- Wallet: owned accounts only; account cards open account detail.
- Transfer: source account, recipient account, amount, intent/OTP/commit state.
- Deposit/Withdraw: request-only state; no false settlement.
- History: customer-owned transaction filters.
- Profile: customer identity, preferences, sign-out.
- Provider screens: utility payment, cards, branches, AI, and eKYC with explicit capability state until their backend providers are enabled.

## Officer/Admin experience

### Officer dashboard

- Header says `Không gian vận hành` and shows `Bank Officer`.
- Operational metrics: customer accounts, pending eKYC, recent transactions, backend status.
- Primary actions: Customer Accounts, eKYC Review, Transaction Audit, Rate Policies.
- No customer transfer, deposit, or personal balance quick actions.

### Officer screens

- Customer Accounts: searchable/listed customer accounts with owner identity, account ID, type, status, and read-only detail.
- eKYC Review: request status and review checkpoint.
- Transaction Audit: scoped read-only transaction/audit view.
- Rate Policies: permission-gated checkpoint.
- Officer Profile: staff identity, permissions, branch, sign-out.

### Officer navigation

Bottom navigation is role-specific: Dashboard, Customers, Review, Audit, Profile. It must not reuse Customer labels such as Wallet or Quick Actions.

## State and access rules

- Navigation is selected from the authenticated role, not from screen text.
- Customer destinations reject Officer access and vice versa.
- Officer account detail may read an owned customer account only through backend-authorized/officer read paths.
- Provider unavailable states show the provider name and next configuration checkpoint.

## Verification targets

- Customer login lands on Customer Home and sees Customer navigation.
- Officer login lands on Officer Dashboard and sees Officer navigation.
- Officer Customer Accounts shows owner/account identity and opens read-only detail.
- Customer cannot see Officer Review/Audit/Rate Policy destinations.
- Officer cannot see Customer Transfer/Deposit/Withdraw actions on the dashboard.
- Both roles preserve sign-out and invalid/inactive profile routing.
