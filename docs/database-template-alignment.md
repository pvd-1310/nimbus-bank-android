# Database alignment with Template EN

Source: Table 16, Firestore collections and primary fields, in `/Users/TDTU/Mobile/Template_EN.docx`. User approved alignment on 2026-11-21, with S3 for evidence, Stripe for payment and chatbot deferred.

The executable data dictionary is `backend/src/database/template-schema.ts` (`templateFields`), containing all 20 template collections. New writes through the Firestore adapter and test store receive stable canonical identifiers. The OpenAPI document includes `x-firestore-template-fields`.

## Canonical fields and compatibility

| Collection | Canonical fields | Transitional behavior |
| --- | --- | --- |
| users | uid, role, fullName, emailMasked, phoneMasked, status, createdAt | Keep `active` for existing clients; explicit inactive status denies access |
| customers | customerId, uidRef, customerNo, kycStatus, address, createdBy | Existing `uid` retained; unknown business data is reported, not invented |
| officers | officerId, uidRef, staffCode, permissions, active, branchId | Permission lookup supports UID-keyed documents and independent IDs linked by uidRef |
| accounts | accountId, ownerUid, type, currency, status, availableMinor, bookedMinor, version | `balanceMinor` remains Android compatibility data; migration does not infer or overwrite money |
| checkingDetails | accountId, overdraftPolicy, dailyLimitMinor, cardAlias | Existing policies preserved |
| savingsDetails | accountId, principalMinor, productId, ratePolicyVersion, openedAt, maturityAt | productId is the template field here |
| mortgageDetails | accountId, loanPrincipalMinor, outstandingMinor, annualRate, paymentFrequency, termMonths | annualRate is decimal: 800 basis points equals 0.08; preserve integer alias |
| transactions | transactionId, requestId, actorUid, sourceAccountId, destinationAccountId, type, amountMinor, status, committedAt | Existing owner-scoped recipient projections are retained as projections, not a second ledger commit |
| transactionIntents | intentId, actorUid, sourceId, target, amountMinor, digest, expiresAt, status | target is `{kind: ACCOUNT or BILL, id}`; sourceId is an account ID or STRIPE for externally funded payment |
| ledgerEntries | entryId, transactionId, accountId, direction, amountMinor, currency, postedAt | Keep signedMinor for invariant checks |
| otpChallenges | challengeId, intentId, actorUid, otpHashOrProviderRef, expiry, attempts, usedAt | Preserve hash/expiresAt aliases for already-issued challenges; no plaintext OTP is stored here |
| ekycRequests | requestId, customerUid, consentAt, storageRefs, reviewStatus, reviewedBy, reviewedAt | storageRefs stores private S3 key/version pairs; reviewer fields are null before a decision |
| bills | billId, ownerUid, provider, invoiceRef, amountMinor, dueAt, status | dueAt may be null when no due date applies |
| payments | paymentId, billOrOrderRef, gateway, merchantRef, amountMinor, status, providerTxnRef | gateway STRIPE or NIMBUS (SIMULATOR only for demo records); merchantRef is server payment ID; providerTxnRef is Stripe PaymentIntent ID; legacy billId/providerId retained |
| orders | orderId, ownerUid, serviceType, quoteRef, amountMinor, fulfillmentStatus, paymentId | Freeze quote reference; paymentId is null until linked |
| ratePolicies | policyId, accountProduct, annualRateBasisPoints, effectiveFrom, version, updatedBy | Preserve productId alias; savingsDetails.productId is unchanged |
| bankBranches | branchId, name, address, latitude, longitude, active, openingHours | No route result is fabricated |
| notifications | notificationId, ownerUid, category, title, message, readAt, createdAt | Preserve body/read for existing Android; unread readAt is null |
| chatHistory | messageId, ownerUid, role, redactedText, actionId, createdAt | Reserved only; chatbot implementation deferred |
| auditLogs | eventId, actorUid, action, resourceId, requestId, timestamp, beforeAfterSummary | New mutations add safe summaries; historical events remain immutable |

The additional collections `moneyRequests`, `dailyUsage`, `policyHeads`, `billPaymentLocks`, `webhookEvents`, `outbox`, `uploadTickets`, `catalog`, `provisionRequests`, `customerChanges`, `customerNumberReservations`, and `contactPhoneReservations` support implementation details and are not substitutes for the template collections.

## Migration procedure

Run from `backend/`, with the selected target configured explicitly in `.env`:

```sh
npm run migrate
```

This is read-only. It prints project/mode, a `planHash`, document paths, names of proposed additions, missing business fields and conflicts. It does not print raw document values, OTP verification material, or credentials. Unknown balances, staff codes, customer numbers and historical creation timestamps are not guessed. Existing documents are not deleted or renamed.

Review conflicts and missing fields before applying. To apply a reviewed plan in the emulator:

```sh
npm run migrate -- --apply --confirm-plan HASH_FROM_PREVIEW
```

For connected Firebase, the command additionally requires `CONFIRM_MIGRATION_PROJECT` equal to the configured real project ID. This variable is separate from seed confirmation. Do not enable it until the user approves the preview. Never run `seed --apply` as a substitute for migration of existing users.

The CLI recomputes the plan and rejects a changed hash. Per-document transactions check the full source fingerprint before writing additions. If a later document changes or the command is interrupted, earlier additive changes may already exist; rerun preview and review the new plan. Applied values and old fields remain intact. Reapplying the same in-memory plan is safe when only its own additions have occurred.

Before migration, let short-lived transaction authorizations expire and start new intents afterward: adding metadata to a checking policy can change its existing digest even when monetary limits are unchanged. This prevents an old authorization from being reused under an unreviewed policy snapshot.

## Firebase integration checkpoint

Android `google-services.json` configures the Android client, not the privileged backend. Backend connected mode needs its own Firebase Admin credentials for the same project. Keep credentials outside the repository, set their local path in `GOOGLE_APPLICATION_CREDENTIALS`, set `APP_MODE=connected` and the actual project ID, and remove both emulator host variables. This setup does not require enabling Cloud Functions or Firebase Storage; Spark remains selected.

Do not migrate credentials, provider secrets or custom claims through the database migration. Claims bootstrap/migration for existing Auth UIDs is a separate reviewed action. Connected money operations still require configured OTP delivery; schema alignment alone does not enable that provider.

No real Firebase database migration or deployment was performed as part of local schema implementation.
