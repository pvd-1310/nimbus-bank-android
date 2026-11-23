# Firestore User and Account Schema Alignment Design

## Intent

Align Firestore's user, customer, account, and checking detail layout with the supplied ER diagram while preserving the existing app and banking flows.

## Goals

- Keep `users/{uid}` for authentication identity and role (`uid`, `email`, `role`, `createdAt`).
- Keep `customers/{uid}` linked to `users/{uid}`, with customer identity and KYC fields (`fullName`, `cccdNumber`, `kycStatus`, `dailyLimit`).
- Keep `accounts/{accountId}` as a separate collection linked to `users/{uid}`, with account number, balance, account type, and status.
- Store checking-specific details beneath the account as a Firestore subcollection.
- Preserve customer onboarding, officer list/edit/history, and eKYC approval behavior.
- Preserve money-operation invariants and read compatibility for the current account schema during transition.

## Non-goals

- Do not merge customer profiles or accounts into `users`, or change officer profile storage.
- Do not delete existing Firebase documents as part of this code change.
- Do not change ledger semantics, authorization, or account ownership.

## Approaches

1. **Match the supplied ER diagram with compatibility fields (chosen).** Use separate user/customer/account entities and nested checking details. Keep internal aliases needed by current transaction and ledger logic while exposing/migrating toward the requested names. Existing documents remain readable.
2. **Replace current fields outright with only diagram fields.** This is simpler visually, but drops money precision/version fields required by transfer safety and makes existing app/backend flows incompatible until all consumers and records are migrated.

## Design

### Storage and lookup

Provisioning writes `users/{uid}`, `customers/{uid}`, `accounts/{accountId}`, and one checking detail at `accounts/{accountId}/checkingDetails/{accountId}`. The detail document ID is the account ID, making the relationship one-to-one and provisioning idempotent. User and customer documents use UID keys. Account documents retain the existing integer minor-unit balance fields, currency, and version because transfer and ledger code relies on them. `accountType` mirrors `type`; `accountNumber` mirrors the account document ID; `balance` mirrors `bookedMinor` as the customer-facing whole-unit amount for VND. The integer minor-unit fields remain the source of truth for all financial mutations, and aliases are refreshed in the same transaction. The customer document uses the diagram's field names, with `cccdNumber` stored only when explicitly supplied/verified under existing privacy rules; KYC workflow remains authoritative for verification status.

Customer lookup first checks `customers/{uid}`. If absent, it retains the current legacy fallback for documents linked through `uidRef` (or legacy `uid`) so existing data continues to work. Account owner queries, detail reads, provisioning, transfers, and demo seeds must support the new nested checking-details path plus legacy top-level detail documents. Customer-number duplicate detection remains supported during the transition.

### Flows

- Officer provisioning creates the four diagram entities/paths while retaining backend-required account fields.
- Officer customer list and edit endpoints continue to expose the same API shape. Edits update `users` for contact/name and `customers` for address. Change history and audit behavior stay intact.
- eKYC approval/rejection continues updating customer status in `customers`; eKYC request evidence remains in `ekycRequests`.
- Demo seeding, schema normalization, migration dictionary, rules, and tests are aligned to the diagram. Existing customer/account records remain readable; a real Firestore migration is a separate reviewed operation.
- Android continues reading the user profile from `users`; no client screen behavior changes.

## Constraints and risks

The diagram omits several fields needed for the current double-entry ledger and transfer consistency (`availableMinor`, `bookedMinor`, `currency`, `version`). These remain in storage to preserve correct monetary behavior. For VND, `balance` is a compatibility projection of `bookedMinor`; transaction math never uses the floating-point alias. `cccdNumber` is sensitive identity data and must not be inferred from eKYC images or logged. Existing Firestore records may have independent document IDs or flat checking detail documents; code must read those until a separately reviewed migration. Nested subcollection writes need adapter support without changing transaction semantics.

## Verification

Update relevant backend tests for canonical writes, legacy profile/account/detail reads, nested detail path behavior, balance invariants, officer edits, and eKYC status updates. Run focused backend tests and the Android/backend builds; no connected Firebase migration is included.

## Builder choices

- Preserve legacy flat detail and field aliases until a separately reviewed data migration is requested.
- Keep public API payloads stable while the Firestore representation transitions.
- Use the account ID as the single checking detail document ID; backend ledger fields are canonical and diagram fields are synchronized projections.
