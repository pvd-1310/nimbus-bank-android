#!/usr/bin/env node

import process from 'node:process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const envFile = fileURLToPath(new URL('../.env', import.meta.url));
if (existsSync(envFile)) process.loadEnvFile(envFile);

let admin;
try {
  admin = await import('firebase-admin');
} catch {
  console.error('Missing optional seed dependency. Run this utility from a trusted Node environment with firebase-admin installed.');
  process.exit(2);
}

const customerUid = process.env.DEMO_CUSTOMER_UID;
const officerUid = process.env.DEMO_OFFICER_UID;
if (!customerUid || !officerUid) {
  console.error('Set DEMO_CUSTOMER_UID and DEMO_OFFICER_UID to the real Firebase Authentication UIDs.');
  process.exit(2);
}
if (customerUid === officerUid) throw new Error('Customer and Officer UIDs must differ.');

const firebaseAdmin = admin.default ?? admin;
firebaseAdmin.initializeApp({
  credential: firebaseAdmin.credential.applicationDefault(),
  projectId: process.env.FIREBASE_PROJECT_ID || undefined
});
const db = firebaseAdmin.firestore();
const now = firebaseAdmin.firestore.FieldValue.serverTimestamp();
const demo = { demo: true, updatedAt: now };

if (process.argv.includes('--reset')) {
  const collections = ['customers', 'officers', 'accounts', 'transactions', 'transactionIntents', 'otpChallenges', 'ekycRequests', 'bills', 'payments', 'orders', 'ratePolicies', 'bankBranches', 'notifications', 'chatHistory', 'auditLogs'];
  for (const collection of collections) {
    const snapshot = await db.collection(collection).where('demo', '==', true).get();
    const batch = db.batch();
    snapshot.docs.forEach(doc => batch.delete(doc.ref));
    if (!snapshot.empty) await batch.commit();
  }
  console.log('Removed only demo-marked records; restoring the seed now.');
}

const users = [
  { id: customerUid, role: 'CUSTOMER', email: 'customer.demo@example.com', fullName: 'Nguyễn Minh Anh', active: true },
  { id: officerUid, role: 'OFFICER', email: 'officer.demo@example.com', fullName: 'Nguyễn Văn Officer', active: true }
];
for (const user of users) {
  await db.collection('users').doc(user.id).set({ ...demo, ...user }, { merge: true });
}
await db.collection('customers').doc(customerUid).set({ ...demo, uid: customerUid, displayName: 'Demo Customer' }, { merge: true });
await db.collection('officers').doc(officerUid).set({ ...demo, uid: officerUid, displayName: 'Demo Officer', permissions: [] }, { merge: true });

const demoAccount = {
  ...demo,
  ownerUid: customerUid,
  currency: 'VND',
  status: 'ACTIVE',
  balanceMinor: 25000000,
  type: 'CHECKING'
};
await db.collection('accounts').doc('demo_customer_checking').set(demoAccount, { merge: true });

await db.collection('transactions').doc('demo_customer_transaction_001').set({
  ...demo,
  ownerUid: customerUid,
  accountId: 'demo_customer_checking',
  type: 'DEPOSIT',
  currency: 'VND',
  amountMinor: 25000000,
  status: 'SETTLED',
  createdAt: now
}, { merge: true });

await db.collection('notifications').doc('demo_customer_notification_001').set({
  ...demo,
  ownerUid: customerUid,
  title: 'Chào mừng đến Nimbus Bank',
  body: 'Tài khoản demo đã sẵn sàng để xem dữ liệu.',
  read: false,
  createdAt: now
}, { merge: true });

console.log(process.argv.includes('--reset')
  ? 'Reset and restored demo profiles and account shell idempotently.'
  : 'Seeded demo profiles and one checking-account shell idempotently.');
