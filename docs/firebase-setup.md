# Firebase setup for the base app

The Android package is fixed as `com.example.final_term`. The repository intentionally does not contain `google-services.json`, credentials, passwords, or provider keys.

## Local Spark setup

1. Create a Firebase project without enabling billing.
2. Register an Android app with package `com.example.final_term`.
3. Download `google-services.json` and place it at `app/google-services.json` locally.
4. Enable Email/Password in Authentication.
5. Create the two demo Authentication users:
   - `customer.demo@example.com`
   - `officer.demo@example.com`
6. Create Firestore in the intended region.
7. Deploy rules and indexes from the repository when the Firebase CLI is available:

```bash
firebase deploy --only firestore:rules,firestore:indexes
```

The base app shows a recoverable setup-required state when Firebase configuration is absent or malformed. It does not fall back to mock login.

Each `users/{uid}` profile should also contain a `fullName` string. The Android home greeting displays this value and falls back to the authenticated email only when the field is empty.

## Trusted seed/reset

Use `scripts/seed-demo-data.mjs` outside Android with Application Default Credentials and the real Authentication UIDs:

```bash
export FIREBASE_PROJECT_ID="your-project-id"
export GOOGLE_APPLICATION_CREDENTIALS="/secure/path/service-account.json"
export DEMO_CUSTOMER_UID="..."
export DEMO_OFFICER_UID="..."
node scripts/seed-demo-data.mjs
```

The script is intentionally not an in-app admin control. It must run only in a trusted environment where `firebase-admin` is installed. Reset is scoped to documents marked `demo: true`.

## Emulator

`firebase.json` contains the Auth and Firestore emulator ports. Emulator wiring can be enabled for local development after the Firebase CLI is installed; no production resource or billing is created by this repository.
