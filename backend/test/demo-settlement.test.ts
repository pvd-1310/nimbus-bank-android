import { expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import { MemoryStore } from "./support/memory-store.js";
import type { Actor } from "../src/modules/auth/auth.types.js";

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
    bankCode: "DEMO_BANK", beneficiaryAccount: "0000000001", amountMinor: 100, currency: "VND" };
  const intent = await bank.requests.demoIntent(actor, body);
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
