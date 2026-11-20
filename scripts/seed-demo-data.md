# Firebase Spark seed setup

This utility is intentionally outside the APK. It never creates passwords and never accepts Firebase Admin credentials from source control.

1. Create a Firebase project without enabling billing.
2. Register Android package `com.example.final_term`.
3. Download `google-services.json` into `app/google-services.json` locally. Do not commit it.
4. Enable Email/Password Authentication and create these two accounts in Firebase Authentication:
   - `customer.demo@example.com`
   - `officer.demo@example.com`
5. Copy the real Firebase UIDs into environment variables, and run the utility from a trusted machine with Application Default Credentials:

The utility seeds `fullName` for both demo profiles so the Android home greeting can show a real profile name.

```bash
export FIREBASE_PROJECT_ID="your-project-id"
export GOOGLE_APPLICATION_CREDENTIALS="/secure/path/service-account.json"
export DEMO_CUSTOMER_UID="firebase-customer-uid"
export DEMO_OFFICER_UID="firebase-officer-uid"
node scripts/seed-demo-data.mjs
```

Reset only demo-marked documents:

```bash
node scripts/seed-demo-data.mjs --reset
```

Install `firebase-admin` only in the trusted seed environment. Never put the service-account JSON, passwords, provider keys, or eKYC files in this repository or APK.
