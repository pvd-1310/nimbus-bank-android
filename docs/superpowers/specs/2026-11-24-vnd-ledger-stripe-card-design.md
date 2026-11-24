# Firebase VND Ledger and Stripe Card Payments

## Intent

Use Firestore as Nimbus's authoritative VND ledger and balance source. Stripe test mode processes card top-ups and card payments for bills and utility orders. Keep internal transfers on the Firebase ledger and keep interbank transfers explicitly demo-only until a real bank rail is connected.

## Goals

- Post internal VND transfers atomically to both accounts, transaction history, audit, and ledger entries.
- Credit top-ups exactly once, only after a verified Stripe `payment_intent.succeeded` webhook.
- Pay bills and catalog orders by Stripe test card without also debiting the Firebase wallet.
- Preserve OTP and policy-required face verification before starting money movement.
- Keep Android balances and history sourced from Firebase and remove the VND/USD sandbox-cent presentation conversion.
- Preserve explicit demo behavior for withdrawals and interbank settlement; never present it as a real external bank transfer.

## Non-goals

- Production card acquiring, real interbank transfer rails, real payout, FX, or Stripe live mode.
- Migrating balances held in legacy Stripe Treasury test accounts into Firebase.
- Changing unrelated UI or the already approved D-OTP demo behavior.

## Approaches

- Keep Stripe Treasury as the wallet and add Firebase as a mirror: rejected because it leaves two competing balances and cannot make Firebase authoritative.
- Use Firebase VND ledger with Stripe PaymentIntents for card collection: selected because it provides one user-facing balance and uses Stripe only for card collection.
- Replace Stripe with a new payment provider: deferred because the project already has Stripe test PaymentIntent, PaymentSheet, webhook, and OTP foundations.

## Constraints and decisions

- Money amounts in the wallet and internal ledger are integer VND units (`amountMinor` for VND is whole đồng).
- Android client success is never proof of settlement; only the signed Stripe webhook can settle a top-up or card payment.
- Webhook processing must be idempotent and validate amount, currency, owner, intent, provider ID, and payment purpose.
- Each posted ledger movement has equal and opposite entries, and balance materializations are updated in the same Firestore transaction.
- VND interbank transfers remain `DEMO_ONLY`; no claim of real delivery.
- Legacy Treasury records remain separate and are not silently counted as Firebase balances.

## Implementation calls

- Reuse the existing PaymentIntent gateway and webhook for top-ups, bills, and demo utility orders, with explicit payment purpose/target metadata.
- Add a dedicated top-up intent/payment route rather than overloading a bill ID.
- Keep utility fulfillment behind the existing outbox and trigger it only after verified payment settlement.
- Keep the existing Firebase demo withdrawal/interbank clearing behavior with explicit demo status.
- Update Android transfer, wallet, account details, home, and history to use VND ledger data and label external transfers as demo.
