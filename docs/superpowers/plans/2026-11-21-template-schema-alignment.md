# Template database alignment

Approved by the user: align to Table 16 in `/Users/TDTU/Mobile/Template_EN.docx`, retain technical collections, keep S3/Stripe and defer chatbot. Execute inline with regression tests; no live database writes until preview has been reviewed.

## Tasks

- [x] Extract the 20 collection field lists into `backend/src/schema.ts`; normalize new writes in both store adapters and report conflicting aliases.
- [x] Add canonical API fields and compatibility for existing Postman requests. Add canonical identity links, Stripe references, rate product names and audit summaries.
- [x] Update trusted demo fixtures and keep unknown real customer/staff business data unresolved instead of inventing it.
- [x] Add a preview-first additive migration with plan hash confirmation, conflict detection, source fingerprint checks and retry-safe transactions.
- [ ] Run complete unit/API/build and isolated emulator checks; address independent review findings.
- [ ] Record evidence and guide the user through backend Firebase Admin configuration when ready for a connected preview.

## Decisions

- Canonical identifiers equal Firestore document IDs; `uidRef` links independent Customer/Officer IDs to Auth UID. Existing UID-keyed profiles remain supported.
- Legacy aliases are retained during transition. Input mixing canonical and legacy forms is rejected rather than silently selecting one.
- `annualRate` is an annual decimal fraction; `annualRateBasisPoints` remains a compatibility integer. Monetary values stay integer minor units.
- Timestamps preserve existing representations; this change does not convert historical timestamp types.
- Migration never rewrites historical audit events, money, role, status already present, or document IDs. Missing business fields are reported for manual completion.
- Do not apply migration on the user's current emulator or live project automatically. Tests use a separate disposable emulator configuration.
