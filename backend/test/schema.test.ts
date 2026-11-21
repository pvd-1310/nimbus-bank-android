import { expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import {
  migrationPatch,
  normalizeDocument,
  templateCollections,
} from "../src/database/template-schema.js";
import { MemoryStore } from "./support/memory-store.js";
it("fills stable template identifiers and links without fabricating customer identity", () => {
  const c = normalizeDocument("customers/uid123", {
    uid: "uid123",
    kycStatus: "NOT_SUBMITTED",
  });
  expect(c.customerId).toBe("uid123");
  expect(c.uidRef).toBe("uid123");
  expect(c.customerNo).toBeUndefined();
  expect(
    normalizeDocument("accounts/a", { ownerUid: "uid123" }).accountId,
  ).toBe("a");
});
it("writes canonical transfer and OTP fields while preserving compatibility aliases", () => {
  const i = normalizeDocument("transactionIntents/i", {
    sourceAccountId: "a",
    destinationAccountId: "b",
    expiresAt: 100,
  });
  expect(i).toMatchObject({
    intentId: "i",
    sourceId: "a",
    target: { kind: "ACCOUNT", id: "b" },
  });
  expect(
    normalizeDocument("otpChallenges/i", { hash: "protected", expiresAt: 100 }),
  ).toMatchObject({
    challengeId: "i",
    intentId: "i",
    otpHashOrProviderRef: "protected",
    expiry: 100,
  });
});
it("maps Stripe fields and versioned rate product without changing amounts", () => {
  expect(
    normalizeDocument("payments/p", {
      billId: "b",
      providerId: "pi_1",
      amountMinor: 20000,
    }),
  ).toMatchObject({
    paymentId: "p",
    billOrOrderRef: "b",
    gateway: "STRIPE",
    merchantRef: "p",
    providerTxnRef: "pi_1",
    amountMinor: 20000,
  });
  expect(
    normalizeDocument("ratePolicies/r", { productId: "savings" })
      .accountProduct,
  ).toBe("savings");
  expect(
    normalizeDocument("mortgageDetails/a", { annualRateBasisPoints: 800 })
      .annualRate,
  ).toBe(0.08);
});
it("rejects conflicting canonical and legacy fields", () => {
  expect(() =>
    normalizeDocument("accounts/a", { accountId: "different" }),
  ).toThrow();
  expect(() =>
    normalizeDocument("ratePolicies/r", {
      accountProduct: "one",
      productId: "two",
    }),
  ).toThrow();
});
it("migration is additive and idempotent and does not rewrite immutable audit history", () => {
  const old = { uid: "u", fullName: "Existing", createdAt: 123 };
  const patch = migrationPatch("users/u", old);
  expect(patch.uid).toBeUndefined();
  expect(migrationPatch("users/u", { ...old, ...patch })).toEqual({});
  expect(migrationPatch("auditLogs/old", { action: "OLD" })).toEqual({});
  expect(templateCollections).toHaveLength(20);
});
it("store boundary consistently normalizes canonical fields", async () => {
  const store = new MemoryStore();
  await store.run(async (t) =>
    t.set("notifications/n", { ownerUid: "u", body: "Hello", read: false }),
  );
  expect(await store.run((t) => t.get("notifications/n"))).toMatchObject({
    notificationId: "n",
    message: "Hello",
    readAt: null,
    category: "GENERAL",
  });
});
it("uses canonical user status and officer uidRef for authorization", async () => {
  const s = new MemoryStore(),
    b = new Banking(s, { otpSecret: "test", deliver: async () => {} });
  await s.run(async (t) => {
    t.set("users/u", { role: "OFFICER", status: "ACTIVE" });
    t.set("officers/staff1", {
      uidRef: "u",
      active: true,
      permissions: ["AUDIT_READ"],
    });
  });
  await expect(
    s.run((t) =>
      b.authorize(
        t,
        { uid: "u", role: "OFFICER", permissions: ["AUDIT_READ"] },
        "AUDIT_READ",
      ),
    ),
  ).resolves.toBeDefined();
  await s.run(async (t) =>
    t.set("users/u", { role: "OFFICER", status: "INACTIVE", active: true }),
  );
  await expect(
    s.run((t) =>
      b.authorize(
        t,
        { uid: "u", role: "OFFICER", permissions: ["AUDIT_READ"] },
        "AUDIT_READ",
      ),
    ),
  ).rejects.toThrow();
});
