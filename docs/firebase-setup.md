# Firebase setup for the base app

The Android package is fixed as `com.example.final_term`. The repository intentionally does not contain `google-services.json`, credentials, passwords, or provider keys.

## Local Spark setup

1. Create a Firebase project without enabling billing.
2. Register an Android app with package `com.example.final_term`.
3. Download `google-services.json` and place it at `app/google-services.json` locally.
4. Enable Email/Password in Authentication.
5. Create Firestore in the intended region.
6. Deploy rules and indexes from the repository when the Firebase CLI is available:

```bash
firebase deploy --only firestore:rules,firestore:indexes
```

The base app shows a recoverable setup-required state when Firebase configuration is absent or malformed. It does not fall back to mock login.

When updating an existing project, deploy the current Firestore rules after reviewing them. The current rules keep ONBOARDING customers limited to their own profile/eKYC API flow and deny direct reads of accounts, transactions, and notifications until eKYC approval.

Each `users/{uid}` profile should also contain a `fullName` string. The Android home greeting displays this value and falls back to the authenticated email only when the field is empty.

## Trusted seed

Use the single backend seed flow described in [README](../README.md#28-seed-dữ-liệu-và-khởi-động-docker). Configure a test project, `DEMO_PASSWORD`, and `CONFIRM_SEED_PROJECT` in the environment before applying:

```bash
cd backend
npm ci
npm run seed
npm run seed -- --apply
```

The first command previews the target; `--apply` creates missing demo records and Auth users in the `demo` namespace without resetting existing data or passwords. The seed is a trusted backend operation, not an in-app admin control. Existing records and users from other seed workflows are not automatically migrated or deleted.

## Emulator

`firebase.json` contains the Auth and Firestore emulator ports. Emulator wiring can be enabled for local development after the Firebase CLI is installed; no production resource or billing is created by this repository.

## Bank-wide interest policies

Officers with `RATE_UPDATE` can schedule a bank-wide savings rate or loan rate. A savings policy applies to all savings accounts; a loan policy changes projected mortgage installments from its effective date. Existing account rates remain in effect before that date. The backend validates annual rates against the configured range in basis points:

```bash
SAVINGS_RATE_MIN_BPS=0
SAVINGS_RATE_MAX_BPS=10000
```

The defaults preserve the existing 0–100% technical range. Set these values to the bank-approved bounds in the backend runtime environment. Each policy version requires a reason and a future effective date. Legacy product-specific savings rates and account-level mortgage rates remain as fallbacks until the first bank-wide policy of the matching type takes effect.

Savings profit is an estimate for the UTC calendar month using simple daily accrual and the policy effective for each interval. Mortgage payments are projections from current outstanding balance and remaining term; for older records without `remainingTermMonths`, `termMonths` is used until the operational record is updated. Neither estimate changes account balance.
