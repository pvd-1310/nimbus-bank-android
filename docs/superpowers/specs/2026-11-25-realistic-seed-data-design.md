# Realistic seed data design

## Intent

Replace placeholder-looking seeded content with coherent synthetic Vietnamese banking and utility data so screens feel believable during local use. The seed remains test data; no real customer or provider records are introduced.

## Goals and boundaries

- Update every non-auth fixture created by `backend/src/scripts/seed-demo.ts`: displayed profile fields, account metadata and initial values, savings/mortgage terms, rates, utility/catalog products, bills, flights, and branch details.
- Preserve login UIDs, emails, roles, password flow, and Firebase claims.
- Remove visible placeholder wording such as `demo`, `DEMO`, `DEMO_`, and namespace-shaped invoice labels from seeded content and the utility screens that present those fixtures.
- Keep `demo: true`, `demoNamespace`, namespace guards, and other internal markers required by backend authorization and test-mode behavior.
- Keep existing API shapes and Firestore schema; add no migration.
- Refresh descriptive fixture values for an existing namespace, but preserve mutable account balances/status, paid bill status, orders, payments, and transaction history.
- Do not run the seed script or write to Firebase as part of this change.

## Approaches

1. Replace only strings that visibly contain `demo`, leaving generic and repetitive account, bill, and catalog values unchanged. This is smaller but does not satisfy the request for all seeded data to feel realistic.
2. Refresh all non-auth fixtures with coherent synthetic values, retain internal test flags and mutable ledger state, and remove utility-specific demo badges/copy. **Chosen by the user:** this updates the full dataset without changing login access or production contracts.

## Data and UI decisions

- Use fictional Vietnamese names, masked contact details, customer/staff numbers, account aliases, provider references, branch information, product listings, and itinerary details. Keep login UIDs/emails/password handling unchanged.
- Use varied, plausible VND amounts, dates, meter readings, loan terms, and savings rates that remain mathematically consistent.
- Keep IDs stable for existing namespace fixtures where possible. New catalog entries use deterministic namespaced IDs.
- Existing records are updated only after confirming `demoNamespace` matches the active seed namespace. Preserve live/mutable fields such as balances and payment status.
- Remove only presentation labels that call seeded utility/catalog data “demo”; backend mode and transaction/audit markers remain as required for truthful system behavior.

## Acceptance

- A dry-run reports the same login identities and does not write data.
- Applying the seed refreshes the non-auth fixture descriptions for the matching namespace and rejects records owned by another namespace.
- Existing mutable balances/statuses and transaction/order/payment records remain intact.
- Utility and service catalog screens show realistic-looking fixture content without visible `demo` badges or placeholder labels.
- Android and backend builds compile; no tests are run unless requested.
