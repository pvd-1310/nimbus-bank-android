import { deleteApp, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { execFile, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { promisify } from "node:util";
import { expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import {
  applyMigrationPlan,
  buildMigrationPlan,
} from "../src/database/migration.js";
import { missingTemplateFields } from "../src/database/template-schema.js";
import { requireEmulatorEnvironment } from "./support/emulator-environment.js";
import { FirestoreStore } from "../src/infrastructure/firestore-store.js";
import { type Actor } from "../src/modules/auth/auth.types.js";
const enabled = process.env.RUN_EMULATOR_TESTS === "1";
const projectId = enabled
  ? requireEmulatorEnvironment(process.env)
  : "demo-mobile-banking";
it.skipIf(!enabled)(
  "schema seed is complete and real migration is additive and idempotent",
  async () => {
    const app = initializeApp({ projectId }, "schema-" + Date.now()),
      db = getFirestore(app),
      ns = "schema_" + Date.now();
    try {
      const seedEnv = {
        ...process.env,
        APP_MODE: "emulator",
        FIREBASE_PROJECT_ID: projectId,
        OTP_SECRET: randomBytes(32).toString("hex"),
        DEMO_NAMESPACE: ns,
        DEMO_PASSWORD: randomBytes(24).toString("hex"),
      };
      const seed = () => promisify(execFile)(
        process.execPath,
        [
          "node_modules/tsx/dist/cli.mjs",
          "src/scripts/seed-demo.ts",
          "--apply",
        ],
        {
          env: seedEnv,
          timeout: 20000,
        },
      );
      await seed();
      for (const path of [
        "users/" + ns + "_customer",
        "customers/" + ns + "_customer",
        "officers/" + ns + "_officer",
        "accounts/" + ns + "_checking",
        "ratePolicies/" + ns + "_savings_v1",
        "mortgageDetails/" + ns + "_mortgage",
        "bills/" + ns + "_electricity",
        "bankBranches/" + ns + "_branch",
      ]) {
        expect(
          missingTemplateFields(path, (await db.doc(path).get()).data()!),
        ).toEqual([]);
      }
      expect((await db.doc("users/" + ns + "_peer").get()).exists).toBe(true);
      expect((await db.doc("transactions/" + ns + "_tx_salary").get()).exists).toBe(true);
      await db.doc("accounts/" + ns + "_checking").update({ availableMinor: 12345, version: 8 });
      await db.doc("users/" + ns + "_customer").update({ fullName: "Tên đã sửa" });
      await db.doc("bills/" + ns + "_water").update({ status: "PAID" });
      await seed();
      expect((await db.doc("accounts/" + ns + "_checking").get()).data()).toMatchObject({ availableMinor: 12345, version: 8 });
      expect((await db.doc("users/" + ns + "_customer").get()).data()?.fullName).toBe("Tên đã sửa");
      expect((await db.doc("bills/" + ns + "_water").get()).data()?.status).toBe("PAID");
      const path = "accounts/" + ns + "_legacy";
      await db.doc(path).set({ ownerUid: ns + "_customer", balanceMinor: 321 });
      const record = (await db.doc(path).get()).data()!;
      const plan = buildMigrationPlan([{ path, data: record }]);
      expect(plan.entries).toHaveLength(1);
      await applyMigrationPlan(new FirestoreStore(db), plan, plan.hash);
      expect((await db.doc(path).get()).data()).toEqual({
        ...record,
        accountId: ns + "_legacy",
      });
      expect(
        await applyMigrationPlan(new FirestoreStore(db), plan, plan.hash),
      ).toEqual({ updated: 0, skipped: 1 });
    } finally {
      await db.terminate();
      await deleteApp(app);
    }
  },
  60000,
);
it.skipIf(!enabled)(
  "Firestore pagination traverses more than 500 documents and supports date cursors",
  async () => {
    const app = initializeApp({ projectId }, "pages-" + Date.now()),
      db = getFirestore(app),
      store = new FirestoreStore(db),
      ownerUid = "pages_" + Date.now();
    try {
      for (let start = 0; start < 510; start += 170) {
        const batch = db.batch();
        for (let i = start; i < Math.min(start + 170, 510); i++)
          batch.set(
            db.doc(
              "pageFixtures/" + ownerUid + "_" + String(i).padStart(4, "0"),
            ),
            { ownerUid, committedAt: i },
          );
        await batch.commit();
      }
      const first = await store.run((t) =>
        t.list("pageFixtures", { ownerUid }, { limit: 100 }),
      );
      const next = await store.run((t) =>
        t.list(
          "pageFixtures",
          { ownerUid },
          { limit: 100, after: first.at(-1)!.id },
        ),
      );
      expect(first).toHaveLength(100);
      expect(next[0].id).toBe(ownerUid + "_0100");
      const date = await store.run((t) =>
        t.list("pageFixtures", { ownerUid }, { limit: 3, from: 200, to: 209 }),
      );
      const dateNext = await store.run((t) =>
        t.list(
          "pageFixtures",
          { ownerUid },
          { limit: 3, from: 200, to: 209, after: date.at(-1)!._cursor },
        ),
      );
      expect(date.map((x) => x.committedAt)).toEqual([200, 201, 202]);
      expect(dateNext.map((x) => x.committedAt)).toEqual([203, 204, 205]);
    } finally {
      await db.terminate();
      await deleteApp(app);
    }
  },
  30000,
);
it.skipIf(!enabled)(
  "boots production entrypoint and verifies real Auth emulator token",
  async () => {
    const app = initializeApp({ projectId }, "http-" + Date.now()),
      auth = getAuth(app),
      db = getFirestore(app),
      uid = "smoke_" + Date.now(),
      password = randomBytes(24).toString("hex"),
      email = uid + "@example.com";
    const child = spawn(process.execPath, ["dist/server.js"], {
      env: {
        ...process.env,
        APP_MODE: "emulator",
        FIREBASE_PROJECT_ID: projectId,
        OTP_SECRET: randomBytes(32).toString("hex"),
        HOST: "127.0.0.1",
        PORT: "3197",
      },
      stdio: "ignore",
    });
    try {
      await auth.createUser({ uid, email, password });
      await auth.setCustomUserClaims(uid, {
        role: "CUSTOMER",
        permissions: [],
      });
      await db
        .doc("users/" + uid)
        .set({ role: "CUSTOMER", active: true, fullName: "Smoke user" });
      await db.doc("accounts/" + uid + "_checking").set({ ownerUid: uid, type: "CHECKING", status: "ACTIVE", currency: "VND", availableMinor: 1000, bookedMinor: 1000 });
      await db.doc("checkingDetails/" + uid + "_checking").set({ dailyLimitMinor: 500 });
      await db.doc("accounts/" + uid + "_other").set({ ownerUid: "another-user", type: "CHECKING", status: "ACTIVE", currency: "VND", availableMinor: 100, bookedMinor: 100 });
      let ready = false;
      for (let n = 0; n < 50; n++) {
        try {
          ready = (await fetch("http://127.0.0.1:3197/health")).ok;
          if (ready) break;
        } catch {}
        await new Promise((r) => setTimeout(r, 100));
      }
      expect(ready).toBe(true);
      const response = await fetch(
        "http://" +
          process.env.FIREBASE_AUTH_EMULATOR_HOST +
          "/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo",
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ email, password, returnSecureToken: true }),
        },
      );
      const login = (await response.json()) as { idToken: string };
      expect(response.ok).toBe(true);
      const profile = await fetch("http://127.0.0.1:3197/me", {
        headers: { Authorization: "Bearer " + login.idToken },
      });
      expect(profile.status).toBe(200);
      expect(((await profile.json()) as { fullName: string }).fullName).toBe(
        "Smoke user",
      );
      expect((await fetch("http://127.0.0.1:3197/accounts/" + uid + "_checking/details", { headers: { Authorization: "Bearer " + login.idToken } })).status).toBe(200);
      expect((await fetch("http://127.0.0.1:3197/accounts/" + uid + "_other/details", { headers: { Authorization: "Bearer " + login.idToken } })).status).toBe(403);
      const denied = await fetch("http://127.0.0.1:3197/officer/audit", {
        headers: { Authorization: "Bearer " + login.idToken },
      });
      expect(denied.status).toBe(403);
      const write = await fetch(
        "http://" +
          process.env.FIRESTORE_EMULATOR_HOST +
          "/v1/projects/" +
          projectId +
          "/databases/(default)/documents/accounts/illegal",
        {
          method: "PATCH",
          headers: {
            Authorization: "Bearer " + login.idToken,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            fields: {
              ownerUid: { stringValue: uid },
              availableMinor: { integerValue: "999" },
            },
          }),
        },
      );
      expect(write.status).toBe(403);
      const officerUid = uid + "_officer";
      const officerEmail = officerUid + "@example.com";
      await auth.createUser({ uid: officerUid, email: officerEmail, password });
      await auth.setCustomUserClaims(officerUid, { role: "OFFICER", permissions: ["CUSTOMER_ADMIN", "AUDIT_READ"] });
      await db.doc("users/" + officerUid).set({ role: "OFFICER", active: true });
      await db.doc("officers/" + officerUid).set({ active: true, permissions: ["AUDIT_READ"] });
      await db.doc("accounts/" + uid).set({ ownerUid: uid, balanceMinor: 100 });
      await db.doc("auditLogs/" + uid).set({ actorUid: uid, action: "FIXTURE" });
      const officerSignIn = await fetch(
        "http://" + process.env.FIREBASE_AUTH_EMULATOR_HOST +
          "/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo",
        { method: "POST", headers: { "content-type": "application/json" },
          body: JSON.stringify({ email: officerEmail, password, returnSecureToken: true }) },
      );
      expect(officerSignIn.ok).toBe(true);
      const officerLogin = (await officerSignIn.json()) as { idToken: string };
      const headers = { Authorization: "Bearer " + officerLogin.idToken };
      const firestoreUrl = "http://" + process.env.FIRESTORE_EMULATOR_HOST +
        "/v1/projects/" + projectId + "/databases/(default)/documents";
      expect((await fetch(firestoreUrl + "/accounts/" + uid, { headers })).status).toBe(403);
      expect((await fetch(firestoreUrl + "/auditLogs/" + uid, { headers })).status).toBe(403);
      expect((await fetch("http://127.0.0.1:3197/officer/audit", { headers })).status).toBe(200);
      expect((await fetch("http://127.0.0.1:3197/officer/rate-policies", { headers })).status).toBe(403);
      const deniedPolicyUpdate = await fetch("http://127.0.0.1:3197/officer/rate-policies", { method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: JSON.stringify({ rateType: "SAVINGS", annualRateBasisPoints: 500, effectiveFrom: Date.now() + 60000, reason: "Policy test" }) });
      expect(deniedPolicyUpdate.status).toBe(403);
    } finally {
      child.kill("SIGTERM");
      await db.terminate();
      await deleteApp(app);
    }
  },
  30000,
);
it.skipIf(!enabled)(
  "real Firestore atomically commits one concurrent transfer and restores idempotent receipt",
  async () => {
    if (!process.env.FIRESTORE_EMULATOR_HOST) throw Error("Emulator required");
    const app = initializeApp({ projectId }, "integration-" + Date.now());
    const db = getFirestore(app);
    const s = new FirestoreStore(db);
    const b = new Banking(s, { otpSecret: "test" });
    const suffix = String(Date.now()),
      uid = "user_" + suffix,
      src = "source_" + suffix,
      dst = "target_" + suffix;
    const a: Actor = { uid, role: "CUSTOMER", permissions: [] };
    try {
      await s.run(async (t) => {
        t.set("users/" + uid, { role: "CUSTOMER", active: true });
        t.set("accounts/" + src, {
          ownerUid: uid,
          type: "CHECKING",
          availableMinor: 1000,
          bookedMinor: 1000,
          currency: "VND",
          version: 0,
          status: "ACTIVE",
        });
        t.set("accounts/" + dst, {
          ownerUid: "other",
          type: "CHECKING",
          availableMinor: 0,
          bookedMinor: 0,
          currency: "VND",
          version: 0,
          status: "ACTIVE",
        });
      });
      const intents = [];
      for (const requestId of ["first", "second"]) {
        const i = await b.transfers.intent(a, {
          requestId,
          sourceAccountId: src,
          destinationAccountId: dst,
          amountMinor: 700,
          currency: "VND",
        });
        const otp = await b.security.challenge(a, i.id);
        await b.security.verify(a, i.id, otp.challengeId, otp.code);
        intents.push(i);
      }
      const outcomes = await Promise.allSettled(
        intents.map((i) => b.transfers.commit(a, i.id)),
      );
      expect(outcomes.filter((x) => x.status === "fulfilled")).toHaveLength(1);
      expect((await b.accounts.get(a, src)).availableMinor).toBe(300);
      const winner =
        intents[outcomes.findIndex((x) => x.status === "fulfilled")];
      expect(await b.transfers.commit(a, winner.id)).toEqual({
        transactionId: winner.id,
        status: "SETTLED",
      });
      const entries = await s.run((t) =>
        t.list("ledgerEntries", { transactionId: winner.id }),
      );
      expect(entries).toHaveLength(2);
      expect(entries.reduce((n, x) => n + x.signedMinor, 0)).toBe(0);
    } finally {
      await db.terminate();
      await deleteApp(app);
    }
  },
  30000,
);
