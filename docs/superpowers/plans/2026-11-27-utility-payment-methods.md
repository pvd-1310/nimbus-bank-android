# Utility payment methods implementation plan

Status: implemented; backend and Android builds pass.

## Goal
Add Nimbus account and Stripe card selection to every utility bill/order confirmation in the existing transaction flow. Preserve the current Stripe path and bind the selected method/source to the secured payment intent.

## Decisions
- Stripe remains selected by default to preserve existing behavior.
- Nimbus is selectable only when a valid active VND payment account is available; account selection is explicit if multiple accounts exist.
- Legacy clients that omit `paymentMethod` continue to use Stripe.
- Switching method/account after an intent exists starts a fresh intent and OTP authorization.
- Nimbus settlement is atomic and idempotent. Order fulfillment is enqueued in the same transaction and drained afterward.

## Changes delivered
1. Backend request validation and intent digest: accept `STRIPE_CARD`/`NIMBUS_ACCOUNT`, bind method and Nimbus source account in the intent digest, and retain legacy Stripe defaults.
2. Backend payment service: implement Nimbus commit with server-side bill, account ownership/status/currency/balance, OTP and idempotency checks; atomically write debit ledger, payment, transaction, bill state, intent, and fulfillment outbox. Keep Stripe behavior intact.
3. Backend routes/recovery: expose eligible account data through existing accounts endpoint and return canonical payment details for both methods; trigger outbox draining after Nimbus settlement.
4. Android confirmation UI: show method selection for bill/order flows, eligible Nimbus account and available balance, insufficient-balance feedback, and keep Stripe available. Pass the chosen method/account into intent and payment requests; route Nimbus completion through existing recovery/receipt flow without opening PaymentSheet.
5. History/receipt: display the persisted method and account source accurately for completed utility payments.
6. Review changed files and run compilation/build checks if available; do not add or run tests unless separately requested.

## Review focus
- No Nimbus debit on Stripe flows and no Stripe PaymentIntent on Nimbus flows.
- No client-controlled amount/balance; server remains authoritative.
- OTP gates and intent idempotency cover both paths.
- Retried commits, duplicate requests, and fulfillment retries cannot double debit or settle.
- Pending/failed transactions are not shown as successful receipts.
- Existing uncommitted deletion of `UtilitiesHubDialogFragment` and its layout is preserved.
