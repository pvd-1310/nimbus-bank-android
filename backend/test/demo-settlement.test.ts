import { expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import { MemoryStore } from "./support/memory-store.js";
import type { Actor } from "../src/modules/auth/auth.types.js";

it("settles a cash withdrawal once and returns the refreshed balance in its receipt", async () => {
  const store = new MemoryStore();
  const actor: Actor = { uid: "customer", role: "CUSTOMER", permissions: [] };
  let now = 100;
  const bank = new Banking(store, { otpSecret: "test fixture", now: () => now });
  await store.run(async (t) => {
    t.set("users/customer", { role: "CUSTOMER", active: true });
    t.set("accounts/checking", { ownerUid: actor.uid, type: "CHECKING", status: "ACTIVE", currency: "VND", availableMinor: 1000, bookedMinor: 1000, version: 0 });
  });
  const input = { requestId: "cash-out", accountId: "checking", type: "WITHDRAWAL", amountMinor: 250, currency: "VND" };
  const first = await bank.requests.demoIntent(actor, input);
  expect(await bank.requests.demoIntent(actor, input)).toMatchObject({ id: first.id });
  const otp = await bank.security.challenge(actor, first.id);
  await bank.security.verify(actor, first.id, otp.challengeId, otp.code);
  expect(await bank.requests.commitDemo(actor, first.id)).toMatchObject({ status: "COMMIT_PENDING" });
  expect(await store.run((t) => t.list("transactions"))).toHaveLength(0);
  expect((await bank.accounts.get(actor, "checking")).bookedMinor).toBe(1000);
  now += 5000;
  const receipt = await bank.requests.reconcileDemo(actor, first.id);
  expect(receipt).toMatchObject({ status: "COMMITTED", balanceAfterMinor: 750 });
  expect(await bank.requests.reconcileDemo(actor, first.id)).toMatchObject({ status: "COMMITTED", balanceAfterMinor: 750 });
  expect((await bank.accounts.get(actor, "checking")).availableMinor).toBe(750);
  expect(await store.run((t) => t.list("ledgerEntries"))).toHaveLength(2);
  expect(await store.run((t) => t.list("transactions"))).toMatchObject([expect.objectContaining({ type: "DEMO_WITHDRAWAL", status: "DEMO_SETTLED", balanceAfterMinor: 750 })]);
});

it("rejects over-limit withdrawals without changing balance or writing a ledger", async () => {
  const store = new MemoryStore();
  const actor: Actor = { uid: "customer", role: "CUSTOMER", permissions: [] };
  let now = 100;
  const bank = new Banking(store, { otpSecret: "test fixture", now: () => now });
  await store.run(async (t) => {
    t.set("users/customer", { role: "CUSTOMER", active: true });
    t.set("accounts/checking", { ownerUid: actor.uid, type: "CHECKING", status: "ACTIVE", currency: "VND", availableMinor: 1000, bookedMinor: 1000, version: 0 });
    t.set("checkingDetails/checking", { dailyLimitMinor: 50 });
  });
  const intent = await bank.requests.demoIntent(actor, { requestId: "over-limit", accountId: "checking", type: "WITHDRAWAL", amountMinor: 100, currency: "VND" });
  const otp = await bank.security.challenge(actor, intent.id);
  await bank.security.verify(actor, intent.id, otp.challengeId, otp.code);
  await bank.requests.commitDemo(actor, intent.id);
  now += 5000;
  expect(await bank.requests.reconcileDemo(actor, intent.id)).toMatchObject({ status: "FAILED", failureCode: "DAILY_LIMIT_EXCEEDED" });
  expect((await bank.accounts.get(actor, "checking")).availableMinor).toBe(1000);
  expect(await store.run((t) => t.list("ledgerEntries"))).toHaveLength(0);
  expect(await store.run((t) => t.list("transactions"))).toHaveLength(0);
});

it("keeps demo movement pending until reconciliation and posts one balanced settlement", async () => {
  const store = new MemoryStore();
  const actor: Actor = { uid: "customer", role: "CUSTOMER", permissions: [] };
  let now = 100;
  const bank = new Banking(store, { otpSecret: "test fixture", now: () => now });
  await store.run(async (t) => {
    t.set("users/customer", { role: "CUSTOMER", active: true });
    t.set("accounts/checking", {
      ownerUid: actor.uid, type: "CHECKING", status: "ACTIVE", currency: "VND",
      availableMinor: 1000, bookedMinor: 1000, version: 0, demo: true,
    });
  });
  const body = { requestId: "ext", accountId: "checking", type: "EXTERNAL_TRANSFER",
    bankCode: "BIDV", beneficiaryAccount: "0000000001", amountMinor: 100, currency: "VND" };
  const intent = await bank.requests.demoIntent(actor, body);
  expect(intent).toMatchObject({ bankCode: "BIDV", beneficiaryAccount: "0000000001" });
  await expect(bank.requests.commitDemo(actor, intent.id)).rejects.toMatchObject({ code: "OTP_REQUIRED" });
  const otp = await bank.security.challenge(actor, intent.id);
  await bank.security.verify(actor, intent.id, otp.challengeId, otp.code);
  expect(await bank.requests.commitDemo(actor, intent.id)).toMatchObject({ status: "COMMIT_PENDING", demo: true });
  expect(await bank.requests.reconcileDemo(actor, intent.id)).toMatchObject({ status: "COMMIT_PENDING" });
  expect((await bank.accounts.get(actor, "checking")).bookedMinor).toBe(1000);
  now += 5000;
  const results = await Promise.all([bank.requests.reconcileDemo(actor, intent.id), bank.requests.reconcileDemo(actor, intent.id)]);
  expect(results).toEqual(Array(2).fill(expect.objectContaining({ status: "COMMITTED", demo: true })));
  expect((await bank.accounts.get(actor, "checking")).bookedMinor).toBe(900);
  expect(await store.run((t) => t.list("transactions"))).toMatchObject([
    expect.objectContaining({ destinationBankCode: "BIDV", destinationAccountId: "BIDV_0000000001" }),
  ]);
  expect(await bank.transactions.list(actor, {})).toMatchObject([
    expect.objectContaining({ destinationBankCode: "BIDV" }),
  ]);
  const rejected = await bank.requests.demoIntent(actor, { ...body, requestId: "reject", beneficiaryAccount: "0000000002" });
  const rejectedOtp = await bank.security.challenge(actor, rejected.id);
  await bank.security.verify(actor, rejected.id, rejectedOtp.challengeId, rejectedOtp.code);
  await bank.requests.commitDemo(actor, rejected.id);
  now += 5000;
  expect(await bank.requests.reconcileDemo(actor, rejected.id)).toMatchObject({
    status: "FAILED", failureCode: "DEMO_PROVIDER_REJECTED",
  });
  expect((await bank.accounts.get(actor, "checking")).bookedMinor).toBe(900);
  expect((await store.run((t) => t.list("ledgerEntries"))).map((entry) => entry.signedMinor).sort((a, b) => a - b))
    .toEqual([-100, 100]);
});

it("rejects an unsupported destination bank", async () => {
  const store = new MemoryStore();
  const actor: Actor = { uid: "customer", role: "CUSTOMER", permissions: [] };
  const bank = new Banking(store, { otpSecret: "test fixture", now: () => 100 });
  await store.run(async (t) => {
    t.set("users/customer", { role: "CUSTOMER", active: true });
    t.set("accounts/checking", { ownerUid: actor.uid, type: "CHECKING", status: "ACTIVE", currency: "VND", availableMinor: 1000, bookedMinor: 1000, version: 0, demo: true });
  });
  await expect(bank.requests.demoIntent(actor, {
    requestId: "unsupported-bank", accountId: "checking", type: "EXTERNAL_TRANSFER",
    bankCode: "UNKNOWN", beneficiaryAccount: "0000000001", amountMinor: 100, currency: "VND",
  })).rejects.toMatchObject({ code: "INVALID_BANK" });
});
